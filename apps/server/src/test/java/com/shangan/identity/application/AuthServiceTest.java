package com.shangan.identity.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.IdGenerator;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.JwtService;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 密码维护必须同时撤销 App Refresh Token 与管理后台持久化 Session。 */
class AuthServiceTest {

  @Test
  void 管理员重置密码按用户名撤销全部后台Session() {
    Instant now = Instant.parse("2026-09-10T02:00:00Z");
    UserRepository users = mock(UserRepository.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    User user =
        new User(
            "user-1",
            "admin",
            "old-hash",
            "管理员",
            "Asia/Shanghai",
            UserStatus.ACTIVE,
            null,
            Set.of(UserRole.ADMIN));
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    when(encoder.encode("new-password")).thenReturn("new-hash");
    AuthService service =
        new AuthService(
            users,
            encoder,
            mock(JwtService.class),
            mock(IdGenerator.class),
            Clock.fixed(now, ZoneOffset.UTC));

    service.resetPassword(user.id(), "new-password");

    verify(users).updatePasswordHash(user.id(), "new-hash", now);
    verify(users).revokeRefreshTokensByUserId(user.id(), now);
    verify(users).revokeAdminSessionsByPrincipal("admin");
  }
}
