package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 验证环境变量初始值、SQLite 整份覆盖和运行时原子刷新。 */
@SpringBootTest
class RuntimeIntegrationSettingsServiceTest {

  @TempDir static Path databaseDirectory;

  @Autowired RuntimeIntegrationSettingsService settings;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("runtime-settings.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.emby.base-url", () -> "https://env-emby.example.test");
    registry.add("app.emby.api-key", () -> "env-emby-key");
    registry.add("app.emby.user-id", () -> "env-user");
    registry.add("app.ai.llm.base-url", () -> "https://env-llm.example.test/v1");
    registry.add("app.ai.llm.api-key", () -> "env-llm-key");
    registry.add("app.ai.llm.model", () -> "env-model");
    registry.add("app.ai.asr.base-url", () -> "https://env-asr.example.test/v1");
    registry.add("app.ai.asr.api-key", () -> "env-asr-key");
    registry.add("app.ai.asr.model", () -> "env-asr-model");
    registry.add("app.ai.mcp.url", () -> "https://env-mcp.example.test/mcp");
    registry.add("app.ai.mcp.bearer-token", () -> "env-mcp-token");
  }

  @Test
  void usesCompleteEnvironmentSnapshotBeforeFirstAdminSave() {
    RuntimeIntegrationSettings current = settings.current();

    assertThat(current.emby().baseUrl()).isEqualTo("https://env-emby.example.test");
    assertThat(current.llm().model()).isEqualTo("env-model");
    assertThat(current.asr().model()).isEqualTo("env-asr-model");
    assertThat(current.mcp().url()).isEqualTo("https://env-mcp.example.test/mcp");
    assertThat(
            jdbc.sql("select count(*) from runtime_integration_settings")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void savesCompleteDatabaseSnapshotAndPublishesItImmediately() {
    RuntimeIntegrationSettings saved = settings.save(databaseSettings());

    assertThat(saved.llm().apiKey()).isEqualTo("database-llm-key");
    assertThat(settings.current()).isEqualTo(saved);
    assertThat(
            jdbc.sql("select llm_model from runtime_integration_settings where id='default'")
                .query(String.class)
                .single())
        .isEqualTo("database-model");

    RuntimeIntegrationSettings withExplicitBlank =
        new RuntimeIntegrationSettings(
            saved.emby(),
            new RuntimeIntegrationSettings.Llm("", "", "", 4096, 0.2, 120),
            saved.asr(),
            saved.mcp(),
            0);
    settings.save(withExplicitBlank);

    assertThat(settings.current().llm().baseUrl()).isBlank();
    assertThat(settings.current().llm().apiKey()).isBlank();
  }

  @Test
  void rejectsInvalidUrlWithoutChangingCurrentSnapshot() {
    RuntimeIntegrationSettings before = settings.current();
    RuntimeIntegrationSettings invalid =
        new RuntimeIntegrationSettings(
            new RuntimeIntegrationSettings.Emby("file:///tmp/media", "key", "user"),
            before.llm(),
            before.asr(),
            before.mcp(),
            0);

    assertThatThrownBy(() -> settings.save(invalid))
        .isInstanceOf(IntegrationSettingsValidationException.class)
        .hasMessageContaining("Emby Base URL");
    assertThat(settings.current()).isEqualTo(before);
  }

  @Test
  void databaseSnapshotWinsAsAWholeWhenServiceStarts() {
    RuntimeIntegrationSettings database = databaseSettings();
    RuntimeIntegrationSettingsRepository repository = fixedRepository(database, false);
    MockEnvironment environment =
        new MockEnvironment().withProperty("app.ai.llm.model", "不应使用的环境模型");

    var service =
        new RuntimeIntegrationSettingsService(
            repository,
            new EnvironmentIntegrationSettings(environment),
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
            transactionManager(new AtomicBoolean()));

    assertThat(service.current()).isEqualTo(database);
    assertThat(service.current().llm().model()).isNotEqualTo("不应使用的环境模型");
  }

  @Test
  void persistenceFailureKeepsPreviouslyPublishedSnapshot() {
    RuntimeIntegrationSettings initial = databaseSettings();
    RuntimeIntegrationSettingsRepository repository = fixedRepository(initial, true);
    AtomicBoolean rolledBack = new AtomicBoolean();
    var service =
        new RuntimeIntegrationSettingsService(
            repository,
            new EnvironmentIntegrationSettings(new MockEnvironment()),
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
            transactionManager(rolledBack));

    assertThatThrownBy(() -> service.save(databaseSettings()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.current()).isEqualTo(initial);
    assertThat(rolledBack).isTrue();
  }

  /** 为原子发布测试提供不依赖 Mockito Agent 的确定性单行仓库。 */
  private RuntimeIntegrationSettingsRepository fixedRepository(
      RuntimeIntegrationSettings initial, boolean failWrites) {
    return new RuntimeIntegrationSettingsRepository() {
      @Override
      public Optional<RuntimeIntegrationSettings> find() {
        return Optional.of(initial);
      }

      @Override
      public void replace(RuntimeIntegrationSettings value) {
        if (failWrites) throw new IllegalStateException("模拟 SQLite 写入失败");
      }
    };
  }

  /** 最小事务管理器只记录回滚终态，避免单元场景依赖真实数据库连接。 */
  private PlatformTransactionManager transactionManager(AtomicBoolean rolledBack) {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {
        rolledBack.set(true);
      }
    };
  }

  private RuntimeIntegrationSettings databaseSettings() {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(
            "https://database-emby.example.test", "database-emby-key", "database-user"),
        new RuntimeIntegrationSettings.Llm(
            "https://database-llm.example.test/v1",
            "database-llm-key",
            "database-model",
            32_000,
            0.3,
            180),
        new RuntimeIntegrationSettings.Asr(
            "https://database-asr.example.test/v1", "database-asr-key", "database-asr", 600),
        new RuntimeIntegrationSettings.Mcp(
            "https://database-mcp.example.test/mcp",
            "database-mcp-token",
            "web_search,web_extract",
            20),
        0);
  }
}
