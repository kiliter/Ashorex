package com.shangan.admin;

import com.shangan.common.integration.RuntimeIntegrationSettings;

/** 管理后台 Emby 配置表单。 */
public record IntegrationSettingsForm(String embyBaseUrl, String embyApiKey, String embyUserId) {

  public IntegrationSettingsForm {
    embyBaseUrl = safe(embyBaseUrl);
    embyApiKey = safe(embyApiKey);
    embyUserId = safe(embyUserId);
  }

  /** 将当前不可变快照转换成可回填的表单。 */
  public static IntegrationSettingsForm from(RuntimeIntegrationSettings value) {
    return new IntegrationSettingsForm(
        value.emby().baseUrl(), value.emby().apiKey(), value.emby().userId());
  }

  /** 构造待校验的 Emby 配置快照。 */
  public RuntimeIntegrationSettings toSettings() {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(embyBaseUrl, embyApiKey, embyUserId), 0);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
