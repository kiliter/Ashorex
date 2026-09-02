package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.catalog.application.CatalogSnapshotWriter;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.MediaMappingPlanner;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证归档课程移除的整批校验、幂等和历史保留边界。 */
class CourseRemovalServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

  @Test
  void removesMultipleArchivedCoursesAndWritesSafeAuditWithoutDeletingLessons() {
    FakeRepository repository = new FakeRepository();
    repository.add(course("course-1", false, null));
    repository.add(course("course-2", false, null));
    repository.lessons.add(
        new MediaItem("lesson-1", "course-1", "emby-1", "课时", 60_000, true, 0, true));

    int removed =
        service(repository)
            .removeArchivedCourses(List.of("course-1", "course-2"), "admin", "request-1");

    assertThat(removed).isEqualTo(2);
    assertThat(repository.courses.values()).allMatch(Course::removed);
    assertThat(repository.lessons).extracting(MediaItem::id).containsExactly("lesson-1");
    assertThat(repository.audits)
        .containsExactly("course-1|admin|request-1", "course-2|admin|request-1");

    assertThat(
            service(repository)
                .removeArchivedCourses(List.of("course-1", "course-2"), "admin", "request-2"))
        .isZero();
    assertThat(repository.audits).hasSize(2);
  }

  @Test
  void rejectsTheWholeBatchWhenItContainsAnActiveCourse() {
    FakeRepository repository = new FakeRepository();
    repository.add(course("archived", false, null));
    repository.add(course("active", true, null));

    assertThatThrownBy(
            () ->
                service(repository)
                    .removeArchivedCourses(List.of("archived", "active"), "admin", "request-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("COURSE_NOT_ARCHIVED"));
    assertThat(repository.courses.get("archived").removed()).isFalse();
    assertThat(repository.audits).isEmpty();
  }

  private CourseSyncService service(FakeRepository repository) {
    AtomicInteger sequence = new AtomicInteger();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new CourseSyncService(
        repository,
        (EmbyGateway) parentId -> List.of(),
        () -> "audit-" + sequence.incrementAndGet(),
        clock,
        new CatalogSnapshotWriter(repository, () -> "mapping-id", clock),
        new MediaMappingPlanner());
  }

  private Course course(String id, boolean enabled, Instant removedAt) {
    return new Course(id, id, "", "source-" + id, enabled, 0, null, null, removedAt);
  }

  /** 纯内存 Fake 只记录课程状态和审计，不连接 SQLite 或 Flyway。 */
  private static final class FakeRepository implements CourseRepository {
    private final Map<String, Course> courses = new LinkedHashMap<>();
    private final List<MediaItem> lessons = new ArrayList<>();
    private final List<String> audits = new ArrayList<>();

    private void add(Course course) {
      courses.put(course.id(), course);
    }

    @Override
    public Optional<Course> findCourse(String courseId) {
      return Optional.ofNullable(courses.get(courseId));
    }

    @Override
    public List<Course> findAllCourses(boolean enabledOnly) {
      return courses.values().stream().filter(course -> !enabledOnly || course.enabled()).toList();
    }

    @Override
    public List<MediaItem> findMediaItems(String courseId, boolean enabledOnly) {
      return lessons.stream().filter(item -> item.courseId().equals(courseId)).toList();
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      return lessons.stream().filter(item -> item.id().equals(mediaItemId)).findFirst();
    }

    @Override
    public void insertCourse(Course course, Instant now) {
      add(course);
    }

    @Override
    public void updateCourseRemoved(String courseId, Instant now) {
      Course course = courses.get(courseId);
      courses.put(
          courseId,
          new Course(
              course.id(),
              course.name(),
              course.description(),
              course.embyParentItemId(),
              false,
              course.sortOrder(),
              course.lastSyncedAt(),
              course.lastSyncError(),
              now));
    }

    @Override
    public void insertCourseRemovalAudit(
        String id, String courseId, String administrator, String requestId, Instant now) {
      audits.add(courseId + "|" + administrator + "|" + requestId);
    }

    @Override
    public void insertMediaItem(MediaItem item, Instant now) {}

    @Override
    public void updateMediaItemFromRemote(MediaItem item, Instant now) {}

    @Override
    public void insertMediaItemSourceMapping(
        String id,
        String mediaItemId,
        String oldEmbyItemId,
        String newEmbyItemId,
        String matchType,
        Instant now) {}

    @Override
    public void markUnavailableExceptMediaIds(
        String courseId, List<String> availableMediaItemIds, Instant now) {}

    @Override
    public void updateCourseSource(String courseId, String embyParentItemId, Instant now) {}

    @Override
    public void updateCourseEnabled(String courseId, boolean enabled, Instant now) {}

    @Override
    public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {}

    @Override
    public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {}
  }
}
