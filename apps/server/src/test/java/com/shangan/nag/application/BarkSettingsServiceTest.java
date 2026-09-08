package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shangan.nag.domain.BarkSettings;
import com.shangan.nag.infrastructure.BarkSettingsRepository;
import org.junit.jupiter.api.Test;

/** 验证个人归属、密钥保留和输入边界，不连接数据库。 */
class BarkSettingsServiceTest {
  @Test
  void 保存仅写当前用户并保留空密钥() {
    var repository = mock(BarkSettingsRepository.class);
    when(repository.find("learner"))
        .thenReturn(new BarkSettings("https://api.day.app", "personal-key", true));
    var service = new BarkSettingsService(repository);
    var saved = service.save("learner", "https://api.day.app/", "", false);
    assertThat(saved).isEqualTo(new BarkSettings("https://api.day.app", "personal-key", false));
    verify(repository).save("learner", saved);
    verify(repository, never()).find("another-user");
  }

  @Test
  void 拒绝凭据地址与无密钥启用() {
    var repository = mock(BarkSettingsRepository.class);
    when(repository.find("learner")).thenReturn(BarkSettings.defaults());
    var service = new BarkSettingsService(repository);
    assertThatThrownBy(() -> service.save("learner", "https://user:pass@example.org", "key", true))
        .hasMessageContaining("HTTPS");
    assertThatThrownBy(() -> service.save("learner", "https://api.day.app", "", true))
        .hasMessageContaining("Key");
    verify(repository, never()).save(any(), any());
  }
}
