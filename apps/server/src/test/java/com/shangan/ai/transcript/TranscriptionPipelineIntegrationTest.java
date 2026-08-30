package com.shangan.ai.transcript;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 使用真实 FFmpeg 和 WireMock 外部服务验证从 Emby 音频到 READY 的完整流水线。 */
@SpringBootTest
class TranscriptionPipelineIntegrationTest {
  private static final String FFMPEG = executable("ffmpeg");
  private static final String FFPROBE = executable("ffprobe");
  private static final WireMockServer externalServices =
      new WireMockServer(wireMockConfig().dynamicPort());

  static {
    externalServices.start();
  }

  @TempDir static Path temporaryDirectory;

  @Autowired TranscriptionJobService jobs;
  @Autowired TranscriptSearchRepository transcripts;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + temporaryDirectory.resolve("transcription-pipeline.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.emby.base-url", externalServices::baseUrl);
    registry.add("app.emby.api-key", () -> "emby-secret");
    registry.add("app.ai.asr.base-url", () -> externalServices.baseUrl() + "/v1");
    registry.add("app.ai.asr.api-key", () -> "asr-secret");
    registry.add("app.ai.asr.model", () -> "asr-model");
    registry.add("app.ai.llm.base-url", () -> externalServices.baseUrl() + "/v1");
    registry.add("app.ai.llm.api-key", () -> "llm-secret");
    registry.add("app.ai.llm.model", () -> "summary-model");
    registry.add(
        "app.ai.transcription.temporary-root",
        () -> temporaryDirectory.resolve("audio-work").toString());
    registry.add("app.ai.transcription.ffmpeg-path", () -> FFMPEG);
    registry.add("app.ai.transcription.ffprobe-path", () -> FFPROBE);
  }

  @BeforeEach
  void setUp() throws Exception {
    externalServices.resetAll();
    for (String table :
        List.of(
            "video_summaries",
            "video_section_summaries",
            "transcript_segments",
            "transcription_jobs",
            "media_items",
            "courses")) {
      jdbc.sql("delete from " + table).update();
    }
    jdbc.sql(
            "insert into courses (id,name,emby_parent_item_id,created_at,updated_at) "
                + "values ('course-1','行测','parent-1',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) "
                + "values ('media-1','course-1','emby-1','资料分析',5000,1,1)")
        .update();

    byte[] audio = generatedAudio();
    externalServices.stubFor(
        get(urlPathEqualTo("/Videos/emby-1/stream"))
            .withQueryParam("Static", equalTo("true"))
            .withHeader("X-Emby-Token", equalTo("emby-secret"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok().withBody(audio)));
    externalServices.stubFor(
        post(urlEqualTo("/v1/audio/transcriptions"))
            .withHeader("Authorization", equalTo("Bearer asr-secret"))
            .willReturn(okJson("{\"segments\":[{\"start\":0,\"end\":2,\"text\":\"增长率计算方法\"}]}")));
    externalServices.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer llm-secret"))
            .willReturn(okJson("{\"choices\":[{\"message\":{\"content\":\"资料分析摘要\"}}]}")));
  }

  @AfterAll
  static void stopWireMock() {
    externalServices.stop();
  }

  @Test
  void completesAudioExtractionAsrHierarchicalSummaryFtsAndCleanup() throws Exception {
    var job = jobs.enqueue("media-1");

    var ready = jobs.process(job.id());

    assertThat(ready.status()).isEqualTo("READY");
    assertThat(transcripts.search("media-1", "增长率", 8)).hasSize(1);
    assertThat(transcripts.summary("media-1").orElseThrow().summary()).isEqualTo("资料分析摘要");
    assertThat(
            jdbc.sql("select count(*) from video_section_summaries").query(Integer.class).single())
        .isEqualTo(1);
    Path workRoot = temporaryDirectory.resolve("audio-work");
    try (var files = Files.list(workRoot)) {
      assertThat(files).isEmpty();
    }
  }

  private byte[] generatedAudio() throws Exception {
    Path fixture = temporaryDirectory.resolve("pipeline-fixture.wav");
    Process process =
        new ProcessBuilder(
                FFMPEG,
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=800:duration=2",
                "-y",
                fixture.toString())
            .start();
    assertThat(process.waitFor()).isZero();
    return Files.readAllBytes(fixture);
  }

  /** 与根验证的精简 PATH 解耦，同时保留 Linux CI 的标准路径回退。 */
  private static String executable(String name) {
    for (String directory : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")) {
      Path candidate = Path.of(directory, name);
      if (Files.isExecutable(candidate)) return candidate.toString();
    }
    return name;
  }
}
