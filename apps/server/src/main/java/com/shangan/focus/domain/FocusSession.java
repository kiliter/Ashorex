package com.shangan.focus.domain;

import com.shangan.common.api.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 专注会话状态机；所有累计时长都由服务端传入的时刻计算。 */
public final class FocusSession {
  private final String id;
  private final String userId;
  private final String planItemId;
  private final String mediaItemId;
  private final String focusType;
  private final long plannedSeconds;
  private final Instant startedAt;
  private String status;
  private long actualSeconds;
  private Instant runningSince;
  private Instant pausedAt;
  private Instant endedAt;

  private FocusSession(
      String id,
      String userId,
      String planItemId,
      String mediaItemId,
      String focusType,
      String status,
      long plannedSeconds,
      long actualSeconds,
      Instant startedAt,
      Instant runningSince,
      Instant pausedAt,
      Instant endedAt) {
    this.id = id;
    this.userId = userId;
    this.planItemId = planItemId;
    this.mediaItemId = mediaItemId;
    this.focusType = focusType;
    this.status = status;
    this.plannedSeconds = plannedSeconds;
    this.actualSeconds = actualSeconds;
    this.startedAt = startedAt;
    this.runningSince = runningSince;
    this.pausedAt = pausedAt;
    this.endedAt = endedAt;
  }

  /** 创建运行中的会话，并验证 V1 支持的三种计时类型。 */
  public static FocusSession start(
      String id,
      String userId,
      String planItemId,
      String mediaItemId,
      String focusType,
      long plannedSeconds,
      Instant now) {
    if (!List.of("POMODORO", "PRACTICE", "MOCK_EXAM").contains(focusType) || plannedSeconds <= 0) {
      throw invalid("FOCUS_SESSION_INVALID", "专注类型或计划时长无效");
    }
    return new FocusSession(
        id,
        userId,
        planItemId,
        mediaItemId,
        focusType,
        "RUNNING",
        plannedSeconds,
        0,
        now,
        now,
        null,
        null);
  }

  /** 从数据库快照恢复状态，不触发任何状态变化。 */
  public static FocusSession restore(
      String id,
      String userId,
      String planItemId,
      String mediaItemId,
      String focusType,
      String status,
      long plannedSeconds,
      long actualSeconds,
      Instant startedAt,
      Instant runningSince,
      Instant pausedAt,
      Instant endedAt) {
    return new FocusSession(
        id,
        userId,
        planItemId,
        mediaItemId,
        focusType,
        status,
        plannedSeconds,
        actualSeconds,
        startedAt,
        runningSince,
        pausedAt,
        endedAt);
  }

  public void pause(Instant now) {
    requireStatus("RUNNING");
    accumulate(now);
    status = "PAUSED";
    pausedAt = now;
    runningSince = null;
  }

  public void resume(Instant now) {
    requireStatus("PAUSED");
    status = "RUNNING";
    runningSince = now;
    pausedAt = null;
  }

  public void finish(Instant now) {
    requireActive();
    if (status.equals("RUNNING")) accumulate(now);
    status = "FINISHED";
    runningSince = null;
    pausedAt = null;
    endedAt = now;
  }

  public void cancel(Instant now) {
    requireActive();
    if (status.equals("RUNNING")) accumulate(now);
    status = "CANCELLED";
    runningSince = null;
    pausedAt = null;
    endedAt = now;
  }

  /** 运行中展示值包含尚未持久化的当前区间，数据库累计值保持短事务更新。 */
  public long actualSecondsAt(Instant now) {
    if (!status.equals("RUNNING")) return actualSeconds;
    return actualSeconds + elapsedSeconds(runningSince, now);
  }

  private void accumulate(Instant now) {
    actualSeconds += elapsedSeconds(runningSince, now);
  }

  private long elapsedSeconds(Instant from, Instant to) {
    if (from == null || to.isBefore(from)) {
      throw invalid("FOCUS_TIME_INVALID", "服务端计时时间顺序无效");
    }
    return Duration.between(from, to).toSeconds();
  }

  private void requireActive() {
    if (!status.equals("RUNNING") && !status.equals("PAUSED")) {
      throw invalid("FOCUS_ILLEGAL_TRANSITION", "专注会话已结束，不能继续操作");
    }
  }

  private void requireStatus(String required) {
    if (!status.equals(required)) {
      throw invalid("FOCUS_ILLEGAL_TRANSITION", "当前专注状态不允许此操作");
    }
  }

  private static BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  public String id() {
    return id;
  }

  public String userId() {
    return userId;
  }

  public String planItemId() {
    return planItemId;
  }

  public String mediaItemId() {
    return mediaItemId;
  }

  public String focusType() {
    return focusType;
  }

  public String status() {
    return status;
  }

  public long plannedSeconds() {
    return plannedSeconds;
  }

  public long actualSeconds() {
    return actualSeconds;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant runningSince() {
    return runningSince;
  }

  public Instant pausedAt() {
    return pausedAt;
  }

  public Instant endedAt() {
    return endedAt;
  }
}
