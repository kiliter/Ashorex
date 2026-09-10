package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

/** 配置入口拒绝媒体代理不支持的地址，错误提示不回显 URL 中的凭据。 */
class RuntimeSettingsUrlTest {
  @Test
  void 未初始化占位行使用环境快照() {
    var repository = mock(RuntimeIntegrationSettingsRepository.class);
    var environment = mock(EnvironmentIntegrationSettings.class);
    var fromEnvironment =
        new RuntimeIntegrationSettings(
            new RuntimeIntegrationSettings.Emby(
                "https://emby.example.invalid", "test-only", "user-1"),
            0L);
    when(repository.find()).thenReturn(Optional.of(RuntimeIntegrationSettings.defaults()));
    when(environment.snapshot()).thenReturn(fromEnvironment);

    var service =
        new RuntimeIntegrationSettingsService(
            repository, environment, Clock.systemUTC(), mock(PlatformTransactionManager.class));

    assertThat(service.current()).isSameAs(fromEnvironment);
    verify(environment).snapshot();
  }

  @Test
  void 管理员已保存配置不被环境变量覆盖() {
    var repository = mock(RuntimeIntegrationSettingsRepository.class);
    var environment = mock(EnvironmentIntegrationSettings.class);
    var saved =
        new RuntimeIntegrationSettings(new RuntimeIntegrationSettings.Emby("", "", ""), 1234L);
    when(repository.find()).thenReturn(Optional.of(saved));

    var service =
        new RuntimeIntegrationSettingsService(
            repository, environment, Clock.systemUTC(), mock(PlatformTransactionManager.class));

    assertThat(service.current()).isSameAs(saved);
    verify(environment, never()).snapshot();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://sample:sample-pass@example.invalid",
        "ftp://example.invalid",
        "file:///tmp/video"
      })
  void 非法媒体地址不入库也不回显(String url) {
    var repository = mock(RuntimeIntegrationSettingsRepository.class);
    when(repository.find()).thenReturn(Optional.of(RuntimeIntegrationSettings.defaults()));
    var service =
        new RuntimeIntegrationSettingsService(
            repository,
            mock(EnvironmentIntegrationSettings.class),
            Clock.systemUTC(),
            mock(PlatformTransactionManager.class));
    var submitted =
        new RuntimeIntegrationSettings(
            new RuntimeIntegrationSettings.Emby(url, "test-only", ""), 0);

    assertThatThrownBy(() -> service.save(submitted))
        .isInstanceOf(IntegrationSettingsValidationException.class)
        .hasMessage("Emby Base URL必须是不含账号密码的 HTTP(S) 地址");
    verify(repository, never()).replace(any());
  }
}
