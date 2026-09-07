package com.shangan.learning.api;

/** 固定步长快进结果：跳转位置与通过边界分开，支持在已通过区间内回看。 */
public record WatchSeekResponse(long positionMs, WatchHeartbeatResponse progress) {}
