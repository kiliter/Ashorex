package com.shangan.supervision.infrastructure;

import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 督学绑定的持久化边界。 */
public interface SupervisionRepository {

  /** 查找一对学员与督学人之间的绑定，含已归档。 */
  Optional<Supervision> find(String learnerUserId, String supervisorUserId);

  Optional<Supervision> findById(String id);

  /** 该学员当前未归档的主督学人绑定。 */
  Optional<Supervision> findActivePrimaryByLearner(String learnerUserId);

  /** 某督学人名下全部未归档绑定。 */
  List<Supervision> findActiveBySupervisor(String supervisorUserId);

  /** 某学员的全部未归档绑定（主督学人 + 协同督学）。 */
  List<Supervision> findActiveByLearner(String learnerUserId);

  /** 后台列表用，包含已归档绑定。 */
  List<Supervision> findAll();

  void insert(Supervision supervision, Instant createdAt);

  /** 复用归档账号对，在同一次更新中恢复类型、权限与有效状态。 */
  void reactivate(
      String id,
      SupervisionKind kind,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo,
      Instant now);

  void updatePermissions(
      String id,
      boolean canView,
      boolean canNag,
      boolean canEditGoal,
      boolean canAddTodo,
      Instant now);

  void archive(String id, Instant archivedAt);

  void restore(String id, Instant now);

  void delete(String id);
}
