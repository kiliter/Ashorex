package com.shangan.learning.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.learning.application.PlaybackSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 创建直接学习或关联计划任务的观看会话。 */
@RestController
@RequestMapping("/api/v1/lessons/{lessonId}/watch-sessions")
public class WatchSessionController {
  private final PlaybackSessionService playback;

  public WatchSessionController(PlaybackSessionService playback) {
    this.playback = playback;
  }

  @PostMapping
  PlaybackSessionService.PlaybackSession create(
      CurrentUser user,
      @PathVariable String lessonId,
      @Valid @RequestBody CreateWatchSessionRequest request) {
    return playback.create(user.userId(), lessonId, request.planItemId(), request.deviceId());
  }

  record CreateWatchSessionRequest(String planItemId, @NotBlank String deviceId) {}
}
