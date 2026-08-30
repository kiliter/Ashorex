package com.shangan.common.integration;

import java.util.Map;

/** 保存整份外部服务配置失败时携带安全的中文字段错误。 */
public class IntegrationSettingsValidationException extends IllegalArgumentException {

  private final Map<String, String> fieldErrors;

  public IntegrationSettingsValidationException(Map<String, String> fieldErrors) {
    super(fieldErrors.values().stream().findFirst().orElse("服务配置不合法"));
    this.fieldErrors = Map.copyOf(fieldErrors);
  }

  public Map<String, String> fieldErrors() {
    return fieldErrors;
  }
}
