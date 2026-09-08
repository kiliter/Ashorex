package com.shangan.supervision.application;

import com.shangan.common.api.BusinessException;
import com.shangan.supervision.domain.Supervision;
import com.shangan.supervision.domain.SupervisionPermission;
import com.shangan.supervision.infrastructure.SupervisionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 督学端的唯一鉴权入口。
 *
 * <p>所有跨用户读取与一键督学都必须先经过这里，避免越权路径分散在各处（见 ADR-0028）。
 */
@Component
public class SupervisionGuard {

  private final SupervisionRepository supervisions;

  public SupervisionGuard(SupervisionRepository supervisions) {
    this.supervisions = supervisions;
  }

  /** 校验督学人对该学员是否具备指定权限；不具备时抛出稳定业务错误。 */
  public Supervision requirePermission(
      String supervisorUserId, String learnerUserId, SupervisionPermission permission) {
    Supervision supervision =
        supervisions
            .find(learnerUserId, supervisorUserId)
            .filter(Supervision::active)
            .orElseThrow(this::forbidden);
    if (!supervision.allows(permission)) {
      throw forbidden();
    }
    return supervision;
  }

  private BusinessException forbidden() {
    return new BusinessException(HttpStatus.FORBIDDEN, "SUPERVISION_FORBIDDEN", "没有该学员的督学权限");
  }
}
