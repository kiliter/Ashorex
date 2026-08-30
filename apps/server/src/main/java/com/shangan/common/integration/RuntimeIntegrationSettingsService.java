package com.shangan.common.integration;

import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 校验、持久化并原子发布当前运行时外部服务配置。 */
@Service
public class RuntimeIntegrationSettingsService implements IntegrationSettingsProvider {

  private final RuntimeIntegrationSettingsRepository repository;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final AtomicReference<RuntimeIntegrationSettings> current;

  public RuntimeIntegrationSettingsService(
      RuntimeIntegrationSettingsRepository repository,
      EnvironmentIntegrationSettings environment,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.current = new AtomicReference<>(repository.find().orElseGet(environment::snapshot));
  }

  @Override
  public RuntimeIntegrationSettings current() {
    return current.get();
  }

  /** 在数据库提交完成后才替换内存快照，提交失败时旧配置继续可用。 */
  public RuntimeIntegrationSettings save(RuntimeIntegrationSettings submitted) {
    RuntimeIntegrationSettings validated = validate(submitted);
    RuntimeIntegrationSettings committed =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  repository.replace(validated);
                  return validated;
                }));
    current.set(committed);
    return committed;
  }

  private RuntimeIntegrationSettings validate(RuntimeIntegrationSettings submitted) {
    Map<String, String> errors = new LinkedHashMap<>();
    RuntimeIntegrationSettings.Emby emby =
        new RuntimeIntegrationSettings.Emby(
            url("embyBaseUrl", "Emby Base URL", submitted.emby().baseUrl(), errors),
            text(submitted.emby().apiKey()),
            text(submitted.emby().userId()));
    RuntimeIntegrationSettings.Llm llm =
        new RuntimeIntegrationSettings.Llm(
            url("llmBaseUrl", "LLM Base URL", submitted.llm().baseUrl(), errors),
            text(submitted.llm().apiKey()),
            text(submitted.llm().model()),
            range(
                "llmMaxContextTokens",
                "最大上下文 Token 数",
                submitted.llm().maxContextTokens(),
                1_024,
                1_000_000,
                errors),
            range("llmTemperature", "Temperature", submitted.llm().temperature(), 0, 2, errors),
            range("llmTimeoutSeconds", "LLM 超时", submitted.llm().timeoutSeconds(), 1, 600, errors));
    RuntimeIntegrationSettings.Asr asr =
        new RuntimeIntegrationSettings.Asr(
            url("asrBaseUrl", "ASR Base URL", submitted.asr().baseUrl(), errors),
            text(submitted.asr().apiKey()),
            text(submitted.asr().model()),
            range(
                "asrTimeoutSeconds", "ASR 超时", submitted.asr().timeoutSeconds(), 1, 1_800, errors));
    RuntimeIntegrationSettings.Mcp mcp =
        new RuntimeIntegrationSettings.Mcp(
            url("mcpUrl", "MCP URL", submitted.mcp().url(), errors),
            text(submitted.mcp().bearerToken()),
            allowedTools(submitted.mcp().allowedTools()),
            range("mcpTimeoutSeconds", "MCP 超时", submitted.mcp().timeoutSeconds(), 1, 120, errors));
    if (!errors.isEmpty()) throw new IntegrationSettingsValidationException(errors);
    return new RuntimeIntegrationSettings(emby, llm, asr, mcp, clock.instant().toEpochMilli());
  }

  private String url(String field, String label, String raw, Map<String, String> errors) {
    String value = text(raw);
    if (value.isBlank()) return value;
    try {
      URI uri = URI.create(value);
      boolean http =
          "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
      if (!http
          || uri.getHost() == null
          || uri.getHost().isBlank()
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
      return value.replaceAll("/+$", "");
    } catch (IllegalArgumentException exception) {
      errors.put(field, label + " 必须是完整的 HTTP 或 HTTPS 地址，且不能包含账号、Query 或 Fragment");
      return value;
    }
  }

  private int range(
      String field, String label, int value, int minimum, int maximum, Map<String, String> errors) {
    if (value < minimum || value > maximum) {
      errors.put(field, label + " 必须在 " + minimum + " 到 " + maximum + " 之间");
    }
    return value;
  }

  private double range(
      String field,
      String label,
      double value,
      double minimum,
      double maximum,
      Map<String, String> errors) {
    if (!Double.isFinite(value) || value < minimum || value > maximum) {
      errors.put(field, label + " 必须在 0 到 2 之间");
    }
    return value;
  }

  private String allowedTools(String raw) {
    return Arrays.stream(text(raw).split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(
            Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new), values -> String.join(",", values)));
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }
}
