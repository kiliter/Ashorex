package com.shangan.ai.content.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证自动补全默认关闭，开启后也只创建缺失转写和摘要任务。 */
class ContentAutoFillSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");

  @Test
  void doesNothingWhenDefaultSwitchIsDisabled() {
    IntegrationSettingsProvider settings = mock(IntegrationSettingsProvider.class);
    CourseRepository courses = mock(CourseRepository.class);
    ContentGenerationJobService jobs = mock(ContentGenerationJobService.class);
    when(settings.current()).thenReturn(runtime(RuntimeIntegrationSettings.AutoFill.defaults()));
    var scheduler = new ContentAutoFillScheduler(settings, courses, jobs, fixedClock());

    scheduler.scanIfDue();

    verify(courses, never()).findAllCourses(anyBoolean());
    verify(jobs, never()).enqueueCourse(any(), any(), anyInt(), any());
  }

  @Test
  void enqueuesOnlyTranscriptionAndSummaryWhenEnabled() {
    IntegrationSettingsProvider settings = mock(IntegrationSettingsProvider.class);
    CourseRepository courses = mock(CourseRepository.class);
    ContentGenerationJobService jobs = mock(ContentGenerationJobService.class);
    when(settings.current()).thenReturn(runtime(new RuntimeIntegrationSettings.AutoFill(true, 15)));
    when(courses.findAllCourses(true))
        .thenReturn(List.of(new Course("course-1", "课程", "", "emby-parent", true, 1, null, null)));
    var scheduler = new ContentAutoFillScheduler(settings, courses, jobs, fixedClock());

    scheduler.scanIfDue();

    verify(jobs)
        .enqueueCourse("course-1", ContentGenerationJob.Type.TRANSCRIBE, 5, "content-auto-fill");
    verify(jobs)
        .enqueueCourse("course-1", ContentGenerationJob.Type.SUMMARIZE, 5, "content-auto-fill");
    verify(jobs, never())
        .enqueueCourse(
            eq("course-1"), eq(ContentGenerationJob.Type.GENERATE_QUIZ), anyInt(), any());
  }

  private Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private RuntimeIntegrationSettings runtime(RuntimeIntegrationSettings.AutoFill autoFill) {
    return new RuntimeIntegrationSettings(
        new RuntimeIntegrationSettings.Emby("", "", ""),
        RuntimeIntegrationSettings.Asr.defaults(),
        RuntimeIntegrationSettings.Llm.defaults(),
        new RuntimeIntegrationSettings.OpenRouter(""),
        autoFill,
        NOW.toEpochMilli());
  }
}
