package com.shangan.identity.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.JwtService;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排登录、Token 轮换、偏好更新和首次管理员创建事务。 */
@Service
public class AuthService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public AuthService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      IdGenerator idGenerator,
      Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 校验用户名密码并签发一对新 Token。 */
  @Transactional
  public TokenPair login(String username, String password) {
    User user = users.findByUsername(username).orElseThrow(this::invalidCredentials);
    if (!passwordEncoder.matches(password, user.passwordHash())) {
      throw invalidCredentials();
    }
    ensureEnabled(user);
    return issueTokenPair(user);
  }

  /** 原子撤销旧 Refresh Token 并签发新 Token，禁止旧 Token 再次使用。 */
  @Transactional
  public TokenPair refresh(String refreshToken) {
    Instant now = clock.instant();
    UserRepository.RefreshTokenRecord stored =
        users
            .findRefreshTokenByHash(jwtService.hashRefreshToken(refreshToken))
            .filter(token -> token.revokedAt() == null)
            .filter(token -> token.expiresAt().isAfter(now))
            .orElseThrow(this::invalidRefreshToken);
    User user = users.findById(stored.userId()).orElseThrow(this::invalidRefreshToken);
    ensureEnabled(user);
    users.revokeRefreshToken(stored.id(), now);
    return issueTokenPair(user);
  }

  /** 幂等撤销当前 Refresh Token；未知 Token 不暴露是否存在。 */
  @Transactional
  public void logout(String refreshToken) {
    users
        .findRefreshTokenByHash(jwtService.hashRefreshToken(refreshToken))
        .ifPresent(token -> users.revokeRefreshToken(token.id(), clock.instant()));
  }

  @Transactional(readOnly = true)
  public User getUser(String userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND", "用户不存在"));
  }

  /** 更新经服务端验证的用户偏好，并返回最新用户快照。 */
  @Transactional
  public User updatePreferences(
      String userId, String timezone, String aliveCheckLevel, String dayEndLocalTime) {
    validatePreferences(timezone, aliveCheckLevel, dayEndLocalTime);
    users.updatePreferences(userId, timezone, aliveCheckLevel, dayEndLocalTime, clock.instant());
    return getUser(userId);
  }

  /** 仅在数据库没有管理员且两个引导参数都存在时创建首次管理员。 */
  @Transactional
  public void bootstrapAdministrator(String username, String password) {
    if (username == null
        || username.isBlank()
        || password == null
        || password.isBlank()
        || users.hasAdministrator()
        || users.findByUsername(username).isPresent()) {
      return;
    }
    Instant now = clock.instant();
    users.insert(
        new User(
            idGenerator.nextId(),
            username,
            passwordEncoder.encode(password),
            "管理员",
            "ADMIN",
            "Asia/Shanghai",
            "NORMAL",
            "23:59",
            true),
        now);
  }

  private TokenPair issueTokenPair(User user) {
    Instant now = clock.instant();
    String refreshToken = jwtService.createRefreshToken();
    users.insertRefreshToken(
        idGenerator.nextId(),
        user.id(),
        jwtService.hashRefreshToken(refreshToken),
        now.plus(JwtService.REFRESH_TOKEN_LIFETIME),
        now);
    return new TokenPair(
        jwtService.createAccessToken(user),
        refreshToken,
        JwtService.ACCESS_TOKEN_LIFETIME.toSeconds());
  }

  private void ensureEnabled(User user) {
    if (!user.enabled()) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "AUTH_USER_DISABLED", "用户已被禁用");
    }
  }

  private void validatePreferences(
      String timezone, String aliveCheckLevel, String dayEndLocalTime) {
    try {
      java.time.ZoneId.of(timezone);
      java.time.LocalTime.parse(dayEndLocalTime);
    } catch (java.time.DateTimeException exception) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "PREFERENCES_INVALID", "时区或日终时间不合法");
    }
    if (!java.util.Set.of("OFF", "NORMAL", "STRICT", "INTENSE").contains(aliveCheckLevel)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "PREFERENCES_INVALID", "验活等级不合法");
    }
  }

  private BusinessException invalidCredentials() {
    return new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "用户名或密码错误");
  }

  private BusinessException invalidRefreshToken() {
    return new BusinessException(
        HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "Refresh Token 无效");
  }

  /** 返回客户端的 Token 对，不包含数据库摘要。 */
  public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}
}
