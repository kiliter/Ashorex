package com.shangan.catalog.application;

import com.shangan.catalog.application.ResourceMappingPlanner.MappingPlan;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 课程同步与重新绑定。
 *
 * <p>关键约束（Spec 12）：任一页读取失败即整体放弃并保留上次可用快照；本地资源 ID 永不重建； 元数据变更只重写投影，不触发数据迁移；歧义项必须人工确认。
 */
@Service
public class CourseSyncService {

  private static final String EMBY = "EMBY";
  private static final Logger log = LoggerFactory.getLogger(CourseSyncService.class);

  private final CourseRepository courses;
  private final EmbyCatalogReader reader;
  private final ResourceMappingPlanner planner;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public CourseSyncService(
      CourseRepository courses,
      EmbyCatalogReader reader,
      ResourceMappingPlanner planner,
      IdGenerator idGenerator,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.courses = courses;
    this.reader = reader;
    this.planner = planner;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** 完整读取远端后再开启短事务建课，不让网络等待持有 SQLite 连接。 */
  public Course createCourse(String parentExternalRef, int sortOrder) {
    ResourceMetadata.CourseMetadata metadata = reader.readCourseMetadata(parentExternalRef);
    List<ResourceMetadata> remote = reader.readResources(parentExternalRef);
    return transaction.execute(
        status -> createFromSnapshot(parentExternalRef, sortOrder, metadata, remote));
  }

  /** 只写完整快照；批量导入在事务外读取远端后调用，禁止网络请求占用写事务。 */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Course createFromSnapshot(
      String parentExternalRef,
      int sortOrder,
      ResourceMetadata.CourseMetadata metadata,
      List<ResourceMetadata> remote) {
    courses
        .findByExternalRef(EMBY, parentExternalRef)
        .ifPresent(
            existing -> {
              throw new BusinessException(
                  HttpStatus.CONFLICT, "COURSE_ALREADY_BOUND", "该 Emby 来源已绑定课程");
            });
    Instant now = clock.instant();
    Course course =
        new Course(
            idGenerator.nextId(),
            EMBY,
            parentExternalRef,
            metadata.title(),
            metadata.overview() == null ? "" : metadata.overview(),
            metadata.productionYear(),
            sortOrder,
            CatalogStatus.ACTIVE,
            false,
            null,
            null,
            null);
    courses.insertCourse(course, now);
    applySync(course, metadata, remote, now, "");
    return courses.findById(course.id()).orElse(course);
  }

  /**
   * 同步单个课程。
   *
   * <p>读取失败保留快照；只有父节点不存在或无权限才标记失联，临时网络故障不隐藏课程。
   */
  public SyncResult sync(String courseId) {
    Course course = requireCourse(courseId);
    ResourceMetadata.CourseMetadata metadata;
    List<ResourceMetadata> remote;
    try {
      metadata = reader.readCourseMetadata(course.externalRef());
      remote = reader.readResources(course.externalRef());
    } catch (BusinessException exception) {
      // 网络超时只记录同步失败；只有明确的父节点不存在/无权限才隐藏课程。
      transaction.executeWithoutResult(
          status -> {
            Course current = requireUnchangedCourse(course);
            boolean missing =
                current.sourceMissing() || "EMBY_PARENT_NOT_FOUND".equals(exception.errorCode());
            courses.markSourceMissing(
                current.id(), missing, exception.getMessage(), clock.instant());
          });
      log.warn("课程 {} 同步失败", course.id());
      return SyncResult.failed(exception.getMessage());
    }
    return transaction.execute(
        status -> {
          Course current = requireUnchangedCourse(course);
          return SyncResult.of(applySync(current, metadata, remote, clock.instant(), ""));
        });
  }

  /**
   * 重新绑定课程到新的父节点。
   *
   * <p>先完整读取远端快照，再在一个事务内更新父绑定、原位映射、创建新资源、标记下架并写审计。 存在歧义时拒绝提交，要求管理员先逐项确认。
   */
  public SyncResult rebind(String courseId, String newParentExternalRef, String actor) {
    Course course = requireCourse(courseId);
    ResourceMetadata.CourseMetadata metadata = reader.readCourseMetadata(newParentExternalRef);
    List<ResourceMetadata> remote = reader.readResources(newParentExternalRef);
    return transaction.execute(
        status -> {
          Course current = requireUnchangedCourse(course);
          courses
              .findByExternalRef(EMBY, newParentExternalRef)
              .filter(existing -> !existing.id().equals(courseId))
              .ifPresent(
                  existing -> {
                    throw new BusinessException(
                        HttpStatus.CONFLICT, "COURSE_ALREADY_BOUND", "该 Emby 来源已绑定课程");
                  });
          MappingPlan plan = planner.plan(courses.findResourcesByCourse(current.id()), remote);
          if (plan.requiresManualConfirmation()) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "CATALOG_MAPPING_AMBIGUOUS",
                "存在 " + plan.ambiguous().size() + " 个无法自动匹配的课时，请先逐项确认");
          }
          Instant now = clock.instant();
          courses.updateSyncedFields(
              course.id(),
              metadata.title(),
              metadata.overview() == null ? "" : metadata.overview(),
              metadata.productionYear(),
              false,
              null,
              now);
          courses.replaceTaxonomy(course.id(), metadata);
          courses.updateCourseExternalRef(course.id(), newParentExternalRef, now);
          applyPlan(course, plan, now, actor);
          return SyncResult.of(plan);
        });
  }

  /** 生成重新绑定预览方案，不写库，供后台向导展示三类结果。 */
  public MappingPlan previewRebind(String courseId, String newParentExternalRef) {
    Course course = requireCourse(courseId);
    return planner.plan(
        courses.findResourcesByCourse(course.id()), reader.readResources(newParentExternalRef));
  }

  /** 管理员手工确认一个歧义项：把远端条目原位映射到指定本地资源。 */
  @Transactional
  public void confirmMapping(String resourceId, String newExternalRef, String actor) {
    LearningResource resource =
        courses
            .findResourceById(resourceId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "学习资源不存在"));
    Instant now = clock.instant();
    courses.updateResourceExternalRef(resource.id(), newExternalRef, now);
    courses.insertSourceMapping(
        idGenerator.nextId(),
        resource.id(),
        resource.externalRef(),
        newExternalRef,
        ResourceMappingPlanner.MatchedBy.MANUAL.name(),
        actor,
        now);
  }

  private MappingPlan applySync(
      Course course,
      ResourceMetadata.CourseMetadata metadata,
      List<ResourceMetadata> remote,
      Instant now,
      String actor) {
    courses.updateSyncedFields(
        course.id(),
        metadata.title(),
        metadata.overview() == null ? "" : metadata.overview(),
        metadata.productionYear(),
        false,
        null,
        now);
    courses.replaceTaxonomy(course.id(), metadata);
    MappingPlan plan = planner.plan(courses.findResourcesByCourse(course.id()), remote);
    applyPlan(course, plan, now, actor);
    return plan;
  }

  /** 只有明确匹配的部分会被写入；歧义项保持原状等待人工处理。 */
  private void applyPlan(Course course, MappingPlan plan, Instant now, String actor) {
    for (ResourceMappingPlanner.InPlace item : plan.inPlace()) {
      LearningResource local = item.local();
      ResourceMetadata remote = item.remote();
      if (!local.externalRef().equals(remote.externalRef())) {
        courses.updateResourceExternalRef(local.id(), remote.externalRef(), now);
        courses.insertSourceMapping(
            idGenerator.nextId(),
            local.id(),
            local.externalRef(),
            remote.externalRef(),
            item.matchedBy().name(),
            actor,
            now);
      }
      // sortIndex 每次同步都以远端 IndexNumber 覆盖写入，因此历史上被「列表位置」写错的序号
      // 会在下一次同步自动纠正；纠正走原位更新，local.id() 不变，进度与 Todo 引用不受影响（ADR-0034）。
      courses.updateResourceSyncedFields(
          local.id(),
          remote.title(),
          remote.sortIndex(),
          remote.durationMs(),
          remote.pageCount(),
          remote.sourceFingerprint(),
          now);
      if (!local.available()) {
        courses.updateResourceAvailability(local.id(), true, now);
      }
    }
    for (ResourceMetadata created : plan.created()) {
      courses.insertResource(
          new LearningResource(
              idGenerator.nextId(),
              course.id(),
              created.resourceType(),
              created.title(),
              created.sortIndex(),
              created.durationMs(),
              created.pageCount(),
              created.externalRef(),
              created.sourceFingerprint(),
              true,
              CatalogStatus.ACTIVE,
              null),
          now);
    }
    // 远端消失的课时只标记 available=0，sortIndex 冻结在最后一次同步到的值：
    // 它等于该课时自己的 Emby IndexNumber，不会被其他在线课时占用，因此不会撞号；
    // 远端重新上架时按 externalRef 原位匹配并刷新序号（ADR-0034）。
    for (LearningResource missing : plan.markedUnavailable()) {
      courses.updateResourceAvailability(missing.id(), false, now);
    }
  }

  /** 网络读取期间若管理员已换来源，旧快照不得覆盖新绑定，要求重新同步。 */
  private Course requireUnchangedCourse(Course snapshot) {
    Course current = requireCourse(snapshot.id());
    if (!current.externalRef().equals(snapshot.externalRef())) {
      throw new BusinessException(HttpStatus.CONFLICT, "COURSE_SOURCE_CHANGED", "课程来源已变更，请重新同步");
    }
    return current;
  }

  private Course requireCourse(String courseId) {
    return courses
        .findById(courseId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
  }

  /** 同步结果摘要，供后台展示。 */
  public record SyncResult(
      boolean succeeded,
      String error,
      int inPlaceCount,
      int createdCount,
      int unavailableCount,
      int ambiguousCount) {

    static SyncResult of(MappingPlan plan) {
      return new SyncResult(
          true,
          null,
          plan.inPlace().size(),
          plan.created().size(),
          plan.markedUnavailable().size(),
          plan.ambiguous().size());
    }

    static SyncResult failed(String error) {
      return new SyncResult(false, error, 0, 0, 0, 0);
    }
  }
}
