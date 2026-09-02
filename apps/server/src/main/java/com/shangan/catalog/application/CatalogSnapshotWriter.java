package com.shangan.catalog.application;

import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在独立短事务中应用完整映射计划，网络请求和歧义分析不会占用 SQLite 写事务。 */
@Service
public class CatalogSnapshotWriter {

  private final CourseRepository courses;
  private final IdGenerator ids;
  private final Clock clock;

  public CatalogSnapshotWriter(CourseRepository courses, IdGenerator ids, Clock clock) {
    this.courses = courses;
    this.ids = ids;
    this.clock = clock;
  }

  /** 原子更新当前媒体来源的完整快照，课程父节点保持不变。 */
  @Transactional
  public void apply(String courseId, MediaMappingPlanner.Plan plan) {
    applyPlan(courseId, null, plan);
  }

  /** 原子更新课程父节点并应用新媒体库快照，失败时两部分都不会保留。 */
  @Transactional
  public void rebind(String courseId, String newEmbyParentItemId, MediaMappingPlanner.Plan plan) {
    applyPlan(courseId, newEmbyParentItemId, plan);
  }

  private void applyPlan(
      String courseId, String newEmbyParentItemId, MediaMappingPlanner.Plan plan) {
    if (plan.hasConflicts()) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "EMBY_MEDIA_MAPPING_CONFLICT", "存在需要管理员确认的课时映射");
    }
    Instant now = clock.instant();
    List<String> availableLocalIds = new ArrayList<>();
    for (MediaMappingPlanner.Mapping mapping : plan.mappings()) {
      if (mapping.matchType() == MediaMappingPlanner.MatchType.NEW) {
        String localId = ids.nextId();
        courses.insertMediaItem(newMediaItem(localId, courseId, mapping), now);
        availableLocalIds.add(localId);
        continue;
      }

      MediaItem existing =
          courses
              .findMediaItem(mapping.localMediaItemId())
              .orElseThrow(
                  () ->
                      new BusinessException(
                          HttpStatus.CONFLICT,
                          "EMBY_MEDIA_MAPPING_CONFLICT",
                          "待映射的本地课时已发生变化，请重新预览"));
      courses.updateMediaItemFromRemote(updatedMediaItem(existing, mapping), now);
      availableLocalIds.add(existing.id());
      if (!existing.embyItemId().equals(mapping.remote().id())) {
        courses.insertMediaItemSourceMapping(
            ids.nextId(),
            existing.id(),
            existing.embyItemId(),
            mapping.remote().id(),
            mapping.matchType().name(),
            now);
      }
    }
    courses.markUnavailableExceptMediaIds(courseId, availableLocalIds, now);
    if (newEmbyParentItemId != null) {
      courses.updateCourseSource(courseId, newEmbyParentItemId, now);
    }
    courses.updateCourseSyncResult(courseId, now, null);
  }

  private MediaItem newMediaItem(
      String localId, String courseId, MediaMappingPlanner.Mapping mapping) {
    return new MediaItem(
        localId,
        courseId,
        mapping.remote().id(),
        mapping.remote().itemType(),
        mapping.remote().sourceFingerprint(),
        mapping.remote().title(),
        mapping.remote().durationMs(),
        true,
        mapping.remote().indexNumber(),
        true);
  }

  private MediaItem updatedMediaItem(MediaItem existing, MediaMappingPlanner.Mapping mapping) {
    return new MediaItem(
        existing.id(),
        existing.courseId(),
        mapping.remote().id(),
        mapping.remote().itemType(),
        mapping.remote().sourceFingerprint(),
        mapping.remote().title(),
        mapping.remote().durationMs(),
        existing.enabled(),
        existing.sortOrder(),
        true);
  }

  /** 在独立短事务中记录同步失败，不写入可能不完整的远端结果。 */
  @Transactional
  public void recordFailure(String courseId) {
    recordFailure(courseId, "同步失败");
  }

  /** 记录不含主机、路径或密钥的安全同步原因。 */
  @Transactional
  public void recordFailure(String courseId, String safeMessage) {
    courses.updateCourseSyncResult(courseId, clock.instant(), safeMessage);
  }
}
