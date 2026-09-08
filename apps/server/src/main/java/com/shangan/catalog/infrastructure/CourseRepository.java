package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 课程、学习资源与元数据投影的持久化边界。 */
public interface CourseRepository {

  List<Course> findAll();

  /** 学习端可见课程：未归档且未失联。 */
  List<Course> findVisible();

  Optional<Course> findById(String id);

  Optional<Course> findByExternalRef(String externalSource, String externalRef);

  void insertCourse(Course course, Instant now);

  /** 同步时更新来自 Emby 的展示字段与同步状态；不触碰本地排序与归档状态。 */
  void updateSyncedFields(
      String courseId,
      String title,
      String overview,
      Integer productionYear,
      boolean sourceMissing,
      String lastSyncError,
      Instant lastSyncedAt);

  /** 重新绑定只替换课程的当前父来源，本地课程身份保持不变。 */
  void updateCourseExternalRef(String courseId, String externalRef, Instant now);

  void updateSortOrder(String courseId, int sortOrder, Instant now);

  void updateStatus(String courseId, boolean archived, Instant now);

  void markSourceMissing(String courseId, boolean sourceMissing, String error, Instant now);

  List<LearningResource> findResourcesByCourse(String courseId);

  /** 学习端可见资源：课程与资源都未归档，且资源仍可用。 */
  List<LearningResource> findVisibleResourcesByCourse(String courseId);

  Optional<LearningResource> findResourceById(String id);

  void insertResource(LearningResource resource, Instant now);

  /** 原位改写来源标识；本地资源 ID 保持不变。 */
  void updateResourceExternalRef(String resourceId, String externalRef, Instant now);

  void updateResourceSyncedFields(
      String resourceId,
      String title,
      int sortIndex,
      Long durationMs,
      Integer pageCount,
      String sourceFingerprint,
      Instant now);

  void updateResourceAvailability(String resourceId, boolean available, Instant now);

  void updateResourceStatus(String resourceId, boolean archived, Instant now);

  /** 首次由客户端回报页数；只允许在原值为空时写入。 */
  boolean updateResourcePageCountIfAbsent(String resourceId, int pageCount, Instant now);

  /** 整表重写课程的三张只读元数据投影。 */
  void replaceTaxonomy(String courseId, ResourceMetadata.CourseMetadata metadata);

  List<String> genresOf(String courseId);

  List<String> tagsOf(String courseId);

  List<ResourceMetadata.Person> peopleOf(String courseId);

  /** 记录来源标识变更审计。 */
  void insertSourceMapping(
      String id,
      String resourceId,
      String oldExternalRef,
      String newExternalRef,
      String matchedBy,
      String confirmedBy,
      Instant now);
}
