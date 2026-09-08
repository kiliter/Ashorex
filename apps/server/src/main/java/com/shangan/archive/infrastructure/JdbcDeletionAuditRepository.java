package com.shangan.archive.infrastructure;

import com.shangan.archive.application.DeletionAuditRepository;
import com.shangan.archive.domain.ArchivableEntityType;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 删除审计的 SQLite 实现。 */
@Repository
public class JdbcDeletionAuditRepository implements DeletionAuditRepository {

  private final JdbcClient jdbcClient;

  public JdbcDeletionAuditRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public void insert(DeletionAudit audit) {
    jdbcClient
        .sql(
            """
            INSERT INTO deletion_audits (
                id, entity_type, entity_id, entity_label, actor, row_counts_json, created_at
            ) VALUES (:id, :type, :entityId, :label, :actor, :counts, :now)
            """)
        .param("id", audit.id())
        .param("type", audit.entityType().name())
        .param("entityId", audit.entityId())
        .param("label", audit.entityLabel())
        .param("actor", audit.actor())
        .param("counts", audit.rowCountsJson())
        .param("now", audit.createdAt().toEpochMilli())
        .update();
  }

  @Override
  public List<DeletionAudit> findRecent(int limit) {
    return jdbcClient
        .sql(
            """
            SELECT id, entity_type, entity_id, entity_label, actor, row_counts_json, created_at
              FROM deletion_audits
             ORDER BY created_at DESC
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (row, rowNumber) ->
                new DeletionAudit(
                    row.getString("id"),
                    ArchivableEntityType.valueOf(row.getString("entity_type")),
                    row.getString("entity_id"),
                    row.getString("entity_label"),
                    row.getString("actor"),
                    row.getString("row_counts_json"),
                    Instant.ofEpochMilli(row.getLong("created_at"))))
        .list();
  }
}
