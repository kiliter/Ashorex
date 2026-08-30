package com.shangan.ai.transcript;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.junit5.WireMockExtension.newInstance;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证每个新 ASR 切片都读取最新保存的地址、密钥和模型。 */
@SpringBootTest
class RuntimeTranscriptionConfigurationTest {
  @RegisterExtension
  static WireMockExtension asr = newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir static Path temporaryDirectory;

  @Autowired RuntimeIntegrationSettingsService settings;
  @Autowired OpenAiCompatibleTranscriptionProvider provider;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + temporaryDirectory.resolve("runtime-asr.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @Test
  void newChunkUsesLatestSavedAsrSnapshot() throws Exception {
    asr.stubFor(
        post(urlEqualTo("/first/audio/transcriptions"))
            .withHeader("Authorization", equalTo("Bearer first-key"))
            .willReturn(okJson("{\"text\":\"旧配置转写\"}")));
    asr.stubFor(
        post(urlEqualTo("/second/audio/transcriptions"))
            .withHeader("Authorization", equalTo("Bearer second-key"))
            .willReturn(okJson("{\"text\":\"新配置转写\"}")));
    Path audio = temporaryDirectory.resolve("chunk.wav");
    Files.write(audio, new byte[] {1, 2, 3});

    settings.save(snapshot(asr.baseUrl() + "/first", "first-key", "first-model"));
    assertThat(transcribe(audio).modelName()).isEqualTo("first-model");

    settings.save(snapshot(asr.baseUrl() + "/second", "second-key", "second-model"));
    var result = transcribe(audio);
    assertThat(result.modelName()).isEqualTo("second-model");
    assertThat(result.segments().getFirst().text()).isEqualTo("新配置转写");
    asr.verify(postRequestedFor(urlEqualTo("/second/audio/transcriptions")));
  }

  private TranscriptionProvider.TranscriptionResult transcribe(Path audio) {
    return provider.transcribe(audio, new TranscriptionProvider.TranscriptionRequest(0, "zh"));
  }

  private RuntimeIntegrationSettings snapshot(String baseUrl, String apiKey, String model) {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby("", "", ""),
        new RuntimeIntegrationSettings.Llm("", "", "", 16_000, 0.2, 120),
        new RuntimeIntegrationSettings.Asr(baseUrl, apiKey, model, 30),
        new RuntimeIntegrationSettings.Mcp("", "", "web_search,web_extract", 20),
        0);
  }
}
