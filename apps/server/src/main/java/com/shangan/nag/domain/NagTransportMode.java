package com.shangan.nag.domain;

/** 全局客户端通知方式；心跳始终存在，SSE 只负责缩短通知延迟。 */
public enum NagTransportMode {
  SSE,
  HEARTBEAT
}
