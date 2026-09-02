package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.catalog.application.CatalogSnapshotWriter;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.MediaMappingPlanner;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证课程归档只改变启用状态，恢复沿用原身份，定时同步只读取活动课程。 */
class CourseArchiveServiceTest {

  @Test
  void archivesAndRestoresTheSameCourseWithoutDeletingLessons() {
    FakeRepository repository = new FakeRepository();
    repository.course = new Course("course-1", "行测", "", "source-1", true, 0, null, null);
    repository.lessons.add(
        new MediaItem("lesson-1", "course-1", "emby-1", "第一课", 60_000, true, 0, true));
    CourseSyncService service = service(repository);

    assertThat(service.countAdminLessons("course-1")).isEqualTo(1);
    service.archiveCourse("course-1");
    assertThat(repository.course.enabled()).isFalse();
    assertThat(repository.lessons).extracting(MediaItem::id).containsExactly("lesson-1");

    service.restoreCourse("course-1");
    assertThat(repository.course.enabled()).isTrue();
    assertThat(repository.enabledUpdates).containsExactly(false, true);
    assertThat(repository.lessons).extracting(MediaItem::id).containsExactly("lesson-1");
  }

  @Test
  void scheduledSynchronizationOnlyQueriesActiveCourses() {
    FakeRepository repository = new FakeRepository();
    repository.course = new Course("course-1", "行测", "", "source-1", false, 0, null, null);

    service(repository).synchronizeAll();

    assertThat(repository.findAllArguments).containsExactly(true);
  }

  private CourseSyncService service(FakeRepository repository) {
    Clock clock = Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);
    return new CourseSyncService(
        repository,
        (EmbyGateway) parentId -> List.of(),
        () -> "generated-id",
        clock,
        new CatalogSnapshotWriter(repository, () -> "generated-id", clock),
        new MediaMappingPlanner());
  }

  /** 纯内存 Fake 只记录归档边界，不连接 SQLite 或 Flyway。 */
  private static final class FakeRepository implements CourseRepository {
    private Course course;
    private final List<MediaItem> lessons = new ArrayList<>();
    private final List<Boolean> enabledUpdates = new ArrayList<>();
    private final List<Boolean> findAllArguments = new ArrayList<>();

    @Override
    public Optional<Course> findCourse(String courseId) {
      return Optional.ofNullable(course);
    }

    @Override
    public List<Course> findAllCourses(boolean enabledOnly) {
      findAllArguments.add(enabledOnly);
      if (course == null || (enabledOnly && !course.enabled())) {
        return List.of();
      }
      return List.of(course);
    }

    @Override
    public List<MediaItem> findMediaItems(String courseId, boolean enabledOnly) {
      return List.copyOf(lessons);
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      return lessons.stream().filter(item -> item.id().equals(mediaItemId)).findFirst();
    }

    @Override
    public void insertCourse(Course value, Instant now) {
      course = value;
    }

    @Override
    public void insertMediaItem(MediaItem item, Instant now) {
      lessons.add(item);
    }

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
    public void updateCourseEnabled(String courseId, boolean enabled, Instant now) {
      enabledUpdates.add(enabled);
      course =
          new Course(
              course.id(),
              course.name(),
              course.description(),
              course.embyParentItemId(),
              enabled,
              course.sortOrder(),
              course.lastSyncedAt(),
              course.lastSyncError());
    }

    @Override
    public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {}

    @Override
    public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {}
  }
}
