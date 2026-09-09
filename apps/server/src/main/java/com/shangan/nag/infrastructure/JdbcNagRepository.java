package com.shangan.nag.infrastructure;

import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 催办与投递流水的 SQLite 实现。 */
@Repository
public class JdbcNagRepository implements NagRepository {

  private static final String SELECT =
      """
      SELECT id, user_id, local_date, threshold_level, trigger_source, triggered_by_user_id,
             idle_minutes, pending_count, message, require_reason, status,
             delivered_at, responded_at, reason_tag, reason_text,
             supervisor_user_id_snapshot, created_at, title, app_superseded
        FROM nags
      """;

  private static final String SELECT_DELIVERY =
      """
      SELECT id, nag_id, channel, status, detail, created_at
        FROM nag_deliveries
      """;

  private final JdbcClient jdbcClient;

  public JdbcNagRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 报告区间两端均为学员本地日期；与 StatsView 的日、周、月边界一致。 */
  @Override
  public ResponseCounts countResponsesBetween(String userId, LocalDate start, LocalDate end) {
    return jdbcClient
        .sql(
            """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN status = 'RESPONDED' THEN 1 ELSE 0 END), 0) AS responded
          FROM nags
         WHERE user_id = :userId AND local_date BETWEEN :start AND :end
        """)
        .param("userId", userId)
        .param("start", start.toString())
        .param("end", end.toString())
        .query((row, index) -> new ResponseCounts(row.getInt("total"), row.getInt("responded")))
        .single();
  }

  @Override
  public Optional<Nag> findById(String id) {
    return jdbcClient.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
  }

  @Override
  public Optional<Nag> findAwaitingByUser(String userId) {
    return jdbcClient
        .sql(
            SELECT
                + """
                 WHERE user_id = :userId AND status = 'DELIVERED' AND app_superseded = 0
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """)
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public boolean autoNagExists(String userId, LocalDate localDate, int thresholdLevel) {
    Long count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM nags
                 WHERE user_id = :userId
                   AND local_date = :date
                   AND threshold_level = :level
                   AND trigger_source = 'AUTO'
                """)
            .param("userId", userId)
            .param("date", localDate.toString())
            .param("level", thresholdLevel)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  @Override
  public int countOn(String userId, LocalDate localDate) {
    Long count =
        jdbcClient
            .sql("SELECT count(*) FROM nags WHERE user_id = :userId AND local_date = :date")
            .param("userId", userId)
            .param("date", localDate.toString())
            .query(Long.class)
            .single();
    return count == null ? 0 : count.intValue();
  }

  @Override
  public List<Nag> findByUser(String userId, int limit) {
    return jdbcClient
        .sql(SELECT + " WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
        .param("userId", userId)
        .param("limit", limit)
        .query(this::map)
        .list();
  }

  @Override
  public List<Nag> findRecent(int limit) {
    return jdbcClient
        .sql(SELECT + " ORDER BY created_at DESC LIMIT :limit")
        .param("limit", limit)
        .query(this::map)
        .list();
  }

  @Override
  public List<Nag> findByUsers(List<String> userIds, int limit) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(SELECT + " WHERE user_id IN (:ids) ORDER BY created_at DESC LIMIT :limit")
        .param("ids", userIds)
        .param("limit", limit)
        .query(this::map)
        .list();
  }

  @Override
  public List<Nag> findFullscreenAwaitingBefore(Instant threshold) {
    return jdbcClient
        .sql(
            SELECT
                + """
                 WHERE status = 'DELIVERED'
                   AND delivered_at IS NOT NULL
                   AND delivered_at < :threshold
                   AND EXISTS (SELECT 1 FROM nag_deliveries d WHERE d.nag_id = nags.id
                               AND d.channel = 'FULLSCREEN' AND d.status <> 'FAILED')
                   AND EXISTS (SELECT 1 FROM users u WHERE u.id = nags.user_id AND u.status = 'ACTIVE')
                 ORDER BY delivered_at
                """)
        .param("threshold", threshold.toEpochMilli())
        .query(this::map)
        .list();
  }

  @Override
  public void insert(Nag nag) {
    jdbcClient
        .sql(
            """
            INSERT INTO nags (
                id, user_id, local_date, threshold_level, trigger_source, triggered_by_user_id,
                idle_minutes, pending_count, message, require_reason, status,
                delivered_at, responded_at, reason_tag, reason_text,
                supervisor_user_id_snapshot, created_at, title
            ) VALUES (
                :id, :userId, :date, :level, :trigger, :triggeredBy,
                :idleMinutes, :pendingCount, :message, :requireReason, :status,
                NULL, NULL, NULL, NULL, :supervisor, :createdAt, :title
            )
            """)
        .param("id", nag.id())
        .param("userId", nag.userId())
        .param("date", nag.localDate().toString())
        .param("level", nag.thresholdLevel())
        .param("trigger", nag.trigger().name())
        .param("triggeredBy", nag.triggeredByUserId())
        .param("idleMinutes", Math.min(nag.idleMinutes(), Integer.MAX_VALUE))
        .param("pendingCount", nag.pendingCount())
        .param("message", nag.message())
        .param("title", nag.title())
        .param("requireReason", nag.requireReason() ? 1 : 0)
        .param("status", nag.status().name())
        .param("supervisor", nag.supervisorUserIdSnapshot())
        .param("createdAt", nag.createdAt().toEpochMilli())
        .update();
  }

  /** 由投递或待回应查询的短事务调用；保留旧记录的渠道状态以继续外部推送。 */
  @Override
  public void supersedeOlderAppNags(String userId) {
    jdbcClient
        .sql(
            """
            UPDATE nags AS older SET app_superseded = 1
             WHERE older.user_id = :userId AND older.app_superseded = 0 AND older.status IN ('PENDING', 'DELIVERED')
               AND EXISTS (
                 SELECT 1 FROM nags AS newer
                  WHERE newer.user_id = older.user_id AND newer.local_date = older.local_date
                    AND newer.status IN ('DELIVERED', 'RESPONDED')
                    AND (newer.created_at > older.created_at
                         OR (newer.created_at = older.created_at AND newer.id > older.id))
               )
            """)
        .param("userId", userId)
        .update();
  }

  @Override
  public void markDelivered(String nagId, Instant deliveredAt) {
    jdbcClient
        .sql(
            """
            UPDATE nags SET status = 'DELIVERED', delivered_at = :at
             WHERE id = :id AND status = 'PENDING'
            """)
        .param("at", deliveredAt.toEpochMilli())
        .param("id", nagId)
        .update();
  }

  @Override
  public void markResponded(
      String nagId, String reasonTag, String reasonText, Instant respondedAt) {
    jdbcClient
        .sql(
            """
            UPDATE nags
               SET status = 'RESPONDED',
                   responded_at = :at,
                   reason_tag = :reasonTag,
                   reason_text = :reasonText
             WHERE id = :id
            """)
        .param("at", respondedAt.toEpochMilli())
        .param("reasonTag", reasonTag)
        .param("reasonText", reasonText)
        .param("id", nagId)
        .update();
  }

  @Override
  public void expireBefore(String userId, LocalDate today) {
    jdbcClient
        .sql(
            "UPDATE nags SET status = 'EXPIRED' WHERE user_id = :userId AND local_date < :today AND status IN ('PENDING', 'DELIVERED')")
        .param("userId", userId)
        .param("today", today.toString())
        .update();
  }

  @Override
  public void markExpired(String nagId) {
    jdbcClient
        .sql(
            "UPDATE nags SET status = 'EXPIRED' WHERE id = :id AND status IN ('PENDING', 'DELIVERED')")
        .param("id", nagId)
        .update();
  }

  /** 条件更新兜底状态竞争，不覆盖已经结束或成功投递的记录。 */
  @Override
  public boolean cancelFailed(String nagId) {
    return jdbcClient
            .sql(
                """
        UPDATE nags SET status = 'CANCELLED'
         WHERE id = :id AND status = 'PENDING'
           AND EXISTS (SELECT 1 FROM nag_deliveries WHERE nag_id = :id)
           AND NOT EXISTS (SELECT 1 FROM nag_deliveries WHERE nag_id = :id AND status <> 'FAILED')
        """)
            .param("id", nagId)
            .update()
        == 1;
  }

  /** JSON 数组只保存管理员动作，SQLite 参数绑定避免内容进入 SQL。 */
  @Override
  public void recordAdminAction(String nagId, String action, String actor, Instant now) {
    jdbcClient
        .sql(
            """
        UPDATE nags SET admin_actions = json_insert(admin_actions, '$[#]',
            json_object('action', :action, 'actor', :actor, 'createdAt', :at)) WHERE id = :id
        """)
        .param("id", nagId)
        .param("action", action)
        .param("actor", actor)
        .param("at", now.toEpochMilli())
        .update();
  }

  /** 操作历史与催办同生命周期，无额外孤儿表。 */
  @Override
  public List<AdminAction> adminActionsOfAll(List<String> nagIds) {
    if (nagIds.isEmpty()) return List.of();
    return jdbcClient
        .sql(
            """
        SELECT n.id, json_extract(a.value, '$.action') AS action,
               json_extract(a.value, '$.actor') AS actor,
               json_extract(a.value, '$.createdAt') AS created_at
          FROM nags n, json_each(n.admin_actions) a
         WHERE n.id IN (:ids) ORDER BY n.id, a.key
        """)
        .param("ids", nagIds)
        .query(
            (row, index) ->
                new AdminAction(
                    row.getString("id"),
                    row.getString("action"),
                    row.getString("actor"),
                    Instant.ofEpochMilli(row.getLong("created_at"))))
        .list();
  }

  @Override
  public void insertDelivery(
      String id, String nagId, NagChannelType channel, String status, String detail, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO nag_deliveries (id, nag_id, channel, status, detail, created_at)
            VALUES (:id, :nagId, :channel, :status, :detail, :now)
            """)
        .param("id", id)
        .param("nagId", nagId)
        .param("channel", channel.name())
        .param("status", status)
        .param("detail", detail == null ? "" : detail)
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public List<Delivery> deliveriesOf(String nagId) {
    return jdbcClient
        .sql(SELECT_DELIVERY + " WHERE nag_id = :nagId ORDER BY created_at, rowid")
        .param("nagId", nagId)
        .query(this::mapDelivery)
        .list();
  }

  @Override
  public List<Delivery> deliveriesOfAll(List<String> nagIds) {
    if (nagIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(SELECT_DELIVERY + " WHERE nag_id IN (:ids) ORDER BY nag_id, created_at, rowid")
        .param("ids", nagIds)
        .query(this::mapDelivery)
        .list();
  }

  @Override
  public boolean deliveredVia(String nagId, NagChannelType channel) {
    Long count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM nag_deliveries
                 WHERE nag_id = :nagId AND channel = :channel AND status <> 'FAILED'
                """)
            .param("nagId", nagId)
            .param("channel", channel.name())
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  @Override
  public List<StatusCount> countByStatus() {
    return jdbcClient
        .sql("SELECT status, count(*) AS status_count FROM nags GROUP BY status")
        .query(
            (row, rowNumber) ->
                new StatusCount(
                    NagStatus.valueOf(row.getString("status")), row.getInt("status_count")))
        .list();
  }

  private Nag map(ResultSet row, int rowNumber) throws SQLException {
    return new Nag(
        row.getString("id"),
        row.getString("user_id"),
        LocalDate.parse(row.getString("local_date")),
        row.getInt("threshold_level"),
        NagTrigger.valueOf(row.getString("trigger_source")),
        row.getString("triggered_by_user_id"),
        row.getLong("idle_minutes"),
        row.getInt("pending_count"),
        row.getString("message"),
        row.getInt("require_reason") == 1,
        NagStatus.valueOf(row.getString("status")),
        nullableInstant(row, "delivered_at"),
        nullableInstant(row, "responded_at"),
        row.getString("reason_tag"),
        row.getString("reason_text"),
        row.getString("supervisor_user_id_snapshot"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        row.getString("title"),
        row.getInt("app_superseded") == 1);
  }

  private Delivery mapDelivery(ResultSet row, int rowNumber) throws SQLException {
    return new Delivery(
        row.getString("id"),
        row.getString("nag_id"),
        NagChannelType.valueOf(row.getString("channel")),
        row.getString("status"),
        row.getString("detail"),
        Instant.ofEpochMilli(row.getLong("created_at")));
  }

  private Instant nullableInstant(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : Instant.ofEpochMilli(value);
  }
}
