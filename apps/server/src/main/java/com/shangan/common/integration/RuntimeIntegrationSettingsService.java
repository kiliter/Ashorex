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

/** 校验、持久化并原子发布当前 Emby 运行时配置。 */
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
    if (!errors.isEmpty()) throw new IntegrationSettingsValidationException(errors);
    return new RuntimeIntegrationSettings(emby, clock.instant().toEpochMilli());
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
