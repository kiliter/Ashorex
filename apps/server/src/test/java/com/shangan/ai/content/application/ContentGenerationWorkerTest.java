package com.shangan.ai.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.ai.content.infrastructure.ContentGenerationJobRepository;
import com.shangan.ai.content.infrastructure.OpenAiCompatibleAsrClient;
import com.shangan.ai.content.infrastructure.QuizGenerationDraftRepository;
import com.shangan.catalog.domain.LessonStudyContent;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.catalog.infrastructure.LessonStudyContentRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.media.emby.EmbyAudioClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** 验证单 Worker 的成功写入、失败隔离、重启恢复和临时音频清理。 */
class ContentGenerationWorkerTest {

  private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");

  private final ContentGenerationJobRepository jobs = mock(ContentGenerationJobRepository.class);
  private final CourseRepository courses = mock(CourseRepository.class);
  private final LessonStudyContentRepository contents = mock(LessonStudyContentRepository.class);
  private final QuizGenerationDraftRepository drafts = mock(QuizGenerationDraftRepository.class);
  private final EmbyAudioClient embyAudio = mock(EmbyAudioClient.class);
  private final OpenAiCompatibleAsrClient asr = mock(OpenAiCompatibleAsrClient.class);
  private final LessonSummaryGenerator summaries = mock(LessonSummaryGenerator.class);
  private final QuizContentGenerator quizzes = mock(QuizContentGenerator.class);
  private final IntegrationSettingsProvider settings = mock(IntegrationSettingsProvider.class);
  private final IdGenerator ids = mock(IdGenerator.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private ContentGenerationWorker worker;

  @BeforeEach
  void setUp() {
    worker =
        new ContentGenerationWorker(
            jobs, courses, contents, drafts, embyAudio, asr, summaries, quizzes, settings, ids,
            clock);
    when(ids.nextId()).thenReturn("generated-id");
    when(settings.current()).thenReturn(runtimeSettings());
    when(jobs.transition(any(), any(), any(), any())).thenReturn(true);
  }

  /** 转写完成后一次性写全文，并由 try-with-resources 删除临时 MP3。 */
  @Test
  void transcribesOneQueuedJobAndDeletesTemporaryAudio(@TempDir Path tempDirectory)
      throws Exception {
    ContentGenerationJob job = job(ContentGenerationJob.Type.TRANSCRIBE);
    Path audio = tempDirectory.resolve("lesson.mp3");
    Files.writeString(audio, "audio-bytes");
    when(jobs.findNextQueued()).thenReturn(Optional.of(job));
    when(courses.findMediaItem("lesson-1"))
        .thenReturn(
            Optional.of(
                new MediaItem("lesson-1", "course-1", "emby-1", "第一课", 60_000, true, 1, true)));
    when(embyAudio.download(eq("emby-1"), any()))
        .thenReturn(new EmbyAudioClient.DownloadedAudio(audio, Files.size(audio)));
    when(asr.transcribe(eq(audio), any())).thenReturn("第一段第二段");

    worker.poll();

    verify(contents).upsertTranscript("generated-id", "lesson-1", "第一段第二段", NOW);
    verify(jobs)
        .transition(
            "job-1",
            ContentGenerationJob.Status.TRANSCRIBING,
            ContentGenerationJob.Status.READY,
            NOW);
    assertThat(audio).doesNotExist();
  }

  /** 摘要失败只标记任务失败，绝不能清除或覆盖已有全文和旧摘要。 */
  @Test
  void summaryFailureKeepsExistingStudyContent() {
    ContentGenerationJob job = job(ContentGenerationJob.Type.SUMMARIZE);
    LessonStudyContent existing =
        new LessonStudyContent("content-1", "lesson-1", "已有全文", "# 旧摘要", NOW, NOW, null, NOW);
    when(jobs.findNextQueued()).thenReturn(Optional.of(job));
    when(contents.findByMediaItemId("lesson-1")).thenReturn(Optional.of(existing));
    when(summaries.generate(any(), any()))
        .thenThrow(
            new BusinessException(HttpStatus.BAD_GATEWAY, "SUMMARY_REQUEST_FAILED", "摘要服务暂时不可用"));

    worker.poll();

    verify(contents, never()).upsertSummary(any(), any(), any(), any());
    verify(jobs)
        .fail(eq("job-1"), eq("SUMMARY_REQUEST_FAILED"), eq("摘要服务暂时不可用"), eq(NOW), anyLong());
  }

  /** 服务启动时明确终止遗留执行态，禁止悄悄重复调用上游。 */
  @Test
  void recoversInterruptedJobsAsFailed() {
    worker.recoverInterruptedJobs();

    verify(jobs).failInterrupted(NOW);
  }

  private ContentGenerationJob job(ContentGenerationJob.Type type) {
    return new ContentGenerationJob(
        "job-1",
        "course-1",
        "lesson-1",
        type,
        ContentGenerationJob.Status.QUEUED,
        5,
        false,
        NOW,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        type == ContentGenerationJob.Type.TRANSCRIBE ? "asr-model" : null,
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : "llm-model",
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : 16_384,
        type == ContentGenerationJob.Type.TRANSCRIBE ? null : 2_048,
        null,
        null,
        1,
        null,
        null,
        "admin");
  }

  private RuntimeIntegrationSettings runtimeSettings() {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby("http://emby.local", "emby-secret", "user-1"),
        new RuntimeIntegrationSettings.Asr(
            "http://asr.local/v1", "asr-secret", "asr-model", "Chinese", 30, 60),
        new RuntimeIntegrationSettings.Llm(
            "http://llm.local/v1", "llm-secret", "llm-model", 16_384, 2_048, 60),
        new RuntimeIntegrationSettings.OpenRouter("router-secret"),
        RuntimeIntegrationSettings.AutoFill.defaults(),
        NOW.toEpochMilli());
  }
}
