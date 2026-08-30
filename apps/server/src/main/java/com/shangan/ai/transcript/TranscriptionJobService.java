package com.shangan.ai.transcript;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 单并发转写应用服务；状态、替换写入和 READY 门槛都由服务端控制。 */
@Service
public class TranscriptionJobService {
  private final TranscriptionStore store;
  private final CourseRepository courses;
  private final FfmpegAudioExtractor extractor;
  private final TranscriptionProvider transcription;
  private final VideoSummaryService summaries;
  private final EmbyTranscriptionMediaSource mediaSource;
  private final IdGenerator ids;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final TaskExecutor executor;

  public TranscriptionJobService(
      TranscriptionStore store,
      CourseRepository courses,
      FfmpegAudioExtractor extractor,
      TranscriptionProvider transcription,
      VideoSummaryService summaries,
      EmbyTranscriptionMediaSource mediaSource,
      IdGenerator ids,
      Clock clock,
      TransactionTemplate transactions,
      ObjectMapper json,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
    this.store = store;
    this.courses = courses;
    this.extractor = extractor;
    this.transcription = transcription;
    this.summaries = summaries;
    this.mediaSource = mediaSource;
    this.ids = ids;
    this.clock = clock;
    this.transactions = transactions;
    this.json = json;
    this.executor = executor;
  }

  /** 管理员触发后立即返回，耗时流水线在 Spring 管理的虚拟线程执行器中串行受数据库约束。 */
  public JobView start(String mediaItemId) {
    JobView job = enqueue(mediaItemId);
    executor.execute(() -> process(job.id()));
    return job;
  }

  /** 创建新任务或对终态任务重试；旧的部分结果在同一事务中清除。 */
  public JobView enqueue(String mediaItemId) {
    try {
      return transactions.execute(
          status -> {
            courses
                .findMediaItem(mediaItemId)
                .orElseThrow(
                    () ->
                        new BusinessException(
                            HttpStatus.NOT_FOUND, "MEDIA_ITEM_NOT_FOUND", "课时不存在"));
            Optional<TranscriptionStore.JobRow> active = store.findActive();
            if (active.isPresent()) throw busy();
            Instant now = clock.instant();
            Optional<TranscriptionStore.JobRow> existing = store.findByMediaItem(mediaItemId);
            if (existing.isPresent()) {
              store.resetJob(existing.get().id(), mediaItemId, now);
              return view(store.findJob(existing.get().id()).orElseThrow());
            }
            String id = ids.nextId();
            store.insertJob(id, mediaItemId, now);
            return view(store.findJob(id).orElseThrow());
          });
    } catch (DataIntegrityViolationException exception) {
      throw busy();
    }
  }

  /** 同步执行单个已排队任务；管理员请求之外也可由后续调度器安全调用。 */
  public JobView process(String jobId) {
    TranscriptionStore.JobRow job = requireJob(jobId);
    if (!job.status().equals("PENDING")) {
      throw new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPTION_NOT_PENDING", "转写任务不在待处理状态");
    }
    try {
      transition(jobId, "EXTRACTING_AUDIO");
      EmbyTranscriptionMediaSource.Source source = mediaSource.resolve(job.embyItemId());
      try (FfmpegAudioExtractor.ExtractedAudio audio =
          extractor.extract(source.uri(), source.headers())) {
        transition(jobId, "TRANSCRIBING");
        List<TranscriptionStore.StoredSegment> segments = transcribe(audio);
        Instant persistedAt = clock.instant();
        transactions.executeWithoutResult(
            status -> store.replaceSegments(job.mediaItemId(), segments, persistedAt, ids::nextId));

        transition(jobId, "SUMMARIZING");
        VideoSummaryService.SummaryBundle bundle =
            summaries.generate(
                job.mediaItemId(),
                segments.stream()
                    .map(
                        segment ->
                            new VideoSummaryService.SegmentInput(
                                segment.startMs(), segment.endMs(), segment.text()))
                    .toList());
        String outline = outlineJson(bundle.sections());
        transactions.executeWithoutResult(
            status -> {
              store.replaceSummaries(job.mediaItemId(), bundle, outline, ids::nextId);
              long segmentCount = store.segmentCount(job.mediaItemId());
              if (segmentCount == 0 || store.ftsCount(job.mediaItemId()) != segmentCount) {
                throw new IllegalStateException("FTS5 索引未完整同步");
              }
              store.updateStatus(jobId, "READY", clock.instant());
            });
      }
    } catch (Exception exception) {
      transactions.executeWithoutResult(
          status -> store.fail(jobId, safeError(exception), clock.instant()));
    }
    return view(requireJob(jobId));
  }

  public Optional<JobView> findByMediaItem(String mediaItemId) {
    return store.findByMediaItem(mediaItemId).map(this::view);
  }

  public List<JobView> list() {
    return store.findAll().stream().map(this::view).toList();
  }

  private List<TranscriptionStore.StoredSegment> transcribe(
      FfmpegAudioExtractor.ExtractedAudio audio) {
    List<TranscriptionProvider.TranscriptionSegment> raw = new ArrayList<>();
    for (FfmpegAudioExtractor.AudioChunk chunk : audio.chunks()) {
      TranscriptionProvider.TranscriptionResult result =
          transcription.transcribe(
              chunk.path(), new TranscriptionProvider.TranscriptionRequest(chunk.startMs(), "zh"));
      raw.addAll(result.segments());
    }
    raw.sort(
        java.util.Comparator.comparingLong(TranscriptionProvider.TranscriptionSegment::startMs)
            .thenComparingLong(TranscriptionProvider.TranscriptionSegment::endMs));
    List<TranscriptionStore.StoredSegment> normalized = new ArrayList<>();
    for (TranscriptionProvider.TranscriptionSegment segment : raw) {
      if (segment.text() == null || segment.text().isBlank()) continue;
      normalized.add(
          new TranscriptionStore.StoredSegment(
              normalized.size(),
              Math.max(0, segment.startMs()),
              Math.max(segment.startMs(), segment.endMs()),
              segment.text().trim()));
    }
    if (normalized.isEmpty()) throw new IllegalStateException("ASR 未生成转写片段");
    return List.copyOf(normalized);
  }

  private String outlineJson(List<VideoSummaryService.SectionResult> sections) {
    try {
      return json.writeValueAsString(
          sections.stream()
              .map(
                  section ->
                      java.util.Map.of(
                          "sectionIndex",
                          section.sectionIndex(),
                          "startMs",
                          section.startMs(),
                          "endMs",
                          section.endMs(),
                          "summary",
                          section.summary()))
              .toList());
    } catch (Exception exception) {
      throw new IllegalStateException("摘要结构序列化失败", exception);
    }
  }

  private void transition(String jobId, String status) {
    transactions.executeWithoutResult(
        transaction -> store.updateStatus(jobId, status, clock.instant()));
  }

  private TranscriptionStore.JobRow requireJob(String jobId) {
    return store
        .findJob(jobId)
        .orElseThrow(
            () ->
                new BusinessException(
                    HttpStatus.NOT_FOUND, "TRANSCRIPTION_JOB_NOT_FOUND", "转写任务不存在"));
  }

  private String safeError(Exception exception) {
    if (exception instanceof FfmpegAudioExtractor.AudioExtractionException
        || exception instanceof EmbyTranscriptionMediaSource.MediaSourceException) {
      return "音频抽取失败";
    }
    if (exception instanceof OpenAiCompatibleTranscriptionProvider.TranscriptionProviderException) {
      return "ASR 转写失败";
    }
    if (exception instanceof VideoSummaryService.SummaryGenerationException) {
      return "视频摘要生成失败";
    }
    return "转写处理失败";
  }

  private BusinessException busy() {
    return new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPTION_BUSY", "已有转写任务正在处理");
  }

  private JobView view(TranscriptionStore.JobRow row) {
    return new JobView(
        row.id(),
        row.mediaItemId(),
        row.title(),
        row.status(),
        row.attemptCount(),
        row.lastError(),
        row.startedAt(),
        row.finishedAt(),
        row.createdAt(),
        row.updatedAt());
  }

  public record JobView(
      String id,
      String mediaItemId,
      String mediaTitle,
      String status,
      int attemptCount,
      String lastError,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt,
      Instant updatedAt) {}
}
