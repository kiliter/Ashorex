package com.shangan.supervision.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.NagScanner;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.supervision.application.SupervisionGuard;
import com.shangan.supervision.application.SupervisorViewService;
import com.shangan.supervision.application.SupervisorViewService.FeedItem;
import com.shangan.supervision.application.SupervisorViewService.LearnerDetail;
import com.shangan.supervision.application.SupervisorViewService.LearnerOverview;
import com.shangan.supervision.application.SupervisorViewService.LearnerReport;
import com.shangan.supervision.domain.SupervisionPermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 督学端 API；只读学员数据 + 一键督学。 */
@RestController
@RequestMapping("/api/v1/supervisor")
public class SupervisorController {

  private final SupervisorViewService views;
  private final SupervisionGuard guard;
  private final NagScanner nagScanner;

  public SupervisorController(
      SupervisorViewService views, SupervisionGuard guard, NagScanner nagScanner) {
    this.views = views;
    this.guard = guard;
    this.nagScanner = nagScanner;
  }

  @GetMapping("/learners")
  List<LearnerOverview> learners(CurrentUser currentUser) {
    return views.learners(currentUser.userId());
  }

  @GetMapping("/learners/{learnerId}")
  LearnerDetail learner(
      CurrentUser currentUser,
      @PathVariable String learnerId,
      @RequestParam(defaultValue = "DAY") String range,
      @RequestParam(required = false) String date) {
    return views.learnerDetail(currentUser.userId(), learnerId, range, date);
  }

  @GetMapping("/feed")
  List<FeedItem> feed(CurrentUser currentUser, @RequestParam(defaultValue = "50") int limit) {
    return views.feed(currentUser.userId(), Math.min(Math.max(limit, 1), 200));
  }

  @GetMapping("/report")
  List<LearnerReport> report(
      CurrentUser currentUser,
      @RequestParam(defaultValue = "WEEK") String range,
      @RequestParam(required = false) String date) {
    return views.report(currentUser.userId(), range, date);
  }

  /** 一键督学；走与自动催办相同的投递管道。 */
  @PostMapping("/learners/{learnerId}/nag")
  Nag nag(
      CurrentUser currentUser,
      @PathVariable String learnerId,
      @RequestBody(required = false) NagRequest request) {
    guard.requirePermission(currentUser.userId(), learnerId, SupervisionPermission.NAG);
    NagRequest body = request == null ? new NagRequest(null, null, null) : request;
    NagChannelType channel = parseChannel(body.channel());
    boolean requireReason = body.requireReason() == null || body.requireReason();
    return nagScanner.createManualNag(
        learnerId,
        currentUser.userId(),
        NagTrigger.SUPERVISOR,
        body.message(),
        channel,
        requireReason);
  }

  /** 渠道为空表示自动选择（在线走全屏，离线走 Server 酱）。 */
  private NagChannelType parseChannel(String channel) {
    if (channel == null || channel.isBlank() || "AUTO".equalsIgnoreCase(channel)) {
      return null;
    }
    try {
      return NagChannelType.valueOf(channel.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new com.shangan.common.api.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "NAG_CHANNEL_UNKNOWN", "未知的投递渠道");
    }
  }

  record NagRequest(String message, String channel, Boolean requireReason) {}
}
