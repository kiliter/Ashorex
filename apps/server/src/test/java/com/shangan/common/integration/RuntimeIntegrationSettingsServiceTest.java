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
    registry.add("app.asr.base-url", () -> "https://env-asr.example.test");
    registry.add("app.llm.base-url", () -> "https://env-cpa.example.test/v1");
    registry.add("app.llm.model", () -> "env-model");
  }

  @Test
  void usesCompleteEnvironmentSnapshotBeforeFirstAdminSave() {
    RuntimeIntegrationSettings current = settings.current();

    assertThat(current.emby().baseUrl()).isEqualTo("https://env-emby.example.test");
    assertThat(current.asr().baseUrl()).isEqualTo("https://env-asr.example.test");
    assertThat(current.llm().model()).isEqualTo("env-model");
    assertThat(current.autoFill().enabled()).isFalse();
    assertThat(
            jdbc.sql("select count(*) from runtime_integration_settings")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void savesCompleteDatabaseSnapshotAndPublishesItImmediately() {
    RuntimeIntegrationSettings saved = settings.save(databaseSettings());

    assertThat(saved.emby().apiKey()).isEqualTo("database-emby-key");
    assertThat(saved.asr().apiKey()).isEqualTo("database-asr-key");
    assertThat(saved.llm().contextLength()).isEqualTo(65536);
    assertThat(settings.current()).isEqualTo(saved);
    assertThat(
            jdbc.sql("select emby_user_id from runtime_integration_settings where id='default'")
                .query(String.class)
                .single())
        .isEqualTo("database-user");
  }

  @Test
  void rejectsInvalidUrlWithoutChangingCurrentSnapshot() {
    RuntimeIntegrationSettings before = settings.current();
    RuntimeIntegrationSettings invalid =
        new RuntimeIntegrationSettings(
            new RuntimeIntegrationSettings.Emby("file:///tmp/media", "key", "user"), 0);

    assertThatThrownBy(() -> settings.save(invalid))
        .isInstanceOf(IntegrationSettingsValidationException.class)
        .hasMessageContaining("Emby Base URL");
    assertThat(settings.current()).isEqualTo(before);
  }

  @Test
  void rejectsLlmOutputBudgetThatLeavesNoRoomForLessonText() {
    RuntimeIntegrationSettings valid = databaseSettings();
    RuntimeIntegrationSettings invalid =
        new RuntimeIntegrationSettings(
            valid.emby(),
            valid.asr(),
            new RuntimeIntegrationSettings.Llm(
                valid.llm().baseUrl(), valid.llm().apiKey(), valid.llm().model(), 4096, 2048, 60),
            valid.openRouter(),
            valid.autoFill(),
            0);

    assertThatThrownBy(() -> settings.save(invalid))
        .isInstanceOf(IntegrationSettingsValidationException.class)
        .hasMessageContaining("正文至少预留 256 Tokens");
  }

  @Test
  void databaseSnapshotWinsAsAWholeWhenServiceStarts() {
    RuntimeIntegrationSettings database = databaseSettings();
    RuntimeIntegrationSettingsRepository repository = fixedRepository(database, false);
    MockEnvironment environment =
        new MockEnvironment().withProperty("app.emby.base-url", "https://env.example.test");

    var service =
        new RuntimeIntegrationSettingsService(
            repository,
            new EnvironmentIntegrationSettings(environment),
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
            transactionManager(new AtomicBoolean()));

    assertThat(service.current()).isEqualTo(database);
    assertThat(service.current().emby().baseUrl()).isNotEqualTo("https://env.example.test");
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
        new RuntimeIntegrationSettings.Asr(
            "https://database-asr.example.test",
            "database-asr-key",
            RuntimeIntegrationSettings.DEFAULT_ASR_MODEL,
            "Chinese",
            30,
            1800),
        new RuntimeIntegrationSettings.Llm(
            "https://database-cpa.example.test/v1",
            "database-llm-key",
            "database-model",
            65536,
            4096,
            300),
        new RuntimeIntegrationSettings.OpenRouter("database-openrouter-key"),
        new RuntimeIntegrationSettings.AutoFill(false, 15),
        0);
  }
}
