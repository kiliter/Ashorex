package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

/** 配置入口拒绝媒体代理不支持的地址，错误提示不回显 URL 中的凭据。 */
class RuntimeSettingsUrlTest {
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
