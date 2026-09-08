package com.shangan.common.integration;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 将 Spring 环境中的外部服务变量映射成首次启动使用的配置快照。 */
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
            value("app.emby.base-url"),
            value("app.emby.api-key"),
            value("app.emby.user-id"),
            integer("app.emby.timeout-seconds", 10)),
        java.util.List.of(),
        new RuntimeIntegrationSettings.ServerChan(
            value("app.serverchan.send-key"),
            integer("app.serverchan.timeout-seconds", 8),
            bool("app.serverchan.nag-enabled", true),
            bool("app.serverchan.daily-digest-enabled", false)),
        new RuntimeIntegrationSettings.Features(
            bool("app.features.document-resources", false),
            integer("app.features.max-document-size-mb", 200)),
        0L);
  }

  private String value(String key) {
    return environment.getProperty(key, "");
  }

  private int integer(String key, int fallback) {
    return environment.getProperty(key, Integer.class, fallback);
  }

  private boolean bool(String key, boolean fallback) {
    return environment.getProperty(key, Boolean.class, fallback);
  }
}
