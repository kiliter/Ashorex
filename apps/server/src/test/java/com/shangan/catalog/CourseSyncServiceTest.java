package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.catalog.application.CatalogSnapshotWriter;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
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

/** 验证课程同步的更新、保留本地设置、远端下线和幂等规则。 */
class CourseSyncServiceTest {

  @Test
  void synchronizesSnapshotsWithoutOverwritingLocalControls() {
    FakeRepository repository = new FakeRepository();
    repository.course = new Course("course-1", "行测", "", "parent-1", true, 0, null, null);
    repository.items.put(
        "emby-old",
        new MediaItem("local-old", "course-1", "emby-old", "旧标题", 1_000, false, 9, true));
    repository.items.put(
        "emby-removed",
        new MediaItem("local-removed", "course-1", "emby-removed", "已移除", 2_000, true, 2, true));
    EmbyGateway emby =
        parentId ->
            List.of(
                new EmbyDtos.MediaItem("emby-old", "新标题", 3_000, 1),
                new EmbyDtos.MediaItem("emby-new", "新增课程", 4_000, 2));
    CourseSyncService service =
        new CourseSyncService(
            repository,
            emby,
            () -> "generated-" + repository.items.size(),
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
            new CatalogSnapshotWriter(
                repository,
                () -> "generated-" + repository.items.size(),
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC)));

    service.syncCourse("course-1");
    service.syncCourse("course-1");

    assertThat(repository.items).hasSize(3);
    assertThat(repository.items.get("emby-old"))
        .extracting(
            MediaItem::title, MediaItem::durationMs, MediaItem::enabled, MediaItem::sortOrder)
        .containsExactly("新标题", 3_000L, false, 9);
    assertThat(repository.items.get("emby-new").available()).isTrue();
    assertThat(repository.items.get("emby-removed").available()).isFalse();
  }

  private static final class FakeRepository implements CourseRepository {
    private Course course;
    private final Map<String, MediaItem> items = new LinkedHashMap<>();

    @Override
    public Optional<Course> findCourse(String courseId) {
      return Optional.ofNullable(course);
    }

    @Override
    public List<Course> findAllCourses(boolean enabledOnly) {
      return course == null ? List.of() : List.of(course);
    }

    @Override
    public List<MediaItem> findMediaItems(String courseId, boolean enabledOnly) {
      return new ArrayList<>(items.values());
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      return items.values().stream().filter(item -> item.id().equals(mediaItemId)).findFirst();
    }

    @Override
    public void insertCourse(Course value, Instant now) {
      course = value;
    }

    @Override
    public void upsertMediaItem(MediaItem item, Instant now) {
      MediaItem existing = items.get(item.embyItemId());
      items.put(
          item.embyItemId(),
          existing == null
              ? item
              : new MediaItem(
                  existing.id(),
                  existing.courseId(),
                  existing.embyItemId(),
                  item.title(),
                  item.durationMs(),
                  existing.enabled(),
                  existing.sortOrder(),
                  true));
    }

    @Override
    public void markUnavailableExcept(String courseId, List<String> availableEmbyIds, Instant now) {
      items.replaceAll(
          (id, item) ->
              availableEmbyIds.contains(id)
                  ? item
                  : new MediaItem(
                      item.id(),
                      item.courseId(),
                      item.embyItemId(),
                      item.title(),
                      item.durationMs(),
                      item.enabled(),
                      item.sortOrder(),
                      false));
    }

    @Override
    public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {}

    @Override
    public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {}
  }
}
