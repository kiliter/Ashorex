package com.shangan.nag.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.NagEventService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bearer 认证的单向通知流，用户 ID 只能来自已认证身份。 */
@RestController
public class NagEventController {
  private final NagEventService events;

  public NagEventController(NagEventService events) {
    this.events = events;
  }

  @GetMapping(value = "/api/v1/nags/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> events(CurrentUser user) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-cache, no-transform")
        .header("X-Accel-Buffering", "no")
        .body(events.subscribe(user.userId()));
  }
}
