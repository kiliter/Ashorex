package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.ai.content.domain.QuizGenerationDraft;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单实例全局串行 Worker，按数据库稳定顺序逐个执行课时内容任务。 */
@Component
public class ContentGenerationWorker {

  private final ContentGenerationJobRepository jobs;
  private final CourseRepository courses;
  private final LessonStudyContentRepository contents;
  private final QuizGenerationDraftRepository drafts;
  private final EmbyAudioClient embyAudio;
  private final OpenAiCompatibleAsrClient asr;
  private final LessonSummaryGenerator summaries;
  private final QuizContentGenerator quizzes;
  private final IntegrationSettingsProvider settings;
  private final IdGenerator ids;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public ContentGenerationWorker(
      ContentGenerationJobRepository jobs,
      CourseRepository courses,
      LessonStudyContentRepository contents,
      QuizGenerationDraftRepository drafts,
      EmbyAudioClient embyAudio,
      OpenAiCompatibleAsrClient asr,
      LessonSummaryGenerator summaries,
      QuizContentGenerator quizzes,
      IntegrationSettingsProvider settings,
      IdGenerator ids,
      Clock clock) {
    this.jobs = jobs;
    this.courses = courses;
    this.contents = contents;
    this.drafts = drafts;
    this.embyAudio = embyAudio;
    this.asr = asr;
    this.summaries = summaries;
    this.quizzes = quizzes;
    this.settings = settings;
    this.ids = ids;
    this.clock = clock;
  }

  /** 服务重启不能悄悄重跑外部调用，遗留执行态统一标记为明确失败。 */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedJobs() {
    jobs.failInterrupted(clock.instant());
  }

  /** 原子进程锁保证同一服务实例全局只处理一个内容任务。 */
  @Scheduled(fixedDelayString = "${app.content.worker-delay-ms:1000}")
  public void poll() {
    if (!running.compareAndSet(false, true)) return;
    try {
      jobs.findNextQueued().ifPresent(this::execute);
    } finally {
      running.set(false);
    }
  }

  private void execute(ContentGenerationJob job) {
    Instant executionStarted = clock.instant();
    RuntimeIntegrationSettings runtime = settings.current();
    try {
      switch (job.type()) {
        case TRANSCRIBE -> transcribe(job, runtime, executionStarted);
        case SUMMARIZE -> summarize(job, runtime, executionStarted);
        case GENERATE_QUIZ -> generateQuiz(job, runtime, executionStarted);
      }
    } catch (BusinessException exception) {
      fail(job, exception.errorCode(), exception.getMessage(), executionStarted);
    } catch (Exception exception) {
      fail(job, "CONTENT_JOB_FAILED", "任务执行失败，请查看服务日志", executionStarted);
    }
  }

  private void transcribe(
      ContentGenerationJob job, RuntimeIntegrationSettings runtime, Instant executionStarted) {
    MediaItem lesson = lesson(job.mediaItemId());
    transition(job, ContentGenerationJob.Status.QUEUED, ContentGenerationJob.Status.FETCHING_AUDIO);
    log(job, "INFO", "FETCHING_AUDIO", "开始从 Emby 获取课时音频");
    Instant fetchStarted = clock.instant();
    long fetchMs;
    long transcribeMs;
    try (EmbyAudioClient.DownloadedAudio audio =
        embyAudio.download(lesson.embyItemId(), runtime.emby())) {
      fetchMs = elapsed(fetchStarted, clock.instant());
      transition(
          job,
          ContentGenerationJob.Status.FETCHING_AUDIO,
          ContentGenerationJob.Status.TRANSCRIBING);
      log(job, "INFO", "TRANSCRIBING", "音频获取完成，开始 ASR 转写");
      Instant transcribeStarted = clock.instant();
      RuntimeIntegrationSettings.Asr asrSnapshot =
          new RuntimeIntegrationSettings.Asr(
              runtime.asr().baseUrl(),
              runtime.asr().apiKey(),
              job.asrModel(),
              runtime.asr().language(),
              runtime.asr().chunkDurationSeconds(),
              runtime.asr().timeoutSeconds());
      String transcript = asr.transcribe(audio.path(), asrSnapshot);
      transcribeMs = elapsed(transcribeStarted, clock.instant());
      contents.upsertTranscript(ids.nextId(), lesson.id(), transcript, clock.instant());
    }
    long totalMs = elapsed(executionStarted, clock.instant());
    jobs.updateMetrics(
        job.id(),
        new ContentGenerationJobRepository.Metrics(
            lesson.durationMs(), fetchMs, transcribeMs, null, null, totalMs, null, null));
    transition(job, ContentGenerationJob.Status.TRANSCRIBING, ContentGenerationJob.Status.READY);
    log(job, "INFO", "READY", "全文转写已完成");
  }

  private void summarize(
      ContentGenerationJob job, RuntimeIntegrationSettings runtime, Instant executionStarted) {
    transition(job, ContentGenerationJob.Status.QUEUED, ContentGenerationJob.Status.SUMMARIZING);
    log(job, "INFO", "SUMMARIZING", "开始按上下文预算生成课时摘要");
    LessonStudyContent content = requireTranscript(job.mediaItemId());
    RuntimeIntegrationSettings.Llm llm = llmSnapshot(job, runtime);
    Instant started = clock.instant();
    LessonSummaryGenerator.Generation generated = summaries.generate(content.fullText(), llm);
    long summarizeMs = elapsed(started, clock.instant());
    contents.upsertSummary(ids.nextId(), job.mediaItemId(), generated.markdown(), clock.instant());
    long totalMs = elapsed(executionStarted, clock.instant());
    jobs.updateMetrics(
        job.id(),
        new ContentGenerationJobRepository.Metrics(
            null,
            null,
            null,
            summarizeMs,
            null,
            totalMs,
            generated.promptTokens(),
            generated.completionTokens()));
    transition(job, ContentGenerationJob.Status.SUMMARIZING, ContentGenerationJob.Status.READY);
    log(job, "INFO", "READY", "Markdown 摘要已完成");
  }

  private void generateQuiz(
      ContentGenerationJob job, RuntimeIntegrationSettings runtime, Instant executionStarted) {
    transition(
        job, ContentGenerationJob.Status.QUEUED, ContentGenerationJob.Status.GENERATING_QUIZ);
    log(job, "INFO", "GENERATING_QUIZ", "开始根据当前课时内容生成题目草稿");
    LessonStudyContent content = requireTranscript(job.mediaItemId());
    RuntimeIntegrationSettings.Llm llm = llmSnapshot(job, runtime);
    Instant started = clock.instant();
    QuizContentGenerator.Generation generated =
        quizzes.generate(
            content.fullText(), content.summaryMarkdown(), job.requestedQuestionCount(), llm);
    long generateMs = elapsed(started, clock.instant());
    drafts.save(toDraft(job, generated.questions()));
    long totalMs = elapsed(executionStarted, clock.instant());
    jobs.updateMetrics(
        job.id(),
        new ContentGenerationJobRepository.Metrics(
            null,
            null,
            null,
            null,
            generateMs,
            totalMs,
            generated.promptTokens(),
            generated.completionTokens()));
    transition(
        job,
        ContentGenerationJob.Status.GENERATING_QUIZ,
        ContentGenerationJob.Status.READY_FOR_REVIEW);
    log(job, "INFO", "READY_FOR_REVIEW", "题目草稿已生成，等待管理员审核或发布");
  }

  private QuizGenerationDraft toDraft(
      ContentGenerationJob job, List<QuizContentGenerator.GeneratedQuestion> questions) {
    List<QuizGenerationDraft.Item> items = new ArrayList<>();
    for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
      QuizContentGenerator.GeneratedQuestion question = questions.get(questionIndex);
      List<QuizGenerationDraft.Option> options = new ArrayList<>();
      for (int optionIndex = 0; optionIndex < question.options().size(); optionIndex++) {
        QuizContentGenerator.GeneratedOption option = question.options().get(optionIndex);
        options.add(
            new QuizGenerationDraft.Option(
                ids.nextId(), option.content(), option.correct(), optionIndex));
      }
      items.add(
          new QuizGenerationDraft.Item(
              ids.nextId(),
              question.type(),
              question.content(),
              question.explanation(),
              questionIndex,
              null,
              options));
    }
    QuizGenerationDraft draft =
        new QuizGenerationDraft(
            ids.nextId(),
            job.id(),
            job.courseId(),
            job.mediaItemId(),
            QuizGenerationDraft.Status.READY_FOR_REVIEW,
            job.requestedQuestionCount(),
            clock.instant(),
            null,
            items);
    draft.validate();
    return draft;
  }

  private RuntimeIntegrationSettings.Llm llmSnapshot(
      ContentGenerationJob job, RuntimeIntegrationSettings runtime) {
    return new RuntimeIntegrationSettings.Llm(
        runtime.llm().baseUrl(),
        runtime.llm().apiKey(),
        job.llmModel(),
        job.llmContextLength(),
        job.llmMaxCompletionTokens(),
        runtime.llm().timeoutSeconds());
  }

  private MediaItem lesson(String lessonId) {
    return courses
        .findMediaItem(lessonId)
        .orElseThrow(
            () ->
                new BusinessException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
  }

  private LessonStudyContent requireTranscript(String lessonId) {
    return contents
        .findByMediaItemId(lessonId)
        .filter(LessonStudyContent::transcriptReady)
        .orElseThrow(
            () ->
                new BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "TRANSCRIPT_NOT_READY",
                    "请先完成该课时全文转写"));
  }

  private void transition(
      ContentGenerationJob job,
      ContentGenerationJob.Status expected,
      ContentGenerationJob.Status target) {
    expected.requireTransition(job.type(), target);
    if (!jobs.transition(job.id(), expected, target, clock.instant())) {
      throw new IllegalStateException("内容任务状态已被其他执行器修改");
    }
  }

  private void fail(
      ContentGenerationJob job, String errorCode, String message, Instant executionStarted) {
    long totalMs = elapsed(executionStarted, clock.instant());
    jobs.fail(job.id(), errorCode, message, clock.instant(), totalMs);
    log(job, "ERROR", "FAILED", message);
  }

  private void log(ContentGenerationJob job, String level, String stage, String message) {
    jobs.addLog(
        new ContentGenerationJob.Log(
            ids.nextId(), job.id(), clock.instant(), level, stage, message));
  }

  private long elapsed(Instant start, Instant end) {
    return Math.max(0, Duration.between(start, end).toMillis());
  }
}
