package com.shangan.admin;

import com.shangan.ai.content.application.ContentGenerationJobService;
import com.shangan.ai.content.infrastructure.ContentGenerationJobRepository;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import com.shangan.common.integration.IntegrationSettingsProvider;
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
  private final LessonStudyContentImportService studyContents;
  private final ContentGenerationJobService contentJobs;
  private final IntegrationSettingsProvider settings;

  public OperationsHealthService(
      @Value("${spring.datasource.url}") String datasourceUrl,
      EmbyHealthService emby,
      CourseSyncService courses,
      LessonStudyContentImportService studyContents,
      ContentGenerationJobService contentJobs,
      IntegrationSettingsProvider settings) {
    this.datasourceUrl = datasourceUrl;
    this.emby = emby;
    this.courses = courses;
    this.studyContents = studyContents;
    this.contentJobs = contentJobs;
    this.settings = settings;
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
    ContentGenerationJobRepository.QueueStats queue = contentJobs.stats();
    var runtime = settings.current();
    return new Snapshot(
        database.toString(),
        fileSize(database),
        fileSize(Path.of(database + "-wal")),
        emby.status(),
        lastCourseSync,
        studyContents.contentCount(),
        runtime.asr().configured(),
        runtime.llm().configured(),
        queue);
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

  /** 后台健康快照不包含任何密钥、Token、远端 URL 或第三方响应正文。 */
  public record Snapshot(
      String databasePath,
      long databaseSizeBytes,
      long walSizeBytes,
      String embyStatus,
      Instant lastCourseSync,
      long lessonStudyContentCount,
      boolean asrConfigured,
      boolean llmConfigured,
      ContentGenerationJobRepository.QueueStats contentQueue) {}
}
