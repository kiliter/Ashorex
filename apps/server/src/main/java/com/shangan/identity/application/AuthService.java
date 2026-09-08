package com.shangan.identity.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.JwtService;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排登录、Token 轮换、账号维护与首次管理员创建事务。 */
@Service
public class AuthService {

  private static final int MIN_PASSWORD_LENGTH = 8;

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
    ensureActive(user);
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
    ensureActive(user);
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

  /** 修改本人密码；必须提供当前密码，成功后撤销全部旧会话。 */
  @Transactional
  public void changePassword(String userId, String currentPassword, String newPassword) {
    User user = getUser(userId);
    if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_MISMATCH", "当前密码不正确");
    }
    validatePassword(newPassword);
    Instant now = clock.instant();
    users.updatePasswordHash(userId, passwordEncoder.encode(newPassword), now);
    users.revokeRefreshTokensByUserId(userId, now);
  }

  /** 修改本人时区；时区决定该用户的每日边界与全部统计口径。 */
  @Transactional
  public User changeTimezone(String userId, String timezone) {
    validateTimezone(timezone);
    users.updateTimezone(userId, timezone, clock.instant());
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
            username.trim(),
            passwordEncoder.encode(password),
            "管理员",
            "Asia/Shanghai",
            UserStatus.ACTIVE,
            null,
            Set.of(UserRole.ADMIN, UserRole.LEARNER)),
        now);
  }

  /** 管理员创建学习用户；用户名唯一性与密码强度在服务端统一校验。 */
  @Transactional
  public User createManagedUser(
      String username, String displayName, String password, String timezone, boolean supervisor) {
    String normalizedUsername = username == null ? "" : username.trim();
    String normalizedDisplayName = displayName == null ? "" : displayName.trim();
    String normalizedTimezone =
        timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
    if (normalizedUsername.length() < 3 || normalizedUsername.length() > 32) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "AUTH_USERNAME_INVALID", "用户名长度必须在 3 到 32 之间");
    }
    validatePassword(password);
    validateTimezone(normalizedTimezone);
    if (users.findByUsername(normalizedUsername).isPresent()) {
      throw new BusinessException(HttpStatus.CONFLICT, "AUTH_USERNAME_TAKEN", "用户名已存在");
    }
    Set<UserRole> roles =
        supervisor ? Set.of(UserRole.LEARNER, UserRole.SUPERVISOR) : Set.of(UserRole.LEARNER);
    User user =
        new User(
            idGenerator.nextId(),
            normalizedUsername,
            passwordEncoder.encode(password),
            normalizedDisplayName.isEmpty() ? normalizedUsername : normalizedDisplayName,
            normalizedTimezone,
            UserStatus.ACTIVE,
            null,
            roles);
    users.insert(user, clock.instant());
    return user;
  }

  /** 管理员重置他人密码，并撤销该用户全部会话。 */
  @Transactional
  public void resetPassword(String userId, String newPassword) {
    validatePassword(newPassword);
    User user = getUser(userId);
    Instant now = clock.instant();
    users.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword), now);
    users.revokeRefreshTokensByUserId(user.id(), now);
  }

  /** 授予或收回督学人身份。 */
  @Transactional
  public void setSupervisorRole(String userId, boolean supervisor) {
    User user = getUser(userId);
    if (supervisor) {
      users.addRole(user.id(), UserRole.SUPERVISOR, clock.instant());
    } else {
      users.removeRole(user.id(), UserRole.SUPERVISOR);
    }
  }

  /** 更新显示名与时区。 */
  @Transactional
  public User updateProfile(String userId, String displayName, String timezone) {
    User user = getUser(userId);
    Instant now = clock.instant();
    if (displayName != null && !displayName.isBlank()) {
      users.updateDisplayName(user.id(), displayName.trim(), now);
    }
    if (timezone != null && !timezone.isBlank()) {
      validateTimezone(timezone.trim());
      users.updateTimezone(user.id(), timezone.trim(), now);
    }
    return getUser(userId);
  }

  @Transactional(readOnly = true)
  public List<User> listUsers() {
    return users.findAll();
  }

  @Transactional(readOnly = true)
  public List<User> listActiveUsers() {
    return users.findActive();
  }

  private void ensureActive(User user) {
    if (!user.active()) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "AUTH_USER_ARCHIVED", "账号已归档");
    }
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_WEAK", "密码至少需要 " + MIN_PASSWORD_LENGTH + " 位");
    }
  }

  private void validateTimezone(String timezone) {
    try {
      ZoneId.of(timezone);
    } catch (RuntimeException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "AUTH_TIMEZONE_INVALID", "时区不是合法的 IANA 名称");
    }
  }

  private TokenPair issueTokenPair(User user) {
    Instant now = clock.instant();
    JwtService.AccessToken accessToken = jwtService.issueAccessToken(user, now);
    JwtService.RefreshToken refreshToken = jwtService.issueRefreshToken(now);
    users.insertRefreshToken(
        idGenerator.nextId(), user.id(), refreshToken.hash(), refreshToken.expiresAt(), now);
    return new TokenPair(
        accessToken.value(),
        accessToken.expiresAt(),
        refreshToken.value(),
        refreshToken.expiresAt(),
        user);
  }

  private BusinessException invalidCredentials() {
    return new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "用户名或密码不正确");
  }

  private BusinessException invalidRefreshToken() {
    return new BusinessException(
        HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "刷新令牌无效或已过期");
  }

  /** 登录与刷新的返回结果，包含最新用户快照供客户端直接展示。 */
  public record TokenPair(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt,
      User user) {}
}
