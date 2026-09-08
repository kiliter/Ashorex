package com.shangan.archive.application;

import com.shangan.archive.domain.ArchivableEntityType;
import com.shangan.archive.domain.CascadePlan;
import com.shangan.archive.infrastructure.CascadeRepository;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.supervision.application.SupervisionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 归档与彻底删除。
 *
 * <p>列表页只能归档；彻底删除只在归档区执行，必须先看预检清单并输入实体名称确认。 全过程在一个事务内完成，磁盘附件与数据库行一起清理，任一步失败整体回滚（见 ADR-0029）。
 */
@Service
public class ArchiveService {

  private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

  /** 归档保留期天数。V2 固定 30 天，只用于后台「到期可清理」提醒，不会自动删除任何数据。 */
  private static final int ARCHIVE_RETENTION_DAYS = 30;

  private final CascadeRepository cascade;
  private final CourseRepository courses;
  private final UserRepository users;
  private final SupervisionService supervisions;
  private final DeletionAuditRepository audits;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final Path attachmentsRoot;

  public ArchiveService(
      CascadeRepository cascade,
      CourseRepository courses,
      UserRepository users,
      SupervisionService supervisions,
      DeletionAuditRepository audits,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${app.attachments-dir}") String attachmentsDir) {
    this.cascade = cascade;
    this.courses = courses;
    this.users = users;
    this.supervisions = supervisions;
    this.audits = audits;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.attachmentsRoot = Path.of(attachmentsDir).toAbsolutePath().normalize();
  }

  /** 归档：软下线，学习端不可见且不可新建引用，历史统计仍可查。 */
  @Transactional
  public void archive(ArchivableEntityType type, String entityId) {
    Instant now = clock.instant();
    switch (type) {
      case COURSE -> courses.updateStatus(requireCourse(entityId).id(), true, now);
      case LEARNING_RESOURCE -> courses.updateResourceStatus(entityId, true, now);
      case USER -> {
        User user = requireUser(entityId);
        users.updateStatus(user.id(), true, now);
        users.revokeRefreshTokensByUserId(user.id(), now);
      }
      case SUPERVISION -> supervisions.archive(entityId);
    }
  }

  @Transactional
  public void restore(ArchivableEntityType type, String entityId) {
    Instant now = clock.instant();
    switch (type) {
      case COURSE -> courses.updateStatus(entityId, false, now);
      case LEARNING_RESOURCE -> courses.updateResourceStatus(entityId, false, now);
      case USER -> users.updateStatus(entityId, false, now);
      case SUPERVISION -> supervisions.restore(entityId);
    }
  }

  /** 彻底删除前的预检清单：涉及的表、行数、磁盘占用与影响面。 */
  @Transactional(readOnly = true)
  public CascadePlan preflight(ArchivableEntityType type, String entityId) {
    return switch (type) {
      case COURSE -> coursePlan(requireCourse(entityId));
      case USER -> userPlan(requireUser(entityId));
      default ->
          throw new BusinessException(
              HttpStatus.BAD_REQUEST, "ARCHIVE_TYPE_UNSUPPORTED", "该类型不支持彻底删除预检");
    };
  }

  /**
   * 彻底删除。
   *
   * <p>必须输入与实体名称一致的确认串；删除顺序固定，磁盘文件与数据库行在同一事务内清理。
   */
  @Transactional
  public DeletionAuditRepository.DeletionAudit purge(
      ArchivableEntityType type, String entityId, String actor, String confirmedLabel) {
    // 名称确认不能替代归档前置条件，直接调用接口也不得删除在用对象。
    boolean active =
        switch (type) {
          case COURSE ->
              requireCourse(entityId).status() == com.shangan.catalog.domain.CatalogStatus.ACTIVE;
          case USER -> requireUser(entityId).active();
          default -> false;
        };
    if (active) {
      throw new BusinessException(HttpStatus.CONFLICT, "ARCHIVE_REQUIRED", "请先归档再彻底删除");
    }
    CascadePlan plan = preflight(type, entityId);
    if (confirmedLabel == null || !confirmedLabel.trim().equals(plan.entityLabel())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "ARCHIVE_LABEL_MISMATCH", "确认名称与实体名称不一致");
    }
    List<String> attachmentPaths =
        type == ArchivableEntityType.COURSE
            ? cascade.courseAttachmentPaths(entityId)
            : cascade.userAttachmentPaths(entityId);

    Map<String, Integer> deleted =
        type == ArchivableEntityType.COURSE
            ? cascade.deleteCourseCascade(entityId)
            : cascade.deleteUserCascade(entityId);

    // 文件删除失败视为整体失败，回滚数据库变更，避免留下无主文件。
    deleteFiles(attachmentPaths);
    if (type == ArchivableEntityType.USER) {
      deleteDirectoryQuietly(attachmentsRoot.resolve(entityId));
    }

    DeletionAuditRepository.DeletionAudit audit =
        new DeletionAuditRepository.DeletionAudit(
            idGenerator.nextId(),
            type,
            entityId,
            plan.entityLabel(),
            actor,
            toJson(deleted),
            clock.instant());
    audits.insert(audit);
    log.info("彻底删除 {} {}，共 {} 张表", type, entityId, deleted.size());
    return audit;
  }

  /** 孤儿数据自检；结果为 0 表示库内无残留。 */
  @Transactional(readOnly = true)
  public CascadeRepository.OrphanReport scanOrphans() {
    return cascade.scanOrphans();
  }

  /** 超过保留期的归档对象数量，用于后台提醒。 */
  @Transactional(readOnly = true)
  public int expiredArchiveCount() {
    Instant threshold = clock.instant().minus(java.time.Duration.ofDays(ARCHIVE_RETENTION_DAYS));
    return cascade.countExpiredArchives(threshold.toEpochMilli());
  }

  @Transactional(readOnly = true)
  public List<DeletionAuditRepository.DeletionAudit> recentAudits(int limit) {
    return audits.findRecent(limit);
  }

  private CascadePlan coursePlan(Course course) {
    Map<String, Integer> counts = cascade.countCourseRows(course.id());
    CascadeRepository.ImpactSummary impact = cascade.courseImpact(course.id());
    return new CascadePlan(
        ArchivableEntityType.COURSE,
        course.id(),
        course.title(),
        steps(CascadePlan.courseOrder(), counts),
        impact.attachmentBytes(),
        impact.affectedUserCount(),
        impact.affectedWatchedMs());
  }

  private CascadePlan userPlan(User user) {
    Map<String, Integer> counts = cascade.countUserRows(user.id());
    return new CascadePlan(
        ArchivableEntityType.USER,
        user.id(),
        user.username(),
        steps(CascadePlan.userOrder(), counts),
        0L,
        1,
        0L);
  }

  private List<CascadePlan.Step> steps(List<String> order, Map<String, Integer> counts) {
    List<CascadePlan.Step> steps = new ArrayList<>();
    int index = 1;
    for (String table : order) {
      steps.add(
          new CascadePlan.Step(index++, table, counts.getOrDefault(table, 0), describe(table)));
    }
    return List.copyOf(steps);
  }

  private String describe(String table) {
    return switch (table) {
      case "todo_attachments" -> "附件行与磁盘文件";
      case "todo_progress_events" -> "进度与专注流水";
      case "todo_deletions" -> "删除台账中引用该对象的行";
      case "todos" -> "含历史日期的待办";
      case "lesson_watch_states" -> "课时累计状态";
      case "course_genres", "course_tags", "course_people" -> "Emby 只读元数据投影";
      case "resource_source_mappings" -> "来源标识变更审计";
      case "learning_resources" -> "学习资源";
      case "courses" -> "课程";
      case "exam_goals" -> "考试目标";
      case "nag_deliveries" -> "催办投递流水";
      case "nags" -> "催办记录";
      case "nag_policies" -> "该用户的策略覆盖";
      case "supervisions" -> "双向督学绑定";
      case "user_presence" -> "在线状态";
      case "user_bark_settings" -> "个人 Bark 推送配置";
      case "refresh_tokens" -> "登录会话";
      case "user_roles" -> "角色";
      case "users" -> "账号";
      default -> "";
    };
  }

  private void deleteFiles(List<String> relativePaths) {
    for (String relativePath : relativePaths) {
      Path path = attachmentsRoot.resolve(relativePath).normalize();
      if (!path.startsWith(attachmentsRoot)) {
        continue;
      }
      try {
        Files.deleteIfExists(path);
      } catch (IOException exception) {
        throw new BusinessException(
            HttpStatus.INTERNAL_SERVER_ERROR, "ARCHIVE_FILE_DELETE_FAILED", "附件文件删除失败，已回滚");
      }
    }
  }

  private void deleteDirectoryQuietly(Path directory) {
    Path normalized = directory.normalize();
    if (!normalized.startsWith(attachmentsRoot) || !Files.isDirectory(normalized)) {
      return;
    }
    try (var stream = Files.walk(normalized)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 目录清理失败不阻塞主流程，孤儿自检会报告残留文件。
                }
              });
    } catch (IOException ignored) {
      // 同上。
    }
  }

  private String toJson(Map<String, Integer> counts) {
    Map<String, Integer> ordered = new LinkedHashMap<>(counts);
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Integer> entry : ordered.entrySet()) {
      if (!first) {
        json.append(',');
      }
      json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
      first = false;
    }
    return json.append('}').toString();
  }

  private Course requireCourse(String courseId) {
    return courses
        .findById(courseId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
  }

  private User requireUser(String userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND", "用户不存在"));
  }
}
