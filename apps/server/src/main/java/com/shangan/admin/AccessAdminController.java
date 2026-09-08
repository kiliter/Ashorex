package com.shangan.admin;

import com.shangan.archive.application.ArchiveService;
import com.shangan.archive.domain.ArchivableEntityType;
import com.shangan.archive.infrastructure.CascadeRepository;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.supervision.domain.SupervisionKind;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户、督学关系与归档区的 JSON API。用户与课程只能归档，彻底删除必须在归档区确认。 */
@RestController
@RequestMapping("/admin/api")
public class AccessAdminController {

  private final AuthService users;
  private final SupervisionService supervisions;
  private final ArchiveService archive;
  private final CourseRepository courses;
  private final Clock clock;

  public AccessAdminController(
      AuthService users,
      SupervisionService supervisions,
      ArchiveService archive,
      CourseRepository courses,
      Clock clock) {
    this.users = users;
    this.supervisions = supervisions;
    this.archive = archive;
    this.courses = courses;
    this.clock = clock;
  }

  // ---------- 用户 ----------

  @GetMapping("/users")
  List<UserRow> users() {
    return users.listUsers().stream().map(UserRow::of).toList();
  }

  @PostMapping("/users")
  ResponseEntity<Void> createUser(@RequestBody CreateUserRequest request) {
    users.createManagedUser(
        request.username(),
        request.displayName(),
        request.password(),
        request.timezone() == null || request.timezone().isBlank()
            ? "Asia/Shanghai"
            : request.timezone(),
        request.supervisor());
    return ResponseEntity.noContent().build();
  }

  /** 重置密码会撤销该用户的全部刷新令牌。 */
  @PostMapping("/users/reset-password")
  ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
    users.resetPassword(request.userId(), request.password());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/users/profile")
  ResponseEntity<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
    users.updateProfile(request.userId(), request.displayName(), request.timezone());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/users/supervisor-role")
  ResponseEntity<Void> supervisorRole(@RequestBody SupervisorRoleRequest request) {
    users.setSupervisorRole(request.userId(), request.supervisor());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/users/archive")
  ResponseEntity<Void> archiveUser(@RequestBody EntityIdRequest request) {
    archive.archive(ArchivableEntityType.USER, request.entityId());
    return ResponseEntity.noContent().build();
  }

  // ---------- 督学关系 ----------

  @GetMapping("/supervisions")
  SupervisionsResponse supervisions() {
    return new SupervisionsResponse(
        supervisions.listBindings(), users.listActiveUsers().stream().map(UserRow::of).toList());
  }

  @PostMapping("/supervisions")
  ResponseEntity<Void> bind(@RequestBody BindRequest request) {
    supervisions.bind(
        request.learnerUserId(),
        request.supervisorUserId(),
        SupervisionKind.valueOf(
            request.kind() == null ? "PRIMARY" : request.kind().toUpperCase(Locale.ROOT)),
        request.canView(),
        request.canNag(),
        request.canEditGoal(),
        request.canAddTodo());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/supervisions/permissions")
  ResponseEntity<Void> permissions(@RequestBody PermissionsRequest request) {
    supervisions.updatePermissions(
        request.supervisionId(),
        request.canView(),
        request.canNag(),
        request.canEditGoal(),
        request.canAddTodo());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/supervisions/archive")
  ResponseEntity<Void> archiveSupervision(@RequestBody SupervisionIdRequest request) {
    supervisions.archive(request.supervisionId());
    return ResponseEntity.noContent().build();
  }

  // ---------- 归档区 ----------

  @GetMapping("/archive")
  ArchiveResponse archiveArea() {
    List<ArchivedCourse> archivedCourses = new ArrayList<>();
    for (Course course : courses.findAll()) {
      if (course.status() == CatalogStatus.ARCHIVED || course.sourceMissing()) {
        archivedCourses.add(
            new ArchivedCourse(
                course.id(), course.title(), course.status().name(), course.sourceMissing()));
      }
    }
    List<UserRow> archivedUsers =
        users.listUsers().stream()
            .filter(user -> user.status() == UserStatus.ARCHIVED)
            .map(UserRow::of)
            .toList();
    return new ArchiveResponse(
        archivedCourses, archivedUsers, archive.scanOrphans(), archive.recentAudits(20));
  }

  /** 彻底删除前的级联清单预览；前端据此展示将被删除的行数。 */
  @GetMapping("/archive/preflight")
  Object preflight(@RequestParam String type, @RequestParam String entityId) {
    return archive.preflight(entityType(type), entityId);
  }

  /**
   * 孤儿数据自检的手动触发入口。
   *
   * <p>职责：立即执行一次 {@link ArchiveService#scanOrphans()}，把五类残留计数与总数回给归档区页面， 让管理员不必等每日备份后的定时自检。
   *
   * <p>边界：只读扫描，不删除也不修复任何数据；发现残留只用于报告，处理仍需走归档区的彻底删除流程。 返回时间戳来自服务端注入的 {@link Clock}，与页面本地时间无关。
   */
  @PostMapping("/archive/scan-orphans")
  OrphanScanResult scanOrphans() {
    CascadeRepository.OrphanReport report = archive.scanOrphans();
    int total =
        report.orphanTodos()
            + report.orphanProgressEvents()
            + report.orphanAttachments()
            + report.orphanWatchStates()
            + report.orphanNags();
    return new OrphanScanResult(report, report.clean(), total, clock.instant());
  }

  @PostMapping("/archive/restore")
  ResponseEntity<Void> restore(@RequestBody ArchiveActionRequest request) {
    archive.restore(entityType(request.type()), request.entityId());
    return ResponseEntity.noContent().build();
  }

  /** 彻底删除必须输入与实体名称一致的确认串，否则抛 ARCHIVE_LABEL_MISMATCH。 */
  @PostMapping("/archive/purge")
  ResponseEntity<Void> purge(@RequestBody PurgeRequest request) {
    archive.purge(entityType(request.type()), request.entityId(), "admin", request.confirmLabel());
    return ResponseEntity.noContent().build();
  }

  /** 把请求里的类型字符串解析为枚举；未知类型返回 400 而不是 500。 */
  private ArchivableEntityType entityType(String type) {
    if (type == null || type.isBlank()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "ARCHIVE_TYPE_UNSUPPORTED", "缺少归档对象类型");
    }
    try {
      return ArchivableEntityType.valueOf(type.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "ARCHIVE_TYPE_UNSUPPORTED", "不支持的归档对象类型");
    }
  }

  @PostMapping("/archive/course")
  ResponseEntity<Void> archiveCourse(@RequestBody EntityIdRequest request) {
    archive.archive(ArchivableEntityType.COURSE, request.entityId());
    return ResponseEntity.noContent().build();
  }

  // ---------- DTO ----------

  /** 用户行；不含密码散列。 */
  public record UserRow(
      String id,
      String username,
      String displayName,
      String timezone,
      String status,
      List<String> roles,
      boolean supervisor) {

    static UserRow of(User user) {
      return new UserRow(
          user.id(),
          user.username(),
          user.displayName(),
          user.timezone(),
          user.status().name(),
          user.roles().stream().map(Enum::name).toList(),
          user.roles().contains(UserRole.SUPERVISOR));
    }
  }

  /** 督学关系页响应。 */
  public record SupervisionsResponse(List<?> bindings, List<UserRow> users) {}

  /** 归档区响应。 */
  public record ArchiveResponse(
      List<ArchivedCourse> archivedCourses,
      List<UserRow> archivedUsers,
      Object orphans,
      List<?> audits) {}

  /** 归档课程行。 */
  public record ArchivedCourse(String id, String title, String status, boolean sourceMissing) {}

  /**
   * 手动孤儿自检结果。
   *
   * @param orphans 五类残留的逐项计数，字段与归档区表格一一对应
   * @param clean 是否完全无残留
   * @param total 五类残留合计行数
   * @param scannedAt 服务端扫描时刻，ISO-8601 UTC
   */
  public record OrphanScanResult(
      CascadeRepository.OrphanReport orphans, boolean clean, int total, Instant scannedAt) {}

  /** 创建用户请求。 */
  public record CreateUserRequest(
      String username, String displayName, String password, String timezone, boolean supervisor) {}

  /** 重置密码请求。 */
  public record ResetPasswordRequest(String userId, String password) {}

  /** 更新资料请求。 */
  public record UpdateProfileRequest(String userId, String displayName, String timezone) {}

  /** 督学身份请求。 */
  public record SupervisorRoleRequest(String userId, boolean supervisor) {}

  /** 通用实体 ID 请求。 */
  public record EntityIdRequest(String entityId) {}

  /** 督学关系 ID 请求。 */
  public record SupervisionIdRequest(String supervisionId) {}

  /** 建立督学关系请求。 */
  public record BindRequest(
      String learnerUserId,
      String supervisorUserId,
      String kind,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo) {}

  /** 更新督学权限请求。 */
  public record PermissionsRequest(
      String supervisionId,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo) {}

  /** 归档区动作请求。 */
  public record ArchiveActionRequest(String type, String entityId) {}

  /** 彻底删除请求。 */
  public record PurgeRequest(String type, String entityId, String confirmLabel) {}
}
