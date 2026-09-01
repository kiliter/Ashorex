package com.shangan.learning.application;

import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/** 按用户设置的视频进度百分比生成下一次验活绝对位置。 */
@Component
public class AliveCheckScheduler {
  /** 返回视频中的绝对检查点；关闭验活或下一检查点已进入完成区间时不再排期。 */
  public OptionalLong nextDuePositionMs(
      String level, int intervalPercent, long currentPositionMs, long durationMs) {
    if ("OFF".equals(level)) return OptionalLong.empty();
    if (intervalPercent < 1 || intervalPercent > 50 || durationMs <= 0) {
      throw new IllegalArgumentException("验活进度间隔必须为 1% 到 50%，且视频时长必须大于零");
    }
    long incrementMs = Math.max(1L, Math.round(durationMs * intervalPercent / 100.0d));
    long nextPositionMs = Math.max(0L, currentPositionMs) + incrementMs;
    long completionToleranceMs = Math.min(30_000L, Math.round(durationMs * 0.02d));
    long completionPositionMs = Math.max(0L, durationMs - completionToleranceMs);
    return nextPositionMs >= completionPositionMs
        ? OptionalLong.empty()
        : OptionalLong.of(nextPositionMs);
  }
}
