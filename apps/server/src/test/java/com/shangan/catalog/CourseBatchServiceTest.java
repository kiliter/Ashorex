package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.catalog.application.CourseBatchService;
import com.shangan.catalog.application.CourseBatchWriter;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证批量建课只在全部来源有效后写入，并隔离单门课程的同步失败。 */
class CourseBatchServiceTest {

  @Test
  void createsRestoresAndSkipsCoursesThenSynchronizesIndependently() {
    FakeRepository repository = new FakeRepository();
    repository.courses.put(
        "active", new Course("active", "已有课程", "", "source-1", true, 0, null, null));
    repository.courses.put(
        "archived", new Course("archived", "已归档课程", "", "source-2", false, 1, null, null));
    FakeEmbyGateway emby = new FakeEmbyGateway();
    emby.addSource("source-1", "已有课程", "Series");
    emby.addSource("source-2", "恢复课程", "Folder");
    emby.addSource("source-3", "新增课程", "CollectionFolder");
    List<String> synchronizedCourseIds = new ArrayList<>();
    CourseBatchService service =
        new CourseBatchService(
            emby,
            new CourseBatchWriter(
                repository,
                () -> "generated-course",
                Clock.fixed(Instant.parse("2026-09-02T03:00:00Z"), ZoneOffset.UTC)),
            courseId -> {
              synchronizedCourseIds.add(courseId);
              if (courseId.equals("generated-course")) {
                throw new BusinessException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "EMBY_UNAVAILABLE",
                    "媒体服务暂时不可用");
              }
            });

    CourseBatchService.BatchResult result =
        service.addAndSynchronize(List.of("source-1", "source-2", "source-3"));

    assertThat(result.items())
        .extracting(CourseBatchService.BatchItem::action, CourseBatchService.BatchItem::syncStatus)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                CourseBatchWriter.Action.SKIPPED, CourseBatchService.SyncStatus.NOT_REQUESTED),
            org.assertj.core.groups.Tuple.tuple(
                CourseBatchWriter.Action.RESTORED, CourseBatchService.SyncStatus.SUCCESS),
            org.assertj.core.groups.Tuple.tuple(
                CourseBatchWriter.Action.CREATED, CourseBatchService.SyncStatus.FAILED));
    assertThat(repository.courses.get("archived").enabled()).isTrue();
    assertThat(repository.courses.get("generated-course").embyParentItemId()).isEqualTo("source-3");
    assertThat(synchronizedCourseIds).containsExactly("archived", "generated-course");
  }

  @Test
  void validatesEverySourceBeforeCreatingAnyCourse() {
    FakeRepository repository = new FakeRepository();
    FakeEmbyGateway emby = new FakeEmbyGateway();
    emby.addSource("source-1", "第一门", "Series");
    CourseBatchService service =
        new CourseBatchService(
            emby,
            new CourseBatchWriter(
                repository,
                () -> "generated-course",
                Clock.fixed(Instant.parse("2026-09-02T03:00:00Z"), ZoneOffset.UTC)),
            courseId -> {});

    assertThatThrownBy(() -> service.addAndSynchronize(List.of("source-1", "missing-source")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("EMBY_PARENT_NOT_FOUND"));
    assertThat(repository.courses).isEmpty();
  }

  @Test
  void historicalRemovedCourseMustBeHardDeletedBeforeTheSourceCanBeAddedAgain() {
    FakeRepository repository = new FakeRepository();
    repository.courses.put(
        "removed",
        new Course(
            "removed",
            "历史移除课程",
            "",
            "source-1",
            false,
            0,
            null,
            null,
            Instant.parse("2026-09-02T04:00:00Z")));
    FakeEmbyGateway emby = new FakeEmbyGateway();
    emby.addSource("source-1", "重新添加课程", "Series");
    CourseBatchService service =
        new CourseBatchService(
            emby,
            new CourseBatchWriter(
                repository,
                () -> "new-course",
                Clock.fixed(Instant.parse("2026-09-02T05:00:00Z"), ZoneOffset.UTC)),
            courseId -> {});

    assertThatThrownBy(() -> service.addAndSynchronize(List.of("source-1")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("COURSE_PENDING_HARD_DELETE"));
    assertThat(repository.courses).doesNotContainKey("new-course");
  }

  private static final class FakeEmbyGateway implements EmbyGateway {
    private final Map<String, EmbyDtos.MediaSource> sources = new LinkedHashMap<>();

    private void addSource(String id, String name, String type) {
      sources.put(id, new EmbyDtos.MediaSource(id, name, type, "", "library-1"));
    }

    @Override
    public EmbyDtos.MediaSource getSource(String itemId) {
      EmbyDtos.MediaSource source = sources.get(itemId);
      if (source == null) {
        throw new BusinessException(
            org.springframework.http.HttpStatus.CONFLICT,
            "EMBY_PARENT_NOT_FOUND",
            "Emby 媒体来源不存在或当前用户无权访问");
      }
      return source;
    }

    @Override
    public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
      return List.of();
    }
  }

  private static final class FakeRepository implements CourseRepository {
    private final Map<String, Course> courses = new LinkedHashMap<>();

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
      return List.of();
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      return Optional.empty();
    }

    @Override
    public void insertCourse(Course course, Instant now) {
      courses.put(course.id(), course);
    }

    @Override
    public void updateCourseEnabled(String courseId, boolean enabled, Instant now) {
      Course course = courses.get(courseId);
      courses.put(
          courseId,
          new Course(
              course.id(),
              course.name(),
              course.description(),
              course.embyParentItemId(),
              enabled,
              course.sortOrder(),
              course.lastSyncedAt(),
              course.lastSyncError()));
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
    public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {}

    @Override
    public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {}
  }
}
