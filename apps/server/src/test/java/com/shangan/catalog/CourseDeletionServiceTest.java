package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.catalog.application.CourseAttachmentCleaner;
import com.shangan.catalog.application.CourseDeletionDerivedDataRefresher;
import com.shangan.catalog.application.CourseDeletionService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.CourseDeletionGraph;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseDeletionRepository;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证已归档课程会删除完整关联图，并保护活动课程与失效预览。 */
class CourseDeletionServiceTest {

  @Test
  void previewsAndDeletesTheWholeGraphThenRefreshesDerivedData() {
    FakeCourseRepository courses = new FakeCourseRepository();
    courses.add(course("course-1", false, null));
    courses.add(course("course-2", false, Instant.parse("2026-09-02T08:00:00Z")));
    FakeDeletionRepository deletions = new FakeDeletionRepository();
    deletions.impact = impact(2, 66, 4);
    var day =
        new CourseDeletionGraph.AffectedDay("user-1", LocalDate.of(2026, 9, 1), "Asia/Shanghai");
    deletions.result =
        new CourseDeletionGraph.DeletionResult(List.of("safe-file.jpg"), List.of(day));
    RecordingCleaner cleaner = new RecordingCleaner();
    RecordingRefresher refresher = new RecordingRefresher();
    CourseDeletionService service =
        new CourseDeletionService(courses, deletions, cleaner, refresher);

    var preview = service.preview(List.of("course-1", "course-2"));
    int deleted = service.delete(List.of("course-1", "course-2"), preview.token());

    assertThat(preview.impact()).isEqualTo(deletions.impact);
    assertThat(deleted).isEqualTo(2);
    assertThat(deletions.deletedCourseIds).containsExactly("course-1", "course-2");
    assertThat(cleaner.paths).containsExactly("safe-file.jpg");
    assertThat(refresher.days).containsExactly(day);
  }

  @Test
  void rejectsTheWholeBatchWhenItContainsAnActiveCourse() {
    FakeCourseRepository courses = new FakeCourseRepository();
    courses.add(course("archived", false, null));
    courses.add(course("active", true, null));
    FakeDeletionRepository deletions = new FakeDeletionRepository();
    CourseDeletionService service =
        new CourseDeletionService(courses, deletions, paths -> {}, days -> {});

    assertThatThrownBy(() -> service.preview(List.of("archived", "active")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("COURSE_NOT_ARCHIVED"));
    assertThat(deletions.deletedCourseIds).isEmpty();
  }

  @Test
  void rejectsDeletionWhenTheConfirmedPreviewHasChanged() {
    FakeCourseRepository courses = new FakeCourseRepository();
    courses.add(course("course-1", false, null));
    FakeDeletionRepository deletions = new FakeDeletionRepository();
    deletions.impact = impact(1, 33, 0);
    CourseDeletionService service =
        new CourseDeletionService(courses, deletions, paths -> {}, days -> {});
    String token = service.preview(List.of("course-1")).token();

    deletions.impact = impact(1, 34, 0);

    assertThatThrownBy(() -> service.delete(List.of("course-1"), token))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo("COURSE_DELETE_PREVIEW_STALE"));
    assertThat(deletions.deletedCourseIds).isEmpty();
  }

  private CourseDeletionGraph.Impact impact(int courses, int lessons, int planItems) {
    return new CourseDeletionGraph.Impact(
        courses, lessons, 1, 1, planItems, 2, 3, 2, 4, 5, 2, 1, 3, 1, 1, 2, 2);
  }

  private Course course(String id, boolean enabled, Instant removedAt) {
    return new Course(id, id, "", "source-" + id, enabled, 0, null, null, removedAt);
  }

  /** 纯内存课程仓储只提供删除资格校验，不连接 SQLite 或 Flyway。 */
  private static final class FakeCourseRepository implements CourseRepository {
    private final Map<String, Course> courses = new LinkedHashMap<>();

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
      return List.of();
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      return Optional.empty();
    }

    @Override
    public void insertCourse(Course course, Instant now) {}

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

  /** Fake 删除仓储记录应用服务是否把整批课程交给单一删除边界。 */
  private static final class FakeDeletionRepository implements CourseDeletionRepository {
    private CourseDeletionGraph.Impact impact =
        new CourseDeletionGraph.Impact(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private CourseDeletionGraph.DeletionResult result =
        new CourseDeletionGraph.DeletionResult(List.of(), List.of());
    private final List<String> deletedCourseIds = new ArrayList<>();

    @Override
    public CourseDeletionGraph.Impact inspect(List<String> courseIds) {
      return impact;
    }

    @Override
    public CourseDeletionGraph.DeletionResult deleteGraph(List<String> courseIds) {
      deletedCourseIds.addAll(courseIds);
      return result;
    }
  }

  private static final class RecordingCleaner implements CourseAttachmentCleaner {
    private List<String> paths = List.of();

    @Override
    public void delete(List<String> storagePaths) {
      paths = List.copyOf(storagePaths);
    }
  }

  private static final class RecordingRefresher implements CourseDeletionDerivedDataRefresher {
    private List<CourseDeletionGraph.AffectedDay> days = List.of();

    @Override
    public void refresh(List<CourseDeletionGraph.AffectedDay> affectedDays) {
      days = List.copyOf(affectedDays);
    }
  }
}
