package com.shangan.learning.application;

import java.security.SecureRandom;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/** 依据用户监督等级，用加密安全随机数生成下一次可信观看验活阈值。 */
@Component
public class AliveCheckScheduler {
  private static final long MINUTE_MS = 60_000;
  private static final Map<String, Range> RANGES =
      Map.of(
          "NORMAL", new Range(40, 60),
          "STRICT", new Range(20, 40),
          "INTENSE", new Range(10, 25));

  private final SecureRandom random;

  public AliveCheckScheduler() {
    this.random = new SecureRandom();
  }

  /** 返回会话累计可信观看毫秒阈值；关闭监督时不生成阈值。 */
  public OptionalLong nextDueWatchMs(String level, long currentVerifiedWatchMs) {
    Range range = RANGES.get(level);
    if (range == null) return OptionalLong.empty();
    long origin = range.minimumMinutes() * MINUTE_MS;
    long bound = range.maximumMinutes() * MINUTE_MS + 1;
    return OptionalLong.of(currentVerifiedWatchMs + random.nextLong(origin, bound));
  }

  private record Range(long minimumMinutes, long maximumMinutes) {}
}
