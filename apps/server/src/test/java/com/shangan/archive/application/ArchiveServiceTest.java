package com.shangan.archive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.archive.domain.ArchivableEntityType;
import com.shangan.archive.domain.CascadePlan;
import com.shangan.archive.infrastructure.CascadeRepository;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.supervision.application.SupervisionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 归档、预检与彻底删除：名称二次确认、删除顺序清单与审计写入。 */
@ExtendWith(MockitoExtension.class)
class ArchiveServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T15:00:00Z");

  @Mock private CascadeRepository cascade;
  @Mock private CourseRepository courses;
  @Mock private UserRepository users;
  @Mock private SupervisionService supervisions;
  @Mock private DeletionAuditRepository audits;

  @TempDir Path attachmentsRoot;

  private ArchiveService service;

  @BeforeEach
  void setUp() {
    service =
        new ArchiveService(
            cascade,
            courses,
            users,
            supervisions,
            audits,
            () -> "audit-1",
            Clock.fixed(NOW, ZoneOffset.UTC),
            attachmentsRoot.toString());
  }

  @Test
  @DisplayName("在用用户即使名称正确也不能直接彻底删除")
  void 在用用户拒绝删除() {
    when(users.findById("user-1")).thenReturn(Optional.of(user()));
    assertThatThrownBy(() -> service.purge(ArchivableEntityType.USER, "user-1", "admin", "demo"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("ARCHIVE_REQUIRED");
    verify(cascade, never()).deleteUserCascade(anyString());
    verify(audits, never()).insert(any());
  }

  @Test
  @DisplayName("在用课程即使名称正确也不能直接彻底删除")
  void 在用课程拒绝删除() {
    Course old = course();
    Course active =
        new Course(
            old.id(),
            old.externalSource(),
            old.externalRef(),
            old.title(),
            old.overview(),
            old.productionYear(),
            old.sortOrder(),
            CatalogStatus.ACTIVE,
            false,
            NOW,
            null,
            null);
    when(courses.findById("course-1")).thenReturn(Optional.of(active));
    assertThatThrownBy(
            () -> service.purge(ArchivableEntityType.COURSE, "course-1", "admin", old.title()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("ARCHIVE_REQUIRED");
    verify(cascade, never()).deleteCourseCascade(anyString());
  }

  @Test
  @DisplayName("归档课程只改状态，不触发任何级联删除")
  void 归档课程只改状态() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));

    service.archive(ArchivableEntityType.COURSE, "course-1");

    verify(courses).updateStatus("course-1", true, NOW);
    verify(cascade, never()).deleteCourseCascade(anyString());
  }

  @Test
  @DisplayName("归档用户同时吊销全部刷新令牌")
  void 归档用户吊销令牌() {
    when(users.findById("user-1")).thenReturn(Optional.of(user()));

    service.archive(ArchivableEntityType.USER, "user-1");

    verify(users).updateStatus("user-1", true, NOW);
    verify(users).revokeRefreshTokensByUserId("user-1", NOW);
  }

  @Test
  @DisplayName("课程预检清单按固定顺序列出全部表并带上行数与影响面")
  void 课程预检按固定顺序() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(cascade.countCourseRows("course-1")).thenReturn(Map.of("todos", 12, "courses", 1));
    when(cascade.courseImpact("course-1"))
        .thenReturn(new CascadeRepository.ImpactSummary(2, 900_000L, 4_096L));

    CascadePlan plan = service.preflight(ArchivableEntityType.COURSE, "course-1");

    assertThat(plan.entityType()).isEqualTo(ArchivableEntityType.COURSE);
    assertThat(plan.entityLabel()).isEqualTo("考研数学强化");
    assertThat(plan.steps())
        .extracting(CascadePlan.Step::table)
        .containsExactlyElementsOf(CascadePlan.courseOrder());
    assertThat(plan.steps()).extracting(CascadePlan.Step::order).startsWith(1, 2, 3);
    assertThat(plan.totalRows()).isEqualTo(13);
    assertThat(plan.affectedUserCount()).isEqualTo(2);
    assertThat(plan.affectedWatchedMs()).isEqualTo(900_000L);
    assertThat(plan.attachmentBytes()).isEqualTo(4_096L);
  }

  @Test
  @DisplayName("确认名称与实体名称不一致时拒绝彻底删除，不删任何数据")
  void 名称不匹配拒绝删除() {
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(cascade.countCourseRows("course-1")).thenReturn(Map.of());
    when(cascade.courseImpact("course-1"))
        .thenReturn(new CascadeRepository.ImpactSummary(0, 0L, 0L));

    assertThatThrownBy(
            () -> service.purge(ArchivableEntityType.COURSE, "course-1", "admin", "考研数学"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ARCHIVE_LABEL_MISMATCH");
    verify(cascade, never()).deleteCourseCascade(anyString());
    verify(audits, never()).insert(any());
  }

  @Test
  @DisplayName("名称一致时执行级联删除、删除磁盘附件并写入审计")
  void 确认后执行级联删除() throws IOException {
    Path attachment = attachmentsRoot.resolve("user-1").resolve("a.png");
    Files.createDirectories(attachment.getParent());
    Files.writeString(attachment, "x");
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(cascade.countCourseRows("course-1")).thenReturn(Map.of("todos", 2));
    when(cascade.courseImpact("course-1"))
        .thenReturn(new CascadeRepository.ImpactSummary(1, 1_000L, 1L));
    when(cascade.courseAttachmentPaths("course-1")).thenReturn(List.of("user-1/a.png"));
    Map<String, Integer> deleted = new LinkedHashMap<>();
    deleted.put("todo_attachments", 1);
    deleted.put("todos", 2);
    when(cascade.deleteCourseCascade("course-1")).thenReturn(deleted);

    DeletionAuditRepository.DeletionAudit audit =
        service.purge(ArchivableEntityType.COURSE, "course-1", "admin", "  考研数学强化  ");

    assertThat(Files.exists(attachment)).isFalse();
    assertThat(audit.id()).isEqualTo("audit-1");
    assertThat(audit.entityType()).isEqualTo(ArchivableEntityType.COURSE);
    assertThat(audit.entityLabel()).isEqualTo("考研数学强化");
    assertThat(audit.actor()).isEqualTo("admin");
    assertThat(audit.rowCountsJson()).isEqualTo("{\"todo_attachments\":1,\"todos\":2}");
    assertThat(audit.createdAt()).isEqualTo(NOW);
    ArgumentCaptor<DeletionAuditRepository.DeletionAudit> written =
        ArgumentCaptor.forClass(DeletionAuditRepository.DeletionAudit.class);
    verify(audits).insert(written.capture());
    assertThat(written.getValue()).isEqualTo(audit);
  }

  @Test
  @DisplayName("附件路径试图跳出附件根目录时被忽略，不会删到目录外的文件")
  void 拒绝路径穿越删除() throws IOException {
    Path outside = attachmentsRoot.getParent().resolve("outside.txt");
    Files.writeString(outside, "keep");
    when(courses.findById("course-1")).thenReturn(Optional.of(course()));
    when(cascade.countCourseRows("course-1")).thenReturn(Map.of());
    when(cascade.courseImpact("course-1"))
        .thenReturn(new CascadeRepository.ImpactSummary(0, 0L, 0L));
    when(cascade.courseAttachmentPaths("course-1")).thenReturn(List.of("../outside.txt"));
    when(cascade.deleteCourseCascade("course-1")).thenReturn(Map.of("courses", 1));

    service.purge(ArchivableEntityType.COURSE, "course-1", "admin", "考研数学强化");

    assertThat(Files.exists(outside)).isTrue();
    Files.deleteIfExists(outside);
  }

  @Test
  @DisplayName("学习资源与督学关系不支持彻底删除预检")
  void 不支持的类型拒绝预检() {
    assertThatThrownBy(
            () -> service.preflight(ArchivableEntityType.LEARNING_RESOURCE, "resource-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ARCHIVE_TYPE_UNSUPPORTED");
  }

  @Test
  @DisplayName("孤儿自检结果直接透传，全为 0 时视为干净")
  void 孤儿自检透传() {
    when(cascade.scanOrphans()).thenReturn(new CascadeRepository.OrphanReport(0, 0, 0, 0, 0));

    CascadeRepository.OrphanReport report = service.scanOrphans();

    assertThat(report.clean()).isTrue();
    assertThat(report.orphanTodos()).isZero();
  }

  @Test
  @DisplayName("超过 30 天保留期的归档对象数量按服务端时钟计算")
  void 过期归档按服务端时钟统计() {
    long threshold = NOW.minus(java.time.Duration.ofDays(30)).toEpochMilli();
    when(cascade.countExpiredArchives(threshold)).thenReturn(4);

    assertThat(service.expiredArchiveCount()).isEqualTo(4);
  }

  private static Course course() {
    return new Course(
        "course-1",
        "EMBY",
        "emby-course-1",
        "考研数学强化",
        "",
        2026,
        0,
        CatalogStatus.ARCHIVED,
        false,
        NOW,
        null,
        NOW);
  }

  private static User user() {
    return new User(
        "user-1",
        "demo",
        "hash",
        "小明",
        "Asia/Shanghai",
        UserStatus.ACTIVE,
        null,
        Set.of(UserRole.LEARNER));
  }
}
