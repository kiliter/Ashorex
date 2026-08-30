package com.shangan.ai.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 验证单任务约束、状态推进以及重试时事务替换旧转写。 */
@SpringBootTest
@Import(TranscriptionJobServiceTest.FixedClockConfiguration.class)
class TranscriptionJobServiceTest {
  @TempDir static Path databaseDirectory;

  @Autowired TranscriptionJobService jobs;
  @Autowired JdbcClient jdbc;

  @MockitoBean FfmpegAudioExtractor extractor;
  @MockitoBean TranscriptionProvider provider;
  @MockitoBean VideoSummaryService.SummaryProvider summaryProvider;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("transcription-job.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.emby.base-url", () -> "http://emby.test");
    registry.add("app.emby.api-key", () -> "emby-test-key");
  }

  @BeforeEach
  void setUp() throws Exception {
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
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) values "
                + "('media-1','course-1','emby-1','资料分析',900000,1,1),"
                + "('media-2','course-1','emby-2','判断推理',900000,1,1)")
        .update();

    Path working = Files.createTempDirectory(databaseDirectory, "extracted-");
    Path chunk = working.resolve("chunk-000.wav");
    Files.write(chunk, new byte[] {1});
    when(extractor.extract(any(), any()))
        .thenReturn(
            new FfmpegAudioExtractor.ExtractedAudio(
                working, List.of(new FfmpegAudioExtractor.AudioChunk(0, 0, 900_000, chunk))));
    when(provider.transcribe(any(), any()))
        .thenReturn(
            new TranscriptionProvider.TranscriptionResult(
                List.of(
                    new TranscriptionProvider.TranscriptionSegment(0, 300_000, "第一节增长率"),
                    new TranscriptionProvider.TranscriptionSegment(300_000, 700_000, "第二节基期量")),
                "asr-model"));
    when(summaryProvider.summarizeSection(any(), anyInt(), any()))
        .thenReturn(new VideoSummaryService.GeneratedText("分段摘要", "summary-model"));
    when(summaryProvider.summarizeGlobal(any(), any()))
        .thenReturn(new VideoSummaryService.GeneratedText("全局摘要", "summary-model"));
  }

  @Test
  void processesToReadyAndRetryReplacesPreviousSegmentsWithoutDuplicates() {
    var first = jobs.enqueue("media-1");
    jobs.process(first.id());

    assertThat(jobs.findByMediaItem("media-1").orElseThrow().status()).isEqualTo("READY");
    assertThat(jdbc.sql("select count(*) from transcript_segments").query(Integer.class).single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql("select count(*) from video_section_summaries").query(Integer.class).single())
        .isPositive();
    assertThat(jdbc.sql("select summary from video_summaries").query(String.class).single())
        .isEqualTo("全局摘要");

    var retry = jobs.enqueue("media-1");
    jobs.process(retry.id());

    var ready = jobs.findByMediaItem("media-1").orElseThrow();
    assertThat(ready.status()).isEqualTo("READY");
    assertThat(ready.attemptCount()).isEqualTo(2);
    assertThat(jdbc.sql("select count(*) from transcript_segments").query(Integer.class).single())
        .isEqualTo(2);
  }

  @Test
  void rejectsSecondGlobalActiveJobWithStableBusinessCode() {
    jobs.enqueue("media-1");

    assertThatThrownBy(() -> jobs.enqueue("media-2"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).errorCode())
        .isEqualTo("TRANSCRIPTION_BUSY");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-08-30T04:00:00Z"), ZoneOffset.UTC);
    }
  }
}
