package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.catalog.application.ResourceMappingPlanner.MappingPlan;
import com.shangan.catalog.application.ResourceMappingPlanner.MatchedBy;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.domain.ResourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Emby 映射优先级与歧义拒绝测试。
 *
 * <p>优先级：当前 externalRef → 课程内唯一来源指纹 → 唯一标题且时长差 ≤2 秒 → 人工确认。
 */
class ResourceMappingPlannerTest {

  private final ResourceMappingPlanner planner = new ResourceMappingPlanner();

  @Test
  @DisplayName("第一优先级：externalRef 命中即原位映射，资源 ID 不变")
  void 命中来源标识() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:1", "fp-1");
    ResourceMetadata remote = metadata("emby:1", "第 1 讲（重命名）", 600_000L, "fp-changed");

    MappingPlan plan = planner.plan(List.of(local), List.of(remote));

    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.local().id()).isEqualTo("res-1");
              assertThat(entry.matchedBy()).isEqualTo(MatchedBy.EXTERNAL_REF);
            });
    assertThat(plan.created()).isEmpty();
    assertThat(plan.markedUnavailable()).isEmpty();
    assertThat(plan.ambiguous()).isEmpty();
  }

  @Test
  @DisplayName("第二优先级：externalRef 变了但来源指纹在课程内唯一时原位映射")
  void 命中来源指纹() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:old", "fp-1");
    ResourceMetadata remote = metadata("emby:new", "完全不同的标题", 999_000L, "fp-1");

    MappingPlan plan = planner.plan(List.of(local), List.of(remote));

    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.local().id()).isEqualTo("res-1");
              assertThat(entry.matchedBy()).isEqualTo(MatchedBy.FINGERPRINT);
            });
    assertThat(plan.requiresManualConfirmation()).isFalse();
  }

  @Test
  @DisplayName("第三优先级：标题唯一且时长差 2 秒内时原位映射")
  void 命中标题与时长() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:old", null);
    ResourceMetadata remote = metadata("emby:new", "第 1 讲", 602_000L, null);

    MappingPlan plan = planner.plan(List.of(local), List.of(remote));

    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(entry -> assertThat(entry.matchedBy()).isEqualTo(MatchedBy.TITLE_DURATION));
  }

  @Test
  @DisplayName("时长差 3 秒超出容差：不自动合并，进入人工确认")
  void 时长差超容差进入歧义() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:old", null);
    ResourceMetadata remote = metadata("emby:new", "第 1 讲", 603_000L, null);

    MappingPlan plan = planner.plan(List.of(local), List.of(remote));

    assertThat(plan.inPlace()).isEmpty();
    assertThat(plan.created()).isEmpty();
    assertThat(plan.ambiguous())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.remote().externalRef()).isEqualTo("emby:new");
              assertThat(entry.candidates())
                  .extracting(LearningResource::id)
                  .containsExactly("res-1");
            });
    assertThat(plan.requiresManualConfirmation()).isTrue();
  }

  @Test
  @DisplayName("一个远端条目对应多个同名本地资源时拒绝自动合并")
  void 一对多进入歧义() {
    LearningResource first = resource("res-1", "第 1 讲", 600_000L, "emby:a", null);
    LearningResource second = resource("res-2", "第 1 讲", 600_500L, "emby:b", null);
    ResourceMetadata remote = metadata("emby:new", "第 1 讲", 600_000L, null);

    MappingPlan plan = planner.plan(List.of(first, second), List.of(remote));

    assertThat(plan.inPlace()).isEmpty();
    assertThat(plan.ambiguous())
        .singleElement()
        .satisfies(
            entry ->
                assertThat(entry.candidates())
                    .extracting(LearningResource::id)
                    .containsExactly("res-1", "res-2"));
  }

  @Test
  @DisplayName("多个远端条目指向同一本地资源时，第二个不再复用已匹配的本地资源")
  void 多对一不重复占用() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:1", "fp-1");
    ResourceMetadata exact = metadata("emby:1", "第 1 讲", 600_000L, "fp-1");
    ResourceMetadata duplicate = metadata("emby:2", "第 1 讲", 600_000L, "fp-1");

    MappingPlan plan = planner.plan(List.of(local), List.of(exact, duplicate));

    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(entry -> assertThat(entry.remote().externalRef()).isEqualTo("emby:1"));
    assertThat(plan.created()).extracting(ResourceMetadata::externalRef).containsExactly("emby:2");
  }

  @Test
  @DisplayName("远端全新条目进入 created，本地未匹配且仍可用的资源进入下架清单")
  void 新增与下架分类() {
    LearningResource stale = resource("res-1", "已删除的课时", 600_000L, "emby:gone", "fp-gone");
    LearningResource alreadyUnavailable =
        new LearningResource(
            "res-2",
            "course-1",
            ResourceType.VIDEO,
            "早就下架的课时",
            2,
            600_000L,
            null,
            "emby:gone-2",
            "fp-gone-2",
            false,
            CatalogStatus.ACTIVE,
            null);
    ResourceMetadata fresh = metadata("emby:new", "新增课时", 300_000L, "fp-new");

    MappingPlan plan = planner.plan(List.of(stale, alreadyUnavailable), List.of(fresh));

    assertThat(plan.created())
        .extracting(ResourceMetadata::externalRef)
        .containsExactly("emby:new");
    assertThat(plan.markedUnavailable()).extracting(LearningResource::id).containsExactly("res-1");
  }

  @Test
  @DisplayName("标题比较忽略大小写与首尾空格")
  void 标题归一化后比较() {
    LearningResource local = resource("res-1", "  Lesson One  ", 600_000L, "emby:old", null);
    ResourceMetadata remote = metadata("emby:new", "lesson one", 600_000L, null);

    MappingPlan plan = planner.plan(List.of(local), List.of(remote));

    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(entry -> assertThat(entry.matchedBy()).isEqualTo(MatchedBy.TITLE_DURATION));
  }

  @Test
  @DisplayName("本地与远端都为空时返回四个空清单")
  void 空输入返回空方案() {
    MappingPlan plan = planner.plan(List.of(), List.of());

    assertThat(plan.inPlace()).isEmpty();
    assertThat(plan.created()).isEmpty();
    assertThat(plan.markedUnavailable()).isEmpty();
    assertThat(plan.ambiguous()).isEmpty();
    assertThat(plan.requiresManualConfirmation()).isFalse();
  }

  /** 远端列表顺序不能降低 externalRef 的优先级。 */
  @Test
  void exactIdentityWinsEvenWhenFuzzyCandidateComesFirst() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:1", "fp-1");
    ResourceMetadata exact = metadata("emby:1", "第 1 讲", 600_000L, "fp-1");
    ResourceMetadata duplicate = metadata("emby:2", "第 1 讲", 600_000L, "fp-1");
    MappingPlan plan = planner.plan(List.of(local), List.of(duplicate, exact));
    assertThat(plan.inPlace())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.remote().externalRef()).isEqualTo("emby:1");
              assertThat(item.matchedBy()).isEqualTo(MatchedBy.EXTERNAL_REF);
            });
    assertThat(plan.created()).extracting(ResourceMetadata::externalRef).containsExactly("emby:2");
  }

  /** 两个新远端指向同一指纹时，不能先迁移一个再把另一个创建成重复课时。 */
  @Test
  void competingFuzzyMatchesBothRequireConfirmation() {
    LearningResource local = resource("res-1", "第 1 讲", 600_000L, "emby:old", "fp-1");
    MappingPlan plan =
        planner.plan(
            List.of(local),
            List.of(
                metadata("emby:new-a", "新名称A", 600_000L, "fp-1"),
                metadata("emby:new-b", "新名称B", 600_000L, "fp-1")));
    assertThat(plan.inPlace()).isEmpty();
    assertThat(plan.created()).isEmpty();
    assertThat(plan.markedUnavailable()).isEmpty();
    assertThat(plan.ambiguous())
        .extracting(item -> item.remote().externalRef())
        .containsExactly("emby:new-a", "emby:new-b");
  }

  private static LearningResource resource(
      String id, String title, Long durationMs, String externalRef, String fingerprint) {
    return new LearningResource(
        id,
        "course-1",
        ResourceType.VIDEO,
        title,
        1,
        durationMs,
        null,
        externalRef,
        fingerprint,
        true,
        CatalogStatus.ACTIVE,
        null);
  }

  private static ResourceMetadata metadata(
      String externalRef, String title, Long durationMs, String fingerprint) {
    return new ResourceMetadata(
        externalRef, title, 1, durationMs, null, fingerprint, ResourceType.VIDEO);
  }
}
