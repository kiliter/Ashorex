package com.shangan.media.emby;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.junit5.WireMockExtension.newInstance;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证管理员保存 Emby 配置后，新请求无需重启即可切换源站和服务端密钥。 */
@SpringBootTest
class RuntimeEmbyConfigurationTest {
  @RegisterExtension
  static WireMockExtension emby = newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir static Path databaseDirectory;

  @Autowired RuntimeIntegrationSettingsService settings;
  @Autowired EmbyClient client;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("runtime-emby.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @Test
  void newRequestUsesLatestSavedEmbySnapshot() {
    emby.stubFor(
        get(urlPathEqualTo("/first/Items"))
            .withHeader("X-Emby-Token", equalTo("first-key"))
            .willReturn(okJson(items("first-item", "旧配置课程"))));
    emby.stubFor(
        get(urlPathEqualTo("/second/Items"))
            .withHeader("X-Emby-Token", equalTo("second-key"))
            .willReturn(okJson(items("second-item", "新配置课程"))));

    settings.save(snapshot(emby.baseUrl() + "/first", "first-key"));
    assertThat(client.listChildren("parent").getFirst().id()).isEqualTo("first-item");

    settings.save(snapshot(emby.baseUrl() + "/second", "second-key"));
    assertThat(client.listChildren("parent").getFirst().id()).isEqualTo("second-item");
    emby.verify(getRequestedFor(urlPathEqualTo("/second/Items")));
  }

  private RuntimeIntegrationSettings snapshot(String baseUrl, String apiKey) {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby(baseUrl, apiKey, "emby-user"),
        new RuntimeIntegrationSettings.Llm("", "", "", 16_000, 0.2, 120),
        new RuntimeIntegrationSettings.Asr("", "", "", 120),
        new RuntimeIntegrationSettings.Mcp("", "", "web_search,web_extract", 20),
        0);
  }

  private String items(String id, String title) {
    return """
        {"Items":[{"Id":"%s","Name":"%s","RunTimeTicks":10000000,"IndexNumber":1}]}
        """
        .formatted(id, title);
  }
}
