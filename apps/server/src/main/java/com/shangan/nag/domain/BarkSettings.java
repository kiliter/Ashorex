package com.shangan.nag.domain;

/** 每个学员独立的 Bark 目的地，密钥不得直接序列化到 API 响应。 */
public record BarkSettings(String baseUrl, String deviceKey, boolean enabled) {
  public static BarkSettings defaults() {
    return new BarkSettings("https://api.day.app", "", false);
  }

  public boolean configured() {
    return deviceKey != null && !deviceKey.isBlank();
  }
}
