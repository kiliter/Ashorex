package com.shangan.presence.domain;

/** 在线状态；IDLE 表示心跳正常但长时间没有有效学习操作。 */
public enum PresenceState {
  ONLINE,
  IDLE,
  OFFLINE
}
