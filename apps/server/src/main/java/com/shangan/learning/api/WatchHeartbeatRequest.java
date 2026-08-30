package com.shangan.learning.api;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 客户端每 10 秒提交的最小可信观看状态。 */
public record WatchHeartbeatRequest(
    @Positive long sequence,
    @PositiveOrZero long positionMs,
    boolean playing,
    boolean foreground) {}
