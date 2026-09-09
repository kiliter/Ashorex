package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.catalog.infrastructure.CatalogFacetRepository;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 全部看完依据观看位置，不把低目标或手动完成的待办误判为看完；使用纯内存依赖。 */
class CatalogQueryFullyWatchedTest {
  private final CourseRepository repository = mock(CourseRepository.class);
  private final ResourceProgressPort progress = mock(ResourceProgressPort.class);
  private final CatalogQueryService service =
      new CatalogQueryService(
          repository,
          mock(CatalogFacetRepository.class),
          progress,
          RuntimeIntegrationSettings::defaults);

  /** 构造可见课程与累计状态，同时覆盖列表、详情共用的汇总。 */
  private CatalogQueryService.CourseSummary summary(
      List<LearningResource> resources, Map<String, ResourceProgressPort.ResourceProgress> states) {
    when(repository.findById("course"))
        .thenReturn(
            Optional.of(
                new Course(
                    "course",
                    "EMBY",
                    "ref",
                    "测试课程",
                    "",
                    null,
                    0,
                    CatalogStatus.ACTIVE,
                    false,
                    null,
                    null,
                    null)));
    when(repository.findVisibleResourcesByCourse("course")).thenReturn(resources);
    when(progress.progressOfCourse("user", "course")).thenReturn(states);
    return service.course("user", "course").summary();
  }

  /** 无效时长保持未知，不能被隐藏筛选排除。 */
  private LearningResource video(String id, Long duration) {
    return new LearningResource(
        id,
        "course",
        ResourceType.VIDEO,
        id,
        1,
        duration,
        null,
        id,
        null,
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  /** 观看位置与待办完成次数刻意独立设置，用于验证两种口径。 */
  private ResourceProgressPort.ResourceProgress state(long position, int completed) {
    return new ResourceProgressPort.ResourceProgress(position, 0, 100, completed);
  }

  @Test
  void allPositionsAtEndAreFullyWatchedEvenWithoutTodoCompletion() {
    assertThat(
            summary(
                    List.of(video("a", 1000L), video("b", 2000L)),
                    Map.of("a", state(1000, 0), "b", state(2100, 0)))
                .fullyWatched())
        .isTrue();
  }

  @Test
  void todoCompletionAndRoundedPercentCannotReplaceFullPosition() {
    var result =
        summary(
            List.of(video("a", 1000L), video("b", 2000L)),
            Map.of("a", state(1000, 1), "b", state(1999, 1)));
    assertThat(result.completedCount()).isEqualTo(2);
    assertThat(result.fullyWatched()).isFalse();
    assertThat(summary(List.of(video("a", 1000L)), Map.of("a", state(300, 1))).fullyWatched())
        .isFalse();
  }

  @Test
  void emptyMissingProgressAndUnknownDurationRemainVisible() {
    assertThat(summary(List.of(), Map.of()).fullyWatched()).isFalse();
    assertThat(summary(List.of(video("a", 1000L)), Map.of()).fullyWatched()).isFalse();
    for (Long duration : new Long[] {null, 0L, -1L}) {
      assertThat(summary(List.of(video("a", duration)), Map.of("a", state(1000, 1))).fullyWatched())
          .isFalse();
    }
  }
}
