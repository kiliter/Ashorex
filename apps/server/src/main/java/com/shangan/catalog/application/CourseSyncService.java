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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final MediaMappingPlanner mappingPlanner;

  public CourseSyncService(
      CourseRepository courses,
      EmbyGateway emby,
      IdGenerator ids,
      Clock clock,
      CatalogSnapshotWriter snapshotWriter,
      MediaMappingPlanner mappingPlanner) {
    this.courses = courses;
    this.emby = emby;
    this.ids = ids;
    this.clock = clock;
    this.snapshotWriter = snapshotWriter;
    this.mappingPlanner = mappingPlanner;
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
            normalizedParentId(embyParentItemId),
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

  /** 读取当前配置用户可见的视频媒体库，供后台创建和重新绑定课程。 */
  public List<EmbyDtos.MediaLibrary> listMediaLibraries() {
    return emby.listMediaLibraries();
  }

  /** 读取管理员正在维护的课程；不存在时返回稳定的业务错误，避免控制器接触仓储。 */
  @Transactional(readOnly = true)
  public Course getAdminCourse(String courseId) {
    return courses
        .findCourse(courseId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
  }

  @Transactional(readOnly = true)
  public List<MediaItem> listAdminLessons(String courseId) {
    return courses.findMediaItems(courseId, false);
  }

  /** 读取管理员正在维护的课时，并通过应用层统一返回稳定业务错误。 */
  @Transactional(readOnly = true)
  public MediaItem getAdminLesson(String lessonId) {
    return courses
        .findMediaItem(lessonId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
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
    try {
      List<EmbyDtos.MediaItem> remoteItems = emby.listChildren(course.embyParentItemId());
      MediaMappingPlanner.Plan plan =
          mappingPlanner.plan(courses.findMediaItems(course.id(), false), remoteItems, Map.of());
      requireNoConflicts(plan);
      snapshotWriter.apply(course.id(), plan);
    } catch (RuntimeException exception) {
      snapshotWriter.recordFailure(course.id(), safeSyncError(exception));
      throw exception;
    }
  }

  /** 在不写数据库的情况下预览新媒体来源的自动映射、新增、失效和冲突。 */
  public SourcePreview previewSource(String courseId, String newEmbyParentItemId) {
    Course course = getAdminCourse(courseId);
    String targetParentId = normalizedParentId(newEmbyParentItemId);
    List<EmbyDtos.MediaItem> remoteItems = emby.listChildren(targetParentId);
    MediaMappingPlanner.Plan plan =
        mappingPlanner.plan(courses.findMediaItems(course.id(), false), remoteItems, Map.of());
    return new SourcePreview(course, targetParentId, plan);
  }

  /** 重新读取完整远端快照并应用管理员确认的一对一映射，然后原子更新课程来源。 */
  public void rebindCourse(
      String courseId, String newEmbyParentItemId, Map<String, String> confirmedMappings) {
    Course course = getAdminCourse(courseId);
    String targetParentId = normalizedParentId(newEmbyParentItemId);
    List<EmbyDtos.MediaItem> remoteItems = emby.listChildren(targetParentId);
    List<MediaItem> localItems = courses.findMediaItems(course.id(), false);
    MediaMappingPlanner.Plan preview = mappingPlanner.plan(localItems, remoteItems, Map.of());
    Map<String, String> validatedMappings =
        validateConfirmedMappings(
            preview, confirmedMappings == null ? Map.of() : confirmedMappings);
    MediaMappingPlanner.Plan plan =
        validatedMappings.isEmpty()
            ? preview
            : mappingPlanner.plan(localItems, remoteItems, validatedMappings);
    requireNoConflicts(plan);
    snapshotWriter.rebind(course.id(), targetParentId, plan);
  }

  /** 每 15 分钟串行同步全部课程；日志不包含主机、密钥或完整异常内容。 */
  @Scheduled(fixedDelay = 900_000L)
  public void synchronizeAll() {
    for (Course course : courses.findAllCourses(false)) {
      try {
        syncCourse(course.id());
      } catch (Exception exception) {
        log.warn("Emby 课程同步失败，courseId={}", course.id());
      }
    }
  }

  private void requireNoConflicts(MediaMappingPlanner.Plan plan) {
    if (plan.hasConflicts()) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "EMBY_MEDIA_MAPPING_CONFLICT", "存在需要管理员确认的课时映射");
    }
  }

  /** 只接受当前完整快照中真实冲突的候选，拒绝篡改表单覆盖已自动确定的映射。 */
  private Map<String, String> validateConfirmedMappings(
      MediaMappingPlanner.Plan preview, Map<String, String> submittedMappings) {
    Map<String, MediaMappingPlanner.Conflict> conflictsByRemoteId = new LinkedHashMap<>();
    for (MediaMappingPlanner.Conflict conflict : preview.conflicts()) {
      conflictsByRemoteId.put(conflict.remote().id(), conflict);
    }
    Map<String, String> validated = new LinkedHashMap<>();
    for (Map.Entry<String, String> submitted : submittedMappings.entrySet()) {
      MediaMappingPlanner.Conflict conflict = conflictsByRemoteId.get(submitted.getKey());
      boolean selectedCandidate =
          conflict != null
              && conflict.candidates().stream()
                  .anyMatch(candidate -> candidate.id().equals(submitted.getValue()));
      boolean selectedAsNew =
          conflict != null && MediaMappingPlanner.CONFIRM_AS_NEW.equals(submitted.getValue());
      if (!selectedCandidate && !selectedAsNew) {
        throw new BusinessException(
            HttpStatus.CONFLICT, "EMBY_MEDIA_MAPPING_CONFLICT", "课时映射已发生变化，请重新预览后确认");
      }
      validated.put(submitted.getKey(), submitted.getValue());
    }
    // 保留页面提交顺序，使映射审计和新课时处理在相同快照下保持确定性。
    return validated;
  }

  private String normalizedParentId(String parentItemId) {
    if (parentItemId == null || parentItemId.isBlank()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "EMBY_PARENT_REQUIRED", "请选择或填写 Emby 媒体来源");
    }
    return parentItemId.trim();
  }

  private String safeSyncError(Exception exception) {
    if (exception instanceof BusinessException businessException) {
      return switch (businessException.errorCode()) {
        case "EMBY_PARENT_NOT_FOUND" -> "媒体来源不存在或无权访问";
        case "EMBY_MEDIA_MAPPING_CONFLICT" -> "存在待确认的课时映射";
        default -> "同步失败";
      };
    }
    return "同步失败";
  }

  /** 后台重新绑定预览；只包含安全元数据和本地课时候选，不包含 Emby 原始路径。 */
  public record SourcePreview(
      Course course, String targetParentItemId, MediaMappingPlanner.Plan plan) {}
}
