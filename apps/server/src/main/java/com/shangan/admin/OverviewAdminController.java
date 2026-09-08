package com.shangan.admin;

import com.shangan.archive.application.ArchiveService;
import com.shangan.archive.infrastructure.CascadeRepository;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.UserTimeService;
import com.shangan.identity.domain.User;
import com.shangan.nag.application.NagScanScheduler;
import com.shangan.nag.application.NagScanner;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.PresenceService;
import com.shangan.presence.application.PresenceService.PresenceView;
import com.shangan.presence.domain.PresenceState;
import com.shangan.todo.application.TodoViewService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览与在线监控的 JSON API。
 *
 * <p>后台前端为 Vue SPA（ADR-0033），控制器只返回 DTO，不再渲染模板。
 */
@RestController
@RequestMapping("/admin/api")
public class OverviewAdminController {

  private final AuthService users;
  private final UserTimeService userTime;
  private final PresenceService presence;
  private final TodoViewService todoViews;
  private final NagRepository nags;
  private final NagScanScheduler scanScheduler;
  private final NagScanner nagScanner;
  private final OperationsHealthService health;
  private final ArchiveService archive;

  public OverviewAdminController(
      AuthService users,
      UserTimeService userTime,
      PresenceService presence,
      TodoViewService todoViews,
      NagRepository nags,
      NagScanScheduler scanScheduler,
      NagScanner nagScanner,
      OperationsHealthService health,
      ArchiveService archive) {
    this.users = users;
    this.userTime = userTime;
    this.presence = presence;
    this.todoViews = todoViews;
    this.nags = nags;
    this.scanScheduler = scanScheduler;
    this.nagScanner = nagScanner;
    this.health = health;
    this.archive = archive;
  }

  @GetMapping("/overview")
  OverviewResponse overview() {
    List<LearnerRow> rows = new ArrayList<>();
    int onlineCount = 0;
    int totalTodos = 0;
    int doneTodos = 0;
    long watchedMs = 0;
    long focusedMs = 0;
    for (User user : users.listActiveUsers()) {
      PresenceView presenceView = presence.view(user);
      TodoViewService.DayView day = todoViews.day(user.id(), userTime.today(user).toString());
      if (presenceView.state() == PresenceState.ONLINE) {
        onlineCount++;
      }
      totalTodos += day.totals().total();
      doneTodos += day.totals().done();
      watchedMs += day.totals().watchedMs();
      focusedMs += day.totals().focusedMs();
      rows.add(
          new LearnerRow(
              user.id(),
              user.username(),
              user.displayName(),
              presenceView.state(),
              presenceView.lastHeartbeatAt(),
              presenceView.idleMinutes(),
              day.totals().total(),
              day.totals().done(),
              day.totals().watchedMs() + day.totals().focusedMs()));
    }
    rows.sort(Comparator.comparingInt(LearnerRow::doneCount));

    int awaiting = (int) nags.findRecent(200).stream().filter(Nag::awaitingResponse).count();
    int respondedTotal =
        nags.countByStatus().stream()
            .filter(count -> count.status() == NagStatus.RESPONDED)
            .mapToInt(NagRepository.StatusCount::count)
            .sum();

    return new OverviewResponse(
        rows,
        onlineCount,
        rows.size(),
        totalTodos,
        doneTodos,
        totalTodos == 0 ? 0 : doneTodos * 100 / totalTodos,
        watchedMs,
        focusedMs,
        awaiting,
        respondedTotal,
        scanScheduler.lastScanAt(),
        health.snapshot(),
        archive.scanOrphans(),
        archive.expiredArchiveCount());
  }

  @GetMapping("/presence")
  PresenceResponse presence(@RequestParam(required = false) String userId) {
    List<PresenceRow> rows = new ArrayList<>();
    for (User user : users.listActiveUsers()) {
      PresenceView view = presence.view(user);
      TodoViewService.DayView day = todoViews.day(user.id(), userTime.today(user).toString());
      // 今日催办数与未回应数：原型 8-2 的「今日催办」列依赖它们。
      List<Nag> todayNags =
          nags.findRecent(200).stream()
              .filter(nag -> nag.userId().equals(user.id()))
              .filter(nag -> nag.localDate().equals(userTime.today(user).toString()))
              .toList();
      rows.add(
          new PresenceRow(
              user.id(),
              user.username(),
              user.displayName(),
              user.timezone(),
              view,
              day.totals().total(),
              day.totals().done(),
              day.totals().watchedMs() + day.totals().focusedMs(),
              todayNags.size(),
              (int) todayNags.stream().filter(Nag::awaitingResponse).count()));
    }
    TodoViewService.DayView selectedDay = null;
    String selectedName = null;
    if (userId != null && !userId.isBlank()) {
      User selected = userTime.requireUser(userId);
      selectedName = selected.displayName();
      selectedDay = todoViews.day(selected.id(), userTime.today(selected).toString());
    }
    return new PresenceResponse(rows, userId, selectedName, selectedDay);
  }

  /** 手动催办；与自动催办共用投递管道，计入每日上限。 */
  @PostMapping("/presence/nag")
  ResponseEntity<Void> nag(@RequestBody ManualNagRequest request) {
    NagChannelType target =
        request.channel() == null || "AUTO".equalsIgnoreCase(request.channel())
            ? null
            : NagChannelType.valueOf(request.channel().toUpperCase(Locale.ROOT));
    nagScanner.createManualNag(
        request.userId(),
        null,
        NagTrigger.MANUAL,
        request.message(),
        target,
        true,
        request.title());
    return ResponseEntity.noContent().build();
  }

  /** 概览响应。 */
  public record OverviewResponse(
      List<LearnerRow> learners,
      int onlineCount,
      int userCount,
      int totalTodos,
      int doneTodos,
      int completionPercent,
      long watchedMs,
      long focusedMs,
      int awaitingNags,
      int respondedNags,
      Instant lastScanAt,
      OperationsHealthService.Snapshot health,
      CascadeRepository.OrphanReport orphans,
      int expiredArchives) {}

  /** 概览表格行。 */
  public record LearnerRow(
      String userId,
      String username,
      String displayName,
      PresenceState state,
      Instant lastHeartbeatAt,
      long idleMinutes,
      int totalCount,
      int doneCount,
      long totalMs) {}

  /** 在线监控响应。 */
  public record PresenceResponse(
      List<PresenceRow> rows,
      String selectedUserId,
      String selectedDisplayName,
      TodoViewService.DayView selectedDay) {}

  /** 在线监控表格行。 */
  public record PresenceRow(
      String userId,
      String username,
      String displayName,
      String timezone,
      PresenceView presence,
      int totalCount,
      int doneCount,
      long totalMs,
      int todayNagCount,
      int unansweredNags) {}

  /** 手动催办请求。 */
  public record ManualNagRequest(String userId, String message, String channel, String title) {}
}
