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
    registry.add("app.ai.llm.api-key", () -> "initial-visible-llm-key");
  }

  @Test
  void pageRequiresAdminAndPreventsCachingWhileSupportingPasswordReveal() throws Exception {
    String currentLlmKey = settings.current().llm().apiKey();
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
        .andExpect(content().string(org.hamcrest.Matchers.containsString(currentLlmKey)));
  }

  @Test
  void saveRequiresCsrfAndAppliesConfigurationImmediately() throws Exception {
    var request =
        post("/admin/settings/integrations")
            .with(user("admin").roles("ADMIN"))
            .param("embyBaseUrl", "https://emby.saved.test")
            .param("embyApiKey", "saved-emby-key")
            .param("embyUserId", "saved-user")
            .param("llmBaseUrl", "https://llm.saved.test/v1")
            .param("llmApiKey", "saved-llm-key")
            .param("llmModel", "saved-model")
            .param("llmMaxContextTokens", "64000")
            .param("llmTemperature", "0.4")
            .param("llmTimeoutSeconds", "240")
            .param("asrBaseUrl", "https://asr.saved.test/v1")
            .param("asrApiKey", "saved-asr-key")
            .param("asrModel", "saved-asr")
            .param("asrTimeoutSeconds", "900")
            .param("mcpUrl", "https://mcp.saved.test/mcp")
            .param("mcpBearerToken", "saved-mcp-token")
            .param("mcpAllowedTools", "web_search,web_extract")
            .param("mcpTimeoutSeconds", "30");

    mockMvc.perform(request).andExpect(status().isForbidden());
    mockMvc
        .perform(request.with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/settings/integrations?saved=true"));

    org.assertj.core.api.Assertions.assertThat(settings.current().llm().model())
        .isEqualTo("saved-model");
    org.assertj.core.api.Assertions.assertThat(settings.current().emby().apiKey())
        .isEqualTo("saved-emby-key");
  }

  @Test
  void invalidUrlRendersChineseFieldErrorWithoutSaving() throws Exception {
    mockMvc
        .perform(
            post("/admin/settings/integrations")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("embyBaseUrl", "file:///tmp/media")
                .param("llmMaxContextTokens", "16000")
                .param("llmTemperature", "0.2")
                .param("llmTimeoutSeconds", "120")
                .param("asrTimeoutSeconds", "120")
                .param("mcpTimeoutSeconds", "20"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("必须是完整的 HTTP 或 HTTPS 地址")));
  }
}
