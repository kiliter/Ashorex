package com.shangan.common.integration;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 将 Spring 环境中的 Emby 变量映射成首次启动使用的配置快照。 */
@Component
public class EnvironmentIntegrationSettings {

  private final Environment environment;

  public EnvironmentIntegrationSettings(Environment environment) {
    this.environment = environment;
  }

  /** 数据库尚无配置行时调用；不会把环境值写入数据库。 */
  public RuntimeIntegrationSettings snapshot() {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(
            value("app.emby.base-url"), value("app.emby.api-key"), value("app.emby.user-id")),
        0);
  }

  private String value(String key) {
    return environment.getProperty(key, "");
  }
}
