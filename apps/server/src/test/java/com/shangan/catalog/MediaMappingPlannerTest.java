package com.shangan.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.catalog.application.MediaMappingPlanner;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.media.emby.EmbyDtos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 Emby Item ID 变化时只重映射外部标识，不替换上岸本地课时身份。 */
class MediaMappingPlannerTest {

  private final MediaMappingPlanner planner = new MediaMappingPlanner();

  @Test
  void remapsByStableSourceFingerprintAndKeepsLocalId() {
    MediaItem local = local("local-1", "old-emby", "path-sha256-v1:same", "第一课", 60_000);
    EmbyDtos.MediaItem remote = remote("new-emby", "path-sha256-v1:same", "第一课", 60_000);

    MediaMappingPlanner.Plan plan = planner.plan(List.of(local), List.of(remote), Map.of());

    assertThat(plan.conflicts()).isEmpty();
    assertThat(plan.mappings())
        .singleElement()
        .satisfies(
            mapping -> {
              assertThat(mapping.localMediaItemId()).isEqualTo("local-1");
              assertThat(mapping.remote().id()).isEqualTo("new-emby");
              assertThat(mapping.matchType())
                  .isEqualTo(MediaMappingPlanner.MatchType.SOURCE_FINGERPRINT);
            });
  }

  @Test
  void remapsLegacyItemOnlyWhenTitleAndDurationCandidateIsUnique() {
    MediaItem local = local("local-1", "old-emby", null, " 01 第一课 ", 60_000);
    EmbyDtos.MediaItem remote = remote("new-emby", "path-sha256-v1:new", "01 第一课", 61_999);

    MediaMappingPlanner.Plan plan = planner.plan(List.of(local), List.of(remote), Map.of());

    assertThat(plan.conflicts()).isEmpty();
    assertThat(plan.mappings())
        .singleElement()
        .extracting(MediaMappingPlanner.Mapping::matchType)
        .isEqualTo(MediaMappingPlanner.MatchType.UNIQUE_LEGACY_METADATA);
  }

  @Test
  void rejectsAmbiguousLegacyMatchesUntilAdministratorConfirmsOne() {
    MediaItem first = local("local-1", "old-1", null, "同名课时", 60_000);
    MediaItem second = local("local-2", "old-2", null, "同名课时", 61_000);
    EmbyDtos.MediaItem remote = remote("new-emby", "path-sha256-v1:new", "同名课时", 60_500);

    MediaMappingPlanner.Plan preview =
        planner.plan(List.of(first, second), List.of(remote), Map.of());
    MediaMappingPlanner.Plan confirmed =
        planner.plan(List.of(first, second), List.of(remote), Map.of("new-emby", "local-2"));

    assertThat(preview.mappings()).isEmpty();
    assertThat(preview.conflicts())
        .singleElement()
        .satisfies(
            conflict ->
                assertThat(conflict.candidates())
                    .extracting(MediaItem::id)
                    .containsExactly("local-1", "local-2"));
    assertThat(confirmed.conflicts()).isEmpty();
    assertThat(confirmed.mappings())
        .singleElement()
        .satisfies(
            mapping -> {
              assertThat(mapping.localMediaItemId()).isEqualTo("local-2");
              assertThat(mapping.matchType())
                  .isEqualTo(MediaMappingPlanner.MatchType.ADMIN_CONFIRMED);
            });
  }

  @Test
  void rejectsOneLocalFingerprintMatchingMultipleRemoteItems() {
    MediaItem local = local("local-1", "old-emby", "path-sha256-v1:same", "第一课", 60_000);
    EmbyDtos.MediaItem first = remote("new-1", "path-sha256-v1:same", "第一课", 60_000);
    EmbyDtos.MediaItem second = remote("new-2", "path-sha256-v1:same", "第一课副本", 60_000);

    MediaMappingPlanner.Plan plan = planner.plan(List.of(local), List.of(first, second), Map.of());

    assertThat(plan.mappings()).isEmpty();
    assertThat(plan.conflicts())
        .extracting(conflict -> conflict.remote().id())
        .containsExactly("new-1", "new-2");
  }

  @Test
  void reportsAutomaticAndNewItemCountsForAdministratorPreview() {
    MediaItem local = local("local-1", "same-emby", null, "第一课", 60_000);
    EmbyDtos.MediaItem existing = remote("same-emby", null, "第一课", 60_000);
    EmbyDtos.MediaItem added = remote("new-emby", "path-sha256-v1:new", "第二课", 30_000);

    MediaMappingPlanner.Plan plan =
        planner.plan(List.of(local), List.of(existing, added), Map.of());

    assertThat(plan.automaticMappingCount()).isEqualTo(1);
    assertThat(plan.newItemCount()).isEqualTo(1);
  }

  private MediaItem local(
      String id, String embyId, String fingerprint, String title, long durationMs) {
    return new MediaItem(
        id, "course-1", embyId, "Video", fingerprint, title, durationMs, true, 1, true);
  }

  private EmbyDtos.MediaItem remote(String id, String fingerprint, String title, long durationMs) {
    return new EmbyDtos.MediaItem(id, title, durationMs, 1, "Video", fingerprint);
  }
}
