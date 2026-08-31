package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.integration.IntegrationSettingsProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭的缺失内容扫描器，只创建转写和摘要任务，不覆盖内容也不生成题目。 */
@Component
public class ContentAutoFillScheduler {

  private final IntegrationSettingsProvider settings;
  private final CourseRepository courses;
  private final ContentGenerationJobService jobs;
  private final Clock clock;
  private final AtomicReference<Instant> nextScanAt = new AtomicReference<>(Instant.EPOCH);

  public ContentAutoFillScheduler(
      IntegrationSettingsProvider settings,
      CourseRepository courses,
      ContentGenerationJobService jobs,
      Clock clock) {
    this.settings = settings;
    this.courses = courses;
    this.jobs = jobs;
    this.clock = clock;
  }

  /** 每分钟检查动态开关，真正扫描间隔使用后台保存的分钟数。 */
  @Scheduled(fixedDelayString = "${app.content.auto-fill-tick-ms:60000}")
  public void scanIfDue() {
    var configuration = settings.current().autoFill();
    if (!configuration.enabled()) return;
    Instant now = clock.instant();
    if (now.isBefore(nextScanAt.get())) return;
    nextScanAt.set(now.plus(configuration.intervalMinutes(), ChronoUnit.MINUTES));
    for (var course : courses.findAllCourses(true)) {
      jobs.enqueueCourse(course.id(), ContentGenerationJob.Type.TRANSCRIBE, 5, "content-auto-fill");
      jobs.enqueueCourse(course.id(), ContentGenerationJob.Type.SUMMARIZE, 5, "content-auto-fill");
    }
  }
}
