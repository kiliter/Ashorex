package com.shangan.supervision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import com.shangan.supervision.domain.SupervisionPermission;
import com.shangan.supervision.infrastructure.SupervisionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** 督学端唯一鉴权入口的越权拒绝测试。 */
@ExtendWith(MockitoExtension.class)
class SupervisionGuardTest {

  private static final String SUPERVISOR = "supervisor-1";
  private static final String LEARNER = "learner-1";

  @Mock private SupervisionRepository supervisions;

  private SupervisionGuard guard;

  @BeforeEach
  void setUp() {
    guard = new SupervisionGuard(supervisions);
  }

  @Test
  @DisplayName("没有任何绑定时拒绝，返回 403 与 SUPERVISION_FORBIDDEN")
  void 无绑定拒绝() {
    when(supervisions.find(LEARNER, SUPERVISOR)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.VIEW))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              BusinessException business = (BusinessException) exception;
              assertThat(business.errorCode()).isEqualTo("SUPERVISION_FORBIDDEN");
              assertThat(business.status()).isEqualTo(HttpStatus.FORBIDDEN);
            });
  }

  @Test
  @DisplayName("绑定已归档时即使权限位为真也拒绝")
  void 已归档绑定拒绝() {
    when(supervisions.find(LEARNER, SUPERVISOR))
        .thenReturn(
            Optional.of(
                binding(
                    SupervisionKind.PRIMARY,
                    true,
                    true,
                    true,
                    true,
                    Instant.parse("2026-09-01T00:00:00Z"))));

    assertThatThrownBy(
            () -> guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.VIEW))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_FORBIDDEN");
  }

  @Test
  @DisplayName("权限开关关闭时拒绝对应操作")
  void 权限开关关闭拒绝() {
    when(supervisions.find(LEARNER, SUPERVISOR))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, true, false, false, false, null)));

    assertThatThrownBy(
            () -> guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.NAG))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("SUPERVISION_FORBIDDEN");
  }

  @Test
  @DisplayName("协同督学只开查看权限时可查看，但改目标与加待办都被拒绝")
  void 协同督学只能查看() {
    when(supervisions.find(LEARNER, SUPERVISOR))
        .thenReturn(
            Optional.of(binding(SupervisionKind.COLLABORATOR, true, true, false, false, null)));

    Supervision allowed = guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.VIEW);
    assertThat(allowed.kind()).isEqualTo(SupervisionKind.COLLABORATOR);
    assertThat(guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.NAG))
        .isSameAs(allowed);

    assertThatThrownBy(
            () -> guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.EDIT_GOAL))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> guard.requirePermission(SUPERVISOR, LEARNER, SupervisionPermission.ADD_TODO))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("四项权限全开时四种权限都通过")
  void 全权限通过() {
    when(supervisions.find(LEARNER, SUPERVISOR))
        .thenReturn(Optional.of(binding(SupervisionKind.PRIMARY, true, true, true, true, null)));

    for (SupervisionPermission permission : SupervisionPermission.values()) {
      assertThat(guard.requirePermission(SUPERVISOR, LEARNER, permission).learnerUserId())
          .isEqualTo(LEARNER);
    }
  }

  private Supervision binding(
      SupervisionKind kind,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo,
      Instant archivedAt) {
    return new Supervision(
        "supervision-1",
        LEARNER,
        SUPERVISOR,
        kind,
        canView,
        canNag,
        canEditGoal,
        canAddTodo,
        archivedAt);
  }
}
