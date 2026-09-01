package com.shangan.identity.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.JwtService;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
  private final List<NewUserInitializer> newUserInitializers;
  private final Clock clock;

  public AuthService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      IdGenerator idGenerator,
      List<NewUserInitializer> newUserInitializers,
      Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.idGenerator = idGenerator;
    this.newUserInitializers = newUserInitializers;
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
      String userId,
      String timezone,
      boolean aliveCheckEnabled,
      int aliveCheckIntervalPercent,
      String dayEndLocalTime) {
    User user = getUser(userId);
    validatePreferences(timezone, aliveCheckIntervalPercent, dayEndLocalTime);
    users.updatePreferences(
        userId,
        timezone,
        aliveCheckEnabled ? "NORMAL" : "OFF",
        aliveCheckIntervalPercent,
        dayEndLocalTime,
        clock.instant());
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
    User administrator =
        new User(
            idGenerator.nextId(),
            username,
            passwordEncoder.encode(password),
            "管理员",
            "ADMIN",
            "Asia/Shanghai",
            "NORMAL",
            50,
            "23:59",
            true);
    users.insert(administrator, now);
    newUserInitializers.forEach(initializer -> initializer.initialize(administrator.id()));
  }

  /** 管理员创建普通用户；用户名唯一性与密码强度在服务端统一校验。 */
  @Transactional
  public User createManagedUser(String username, String displayName, String password) {
    String normalizedUsername = username == null ? "" : username.trim();
    String normalizedDisplayName = displayName == null ? "" : displayName.trim();
    validateManagedUser(normalizedUsername, normalizedDisplayName, password);
    if (users.findByUsername(normalizedUsername).isPresent()) {
      throw new BusinessException(HttpStatus.CONFLICT, "USER_USERNAME_EXISTS", "用户名已存在");
    }
    Instant now = clock.instant();
    User user =
        new User(
            idGenerator.nextId(),
            normalizedUsername,
            passwordEncoder.encode(password),
            normalizedDisplayName,
            "USER",
            "Asia/Shanghai",
            "NORMAL",
            50,
            "23:59",
            true);
    users.insert(user, now);
    newUserInitializers.forEach(initializer -> initializer.initialize(user.id()));
    return user;
  }

  /** 返回后台用户列表；页面必须避免渲染 passwordHash 字段。 */
  @Transactional(readOnly = true)
  public List<User> listManagedUsers() {
    return users.findAll();
  }

  /** 管理员启停普通用户；禁用会立即撤销全部 Refresh Token。 */
  @Transactional
  public void setManagedUserEnabled(String userId, boolean enabled) {
    User user = getUser(userId);
    if ("ADMIN".equals(user.role())) {
      throw new BusinessException(HttpStatus.CONFLICT, "ADMIN_DISABLE_FORBIDDEN", "不能在用户页停用管理员");
    }
    Instant now = clock.instant();
    users.setEnabled(userId, enabled, now);
    if (!enabled) {
      users.revokeRefreshTokensByUserId(userId, now);
    }
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
      String timezone, int aliveCheckIntervalPercent, String dayEndLocalTime) {
    try {
      java.time.ZoneId.of(timezone);
      java.time.LocalTime.parse(dayEndLocalTime);
    } catch (java.time.DateTimeException exception) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "PREFERENCES_INVALID", "时区或日终时间不合法");
    }
    if (aliveCheckIntervalPercent < 1 || aliveCheckIntervalPercent > 50) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "PREFERENCES_INVALID", "验活进度间隔必须为 1% 到 50%");
    }
  }

  private void validateManagedUser(String username, String displayName, String password) {
    if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "USER_USERNAME_INVALID", "用户名需为 3 到 64 位字母、数字或 ._- 字符");
    }
    if (displayName.isBlank() || displayName.length() > 64) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "USER_DISPLAY_NAME_INVALID", "显示名称不能为空且最多 64 字");
    }
    if (password == null || password.length() < 12 || password.length() > 128) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "USER_PASSWORD_INVALID", "密码长度需为 12 到 128 位");
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
