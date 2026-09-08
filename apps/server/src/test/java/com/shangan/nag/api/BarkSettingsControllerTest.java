package com.shangan.nag.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.BarkSettingsService;
import com.shangan.nag.domain.BarkSettings;
import org.junit.jupiter.api.Test;

/** API 身份只来自已验证的会话，响应不能包含设备密钥。 */
class BarkSettingsControllerTest {
  @Test
  void 读写均使用当前身份且响应不回显密钥() throws Exception {
    var service = mock(BarkSettingsService.class);
    var controller = new BarkSettingsController(service);
    var user = new CurrentUser("learner-a", "小明", "USER", "Asia/Shanghai");
    var value = new BarkSettings("https://api.day.app", "private-device-key", true);
    when(service.get("learner-a")).thenReturn(value);
    when(service.save("learner-a", "https://api.day.app", "new-device-key", true))
        .thenReturn(value);
    var read = controller.get(user);
    var saved =
        controller.save(
            user,
            new BarkSettingsController.Request("https://api.day.app", "new-device-key", true));
    assertThat(read).isEqualTo(saved);
    assertThat(read.deviceKeyConfigured()).isTrue();
    assertThat(new ObjectMapper().writeValueAsString(read))
        .doesNotContain("private-device-key", "new-device-key", "\"deviceKey\":");
    verify(service).get("learner-a");
    verify(service).save("learner-a", "https://api.day.app", "new-device-key", true);
    verifyNoMoreInteractions(service);
  }
}
