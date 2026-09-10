package com.shangan.supervision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import com.shangan.supervision.infrastructure.SupervisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 督学绑定：主督学人唯一、自我督学拒绝、归档与角色回收。 */
@ExtendWith(MockitoExtension.class)
class SupervisionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T16:00:00Z");
  private static final String LEARNER = "learner-1";
  private static final String SUPERVISOR = "supervisor-1";

  @Mock private SupervisionRepository supervisions;
  @Mock private UserRepository users;

  private SupervisionService service;

  @BeforeEach
  void setUp() {
    service =
        new SupervisionService(
            supervisions, users, () -> "supervision-1", Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("不能把自己设为督学人")
  void 拒绝自我督学() {
    assertThatThrownBy(
            () -> service.bind(LEARNER, LEARNER, SupervisionKind.PRIMARY, true, true, false, false))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_SELF_BIND");
    verify(supervisions, never()).insert(any(), any());
  }

  @Test
  @DisplayName("学员已有未归档主督学人时再绑主督学人返回 SUPERVISION_PRIMARY_EXISTS")
  void 主督学人唯一() {
    when(users.findById(LEARNER)).thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)));
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.LEARNER)));
    when(supervisions.find(LEARNER, SUPERVISOR)).thenReturn(Optional.empty());
    when(supervisions.findActivePrimaryByLearner(LEARNER))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, null)));

    assertThatThrownBy(
            () ->
                service.bind(
                    LEARNER, SUPERVISOR, SupervisionKind.PRIMARY, true, true, false, false))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_PRIMARY_EXISTS");
    verify(supervisions, never()).insert(any(), any());
  }

  @Test
  @DisplayName("同一对学员与督学人已有未归档绑定时返回 SUPERVISION_ALREADY_BOUND")
  void 重复绑定被拒绝() {
    when(users.findById(LEARNER)).thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)));
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.SUPERVISOR)));
    when(supervisions.find(LEARNER, SUPERVISOR))
        .thenReturn(Optional.of(binding(SupervisionKind.COLLABORATOR, null)));

    assertThatThrownBy(
            () ->
                service.bind(
                    LEARNER, SUPERVISOR, SupervisionKind.COLLABORATOR, true, false, false, false))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_ALREADY_BOUND");
  }

  @Test
  @DisplayName("绑定成功时写入四项权限，并为督学人补上 SUPERVISOR 角色")
  void 绑定成功补角色() {
    when(users.findById(LEARNER)).thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)));
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.LEARNER)));
    when(supervisions.find(LEARNER, SUPERVISOR)).thenReturn(Optional.empty());
    when(supervisions.findActivePrimaryByLearner(LEARNER)).thenReturn(Optional.empty());

    Supervision created =
        service.bind(LEARNER, SUPERVISOR, SupervisionKind.PRIMARY, true, true, false, false);

    assertThat(created.id()).isEqualTo("supervision-1");
    assertThat(created.learnerUserId()).isEqualTo(LEARNER);
    assertThat(created.supervisorUserId()).isEqualTo(SUPERVISOR);
    assertThat(created.kind()).isEqualTo(SupervisionKind.PRIMARY);
    assertThat(created.canView()).isTrue();
    assertThat(created.canNag()).isTrue();
    assertThat(created.canEditGoal()).isFalse();
    assertThat(created.canAddTodo()).isFalse();
    assertThat(created.active()).isTrue();
    verify(supervisions).insert(created, NOW);
    verify(users).addRole(SUPERVISOR, UserRole.SUPERVISOR, NOW);
  }

  @Test
  @DisplayName("督学人已有 SUPERVISOR 角色时不重复添加")
  void 已有角色不重复添加() {
    when(users.findById(LEARNER)).thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)));
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.SUPERVISOR)));
    when(supervisions.find(LEARNER, SUPERVISOR)).thenReturn(Optional.empty());
    when(supervisions.findActivePrimaryByLearner(LEARNER)).thenReturn(Optional.empty());

    service.bind(LEARNER, SUPERVISOR, SupervisionKind.PRIMARY, true, true, false, false);

    verify(users, never()).addRole(anyString(), any(), any());
  }

  @Test
  @DisplayName("同一账号对已归档时复用原绑定并更新类型与权限")
  void 重新绑定复用归档行() {
    Supervision archived = binding(SupervisionKind.PRIMARY, NOW.minusSeconds(60));
    when(users.findById(LEARNER)).thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)));
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.LEARNER)));
    when(supervisions.find(LEARNER, SUPERVISOR)).thenReturn(Optional.of(archived));

    Supervision restored =
        service.bind(LEARNER, SUPERVISOR, SupervisionKind.COLLABORATOR, true, false, false, false);

    assertThat(restored.id()).isEqualTo(archived.id());
    assertThat(restored.kind()).isEqualTo(SupervisionKind.COLLABORATOR);
    assertThat(restored.canView()).isTrue();
    assertThat(restored.canNag()).isFalse();
    assertThat(restored.active()).isTrue();
    verify(supervisions)
        .reactivate(archived.id(), SupervisionKind.COLLABORATOR, true, false, false, false, NOW);
    verify(supervisions, never()).insert(any(), any());
    verify(users).addRole(SUPERVISOR, UserRole.SUPERVISOR, NOW);
  }

  @Test
  @DisplayName("归档最后一个绑定后回收督学人的 SUPERVISOR 角色")
  void 归档后回收角色() {
    when(supervisions.findById("supervision-1"))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, null)));
    when(supervisions.findActiveBySupervisor(SUPERVISOR)).thenReturn(List.of());

    service.archive("supervision-1");

    verify(supervisions).archive("supervision-1", NOW);
    verify(users).removeRole(SUPERVISOR, UserRole.SUPERVISOR);
  }

  @Test
  @DisplayName("督学人仍有其他学员时不回收角色")
  void 仍有学员不回收角色() {
    when(supervisions.findById("supervision-1"))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, null)));
    when(supervisions.findActiveBySupervisor(SUPERVISOR))
        .thenReturn(List.of(binding(SupervisionKind.COLLABORATOR, null)));

    service.archive("supervision-1");

    verify(users, never()).removeRole(anyString(), any());
  }

  @Test
  @DisplayName("恢复主督学绑定时若已有其他主督学人则拒绝")
  void 恢复冲突被拒绝() {
    when(supervisions.findById("supervision-1"))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, NOW)));
    when(supervisions.findActivePrimaryByLearner(LEARNER))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, null)));

    assertThatThrownBy(() -> service.restore("supervision-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_PRIMARY_EXISTS");
    verify(supervisions, never()).restore(anyString(), any());
  }

  @Test
  @DisplayName("绑定不存在时返回 SUPERVISION_NOT_FOUND")
  void 绑定不存在() {
    when(supervisions.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.archive("missing"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_NOT_FOUND");
  }

  @Test
  @DisplayName("主督学人查询返回未归档主绑定的督学人 ID")
  void 主督学人查询() {
    when(supervisions.findActivePrimaryByLearner(LEARNER))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, null)));

    assertThat(service.primarySupervisorOf(LEARNER)).contains(SUPERVISOR);
  }

  @Test
  @DisplayName("后台绑定列表跳过账号已被彻底删除的绑定")
  void 列表跳过缺失账号() {
    when(supervisions.findAll())
        .thenReturn(
            List.of(
                binding(SupervisionKind.PRIMARY, null),
                binding(SupervisionKind.COLLABORATOR, null)));
    when(users.findById(LEARNER))
        .thenReturn(Optional.of(user(LEARNER, UserRole.LEARNER)), Optional.empty());
    when(users.findById(SUPERVISOR)).thenReturn(Optional.of(user(SUPERVISOR, UserRole.SUPERVISOR)));

    List<SupervisionService.Binding> bindings = service.listBindings();

    assertThat(bindings).hasSize(1);
    assertThat(bindings.getFirst().learner().id()).isEqualTo(LEARNER);
    assertThat(bindings.getFirst().supervisor().id()).isEqualTo(SUPERVISOR);
  }

  private static Supervision binding(SupervisionKind kind, Instant archivedAt) {
    return new Supervision(
        "supervision-1", LEARNER, SUPERVISOR, kind, true, true, false, false, archivedAt);
  }

  private static User user(String id, UserRole role) {
    return new User(id, id, "hash", id, "Asia/Shanghai", UserStatus.ACTIVE, null, Set.of(role));
  }
}
