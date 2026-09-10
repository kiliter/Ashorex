package com.shangan.diagnostics.infrastructure;

import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQLite 实现；时间戳按 UTC Epoch 毫秒存储。 */
@Repository
public class JdbcDiagnosticLogRepository implements DiagnosticLogRepository {

  private static final String SELECT =
      """
      SELECT id, user_id, storage_path, size_bytes, app_version, platform, uploaded_at
        FROM diagnostic_log_uploads
      """;

  private final JdbcClient jdbcClient;

  public JdbcDiagnosticLogRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public void insert(DiagnosticLogUpload upload) {
    jdbcClient
        .sql(
            """
            INSERT INTO diagnostic_log_uploads (
              id, user_id, storage_path, size_bytes, app_version, platform, uploaded_at
            ) VALUES (
              :id, :userId, :storagePath, :sizeBytes, :appVersion, :platform, :uploadedAt
            )
            """)
        .param("id", upload.id())
        .param("userId", upload.userId())
        .param("storagePath", upload.storagePath())
        .param("sizeBytes", upload.sizeBytes())
        .param("appVersion", upload.appVersion())
        .param("platform", upload.platform())
        .param("uploadedAt", upload.uploadedAt().toEpochMilli())
        .update();
  }

  @Override
  public Optional<DiagnosticLogUpload> findById(String id) {
    return jdbcClient.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
  }

  @Override
  public List<DiagnosticLogUpload> listRecent(int limit) {
    return jdbcClient
        .sql(SELECT + " ORDER BY uploaded_at DESC LIMIT :limit")
        .param("limit", limit)
        .query(this::map)
        .list();
  }

  @Override
  public List<DiagnosticLogUpload> listByUserOldestFirst(String userId) {
    return jdbcClient
        .sql(SELECT + " WHERE user_id = :userId ORDER BY uploaded_at ASC, id ASC")
        .param("userId", userId)
        .query(this::map)
        .list();
  }

  @Override
  public void delete(String id) {
    jdbcClient.sql("DELETE FROM diagnostic_log_uploads WHERE id = :id").param("id", id).update();
  }

  @Override
  public Optional<String> usernameOf(String userId) {
    return jdbcClient
        .sql("SELECT username FROM users WHERE id = :userId")
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  private DiagnosticLogUpload map(java.sql.ResultSet row, int ignored)
      throws java.sql.SQLException {
    return new DiagnosticLogUpload(
        row.getString("id"),
        row.getString("user_id"),
        row.getString("storage_path"),
        row.getLong("size_bytes"),
        row.getString("app_version"),
        row.getString("platform"),
        Instant.ofEpochMilli(row.getLong("uploaded_at")));
  }
}
