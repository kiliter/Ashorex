package com.shangan.common.integration;

/** Emby 的不可变运行时配置快照。 */
public record RuntimeIntegrationSettings(Emby emby, long updatedAt) {

  /** Emby 固定源站配置；用户 ID 可为空，但地址和密钥必须同时存在才视为已配置。 */
  public record Emby(String baseUrl, String apiKey, String userId) {
    public boolean configured() {
      return present(baseUrl) && present(apiKey);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
