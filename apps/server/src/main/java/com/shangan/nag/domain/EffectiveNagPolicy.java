package com.shangan.nag.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;

/**
 * 生效催办策略。
 *
 * <p>由全局默认与按用户覆盖合并得出；调度类字段（扫描周期、在线宽限、心跳间隔）只取全局值， 避免每用户不同扫描周期带来的调度复杂度（见 ADR-0026）。
 */
public record EffectiveNagPolicy(
    int scanIntervalMinutes,
    int presenceGraceSeconds,
    int heartbeatIntervalSeconds,
    int firstThresholdMinutes,
    int repeatIntervalMinutes,
    int dailyMax,
    int fullscreenTimeoutMinutes,
    LocalTime quietStart,
    LocalTime quietEnd,
    int minPending,
    int minReasonLength,
    boolean channelFullscreenEnabled,
    boolean channelServerchanEnabled,
    String messageTemplate,
    boolean notifySupervisorOnBulkDelete,
    boolean notifySupervisorOnGaveUp,
    boolean notifySupervisorOnHalfDoneDelete) {

  public Duration presenceGrace() {
    return Duration.ofSeconds(presenceGraceSeconds);
  }

  public Duration fullscreenTimeout() {
    return Duration.ofMinutes(fullscreenTimeoutMinutes);
  }

  /** 免打扰时段允许跨零点，例如 23:30 – 07:00。 */
  public boolean inQuietHours(LocalTime localTime) {
    if (quietStart.equals(quietEnd)) {
      return false;
    }
    if (quietStart.isBefore(quietEnd)) {
      return !localTime.isBefore(quietStart) && localTime.isBefore(quietEnd);
    }
    return !localTime.isBefore(quietStart) || localTime.isBefore(quietEnd);
  }

  /**
   * 计算当前空闲时长对应的催办档位。
   *
   * <p>未达首次阈值时返回空；{@code repeatIntervalMinutes} 为 0 时档位恒为 1，即当天只催一次。
   */
  public Optional<Integer> thresholdLevel(long idleMinutes) {
    if (idleMinutes < firstThresholdMinutes) {
      return Optional.empty();
    }
    if (repeatIntervalMinutes <= 0) {
      return Optional.of(1);
    }
    long extra = (idleMinutes - firstThresholdMinutes) / repeatIntervalMinutes;
    return Optional.of((int) (1 + extra));
  }

  /** 渲染催办文案；只支持白名单变量，避免模板注入。 */
  public String renderMessage(String username, int pendingCount, long idleMinutes, String date) {
    return messageTemplate
        .replace("{{user}}", username == null ? "" : username)
        .replace("{{pending}}", String.valueOf(pendingCount))
        .replace("{{idleMinutes}}", String.valueOf(idleMinutes))
        .replace("{{date}}", date == null ? "" : date);
  }
}
