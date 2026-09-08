package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 课时序号（sortIndex）的数据正确性回归。
 *
 * <p>历史 bug：序号由远端返回列表的位置推导，一集下架后其后课时整体前移，并与保留旧序号的 下架课时撞号（见 ADR-0034）。这里用 Fake 网关与内存仓储验证修复，不接触真实数据库。
 */
class CourseSyncSortIndexTest {

  private static final String PARENT = "series-1";

  @Test
  void firstSyncUsesRemoteIndexNumberAsSortIndex() {
    FakeEmbyGateway emby = new FakeEmbyGateway(episodes(1, 2, 3, 4, 5));
    InMemoryCourseRepository courses = new InMemoryCourseRepository();
    CourseSyncService service = service(courses, emby);

    Course course = service.createCourse(PARENT, 0);

    assertThat(courses.findResourcesByCourse(course.id()))
        .extracting(LearningResource::title, LearningResource::sortIndex)
        .containsExactly(
            tuple("第01讲", 1),
            tuple("第02讲", 2),
            tuple("第03讲", 3),
            tuple("第04讲", 4),
            tuple("第05讲", 5));
  }

  @Test
  void droppedEpisodeDoesNotShiftLaterLessonsAndDoesNotCollide() {
    FakeEmbyGateway emby = new FakeEmbyGateway(episodes(1, 2, 3, 4, 5));
    InMemoryCourseRepository courses = new InMemoryCourseRepository();
    CourseSyncService service = service(courses, emby);
    Course course = service.createCourse(PARENT, 0);
    Map<String, String> idsBefore = externalRefToId(courses.findResourcesByCourse(course.id()));

    emby.setEpisodes(episodes(1, 2, 4, 5));
    CourseSyncService.SyncResult result = service.sync(course.id());

    List<LearningResource> resources = courses.findResourcesByCourse(course.id());
    assertThat(result.succeeded()).isTrue();
    assertThat(result.unavailableCount()).isEqualTo(1);
    // 第 03 讲下架后：其余课时序号不前移，下架课时序号冻结在 3，全课程无重复序号。
    assertThat(resources)
        .extracting(
            LearningResource::title, LearningResource::sortIndex, LearningResource::available)
        .containsExactly(
            tuple("第01讲", 1, true),
            tuple("第02讲", 2, true),
            tuple("第03讲", 3, false),
            tuple("第04讲", 4, true),
            tuple("第05讲", 5, true));
    assertThat(resources).extracting(LearningResource::sortIndex).doesNotHaveDuplicates();
    // 硬约束：learning_resources.id 永不重建。
    assertThat(externalRefToId(resources)).isEqualTo(idsBefore);
  }

  @Test
  void syncRepairsSortIndexPersistedByTheOldPositionBasedLogic() {
    FakeEmbyGateway emby = new FakeEmbyGateway(episodes(1, 2, 3));
    InMemoryCourseRepository courses = new InMemoryCourseRepository();
    CourseSyncService service = service(courses, emby);
    Course course = service.createCourse(PARENT, 0);
    // 模拟旧逻辑遗留的错误序号：第 03 讲被写成 7。
    LearningResource wrong =
        courses.findResourcesByCourse(course.id()).stream()
            .filter(resource -> resource.title().equals("第03讲"))
            .findFirst()
            .orElseThrow();
    courses.forceSortIndex(wrong.id(), 7);

    service.sync(course.id());

    assertThat(courses.findResourcesByCourse(course.id()))
        .extracting(LearningResource::id, LearningResource::sortIndex)
        .contains(tuple(wrong.id(), 3));
  }

  private CourseSyncService service(CourseRepository courses, EmbyGateway emby) {
    AtomicInteger sequence = new AtomicInteger();
    IdGenerator idGenerator = () -> "id-" + sequence.incrementAndGet();
    return new CourseSyncService(
        courses,
        new EmbyCatalogReader(emby),
        new ResourceMappingPlanner(),
        idGenerator,
        Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
  }

  private Map<String, String> externalRefToId(List<LearningResource> resources) {
    Map<String, String> mapping = new LinkedHashMap<>();
    resources.forEach(resource -> mapping.put(resource.externalRef(), resource.id()));
    return mapping;
  }

  /** 构造带远端集号的课时快照，等价于 EmbyClient 从 IndexNumber 解析出的结果。 */
  private static List<EmbyDtos.MediaItem> episodes(int... indexNumbers) {
    List<EmbyDtos.MediaItem> items = new ArrayList<>();
    for (int indexNumber : indexNumbers) {
      items.add(
          new EmbyDtos.MediaItem(
              "ep-" + indexNumber,
              String.format("第%02d讲", indexNumber),
              60_000L * indexNumber,
              indexNumber,
              "Episode",
              "fp-" + indexNumber));
    }
    return List.copyOf(items);
  }

  /** 只回放预设课时列表的 Fake 网关；不发起任何 HTTP 请求。 */
  private static final class FakeEmbyGateway implements EmbyGateway {

    private List<EmbyDtos.MediaItem> episodes;

    private FakeEmbyGateway(List<EmbyDtos.MediaItem> episodes) {
      this.episodes = episodes;
    }

    private void setEpisodes(List<EmbyDtos.MediaItem> episodes) {
      this.episodes = episodes;
    }

    @Override
    public EmbyDtos.SourceMetadata getSourceMetadata(String itemId) {
      return new EmbyDtos.SourceMetadata(
          itemId, "回归测试课程", "", 2026, List.of(), List.of(), List.of());
    }

    @Override
    public List<EmbyDtos.MediaItem> listChildren(String parentItemId) {
      return episodes;
    }
  }

  /** 只实现 CourseSyncService 用到的方法；其余方法未使用，被调用即视为测试假设失效。 */
  private static final class InMemoryCourseRepository implements CourseRepository {

    private final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<String, LearningResource> resources = new LinkedHashMap<>();

    private void forceSortIndex(String resourceId, int sortIndex) {
      replace(resourceId, resource -> withSortIndex(resource, sortIndex));
    }

    private void replace(
        String resourceId, java.util.function.UnaryOperator<LearningResource> mutation) {
      LearningResource current = resources.get(resourceId);
      resources.put(resourceId, mutation.apply(current));
    }

    private static LearningResource withSortIndex(LearningResource resource, int sortIndex) {
      return new LearningResource(
          resource.id(),
          resource.courseId(),
          resource.resourceType(),
          resource.title(),
          sortIndex,
          resource.durationMs(),
          resource.pageCount(),
          resource.externalRef(),
          resource.sourceFingerprint(),
          resource.available(),
          resource.status(),
          resource.archivedAt());
    }

    @Override
    public Optional<Course> findById(String id) {
      return Optional.ofNullable(courses.get(id));
    }

    @Override
    public Optional<Course> findByExternalRef(String externalSource, String externalRef) {
      return courses.values().stream()
          .filter(
              course ->
                  course.externalSource().equals(externalSource)
                      && course.externalRef().equals(externalRef))
          .findFirst();
    }

    @Override
    public void insertCourse(Course course, Instant now) {
      courses.put(course.id(), course);
    }

    @Override
    public void updateSyncedFields(
        String courseId,
        String title,
        String overview,
        Integer productionYear,
        boolean sourceMissing,
        String lastSyncError,
        Instant lastSyncedAt) {
      Course course = courses.get(courseId);
      courses.put(
          courseId,
          new Course(
              course.id(),
              course.externalSource(),
              course.externalRef(),
              title,
              overview,
              productionYear,
              course.sortOrder(),
              course.status(),
              sourceMissing,
              lastSyncedAt,
              lastSyncError,
              course.archivedAt()));
    }

    @Override
    public List<LearningResource> findResourcesByCourse(String courseId) {
      return resources.values().stream()
          .filter(resource -> resource.courseId().equals(courseId))
          .sorted(
              java.util.Comparator.comparingInt(LearningResource::sortIndex)
                  .thenComparing(LearningResource::title))
          .toList();
    }

    @Override
    public void insertResource(LearningResource resource, Instant now) {
      resources.put(resource.id(), resource);
    }

    @Override
    public void updateResourceSyncedFields(
        String resourceId,
        String title,
        int sortIndex,
        Long durationMs,
        Integer pageCount,
        String sourceFingerprint,
        Instant now) {
      replace(
          resourceId,
          resource ->
              new LearningResource(
                  resource.id(),
                  resource.courseId(),
                  resource.resourceType(),
                  title,
                  sortIndex,
                  durationMs,
                  pageCount,
                  resource.externalRef(),
                  sourceFingerprint,
                  resource.available(),
                  resource.status(),
                  resource.archivedAt()));
    }

    @Override
    public void updateResourceAvailability(String resourceId, boolean available, Instant now) {
      replace(
          resourceId,
          resource ->
              new LearningResource(
                  resource.id(),
                  resource.courseId(),
                  resource.resourceType(),
                  resource.title(),
                  resource.sortIndex(),
                  resource.durationMs(),
                  resource.pageCount(),
                  resource.externalRef(),
                  resource.sourceFingerprint(),
                  available,
                  resource.status(),
                  resource.archivedAt()));
    }

    @Override
    public void replaceTaxonomy(String courseId, ResourceMetadata.CourseMetadata metadata) {
      // 投影表与课时序号无关，回归测试忽略。
    }

    @Override
    public List<Course> findAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Course> findVisible() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateCourseExternalRef(String courseId, String externalRef, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateSortOrder(String courseId, int sortOrder, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateStatus(String courseId, boolean archived, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markSourceMissing(
        String courseId, boolean sourceMissing, String error, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<LearningResource> findVisibleResourcesByCourse(String courseId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<LearningResource> findResourceById(String id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateResourceExternalRef(String resourceId, String externalRef, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateResourceStatus(String resourceId, boolean archived, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateResourcePageCountIfAbsent(String resourceId, int pageCount, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> genresOf(String courseId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<String> tagsOf(String courseId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ResourceMetadata.Person> peopleOf(String courseId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertSourceMapping(
        String id,
        String resourceId,
        String oldExternalRef,
        String newExternalRef,
        String matchedBy,
        String confirmedBy,
        Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
