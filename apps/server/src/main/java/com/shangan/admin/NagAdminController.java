package com.shangan.admin;

import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.infrastructure.NagPolicyRepository;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.todo.application.TodoDeletionService;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 催办策略配置、催办记录与删除台账的 JSON API。App 端没有任何策略入口。 */
@RestController
@RequestMapping("/admin/api")
public class NagAdminController {

  private final NagPolicyResolver policies;
  private final NagRepository nags;
  private final TodoDeletionService deletions;
  private final AuthService users;
  private final Clock clock;

  public NagAdminController(
      NagPolicyResolver policies,
      NagRepository nags,
      TodoDeletionService deletions,
      AuthService users,
      Clock clock) {
    this.policies = policies;
    this.nags = nags;
    this.deletions = deletions;
    this.users = users;
    this.clock = clock;
  }

  @GetMapping("/nag-policy")
  NagPolicyResponse policy() {
    List<OverrideRow> rows = new ArrayList<>();
    for (NagPolicyRepository.StoredPolicy stored : policies.overrides()) {
      users.listUsers().stream()
          .filter(user -> user.id().equals(stored.userId()))
          .findFirst()
          .ifPresent(
              user ->
                  rows.add(
                      new OverrideRow(
                          user.id(), user.username(), user.displayName(), stored.policy())));
    }
    return new NagPolicyResponse(
        policies.global(), rows, users.listActiveUsers().stream().map(UserOption::of).toList());
  }

  @PostMapping("/nag-policy")
  ResponseEntity<Void> saveGlobal(@RequestBody GlobalPolicyRequest request) {
    policies.saveGlobal(request.toPolicy(), clock.instant());
    return ResponseEntity.noContent().build();
  }

  /** 按用户覆盖只允许改阈值类字段，扫描周期与心跳间隔始终跟随全局。 */
  @PostMapping("/nag-policy/overrides")
  ResponseEntity<Void> saveOverride(@RequestBody OverrideRequest request) {
    EffectiveNagPolicy global = policies.global();
    EffectiveNagPolicy override =
        new EffectiveNagPolicy(
            global.scanIntervalMinutes(),
            global.presenceGraceSeconds(),
            global.heartbeatIntervalSeconds(),
            request.firstThresholdMinutes(),
            request.repeatIntervalMinutes(),
            request.dailyMax(),
            request.fullscreenTimeoutMinutes(),
            LocalTime.parse(request.quietStart()),
            LocalTime.parse(request.quietEnd()),
            request.minPending(),
            request.minReasonLength(),
            request.channelFullscreenEnabled(),
            request.channelServerchanEnabled(),
            global.messageTemplate(),
            global.notifySupervisorOnBulkDelete(),
            global.notifySupervisorOnGaveUp(),
            global.notifySupervisorOnHalfDoneDelete());
    policies.saveOverride(request.userId(), override, clock.instant());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/nag-policy/overrides/{userId}")
  ResponseEntity<Void> deleteOverride(@PathVariable String userId) {
    policies.removeOverride(userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/nags")
  NagRecordsResponse records() {
    Map<String, String> usernames = new LinkedHashMap<>();
    for (User user : users.listUsers()) {
      usernames.put(user.id(), user.username());
    }
    List<TodoRepository.Deletion> recentDeletions = deletions.recent(100);
    Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    for (TodoRepository.Deletion deletion : recentDeletions) {
      reasonCounts.merge(deletion.reasonTag().name(), 1, Integer::sum);
    }
    List<Nag> recentNags = nags.findRecent(100);
    return new NagRecordsResponse(
        recentNags,
        usernames,
        recentDeletions,
        reasonCounts,
        nags.countByStatus(),
        deliveryAttempts(recentNags));
  }

  /**
   * 把投递流水按催办 ID 分组，供后台区分「还没轮到投递」与「渠道都试过且失败」。
   *
   * <p>{@code detail} 由各渠道生成时已保证不含 SendKey、目标地址与堆栈，这里只做原样透传，不再拼接任何来源信息。
   * 没有任何投递记录的催办不会出现在返回映射里，前端据此显示「待投递」。
   */
  private Map<String, List<DeliveryAttempt>> deliveryAttempts(List<Nag> records) {
    if (records.isEmpty()) {
      return Map.of();
    }
    Map<String, List<DeliveryAttempt>> grouped = new LinkedHashMap<>();
    for (NagRepository.Delivery delivery :
        nags.deliveriesOfAll(records.stream().map(Nag::id).toList())) {
      grouped
          .computeIfAbsent(delivery.nagId(), key -> new ArrayList<>())
          .add(
              new DeliveryAttempt(
                  delivery.channel().name(),
                  delivery.status(),
                  delivery.detail(),
                  delivery.createdAt()));
    }
    return grouped;
  }

  /** 催办策略页响应。 */
  public record NagPolicyResponse(
      EffectiveNagPolicy global, List<OverrideRow> overrides, List<UserOption> users) {}

  /** 按用户覆盖表格行。 */
  public record OverrideRow(
      String userId, String username, String displayName, EffectiveNagPolicy policy) {}

  /** 下拉选项。 */
  public record UserOption(String id, String username, String displayName) {
    static UserOption of(User user) {
      return new UserOption(user.id(), user.username(), user.displayName());
    }
  }

  /** 催办记录页响应。 */
  public record NagRecordsResponse(
      List<Nag> nags,
      Map<String, String> usernames,
      List<TodoRepository.Deletion> deletions,
      Map<String, Integer> reasonCounts,
      List<NagRepository.StatusCount> statusCounts,
      Map<String, List<DeliveryAttempt>> deliveryAttempts) {}

  /**
   * 一次渠道投递尝试。
   *
   * <p>{@code status} 取 {@code SENT} / {@code SHOWN} / {@code FAILED}；{@code detail} 是渠道写入的脱敏原因， 不含
   * SendKey、目标地址与堆栈。
   */
  public record DeliveryAttempt(String channel, String status, String detail, Instant createdAt) {}

  /** 全局策略保存请求。 */
  public record GlobalPolicyRequest(
      int scanIntervalMinutes,
      int presenceGraceSeconds,
      int heartbeatIntervalSeconds,
      int firstThresholdMinutes,
      int repeatIntervalMinutes,
      int dailyMax,
      int fullscreenTimeoutMinutes,
      String quietStart,
      String quietEnd,
      int minPending,
      int minReasonLength,
      boolean channelFullscreenEnabled,
      boolean channelServerchanEnabled,
      String messageTemplate,
      boolean notifyBulkDelete,
      boolean notifyGaveUp,
      boolean notifyHalfDoneDelete) {

    EffectiveNagPolicy toPolicy() {
      return new EffectiveNagPolicy(
          scanIntervalMinutes,
          presenceGraceSeconds,
          heartbeatIntervalSeconds,
          firstThresholdMinutes,
          repeatIntervalMinutes,
          dailyMax,
          fullscreenTimeoutMinutes,
          LocalTime.parse(quietStart),
          LocalTime.parse(quietEnd),
          minPending,
          minReasonLength,
          channelFullscreenEnabled,
          channelServerchanEnabled,
          messageTemplate,
          notifyBulkDelete,
          notifyGaveUp,
          notifyHalfDoneDelete);
    }
  }

  /** 按用户覆盖保存请求。 */
  public record OverrideRequest(
      String userId,
      int firstThresholdMinutes,
      int repeatIntervalMinutes,
      int dailyMax,
      int fullscreenTimeoutMinutes,
      String quietStart,
      String quietEnd,
      int minPending,
      int minReasonLength,
      boolean channelFullscreenEnabled,
      boolean channelServerchanEnabled) {}
}
