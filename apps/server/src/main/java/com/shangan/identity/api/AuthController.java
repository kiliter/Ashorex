package com.shangan.identity.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.AuthService.TokenPair;
import com.shangan.identity.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** App 身份认证和用户偏好 API。 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/auth/login")
  TokenPair login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request.username(), request.password());
  }

  @PostMapping("/auth/refresh")
  TokenPair refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/auth/logout")
  ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  UserResponse me(CurrentUser currentUser) {
    return UserResponse.from(authService.getUser(currentUser.userId()));
  }

  @GetMapping("/preferences")
  PreferencesResponse preferences(CurrentUser currentUser) {
    return PreferencesResponse.from(authService.getUser(currentUser.userId()));
  }

  @PutMapping("/preferences")
  PreferencesResponse updatePreferences(
      CurrentUser currentUser, @Valid @RequestBody PreferencesRequest request) {
    return PreferencesResponse.from(
        authService.updatePreferences(
            currentUser.userId(),
            request.timezone(),
            request.aliveCheckEnabled(),
            request.aliveCheckIntervalPercent(),
            request.dayEndLocalTime()));
  }

  record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  record RefreshTokenRequest(@NotBlank String refreshToken) {}

  record PreferencesRequest(
      @NotBlank String timezone,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean aliveCheckEnabled,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "50")
          @Min(1)
          @Max(50)
          int aliveCheckIntervalPercent,
      @NotBlank String dayEndLocalTime) {}

  record UserResponse(
      String id, String username, String displayName, String role, String timezone) {
    static UserResponse from(User user) {
      return new UserResponse(
          user.id(), user.username(), user.displayName(), user.role(), user.timezone());
    }
  }

  record PreferencesResponse(
      String timezone,
      boolean aliveCheckEnabled,
      int aliveCheckIntervalPercent,
      String dayEndLocalTime) {
    static PreferencesResponse from(User user) {
      return new PreferencesResponse(
          user.timezone(),
          !"OFF".equals(user.aliveCheckLevel()),
          user.aliveCheckIntervalPercent(),
          user.dayEndLocalTime());
    }
  }
}
