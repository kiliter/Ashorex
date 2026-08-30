package com.shangan.learning.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.learning.application.PlaybackSessionService;
import com.shangan.learning.application.WatchSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 创建直接学习或关联计划任务的观看会话。 */
@RestController
public class WatchSessionController {
  private final PlaybackSessionService playback;
  private final WatchSessionService watching;

  public WatchSessionController(PlaybackSessionService playback, WatchSessionService watching) {
    this.playback = playback;
    this.watching = watching;
  }

  @PostMapping("/api/v1/lessons/{lessonId}/watch-sessions")
  PlaybackSessionService.PlaybackSession create(
      CurrentUser user,
      @PathVariable String lessonId,
      @Valid @RequestBody CreateWatchSessionRequest request) {
    return playback.create(user.userId(), lessonId, request.planItemId(), request.deviceId());
  }

  @PostMapping("/api/v1/watch-sessions/{sessionId}/heartbeat")
  WatchHeartbeatResponse heartbeat(
      CurrentUser user,
      @PathVariable String sessionId,
      @Valid @RequestBody WatchHeartbeatRequest request) {
    return watching.heartbeat(user.userId(), sessionId, request);
  }

  @PostMapping("/api/v1/watch-sessions/{sessionId}/alive-check")
  WatchHeartbeatResponse confirmAliveCheck(CurrentUser user, @PathVariable String sessionId) {
    return watching.confirmAliveCheck(user.userId(), sessionId);
  }

  @PostMapping("/api/v1/watch-sessions/{sessionId}/stop")
  WatchHeartbeatResponse stop(CurrentUser user, @PathVariable String sessionId) {
    return watching.stop(user.userId(), sessionId);
  }

  record CreateWatchSessionRequest(String planItemId, @NotBlank String deviceId) {}
}
