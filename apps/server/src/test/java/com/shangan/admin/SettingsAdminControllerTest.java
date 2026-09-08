package com.shangan.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.integration.IntegrationSettingsValidationException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyHealthService;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 运行配置后台接口的 Controller 切片测试。
 *
 * <p>覆盖 ADR-0033 要求由切片测试兜底的后台行为约束：密钥永不明文回显、Emby 探测三态映射、 未配置不算错误、失败文案不含 Base URL / API Key /
 * 堆栈，以及校验失败的 Problem Details 结构。
 *
 * <p>不启动数据库与 Flyway，全部依赖以 Mockito 打桩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运行配置后台接口")
class SettingsAdminControllerTest {

  private static final String SECRET_API_KEY = "emby-secret-key-should-never-leak";
  private static final String SECRET_SEND_KEY = "SCT-secret-send-key-should-never-leak";
  private static final String BASE_URL = "http://emby.internal:8096";

  @Mock private RuntimeIntegrationSettingsService settings;
  @Mock private EmbyHealthService embyHealth;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new SettingsAdminController(settings, embyHealth))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("读取配置只回显是否已配置，绝不下发密钥明文")
  void settingsNeverExposeSecrets() throws Exception {
    when(settings.current()).thenReturn(configured());
    when(embyHealth.status()).thenReturn("可用");

    String body =
        mockMvc
            .perform(get("/admin/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emby.apiKeyConfigured").value(true))
            .andExpect(jsonPath("$.emby.baseUrl").value(BASE_URL))
            .andExpect(jsonPath("$.serverChan.sendKeyConfigured").value(true))
            .andExpect(jsonPath("$.emby.apiKey").doesNotExist())
            .andExpect(jsonPath("$.serverChan.sendKey").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain(SECRET_API_KEY).doesNotContain(SECRET_SEND_KEY);
  }

  @Test
  @DisplayName("Emby 未配置时测试连接返回 200 与「未配置」提示，而不是错误状态")
  void testEmbyReportsNotConfiguredAsOk200() throws Exception {
    when(embyHealth.probe())
        .thenReturn(
            new EmbyHealthService.Probe(
                false, "未配置", "Emby 尚未配置：请先填写 Base URL、API Key 与 User ID 并保存，然后再测试连接。", 0L));

    mockMvc
        .perform(
            post("/admin/api/settings/test-emby").contentType("application/json").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.status").value("未配置"))
        .andExpect(jsonPath("$.latencyMs").value(0))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尚未配置")));

    // 探测是只读动作，不得顺手写回任何配置。
    verify(settings, never()).save(any());
  }

  @Test
  @DisplayName("Emby 可用时返回 ok=true 与探测耗时")
  void testEmbyReportsHealthy() throws Exception {
    when(embyHealth.probe())
        .thenReturn(new EmbyHealthService.Probe(true, "可用", "Emby 连接正常，System/Info 可读。", 42L));

    mockMvc
        .perform(
            post("/admin/api/settings/test-emby").contentType("application/json").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.status").value("可用"))
        .andExpect(jsonPath("$.latencyMs").value(42));
  }

  @Test
  @DisplayName("Emby 探测失败只下发脱敏中文文案，不含地址、密钥与堆栈")
  void testEmbyFailureIsRedacted() throws Exception {
    when(embyHealth.probe())
        .thenReturn(
            new EmbyHealthService.Probe(false, "不可用", "无法建立到 Emby 的连接，请确认地址、端口已开放且服务正在运行。", 3001L));

    String body =
        mockMvc
            .perform(
                post("/admin/api/settings/test-emby").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.status").value("不可用"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body)
        .doesNotContain(BASE_URL)
        .doesNotContain(SECRET_API_KEY)
        .doesNotContain("Exception")
        .doesNotContain("at com.shangan");
  }

  @Test
  @DisplayName("保存时留空密钥表示保持原值，不会把密钥清成空串")
  void saveKeepsExistingSecretsWhenBlank() throws Exception {
    when(settings.current()).thenReturn(configured());

    mockMvc
        .perform(
            post("/admin/api/settings")
                .contentType("application/json")
                .content(
                    """
                    {"embyBaseUrl":"http://emby.internal:8096","embyApiKey":"","embyUserId":"u-1",
                     "embyTimeoutSeconds":10,"serverChanSendKey":"","serverChanTimeoutSeconds":5,
                     "serverChanNagEnabled":true,"serverChanDailyDigestEnabled":false,
                     "documentResources":false,"maxDocumentSizeMb":20}
                    """))
        .andExpect(status().isNoContent());

    org.mockito.ArgumentCaptor<RuntimeIntegrationSettings> captor =
        org.mockito.ArgumentCaptor.forClass(RuntimeIntegrationSettings.class);
    verify(settings).save(captor.capture());
    Assertions.assertThat(captor.getValue().emby().apiKey()).isEqualTo(SECRET_API_KEY);
    Assertions.assertThat(captor.getValue().serverChan().sendKey()).isEqualTo(SECRET_SEND_KEY);
  }

  @Test
  @DisplayName("配置校验失败返回 400 与 SETTINGS_INVALID 字段级错误，且无堆栈")
  void saveValidationFailureReturnsFieldErrors() throws Exception {
    when(settings.current()).thenReturn(configured());
    org.mockito.Mockito.doThrow(
            new IntegrationSettingsValidationException(
                Map.of("embyBaseUrl", "Base URL 必须以 http 开头")))
        .when(settings)
        .save(any());

    String body =
        mockMvc
            .perform(
                post("/admin/api/settings")
                    .contentType("application/json")
                    .content(
                        """
                        {"embyBaseUrl":"ftp://bad","embyApiKey":"","embyUserId":"u-1",
                         "embyTimeoutSeconds":10,"serverChanSendKey":"","serverChanTimeoutSeconds":5,
                         "serverChanNagEnabled":true,"serverChanDailyDigestEnabled":false,
                         "documentResources":false,"maxDocumentSizeMb":20}
                        """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SETTINGS_INVALID"))
            .andExpect(jsonPath("$.fieldErrors.embyBaseUrl").value("Base URL 必须以 http 开头"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain("at com.shangan").doesNotContain("stackTrace");
  }

  /** 一份已完整配置的运行配置，密钥使用可断言的哨兵值。 */
  private RuntimeIntegrationSettings configured() {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(BASE_URL, SECRET_API_KEY, "u-1", 10),
        List.of(),
        new RuntimeIntegrationSettings.ServerChan(SECRET_SEND_KEY, 5, true, false),
        new RuntimeIntegrationSettings.Features(false, 20),
        0L);
  }
}
