package com.shangan.common.integration;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    RuntimeIntegrationSettings stored = repository.find().orElse(null);
    // V001 会预置 updated_at=0 的空行，它只表示“尚未初始化”。
    // 管理员一旦保存（即使主动清空字段）updated_at 也不再为 0，不会被环境变量覆盖。
    this.current =
        new AtomicReference<>(
            stored == null || stored.updatedAt() == 0 ? environment.snapshot() : stored);
  }

  @Override
  public RuntimeIntegrationSettings current() {
    return current.get();
  }

  /** 在数据库提交完成后才替换内存快照，提交失败时旧配置继续可用。 */
  public RuntimeIntegrationSettings save(RuntimeIntegrationSettings submitted) {
    return commit(validate(submitted));
  }

  /** 单独更新 Emby 媒体库绑定，不要求管理员重复提交其他服务密钥。 */
  public RuntimeIntegrationSettings saveEmbyLibraries(
      List<RuntimeIntegrationSettings.EmbyLibrary> libraries) {
    RuntimeIntegrationSettings previous = current.get();
    return commit(
        validate(
            new RuntimeIntegrationSettings(
                previous.emby(),
                libraries,
                previous.serverChan(),
                previous.features(),
                clock.millis(),
                previous.bark())));
  }

  private RuntimeIntegrationSettings commit(RuntimeIntegrationSettings validated) {
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

  /** 校验失败时抛出携带逐字段错误的异常，页面可直接回显；密钥不参与格式校验。 */
  private RuntimeIntegrationSettings validate(RuntimeIntegrationSettings submitted) {
    Map<String, String> errors = new LinkedHashMap<>();
    RuntimeIntegrationSettings.Emby emby =
        new RuntimeIntegrationSettings.Emby(
            url("embyBaseUrl", "Emby Base URL", submitted.emby().baseUrl(), errors),
            text(submitted.emby().apiKey()),
            text(submitted.emby().userId()),
            range(
                "embyTimeoutSeconds",
                "Emby 超时秒数",
                submitted.emby().timeoutSeconds(),
                1,
                120,
                errors));
    RuntimeIntegrationSettings.ServerChan serverChan =
        new RuntimeIntegrationSettings.ServerChan(
            text(submitted.serverChan().sendKey()),
            range(
                "serverChanTimeoutSeconds",
                "Server 酱超时秒数",
                submitted.serverChan().timeoutSeconds(),
                1,
                60,
                errors),
            submitted.serverChan().nagEnabled(),
            submitted.serverChan().dailyDigestEnabled());
    RuntimeIntegrationSettings.Features features =
        new RuntimeIntegrationSettings.Features(
            submitted.features().documentResources(),
            range(
                "featureMaxDocumentSizeMb",
                "材料单文件上限（MB）",
                submitted.features().maxDocumentSizeMb(),
                1,
                2048,
                errors));
    List<RuntimeIntegrationSettings.EmbyLibrary> libraries =
        validatedLibraries(submitted.embyLibraries(), errors);
    RuntimeIntegrationSettings.Bark bark =
        new RuntimeIntegrationSettings.Bark(
            url("barkBaseUrl", "Bark 服务地址", submitted.bark().baseUrl(), errors),
            text(submitted.bark().deviceKey()),
            submitted.bark().enabled(),
            range(
                "barkTimeoutSeconds",
                "Bark 超时秒数",
                submitted.bark().timeoutSeconds(),
                1,
                60,
                errors));
    try {
      URI uri = URI.create(bark.baseUrl());
      if (uri.getQuery() != null || uri.getFragment() != null)
        errors.put("barkBaseUrl", "Bark 服务地址不能包含查询参数或片段");
    } catch (IllegalArgumentException ignored) {
      errors.put("barkBaseUrl", "Bark 服务地址格式不合法");
    }
    if (bark.enabled() && !bark.configured())
      errors.put("barkDeviceKey", "启用 Bark 前请填写服务地址和设备 Key");
    if (!errors.isEmpty()) {
      throw new IntegrationSettingsValidationException(errors);
    }
    return new RuntimeIntegrationSettings(
        emby, libraries, serverChan, features, clock.millis(), bark);
  }

  /** 媒体库必须有 ID 且不重复；名称缺失时回退为 ID，避免页面出现空行。 */
  private List<RuntimeIntegrationSettings.EmbyLibrary> validatedLibraries(
      List<RuntimeIntegrationSettings.EmbyLibrary> submitted, Map<String, String> errors) {
    if (submitted == null || submitted.isEmpty()) {
      return List.of();
    }
    Set<String> seen = new LinkedHashSet<>();
    List<RuntimeIntegrationSettings.EmbyLibrary> normalized = new ArrayList<>();
    for (RuntimeIntegrationSettings.EmbyLibrary library : submitted) {
      String id = text(library.id());
      if (id.isEmpty()) {
        errors.put("embyLibraries", "媒体库 ID 不能为空");
        continue;
      }
      if (!seen.add(id)) {
        continue;
      }
      String name = text(library.name());
      normalized.add(
          new RuntimeIntegrationSettings.EmbyLibrary(
              id,
              name.isEmpty() ? id : name,
              library.contentType() == null
                  ? RuntimeIntegrationSettings.EmbyLibraryType.MIXED
                  : library.contentType()));
    }
    return List.copyOf(normalized);
  }

  /** 允许留空表示未配置；填写时必须是带 scheme 与 host 的绝对地址。 */
  private String url(String field, String label, String value, Map<String, String> errors) {
    String normalized = text(value);
    if (normalized.isEmpty()) {
      return normalized;
    }
    try {
      URI uri = URI.create(normalized);
      // 媒体代理仅支持 HTTP(S)，凭据必须单独填写，不能混入会回显的地址。
      if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        errors.put(field, label + "必须是不含账号密码的 HTTP(S) 地址");
      }
    } catch (IllegalArgumentException exception) {
      errors.put(field, label + "格式不合法");
    }
    return normalized;
  }

  private int range(
      String field, String label, int value, int min, int max, Map<String, String> errors) {
    if (value < min || value > max) {
      errors.put(field, label + "必须在 " + min + " 到 " + max + " 之间");
    }
    return value;
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }
}
