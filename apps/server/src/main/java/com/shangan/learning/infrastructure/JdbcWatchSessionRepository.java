package com.shangan.learning.infrastructure;

import com.shangan.learning.domain.WatchProgressPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用单行更新保存心跳聚合状态，并持久化验活阈值和结果。 */
@Repository
public class JdbcWatchSessionRepository implements WatchSessionRepository {
  private final JdbcClient jdbc;

  public JdbcWatchSessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Session> findOwned(String userId, String sessionId) {
    return jdbc.sql("select * from watch_sessions where id=:id and user_id=:userId")
        .param("id", sessionId)
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<Session> findOpenForPlan(String userId, String planId) {
    return jdbc.sql(
            "select * from watch_sessions where user_id=:userId and plan_item_id in "
                + "(select id from daily_plan_items where plan_id=:planId) "
                + "and status in ('ACTIVE','PAUSED')")
        .param("userId", userId)
        .param("planId", planId)
        .query(this::map)
        .list();
  }

  @Override
  public boolean updateHeartbeat(
      String sessionId,
      WatchProgressPolicy.Decision decision,
      boolean aliveCheckPending,
      String status,
      Instant now) {
    return jdbc.sql(
                """
            update watch_sessions set
              last_reported_position_ms=:lastPosition,
              max_verified_position_ms=:maximum,
              verified_watch_ms=:verifiedWatch,
              last_sequence=:sequence,
              last_heartbeat_at=:heartbeatAt,
              alive_check_pending=:pending,
              status=:status,
              ended_at=case when :status='COMPLETED' then :now else ended_at end,
              updated_at=:now
            where id=:id and last_sequence < :sequence and status in ('ACTIVE','PAUSED')
            """)
            .param("lastPosition", decision.lastReportedPositionMs())
            .param("maximum", decision.maxVerifiedPositionMs())
            .param("verifiedWatch", decision.verifiedWatchMs())
            .param("sequence", decision.lastSequence())
            .param("heartbeatAt", decision.lastHeartbeatAt().toEpochMilli())
            .param("pending", aliveCheckPending ? 1 : 0)
            .param("status", status)
            .param("now", now.toEpochMilli())
            .param("id", sessionId)
            .update()
        > 0;
  }

  @Override
  public void markSynced(String sessionId, long syncedVerifiedWatchMs, Instant now) {
    jdbc.sql(
            "update watch_sessions set synced_verified_watch_ms=:synced, updated_at=:now where id=:id")
        .param("synced", syncedVerifiedWatchMs)
        .param("now", now.toEpochMilli())
        .param("id", sessionId)
        .update();
  }

  @Override
  public void stop(String sessionId, String status, Instant endedAt) {
    jdbc.sql(
            "update watch_sessions set status=:status, ended_at=:endedAt, updated_at=:endedAt "
                + "where id=:id and status in ('ACTIVE','PAUSED')")
        .param("status", status)
        .param("endedAt", endedAt.toEpochMilli())
        .param("id", sessionId)
        .update();
  }

  @Override
  public void setAliveState(
      String sessionId, boolean pending, Long nextDueWatchMs, String status, Instant now) {
    jdbc.sql(
            "update watch_sessions set alive_check_pending=:pending, "
                + "alive_check_due_watch_ms=:due, status=:status, updated_at=:now where id=:id")
        .param("pending", pending ? 1 : 0)
        .param("due", nextDueWatchMs)
        .param("status", status)
        .param("now", now.toEpochMilli())
        .param("id", sessionId)
        .update();
  }

  @Override
  public void insertAliveCheck(String id, String sessionId, Instant requiredAt) {
    jdbc.sql(
            "insert into alive_checks (id,watch_session_id,required_at,created_at) "
                + "values (:id,:sessionId,:requiredAt,:requiredAt)")
        .param("id", id)
        .param("sessionId", sessionId)
        .param("requiredAt", requiredAt.toEpochMilli())
        .update();
  }

  @Override
  public Optional<AliveCheck> findUnansweredAliveCheck(String sessionId) {
    return jdbc.sql(
            "select id,required_at from alive_checks where watch_session_id=:sessionId "
                + "and responded_at is null order by required_at desc limit 1")
        .param("sessionId", sessionId)
        .query(
            (rs, row) ->
                new AliveCheck(rs.getString("id"), Instant.ofEpochMilli(rs.getLong("required_at"))))
        .optional();
  }

  @Override
  public void answerAliveCheck(String id, String status, Instant respondedAt) {
    jdbc.sql(
            "update alive_checks set status=:status, responded_at=:respondedAt "
                + "where id=:id and responded_at is null")
        .param("status", status)
        .param("respondedAt", respondedAt.toEpochMilli())
        .param("id", id)
        .update();
  }

  private Session map(ResultSet rs, int row) throws SQLException {
    return new Session(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("media_item_id"),
        rs.getString("plan_item_id"),
        rs.getString("status"),
        rs.getLong("duration_ms"),
        rs.getLong("last_reported_position_ms"),
        rs.getLong("max_verified_position_ms"),
        rs.getLong("verified_watch_ms"),
        rs.getLong("synced_verified_watch_ms"),
        rs.getLong("last_sequence"),
        Instant.ofEpochMilli(rs.getLong("last_heartbeat_at")),
        rs.getObject("alive_check_due_watch_ms") == null
            ? null
            : rs.getLong("alive_check_due_watch_ms"),
        rs.getInt("alive_check_pending") == 1);
  }
}
