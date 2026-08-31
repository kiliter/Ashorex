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
            value("app.emby.base-url"), value("app.emby.api-key"), value("app.emby.user-id")),
        new RuntimeIntegrationSettings.Asr(
            value("app.asr.base-url"),
            value("app.asr.api-key"),
            valueOr("app.asr.model", RuntimeIntegrationSettings.DEFAULT_ASR_MODEL),
            valueOr("app.asr.language", "Chinese"),
            integer("app.asr.chunk-duration-seconds", 30),
            integer("app.asr.timeout-seconds", 1800)),
        new RuntimeIntegrationSettings.Llm(
            value("app.llm.base-url"),
            value("app.llm.api-key"),
            value("app.llm.model"),
            integer("app.llm.context-length", 131072),
            integer("app.llm.max-completion-tokens", 8192),
            integer("app.llm.timeout-seconds", 300)),
        new RuntimeIntegrationSettings.OpenRouter(value("app.openrouter.api-key")),
        new RuntimeIntegrationSettings.AutoFill(
            Boolean.parseBoolean(valueOr("app.content-auto-fill.enabled", "false")),
            integer("app.content-auto-fill.interval-minutes", 15)),
        0);
  }

  private String value(String key) {
    return environment.getProperty(key, "");
  }

  private String valueOr(String key, String fallback) {
    return environment.getProperty(key, fallback);
  }

  private int integer(String key, int fallback) {
    return environment.getProperty(key, Integer.class, fallback);
  }
}
