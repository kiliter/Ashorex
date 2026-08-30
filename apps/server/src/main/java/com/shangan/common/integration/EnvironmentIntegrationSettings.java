package com.shangan.common.integration;

import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 将现有 Spring 环境变量映射成首次启动使用的完整配置快照。 */
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
        new RuntimeIntegrationSettings.Llm(
            value("app.ai.llm.base-url"),
            value("app.ai.llm.api-key"),
            value("app.ai.llm.model"),
            integer("app.ai.llm.max-context-tokens", 16_000),
            decimal("app.ai.llm.temperature", 0.2),
            seconds("app.ai.llm.timeout", Duration.ofMinutes(2))),
        new RuntimeIntegrationSettings.Asr(
            value("app.ai.asr.base-url"),
            value("app.ai.asr.api-key"),
            value("app.ai.asr.model"),
            seconds("app.ai.asr.timeout", Duration.ofMinutes(2))),
        new RuntimeIntegrationSettings.Mcp(
            value("app.ai.mcp.url"),
            value("app.ai.mcp.bearer-token"),
            environment.getProperty("app.ai.mcp.allowed-tools", "web_search,web_extract"),
            seconds("app.ai.mcp.timeout", Duration.ofSeconds(20))),
        0);
  }

  private String value(String key) {
    return environment.getProperty(key, "");
  }

  private int integer(String key, int fallback) {
    return environment.getProperty(key, Integer.class, fallback);
  }

  private double decimal(String key, double fallback) {
    return environment.getProperty(key, Double.class, fallback);
  }

  private int seconds(String key, Duration fallback) {
    Duration duration = environment.getProperty(key, Duration.class, fallback);
    return Math.toIntExact(duration.toSeconds());
  }
}
