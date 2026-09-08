package com.shangan.identity.application;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 用户时区换算的显式应用服务。
 *
 * <p>「一天」的边界、统计归集与催办扫描全部依赖用户 IANA 时区，其他模块只能通过本服务换算， 不允许自行读取用户表或调用 {@code LocalDate.now()}。
 */
@Service
public class UserTimeService {

  private final UserRepository users;
  private final Clock clock;

  public UserTimeService(UserRepository users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  public ZoneId zoneOf(String userId) {
    return zoneOf(requireUser(userId));
  }

  public ZoneId zoneOf(User user) {
    try {
      return ZoneId.of(user.timezone());
    } catch (RuntimeException exception) {
      return ZoneId.of("Asia/Shanghai");
    }
  }

  /** 该用户当前所在日期，即今日 Todo 的 local_date。 */
  public LocalDate today(String userId) {
    return LocalDate.ofInstant(clock.instant(), zoneOf(userId));
  }

  public LocalDate today(User user) {
    return LocalDate.ofInstant(clock.instant(), zoneOf(user));
  }

  public LocalDateTime localNow(User user) {
    return LocalDateTime.ofInstant(clock.instant(), zoneOf(user));
  }

  public LocalTime localTimeNow(User user) {
    return localNow(user).toLocalTime();
  }

  /** 把某个瞬间归集到该用户的本地日期，用于统计按天聚合。 */
  public LocalDate localDateOf(User user, Instant instant) {
    return LocalDate.ofInstant(instant, zoneOf(user));
  }

  /** 该用户某个本地日期的起始瞬间（含）。 */
  public Instant startOfDay(User user, LocalDate date) {
    return date.atStartOfDay(zoneOf(user)).toInstant();
  }

  /** 该用户某个本地日期的结束瞬间（不含）。 */
  public Instant endOfDayExclusive(User user, LocalDate date) {
    return date.plusDays(1).atStartOfDay(zoneOf(user)).toInstant();
  }

  public User requireUser(String userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND", "用户不存在"));
  }
}
