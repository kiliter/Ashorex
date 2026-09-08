package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 仅验证重绑身份和失败可见性，不连接数据库或测试 SQL。 */
class CourseSyncReviewTest {
  private final Instant now = Instant.parse("2026-09-08T12:00:00Z");
  private final CourseRepository courses = mock(CourseRepository.class);
  private final EmbyCatalogReader reader = mock(EmbyCatalogReader.class);
  private final org.springframework.transaction.PlatformTransactionManager tx =
      mock(org.springframework.transaction.PlatformTransactionManager.class);
  private final CourseSyncService service =
      new CourseSyncService(
          courses,
          reader,
          new ResourceMappingPlanner(),
          mock(IdGenerator.class),
          Clock.fixed(now, ZoneOffset.UTC),
          tx);

  private Course course() {
    return new Course(
        "course-1",
        "EMBY",
        "parent-old",
        "课程",
        "",
        null,
        0,
        CatalogStatus.ACTIVE,
        false,
        null,
        null,
        null);
  }

  @Test
  void rebindPersistsNewParentForFutureSyncs() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(reader.readCourseMetadata("parent-new"))
        .thenReturn(
            new ResourceMetadata.CourseMetadata(
                "parent-new", "课程", "", null, List.of(), List.of(), List.of()));
    when(reader.readResources("parent-new")).thenReturn(List.of());
    assertThat(service.rebind("course-1", "parent-new", "admin").succeeded()).isTrue();
    verify(courses).updateCourseExternalRef("course-1", "parent-new", now);
    // 所有远端读取结束后才开始事务，避免长读事务升级写事务时遇到旧快照。
    var order = inOrder(reader, tx, courses);
    order.verify(reader).readCourseMetadata("parent-new");
    order.verify(reader).readResources("parent-new");
    order.verify(tx).getTransaction(any());
    order.verify(courses).updateCourseExternalRef("course-1", "parent-new", now);
  }

  @Test
  void transientNetworkFailureKeepsCourseVisible() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(reader.readCourseMetadata("parent-old"))
        .thenThrow(
            new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用"));
    assertThat(service.sync("course-1").succeeded()).isFalse();
    verify(courses).markSourceMissing("course-1", false, "媒体服务暂时不可用", now);
    verify(courses, never()).updateCourseExternalRef(anyString(), anyString(), any());
  }

  @Test
  void missingParentHidesCourseWithoutDiscardingResources() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(reader.readCourseMetadata("parent-old"))
        .thenThrow(new BusinessException(HttpStatus.CONFLICT, "EMBY_PARENT_NOT_FOUND", "来源不存在"));
    assertThat(service.sync("course-1").succeeded()).isFalse();
    verify(courses).markSourceMissing("course-1", true, "来源不存在", now);
    verify(courses, never()).updateResourceAvailability(anyString(), anyBoolean(), any());
  }
}
