package com.shangan.catalog.application;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排 Emby 课程同步，并在失败时保留最后一次可用本地快照。 */
@Service
public class CourseSyncService {

  private static final Logger log = LoggerFactory.getLogger(CourseSyncService.class);

  private final CourseRepository courses;
  private final EmbyGateway emby;
  private final IdGenerator ids;
  private final Clock clock;
  private final CatalogSnapshotWriter snapshotWriter;

  public CourseSyncService(
      CourseRepository courses,
      EmbyGateway emby,
      IdGenerator ids,
      Clock clock,
      CatalogSnapshotWriter snapshotWriter) {
    this.courses = courses;
    this.emby = emby;
    this.ids = ids;
    this.clock = clock;
    this.snapshotWriter = snapshotWriter;
  }

  /** 创建管理员维护的课程绑定，首次同步由管理员显式触发或定时任务完成。 */
  @Transactional
  public Course createCourse(String name, String description, String embyParentItemId) {
    Instant now = clock.instant();
    Course course =
        new Course(
            ids.nextId(),
            name.trim(),
            description == null ? "" : description.trim(),
            embyParentItemId.trim(),
            true,
            courses.findAllCourses(false).size(),
            null,
            null);
    courses.insertCourse(course, now);
    return course;
  }

  @Transactional(readOnly = true)
  public List<Course> listAdminCourses() {
    return courses.findAllCourses(false);
  }

  @Transactional(readOnly = true)
  public List<MediaItem> listAdminLessons(String courseId) {
    return courses.findMediaItems(courseId, false);
  }

  /** 转写后台需要跨课程选择课时，保持查询仍通过应用服务而非 Controller 直连仓储。 */
  @Transactional(readOnly = true)
  public List<MediaItem> listAllAdminLessons() {
    return courses.findAllCourses(false).stream()
        .flatMap(course -> courses.findMediaItems(course.id(), false).stream())
        .toList();
  }

  /** 更新管理员可控字段，不修改 Emby 同步字段。 */
  @Transactional
  public void updateLessonControls(String lessonId, boolean enabled, int sortOrder) {
    courses.updateMediaControls(lessonId, enabled, sortOrder);
  }

  /** 同步一门课程；远端请求不占用事务，本地快照由独立短事务原子写入。 */
  public void syncCourse(String courseId) {
    Course course =
        courses
            .findCourse(courseId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    List<EmbyDtos.MediaItem> remoteItems = emby.listChildren(course.embyParentItemId());
    snapshotWriter.apply(course.id(), remoteItems);
  }

  /** 每 15 分钟串行同步全部课程；日志不包含主机、密钥或完整异常内容。 */
  @Scheduled(fixedDelay = 900_000L)
  public void synchronizeAll() {
    for (Course course : courses.findAllCourses(false)) {
      try {
        syncCourse(course.id());
      } catch (Exception exception) {
        snapshotWriter.recordFailure(course.id());
        log.warn("Emby 课程同步失败，courseId={}", course.id());
      }
    }
  }
}
