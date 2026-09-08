package com.shangan.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

/** 直接驱动安全链使用的认证转换器与授权判断，不连接数据库。 */
class ActiveAccountSecurityTest {
  private final UserRepository users = mock(UserRepository.class);
  private final SecurityConfiguration configuration = new SecurityConfiguration();

  @Test
  void 归档后的有效签名令牌不再接受() {
    when(users.findById("user-1"))
        .thenReturn(Optional.of(account(UserStatus.ARCHIVED, UserRole.LEARNER)));
    assertThatThrownBy(() -> configuration.jwtAuthenticationConverter(users).convert(token()))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void 已删除用户的旧令牌不再接受() {
    when(users.findById("user-1")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> configuration.jwtAuthenticationConverter(users).convert(token()))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void 角色取当前账号而非旧令牌里的管理员声明() {
    when(users.findById("user-1"))
        .thenReturn(Optional.of(account(UserStatus.ACTIVE, UserRole.LEARNER)));
    // 权限是无序集合；严格校验全部成员，不依赖不同 JVM 的遍历顺序。
    assertThat(configuration.jwtAuthenticationConverter(users).convert(token()).getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .containsExactlyInAnyOrder("FACTOR_BEARER", "ROLE_LEARNER");
  }

  @Test
  void 归档或移除管理员角色后已有Session不再授权() {
    var session =
        new UsernamePasswordAuthenticationToken(
            "demo", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(users.findByUsername("demo"))
        .thenReturn(Optional.of(account(UserStatus.ACTIVE, UserRole.ADMIN)));
    assertThat(SecurityConfiguration.activeAdministrator(session, users)).isTrue();
    when(users.findByUsername("demo"))
        .thenReturn(Optional.of(account(UserStatus.ARCHIVED, UserRole.ADMIN)));
    assertThat(SecurityConfiguration.activeAdministrator(session, users)).isFalse();
    when(users.findByUsername("demo"))
        .thenReturn(Optional.of(account(UserStatus.ACTIVE, UserRole.LEARNER)));
    assertThat(SecurityConfiguration.activeAdministrator(session, users)).isFalse();
  }

  /** 构造已签名校验后的旧声明，模拟权限变更发生在令牌签发之后。 */
  private Jwt token() {
    return Jwt.withTokenValue("test-only")
        .header("alg", "HS256")
        .subject("user-1")
        .claim("roles", List.of("ADMIN"))
        .build();
  }

  private User account(UserStatus status, UserRole role) {
    return new User("user-1", "demo", "unused", "测试", "Asia/Shanghai", status, null, Set.of(role));
  }
}
