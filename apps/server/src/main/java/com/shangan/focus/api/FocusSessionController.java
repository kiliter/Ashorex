package com.shangan.focus.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.focus.application.FocusSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** iOS 专注计时 API；客户端不上传已完成秒数。 */
@RestController
@RequestMapping("/api/v1/focus-sessions")
public class FocusSessionController {
  private final FocusSessionService focus;

  public FocusSessionController(FocusSessionService focus) {
    this.focus = focus;
  }

  @PostMapping
  FocusSessionService.FocusView start(CurrentUser user, @Valid @RequestBody StartRequest request) {
    return focus.start(user.userId(), request.toCommand());
  }

  @PostMapping("/{id}/pause")
  FocusSessionService.FocusView pause(CurrentUser user, @PathVariable String id) {
    return focus.pause(user.userId(), id);
  }

  @PostMapping("/{id}/resume")
  FocusSessionService.FocusView resume(CurrentUser user, @PathVariable String id) {
    return focus.resume(user.userId(), id);
  }

  @PostMapping("/{id}/finish")
  FocusSessionService.FocusView finish(CurrentUser user, @PathVariable String id) {
    return focus.finish(user.userId(), id);
  }

  @PostMapping("/{id}/cancel")
  FocusSessionService.FocusView cancel(CurrentUser user, @PathVariable String id) {
    return focus.cancel(user.userId(), id);
  }

  @GetMapping("/active")
  ResponseEntity<FocusSessionService.FocusView> active(CurrentUser user) {
    Optional<FocusSessionService.FocusView> active = focus.active(user.userId());
    return active.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
  }

  record StartRequest(String mediaItemId, @NotBlank String focusType, @Min(1) long plannedSeconds) {
    FocusSessionService.StartCommand toCommand() {
      // V1.3 专注是首页独立工具；显式传 null 防止客户端把它伪装成作战单任务。
      return new FocusSessionService.StartCommand(null, mediaItemId, focusType, plannedSeconds);
    }
  }
}
