package com.shangan.focus.infrastructure;

import com.shangan.focus.domain.FocusSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 SQLite 部分唯一索引保证每个用户最多一个活动专注会话。 */
@Repository
public class JdbcFocusSessionRepository implements FocusSessionRepository {
  private final JdbcClient jdbc;

  public JdbcFocusSessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<FocusSession> findOwned(String userId, String sessionId) {
    return jdbc.sql("select * from focus_sessions where id=:id and user_id=:userId")
        .param("id", sessionId)
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<FocusSession> findActive(String userId) {
    return jdbc.sql(
            "select * from focus_sessions where user_id=:userId and status in ('RUNNING','PAUSED')")
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<FocusSession> findActiveByPlan(String userId, String planId) {
    return jdbc.sql(
            """
            select f.* from focus_sessions f
            join daily_plan_items i on i.id=f.plan_item_id
            where f.user_id=:userId and i.plan_id=:planId and f.status in ('RUNNING','PAUSED')
            """)
        .param("userId", userId)
        .param("planId", planId)
        .query(this::map)
        .list();
  }

  @Override
  public void insert(FocusSession session, Instant now) {
    jdbc.sql(
            """
            insert into focus_sessions (
              id,user_id,plan_item_id,media_item_id,focus_type,status,
              planned_seconds,actual_seconds,started_at,running_since,
              paused_at,ended_at,created_at,updated_at
            ) values (
              :id,:userId,:planItemId,:mediaItemId,:focusType,:status,
              :planned,:actual,:startedAt,:runningSince,:pausedAt,:endedAt,:now,:now
            )
            """)
        .param("id", session.id())
        .param("userId", session.userId())
        .param("planItemId", session.planItemId())
        .param("mediaItemId", session.mediaItemId())
        .param("focusType", session.focusType())
        .param("status", session.status())
        .param("planned", session.plannedSeconds())
        .param("actual", session.actualSeconds())
        .param("startedAt", session.startedAt().toEpochMilli())
        .param("runningSince", epoch(session.runningSince()))
        .param("pausedAt", epoch(session.pausedAt()))
        .param("endedAt", epoch(session.endedAt()))
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void update(FocusSession session, Instant now) {
    jdbc.sql(
            """
            update focus_sessions set status=:status,actual_seconds=:actual,
              running_since=:runningSince,paused_at=:pausedAt,ended_at=:endedAt,updated_at=:now
            where id=:id
            """)
        .param("status", session.status())
        .param("actual", session.actualSeconds())
        .param("runningSince", epoch(session.runningSince()))
        .param("pausedAt", epoch(session.pausedAt()))
        .param("endedAt", epoch(session.endedAt()))
        .param("now", now.toEpochMilli())
        .param("id", session.id())
        .update();
  }

  private FocusSession map(ResultSet rs, int row) throws SQLException {
    return FocusSession.restore(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("plan_item_id"),
        rs.getString("media_item_id"),
        rs.getString("focus_type"),
        rs.getString("status"),
        rs.getLong("planned_seconds"),
        rs.getLong("actual_seconds"),
        Instant.ofEpochMilli(rs.getLong("started_at")),
        instant(rs, "running_since"),
        instant(rs, "paused_at"),
        instant(rs, "ended_at"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column) == null ? null : Instant.ofEpochMilli(rs.getLong(column));
  }

  private Long epoch(Instant value) {
    return value == null ? null : value.toEpochMilli();
  }
}
