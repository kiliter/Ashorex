package com.shangan.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 验证服务配置页的 ADMIN 权限、CSRF、禁止缓存和保存交互。 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationSettingsAdminTest {

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired RuntimeIntegrationSettingsService settings;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("integration-settings-admin.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.emby.api-key", () -> "initial-visible-emby-key");
  }

  @Test
  void pageRequiresAdminAndPreventsCachingWhileSupportingPasswordReveal() throws Exception {
    String currentEmbyKey = settings.current().emby().apiKey();
    mockMvc.perform(get("/admin/settings/integrations")).andExpect(status().is3xxRedirection());
    mockMvc
        .perform(get("/admin/settings/integrations").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/admin/settings/integrations").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("服务配置")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"password\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-password-toggle")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(currentEmbyKey)))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("LLM 摘要与出题")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ASR 转写服务")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("MCP"))));
  }

  @Test
  void saveRequiresCsrfAndAppliesConfigurationImmediately() throws Exception {
    var request =
        post("/admin/settings/integrations")
            .with(user("admin").roles("ADMIN"))
            .param("embyBaseUrl", "https://emby.saved.test")
            .param("embyApiKey", "saved-emby-key")
            .param("embyUserId", "saved-user")
            .param("asrBaseUrl", "https://asr.saved.test")
            .param("asrApiKey", "saved-asr-key")
            .param("asrModel", "mlx-community/Qwen3-ASR-1.7B-8bit")
            .param("asrLanguage", "Chinese")
            .param("asrChunkDurationSeconds", "30")
            .param("asrTimeoutSeconds", "1800")
            .param("llmBaseUrl", "https://cpa.saved.test/v1")
            .param("llmApiKey", "saved-llm-key")
            .param("llmModel", "openai/test-model")
            .param("llmContextLength", "131072")
            .param("llmMaxCompletionTokens", "8192")
            .param("llmTimeoutSeconds", "300")
            .param("openRouterApiKey", "saved-openrouter-key")
            .param("autoFillIntervalMinutes", "15");

    mockMvc.perform(request).andExpect(status().isForbidden());
    mockMvc
        .perform(request.with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/settings/integrations?saved=true"));

    org.assertj.core.api.Assertions.assertThat(settings.current().emby().apiKey())
        .isEqualTo("saved-emby-key");
    org.assertj.core.api.Assertions.assertThat(settings.current().asr().baseUrl())
        .isEqualTo("https://asr.saved.test");
    org.assertj.core.api.Assertions.assertThat(settings.current().llm().model())
        .isEqualTo("openai/test-model");
    org.assertj.core.api.Assertions.assertThat(settings.current().autoFill().enabled()).isFalse();
  }

  @Test
  void invalidUrlRendersChineseFieldErrorWithoutSaving() throws Exception {
    mockMvc
        .perform(
            post("/admin/settings/integrations")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("embyBaseUrl", "file:///tmp/media"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("必须是完整的 HTTP 或 HTTPS 地址")));
  }
}
