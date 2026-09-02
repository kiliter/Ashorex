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
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC)),
            new MediaMappingPlanner());

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

  @Test
  void rebindsLegacyLessonInPlaceAndAuditsChangedEmbyId() {
    FakeRepository repository = new FakeRepository();
    repository.course = new Course("course-1", "蓝牙课程", "", "deleted-parent", true, 0, null, null);
    repository.items.put(
        "old-emby",
        new MediaItem("local-lesson", "course-1", "old-emby", "第一课", 60_000, false, 9, true));
    EmbyGateway emby =
        parentId ->
            List.of(
                new EmbyDtos.MediaItem(
                    "new-emby", "第一课", 61_500, 1, "Movie", "path-sha256-v1:new"));
    Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    CourseSyncService service =
        new CourseSyncService(
            repository,
            emby,
            () -> "generated-" + (++repository.generatedIds),
            clock,
            new CatalogSnapshotWriter(
                repository, () -> "generated-" + (++repository.generatedIds), clock),
            new MediaMappingPlanner());

    service.rebindCourse("course-1", "new-parent", Map.of());

    assertThat(repository.course.embyParentItemId()).isEqualTo("new-parent");
    assertThat(repository.items).containsOnlyKeys("new-emby");
    assertThat(repository.items.get("new-emby"))
        .extracting(
            MediaItem::id, MediaItem::embyItemType, MediaItem::enabled, MediaItem::sortOrder)
        .containsExactly("local-lesson", "Movie", false, 9);
    assertThat(repository.mappingAudit)
        .containsExactly("old-emby->new-emby:UNIQUE_LEGACY_METADATA");
  }

  @Test
  void rejectsSubmittedMappingThatIsNotAConflictCandidate() {
    FakeRepository repository = new FakeRepository();
    repository.course = new Course("course-1", "行测", "", "old-parent", true, 0, null, null);
    repository.items.put(
        "same-emby",
        new MediaItem("local-lesson", "course-1", "same-emby", "第一课", 60_000, true, 1, true));
    EmbyGateway emby = parentId -> List.of(new EmbyDtos.MediaItem("same-emby", "第一课", 60_000, 1));
    Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    CourseSyncService service =
        new CourseSyncService(
            repository,
            emby,
            () -> "generated-" + (++repository.generatedIds),
            clock,
            new CatalogSnapshotWriter(
                repository, () -> "generated-" + (++repository.generatedIds), clock),
            new MediaMappingPlanner());

    assertThatThrownBy(
            () ->
                service.rebindCourse("course-1", "new-parent", Map.of("same-emby", "local-lesson")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo("EMBY_MEDIA_MAPPING_CONFLICT"));
    assertThat(repository.course.embyParentItemId()).isEqualTo("old-parent");
    assertThat(repository.mappingAudit).isEmpty();
  }

  private static final class FakeRepository implements CourseRepository {
    private Course course;
    private final Map<String, MediaItem> items = new LinkedHashMap<>();
    private final List<String> mappingAudit = new ArrayList<>();
    private int generatedIds;

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
    public void insertMediaItem(MediaItem item, Instant now) {
      items.put(item.embyItemId(), item);
    }

    @Override
    public void updateMediaItemFromRemote(MediaItem item, Instant now) {
      items.entrySet().removeIf(entry -> entry.getValue().id().equals(item.id()));
      items.put(item.embyItemId(), item);
    }

    @Override
    public void insertMediaItemSourceMapping(
        String id,
        String mediaItemId,
        String oldEmbyItemId,
        String newEmbyItemId,
        String matchType,
        Instant now) {
      mappingAudit.add(oldEmbyItemId + "->" + newEmbyItemId + ":" + matchType);
    }

    @Override
    public void markUnavailableExceptMediaIds(
        String courseId, List<String> availableMediaItemIds, Instant now) {
      items.replaceAll(
          (id, item) ->
              availableMediaItemIds.contains(item.id())
                  ? item
                  : new MediaItem(
                      item.id(),
                      item.courseId(),
                      item.embyItemId(),
                      item.embyItemType(),
                      item.sourceFingerprint(),
                      item.title(),
                      item.durationMs(),
                      item.enabled(),
                      item.sortOrder(),
                      false));
    }

    @Override
    public void updateCourseSource(String courseId, String embyParentItemId, Instant now) {
      course =
          new Course(
              course.id(),
              course.name(),
              course.description(),
              embyParentItemId,
              course.enabled(),
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
