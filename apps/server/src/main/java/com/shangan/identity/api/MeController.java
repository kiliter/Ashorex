package com.shangan.identity.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.supervision.domain.Supervision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户资料与只读设置。
 *
 * <p>催办策略在这里是**只读**展示，App 端没有任何修改入口（见 ADR-0026）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private final AuthService authService;
  private final SupervisionService supervisions;
  private final NagPolicyResolver nagPolicies;
  private final IntegrationSettingsProvider settings;

  public MeController(
      AuthService authService,
      SupervisionService supervisions,
      NagPolicyResolver nagPolicies,
      IntegrationSettingsProvider settings) {
    this.authService = authService;
    this.supervisions = supervisions;
    this.nagPolicies = nagPolicies;
    this.settings = settings;
  }

  @GetMapping
  MeResponse me(CurrentUser currentUser) {
    User user = authService.getUser(currentUser.userId());
    EffectiveNagPolicy policy = nagPolicies.resolve(user.id());
    List<SupervisorRef> mySupervisors = new ArrayList<>();
    for (Supervision supervision : supervisions.supervisorsOf(user.id())) {
      User supervisor = authService.getUser(supervision.supervisorUserId());
      mySupervisors.add(
          new SupervisorRef(
              supervisor.id(),
              supervisor.displayName(),
              supervision.kind().name(),
              supervision.canNag()));
    }
    boolean supervisorMode = !supervisions.learnersOf(user.id()).isEmpty();
    return new MeResponse(
        user.id(),
        user.username(),
        user.displayName(),
        user.timezone(),
        user.roles().stream().map(UserRole::name).sorted().toList(),
        mySupervisors,
        supervisorMode,
        supervisions.learnersOf(user.id()).size(),
        new NagPolicyView(
            policy.heartbeatIntervalSeconds(),
            policy.firstThresholdMinutes(),
            policy.repeatIntervalMinutes(),
            policy.dailyMax(),
            policy.quietStart().toString(),
            policy.quietEnd().toString(),
            policy.minReasonLength(),
            // 全屏催办超时后改走 Server 酱，App 需要这个分钟数来渲染原型 7-1 的提示文案。
            policy.fullscreenTimeoutMinutes(),
            policy.channelFullscreenEnabled(),
            policy.channelServerchanEnabled()),
        new FeatureView(
            settings.current().features().documentResources(),
            settings.current().features().maxDocumentSizeMb()));
  }

  @PatchMapping("/password")
  ResponseEntity<Void> changePassword(
      CurrentUser currentUser, @Valid @RequestBody ChangePasswordRequest request) {
    authService.changePassword(
        currentUser.userId(), request.currentPassword(), request.newPassword());
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/timezone")
  MeResponse changeTimezone(
      CurrentUser currentUser, @Valid @RequestBody ChangeTimezoneRequest request) {
    authService.changeTimezone(currentUser.userId(), request.timezone());
    return me(currentUser);
  }

  record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

  record ChangeTimezoneRequest(@NotBlank String timezone) {}

  record SupervisorRef(String userId, String displayName, String kind, boolean canNag) {}

  /** 只读催办策略视图。 */
  record NagPolicyView(
      int heartbeatIntervalSeconds,
      int firstThresholdMinutes,
      int repeatIntervalMinutes,
      int dailyMax,
      String quietStart,
      String quietEnd,
      int minReasonLength,
      int fullscreenTimeoutMinutes,
      boolean fullscreenEnabled,
      boolean serverChanEnabled) {}

  /** 功能开关视图。 */
  record FeatureView(boolean documentResources, int maxDocumentSizeMb) {}

  record MeResponse(
      String id,
      String username,
      String displayName,
      String timezone,
      List<String> roles,
      List<SupervisorRef> supervisors,
      boolean supervisorMode,
      int learnerCount,
      NagPolicyView nagPolicy,
      FeatureView features) {}
}
