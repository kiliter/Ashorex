package com.shangan.ai.content.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** 验证 mlx-audio 风格 multipart 请求和 NDJSON 文本拼接契约。 */
class OpenAiCompatibleAsrClientTest {

  @TempDir Path tempDirectory;
  private WireMockServer asr;

  @AfterEach
  void stopServer() {
    if (asr != null) asr.stop();
  }

  @Test
  void concatenatesOnlyOrderedTextChunks() throws Exception {
    startServer();
    asr.stubFor(
        post(urlPathEqualTo("/v1/audio/transcriptions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/x-ndjson")
                    .withBody(
                        "{\"text\":\"第一段\",\"accumulated\":\"第一段\"}\n"
                            + "{\"text\":\"，第二段。\",\"accumulated\":\"第一段，第二段。\"}\n")));
    Path audio = tempDirectory.resolve("lesson.mp3");
    Files.writeString(audio, "audio-bytes");
    var client = new OpenAiCompatibleAsrClient(new ObjectMapper());

    String transcript = client.transcribe(audio, settings());

    assertThat(transcript).isEqualTo("第一段，第二段。");
    asr.verify(
        postRequestedFor(urlPathEqualTo("/v1/audio/transcriptions"))
            .withHeader("Authorization", equalTo("Bearer asr-secret"))
            .withRequestBody(containing("mlx-community/Qwen3-ASR-1.7B-8bit"))
            .withRequestBody(containing("stream"))
            .withRequestBody(containing("chunk_duration")));
  }

  @Test
  void rejectsStreamErrorAndEmptyTranscript() throws Exception {
    startServer();
    Path audio = tempDirectory.resolve("lesson.mp3");
    Files.writeString(audio, "audio-bytes");
    var client = new OpenAiCompatibleAsrClient(new ObjectMapper());
    asr.stubFor(
        post(urlPathEqualTo("/v1/audio/transcriptions"))
            .willReturn(aResponse().withBody("{\"error\":\"model failed\"}\n")));

    assertThatThrownBy(() -> client.transcribe(audio, settings()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("转写服务调用失败");
  }

  private void startServer() {
    asr = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    asr.start();
  }

  private RuntimeIntegrationSettings.Asr settings() {
    return new RuntimeIntegrationSettings.Asr(
        asr.baseUrl(),
        "asr-secret",
        RuntimeIntegrationSettings.DEFAULT_ASR_MODEL,
        "Chinese",
        30,
        60);
  }
}
