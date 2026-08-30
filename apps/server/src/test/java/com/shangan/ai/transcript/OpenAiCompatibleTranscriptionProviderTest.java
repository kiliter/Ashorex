package com.shangan.ai.transcript;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.junit5.WireMockExtension.newInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** 通过 WireMock 固定 OpenAI-compatible multipart 请求与时间戳响应契约。 */
class OpenAiCompatibleTranscriptionProviderTest {
  @RegisterExtension
  static WireMockExtension asr = newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path temporaryDirectory;

  @Test
  void uploadsMultipartWithServerSideKeyAndReturnsAbsoluteTimestampSegments() throws Exception {
    asr.stubFor(
        post(urlEqualTo("/v1/audio/transcriptions"))
            .withHeader("Authorization", equalTo("Bearer asr-secret"))
            .withMultipartRequestBody(aMultipart().withName("model").withBody(equalTo("asr-model")))
            .withMultipartRequestBody(
                aMultipart().withName("response_format").withBody(equalTo("verbose_json")))
            .willReturn(
                okJson(
                    """
                    {"text":"第一段 第二段","segments":[
                      {"start":0.5,"end":1.5,"text":" 第一段 "},
                      {"start":1.5,"end":2.0,"text":"第二段"}
                    ]}
                    """)));
    Path audio = temporaryDirectory.resolve("chunk.wav");
    Files.write(audio, new byte[] {1, 2, 3});
    var provider = provider("asr-secret");

    var result =
        provider.transcribe(audio, new TranscriptionProvider.TranscriptionRequest(600_000, "zh"));

    assertThat(result.modelName()).isEqualTo("asr-model");
    assertThat(result.segments())
        .containsExactly(
            new TranscriptionProvider.TranscriptionSegment(600_500, 601_500, "第一段"),
            new TranscriptionProvider.TranscriptionSegment(601_500, 602_000, "第二段"));
    asr.verify(postRequestedFor(urlEqualTo("/v1/audio/transcriptions")));
  }

  @Test
  void thirdPartyFailureDoesNotExposeApiKey() throws Exception {
    asr.stubFor(post(urlEqualTo("/v1/audio/transcriptions")).willReturn(serverError()));
    Path audio = temporaryDirectory.resolve("chunk.wav");
    Files.write(audio, new byte[] {1});

    assertThatThrownBy(
            () ->
                provider("do-not-leak")
                    .transcribe(audio, new TranscriptionProvider.TranscriptionRequest(0, "zh")))
        .isInstanceOf(OpenAiCompatibleTranscriptionProvider.TranscriptionProviderException.class)
        .hasMessageNotContaining("do-not-leak");
  }

  private OpenAiCompatibleTranscriptionProvider provider(String apiKey) {
    return new OpenAiCompatibleTranscriptionProvider(
        asr.baseUrl() + "/v1", apiKey, "asr-model", Duration.ofSeconds(5), new ObjectMapper());
  }
}
