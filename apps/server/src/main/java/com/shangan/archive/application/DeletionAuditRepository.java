package com.shangan.archive.application;

import com.shangan.archive.domain.ArchivableEntityType;
import java.time.Instant;
import java.util.List;

/** 删除审计的持久化边界；只记录删了什么、删了多少行、谁执行的。 */
public interface DeletionAuditRepository {

  void insert(DeletionAudit audit);

  List<DeletionAudit> findRecent(int limit);

  /** 审计行，不含被删内容明细。 */
  record DeletionAudit(
      String id,
      ArchivableEntityType entityType,
      String entityId,
      String entityLabel,
      String actor,
      String rowCountsJson,
      Instant createdAt) {}
}
