package com.shangan.media.emby;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** 验证 Emby 只输出指定规格的完整音频流，并由调用方可靠删除临时文件。 */
class EmbyAudioClientTest {

  @TempDir Path tempDirectory;
  private WireMockServer emby;

  @AfterEach
  void stopServer() {
    if (emby != null) emby.stop();
  }

  @Test
  void downloadsMp3AudioWithoutFetchingVideo() throws Exception {
    emby = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    emby.start();
    emby.stubFor(
        post(urlPathEqualTo("/Items/emby-1/PlaybackInfo"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"MediaSources\":[{\"Id\":\"source-1\"}]}")));
    emby.stubFor(
        get(urlPathEqualTo("/Audio/emby-1/stream.mp3"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "audio/mpeg")
                    .withBody("fake-mp3-audio".getBytes(StandardCharsets.UTF_8))));
    var configuration =
        new RuntimeIntegrationSettings.Emby(emby.baseUrl(), "emby-secret", "emby-user");
    var client = new EmbyAudioClient(new ObjectMapper(), tempDirectory.toString());

    Path audioPath;
    try (EmbyAudioClient.DownloadedAudio audio = client.download("emby-1", configuration)) {
      audioPath = audio.path();
      assertThat(Files.readString(audio.path())).isEqualTo("fake-mp3-audio");
      assertThat(audio.sizeBytes()).isEqualTo(14);
    }
    assertThat(audioPath).doesNotExist();

    emby.verify(
        getRequestedFor(urlPathEqualTo("/Audio/emby-1/stream.mp3"))
            .withHeader("X-Emby-Token", equalTo("emby-secret"))
            .withQueryParam("MediaSourceId", equalTo("source-1"))
            .withQueryParam("AudioCodec", equalTo("mp3"))
            .withQueryParam("AudioSampleRate", equalTo("16000"))
            .withQueryParam("MaxAudioChannels", equalTo("1"))
            .withQueryParam("AudioBitRate", equalTo("64000")));
  }
}
