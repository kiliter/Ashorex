package com.shangan.learning.api;

/** 心跳后的服务端可信位置、纠偏和验活指令。 */
public record WatchHeartbeatResponse(
    long trustedPositionMs,
    long verifiedWatchMs,
    boolean seekAllowed,
    boolean aliveCheckRequired,
    boolean completed,
    String status) {}
