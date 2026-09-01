package com.shangan.common.integration;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 校验、持久化并原子发布当前外部服务运行时配置。 */
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
    RuntimeIntegrationSettings.Asr asr =
        new RuntimeIntegrationSettings.Asr(
            url("asrBaseUrl", "ASR Base URL", submitted.asr().baseUrl(), errors),
            text(submitted.asr().apiKey()),
            text(submitted.asr().model()),
            text(submitted.asr().language()),
            range(
                "asrChunkDurationSeconds",
                "ASR 分片秒数",
                submitted.asr().chunkDurationSeconds(),
                5,
                600,
                errors),
            range(
                "asrTimeoutSeconds",
                "ASR 超时秒数",
                submitted.asr().timeoutSeconds(),
                1,
                7200,
                errors));
    RuntimeIntegrationSettings.Llm llm =
        new RuntimeIntegrationSettings.Llm(
            url("llmBaseUrl", "LLM Base URL", submitted.llm().baseUrl(), errors),
            text(submitted.llm().apiKey()),
            text(submitted.llm().model()),
            range(
                "llmContextLength",
                "LLM 上下文长度",
                submitted.llm().contextLength(),
                4096,
                2_000_000,
                errors),
            // 最大输出直接采用 OpenRouter 模型目录或管理员手工配置的值，不额外限制。
            submitted.llm().maxCompletionTokens(),
            range(
                "llmTimeoutSeconds", "LLM 超时秒数", submitted.llm().timeoutSeconds(), 1, 1800, errors),
            text(submitted.llm().reasoningEffort()));
    RuntimeIntegrationSettings.AutoFill autoFill =
        new RuntimeIntegrationSettings.AutoFill(
            submitted.autoFill().enabled(),
            range(
                "autoFillIntervalMinutes",
                "自动补全扫描间隔",
                submitted.autoFill().intervalMinutes(),
                1,
                1440,
                errors));
    long inputBudget = (long) llm.contextLength() - llm.maxCompletionTokens() - 2048L;
    if (llm.maxCompletionTokens() <= 0 || inputBudget < 256L) {
      errors.put("llmMaxCompletionTokens", "LLM 输出预算必须为正文至少预留 256 Tokens");
    }
    if (!errors.isEmpty()) throw new IntegrationSettingsValidationException(errors);
    return new RuntimeIntegrationSettings(
        emby,
        asr,
        llm,
        new RuntimeIntegrationSettings.OpenRouter(text(submitted.openRouter().apiKey())),
        autoFill,
        clock.instant().toEpochMilli());
  }

  private int range(
      String field, String label, int value, int minimum, int maximum, Map<String, String> errors) {
    if (value < minimum || value > maximum) {
      errors.put(field, label + "必须在 " + minimum + " 到 " + maximum + " 之间");
    }
    return value;
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

  private String text(String value) {
    return value == null ? "" : value.trim();
  }
}
