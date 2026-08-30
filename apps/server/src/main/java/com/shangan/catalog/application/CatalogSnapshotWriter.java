package com.shangan.catalog.application;

import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.media.emby.EmbyDtos;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在独立短事务中写入 Emby 课程快照，避免网络请求占用 SQLite 写事务。 */
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

  /** 原子替换一门课程的远端快照，并保留管理员维护的启用状态和排序。 */
  @Transactional
  public void apply(String courseId, List<EmbyDtos.MediaItem> remoteItems) {
    Instant now = clock.instant();
    for (EmbyDtos.MediaItem remote : remoteItems) {
      courses.upsertMediaItem(
          new MediaItem(
              ids.nextId(),
              courseId,
              remote.id(),
              remote.title(),
              remote.durationMs(),
              true,
              remote.indexNumber(),
              true),
          now);
    }
    courses.markUnavailableExcept(
        courseId, remoteItems.stream().map(EmbyDtos.MediaItem::id).toList(), now);
    courses.updateCourseSyncResult(courseId, now, null);
  }

  /** 在独立短事务中记录同步失败，不写入可能不完整的远端结果。 */
  @Transactional
  public void recordFailure(String courseId) {
    courses.updateCourseSyncResult(courseId, clock.instant(), "同步失败");
  }
}
