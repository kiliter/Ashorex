package com.shangan.common.integration;

/** 外部适配器读取当前不可变配置的公共应用边界。 */
public interface IntegrationSettingsProvider {

  /** 返回调用开始时应使用的配置快照。 */
  RuntimeIntegrationSettings current();
}
