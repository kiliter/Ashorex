package com.shangan.presence.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.presence.application.PresenceService;
import com.shangan.presence.application.PresenceService.HeartbeatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 心跳 API；固定周期由服务端下发，客户端遵循。 */
@RestController
@RequestMapping("/api/v1/heartbeat")
public class HeartbeatController {

  private final PresenceService presence;

  public HeartbeatController(PresenceService presence) {
    this.presence = presence;
  }

  @PostMapping
  HeartbeatResponse heartbeat(
      CurrentUser currentUser, @RequestBody(required = false) HeartbeatRequest request) {
    HeartbeatRequest body = request == null ? new HeartbeatRequest("BACKGROUND", "", 0) : request;
    return presence.heartbeat(currentUser.userId(), body.appState(), body.clientVersion());
  }

  record HeartbeatRequest(String appState, String clientVersion, int queuedEvents) {}
}
