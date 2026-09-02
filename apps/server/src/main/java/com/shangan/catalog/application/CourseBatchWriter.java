package com.shangan.catalog.application;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.media.emby.EmbyDtos;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在一个短事务中创建、恢复或跳过课程；远端来源验证和视频同步均在事务外完成。 */
@Service
public class CourseBatchWriter {

  private final CourseRepository courses;
  private final IdGenerator ids;
  private final Clock clock;

  public CourseBatchWriter(CourseRepository courses, IdGenerator ids, Clock clock) {
    this.courses = courses;
    this.ids = ids;
    this.clock = clock;
  }

  /** 同一来源只保留一个本地课程身份；已归档课程恢复原身份，活动课程幂等跳过。 */
  @Transactional
  public List<PreparedCourse> prepare(List<EmbyDtos.MediaSource> sources) {
    Instant now = clock.instant();
    List<Course> existingCourses = courses.findAllCourses(false);
    Map<String, Course> existingBySourceId = new LinkedHashMap<>();
    int nextSortOrder = 0;
    for (Course course : existingCourses) {
      existingBySourceId.put(course.embyParentItemId(), course);
      nextSortOrder = Math.max(nextSortOrder, course.sortOrder() + 1);
    }

    List<PreparedCourse> prepared = new ArrayList<>();
    for (EmbyDtos.MediaSource source : sources) {
      Course existing = existingBySourceId.get(source.id());
      if (existing != null && existing.enabled()) {
        prepared.add(new PreparedCourse(source, existing, Action.SKIPPED));
        continue;
      }
      if (existing != null) {
        courses.updateCourseEnabled(existing.id(), true, now);
        Course restored = withEnabled(existing, true);
        existingBySourceId.put(source.id(), restored);
        prepared.add(new PreparedCourse(source, restored, Action.RESTORED));
        continue;
      }
      Course created =
          new Course(
              ids.nextId(), source.name(), "", source.id(), true, nextSortOrder++, null, null);
      courses.insertCourse(created, now);
      existingBySourceId.put(source.id(), created);
      prepared.add(new PreparedCourse(source, created, Action.CREATED));
    }
    return List.copyOf(prepared);
  }

  private Course withEnabled(Course course, boolean enabled) {
    return new Course(
        course.id(),
        course.name(),
        course.description(),
        course.embyParentItemId(),
        enabled,
        course.sortOrder(),
        course.lastSyncedAt(),
        course.lastSyncError(),
        enabled ? null : course.removedAt());
  }

  /** 批量写入动作；同步结果由事务外的编排服务单独记录。 */
  public enum Action {
    CREATED,
    RESTORED,
    SKIPPED
  }

  /** 一项已确定的课程身份和准备动作。 */
  public record PreparedCourse(EmbyDtos.MediaSource source, Course course, Action action) {}
}
