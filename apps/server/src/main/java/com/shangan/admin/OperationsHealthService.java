package com.shangan.admin;

import com.shangan.ai.transcript.TranscriptionJobService;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.media.emby.EmbyHealthService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 汇总管理后台所需的只读运行状态，严格只返回路径、大小、状态和配置布尔值。 */
@Service
public class OperationsHealthService {
  private static final String SQLITE_PREFIX = "jdbc:sqlite:";

  private final String datasourceUrl;
  private final EmbyHealthService emby;
  private final CourseSyncService courses;
  private final TranscriptionJobService transcriptions;
  private final boolean llmConfigured;
  private final boolean asrConfigured;
  private final boolean mcpConfigured;

  public OperationsHealthService(
      @Value("${spring.datasource.url}") String datasourceUrl,
      EmbyHealthService emby,
      CourseSyncService courses,
      TranscriptionJobService transcriptions,
      @Value("${app.ai.llm.base-url:}") String llmBaseUrl,
      @Value("${app.ai.llm.api-key:}") String llmApiKey,
      @Value("${app.ai.llm.model:}") String llmModel,
      @Value("${app.ai.asr.base-url:}") String asrBaseUrl,
      @Value("${app.ai.asr.api-key:}") String asrApiKey,
      @Value("${app.ai.asr.model:}") String asrModel,
      @Value("${app.ai.mcp.url:}") String mcpUrl) {
    this.datasourceUrl = datasourceUrl;
    this.emby = emby;
    this.courses = courses;
    this.transcriptions = transcriptions;
    this.llmConfigured = configured(llmBaseUrl, llmApiKey, llmModel);
    this.asrConfigured = configured(asrBaseUrl, asrApiKey, asrModel);
    this.mcpConfigured = configured(mcpUrl);
  }

  /** 在一个只读事务内获取数据库业务状态；文件大小读取失败时安全降级为 0。 */
  @Transactional(readOnly = true)
  public Snapshot snapshot() {
    Path database = databasePath();
    Instant lastCourseSync =
        courses.listAdminCourses().stream()
            .map(course -> course.lastSyncedAt())
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    String activeTranscription =
        transcriptions.list().stream()
            .filter(job -> isActive(job.status()))
            .findFirst()
            .map(job -> job.mediaTitle() + "（" + job.status() + "）")
            .orElse("无");
    return new Snapshot(
        database.toString(),
        fileSize(database),
        fileSize(Path.of(database + "-wal")),
        emby.status(),
        lastCourseSync,
        activeTranscription,
        llmConfigured,
        asrConfigured,
        mcpConfigured);
  }

  private Path databasePath() {
    String configuredPath =
        datasourceUrl.startsWith(SQLITE_PREFIX)
            ? datasourceUrl.substring(SQLITE_PREFIX.length())
            : datasourceUrl;
    return Path.of(configuredPath).toAbsolutePath().normalize();
  }

  private long fileSize(Path path) {
    try {
      return Files.exists(path) ? Files.size(path) : 0;
    } catch (java.io.IOException exception) {
      return 0;
    }
  }

  private boolean isActive(String status) {
    return java.util.Set.of("PENDING", "EXTRACTING_AUDIO", "TRANSCRIBING", "SUMMARIZING")
        .contains(status);
  }

  private static boolean configured(String... values) {
    return java.util.Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
  }

  /** 后台健康快照不包含任何密钥、Token、远端 URL 或第三方响应正文。 */
  public record Snapshot(
      String databasePath,
      long databaseSizeBytes,
      long walSizeBytes,
      String embyStatus,
      Instant lastCourseSync,
      String activeTranscription,
      boolean llmConfigured,
      boolean asrConfigured,
      boolean mcpConfigured) {}
}
