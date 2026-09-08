package com.shangan.identity.api;

import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.AuthService.TokenPair;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 移动端身份认证 API。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return TokenResponse.from(authService.login(request.username(), request.password()));
  }

  @PostMapping("/refresh")
  TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return TokenResponse.from(authService.refresh(request.refreshToken()));
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }

  record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  record RefreshTokenRequest(@NotBlank String refreshToken) {}

  /** 登录响应；不返回密码哈希，角色以字符串数组给出。 */
  record TokenResponse(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      AuthenticatedUser user) {

    static TokenResponse from(TokenPair pair) {
      return new TokenResponse(
          pair.accessToken(),
          pair.accessTokenExpiresAt(),
          pair.refreshToken(),
          pair.refreshTokenExpiresAt(),
          AuthenticatedUser.from(pair.user()));
    }
  }

  record AuthenticatedUser(
      String id, String username, String displayName, String timezone, List<String> roles) {

    static AuthenticatedUser from(User user) {
      return new AuthenticatedUser(
          user.id(),
          user.username(),
          user.displayName(),
          user.timezone(),
          user.roles().stream().map(UserRole::name).sorted().toList());
    }
  }
}
