package com.shangan.nag.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.NagResponseService;
import com.shangan.nag.domain.Nag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学员侧催办 API；App 端只能读取与回应，不能修改任何策略。 */
@RestController
@RequestMapping("/api/v1/nags")
public class NagController {

  private final NagResponseService nags;

  public NagController(NagResponseService nags) {
    this.nags = nags;
  }

  @GetMapping("/pending")
  ResponseEntity<Nag> pending(CurrentUser currentUser) {
    return nags.pending(currentUser.userId())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping
  List<Nag> history(CurrentUser currentUser, @RequestParam(defaultValue = "30") int limit) {
    return nags.history(currentUser.userId(), Math.min(Math.max(limit, 1), 200));
  }

  @PostMapping("/{nagId}/respond")
  Nag respond(
      CurrentUser currentUser,
      @PathVariable String nagId,
      @Valid @RequestBody RespondRequest request) {
    return nags.respond(currentUser.userId(), nagId, request.reasonTag(), request.reasonText());
  }

  record RespondRequest(String reasonTag, String reasonText) {}
}
