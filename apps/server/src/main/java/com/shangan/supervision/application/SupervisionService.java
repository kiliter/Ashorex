package com.shangan.supervision.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.infrastructure.UserRepository;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import com.shangan.supervision.infrastructure.SupervisionRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护督学绑定，并对外提供「谁在督学谁」的查询。 */
@Service
public class SupervisionService {

  private final SupervisionRepository supervisions;
  private final UserRepository users;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public SupervisionService(
      SupervisionRepository supervisions,
      UserRepository users,
      IdGenerator idGenerator,
      Clock clock) {
    this.supervisions = supervisions;
    this.users = users;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 学员当前主督学人 ID；用于事件写入时固化督学人快照。 */
  @Transactional(readOnly = true)
  public Optional<String> primarySupervisorOf(String learnerUserId) {
    return supervisions
        .findActivePrimaryByLearner(learnerUserId)
        .map(Supervision::supervisorUserId);
  }

  /** 某督学人名下的学员绑定列表。 */
  @Transactional(readOnly = true)
  public List<Supervision> learnersOf(String supervisorUserId) {
    return supervisions.findActiveBySupervisor(supervisorUserId);
  }

  /** 某学员的督学人绑定列表，供「我的」页展示。 */
  @Transactional(readOnly = true)
  public List<Supervision> supervisorsOf(String learnerUserId) {
    return supervisions.findActiveByLearner(learnerUserId);
  }

  @Transactional(readOnly = true)
  public List<Supervision> listAll() {
    return supervisions.findAll();
  }

  /** 创建绑定；同一学员只能有一个未归档主督学人，且不允许自我督学。 */
  @Transactional
  public Supervision bind(
      String learnerUserId,
      String supervisorUserId,
      SupervisionKind kind,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo) {
    if (learnerUserId.equals(supervisorUserId)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "SUPERVISION_SELF_BIND", "不能把自己设为督学人");
    }
    User learner = requireUser(learnerUserId, "SUPERVISION_LEARNER_NOT_FOUND");
    User supervisor = requireUser(supervisorUserId, "SUPERVISION_SUPERVISOR_NOT_FOUND");
    Optional<Supervision> existing = supervisions.find(learner.id(), supervisor.id());
    if (existing.filter(Supervision::active).isPresent()) {
      throw new BusinessException(HttpStatus.CONFLICT, "SUPERVISION_ALREADY_BOUND", "该督学关系已存在");
    }
    if (kind == SupervisionKind.PRIMARY
        && supervisions.findActivePrimaryByLearner(learner.id()).isPresent()) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "SUPERVISION_PRIMARY_EXISTS", "该学员已有主督学人，请先改绑");
    }
    Supervision supervision =
        new Supervision(
            existing.map(Supervision::id).orElseGet(idGenerator::nextId),
            learner.id(),
            supervisor.id(),
            kind,
            canView,
            canNag,
            canEditGoal,
            canAddTodo,
            null);
    if (existing.isPresent()) {
      // 账号对唯一索引覆盖已归档行，重绑必须原地恢复，不能再插入新 ID。
      supervisions.reactivate(
          supervision.id(), kind, canView, canNag, canEditGoal, canAddTodo, clock.instant());
    } else {
      supervisions.insert(supervision, clock.instant());
    }
    if (!supervisor.hasRole(UserRole.SUPERVISOR)) {
      users.addRole(supervisor.id(), UserRole.SUPERVISOR, clock.instant());
    }
    return supervision;
  }

  @Transactional
  public void updatePermissions(
      String supervisionId,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo) {
    requireSupervision(supervisionId);
    supervisions.updatePermissions(
        supervisionId, canView, canNag, canEditGoal, canAddTodo, clock.instant());
  }

  /** 归档绑定；归档后督学人立即失去该学员的全部权限，但历史事件快照保留。 */
  @Transactional
  public void archive(String supervisionId) {
    Supervision supervision = requireSupervision(supervisionId);
    supervisions.archive(supervision.id(), clock.instant());
    dropSupervisorRoleIfUnused(supervision.supervisorUserId());
  }

  @Transactional
  public void restore(String supervisionId) {
    Supervision supervision = requireSupervision(supervisionId);
    if (supervision.kind() == SupervisionKind.PRIMARY
        && supervisions.findActivePrimaryByLearner(supervision.learnerUserId()).isPresent()) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "SUPERVISION_PRIMARY_EXISTS", "该学员已有主督学人，无法恢复");
    }
    supervisions.restore(supervision.id(), clock.instant());
    User supervisor =
        requireUser(supervision.supervisorUserId(), "SUPERVISION_SUPERVISOR_NOT_FOUND");
    if (!supervisor.hasRole(UserRole.SUPERVISOR)) {
      users.addRole(supervisor.id(), UserRole.SUPERVISOR, clock.instant());
    }
  }

  /** 学员列表用：把绑定与学员账号信息组合成后台可渲染的视图。 */
  @Transactional(readOnly = true)
  public List<Binding> listBindings() {
    List<Binding> result = new ArrayList<>();
    for (Supervision supervision : supervisions.findAll()) {
      Optional<User> learner = users.findById(supervision.learnerUserId());
      Optional<User> supervisor = users.findById(supervision.supervisorUserId());
      if (learner.isEmpty() || supervisor.isEmpty()) {
        continue;
      }
      result.add(new Binding(supervision, learner.get(), supervisor.get()));
    }
    return List.copyOf(result);
  }

  /** 督学人不再督学任何人时收回 SUPERVISOR 角色，避免出现空督学端入口。 */
  private void dropSupervisorRoleIfUnused(String supervisorUserId) {
    if (supervisions.findActiveBySupervisor(supervisorUserId).isEmpty()) {
      users.removeRole(supervisorUserId, UserRole.SUPERVISOR);
    }
  }

  private Supervision requireSupervision(String supervisionId) {
    return supervisions
        .findById(supervisionId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "SUPERVISION_NOT_FOUND", "督学关系不存在"));
  }

  private User requireUser(String userId, String errorCode) {
    return users
        .findById(userId)
        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, errorCode, "用户不存在"));
  }

  /** 后台列表视图：绑定 + 学员 + 督学人。 */
  public record Binding(Supervision supervision, User learner, User supervisor) {}
}
