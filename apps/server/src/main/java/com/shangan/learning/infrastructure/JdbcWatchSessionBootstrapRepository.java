package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 保存播放选择和会话身份，代理请求据此限制媒体路径。 */
@Repository
public class JdbcWatchSessionBootstrapRepository implements WatchSessionBootstrapRepository {
  private final JdbcClient jdbc;

  public JdbcWatchSessionBootstrapRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SessionPlayback value, Instant now) {
    jdbc.sql(
            """
            insert into watch_sessions (
              id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,
              play_session_id,upstream_path,hls,duration_ms,started_position_ms,
              last_reported_position_ms,max_verified_position_ms,verified_watch_ms,
              last_sequence,last_heartbeat_at,alive_check_due_watch_ms,alive_check_pending,
              started_at,created_at,updated_at
            ) values (
              :id,:userId,:mediaId,:embyId,:planItemId,:deviceId,'ACTIVE',
              :playSessionId,:path,:hls,:duration,:startedPosition,:startedPosition,
              :startedPosition,0,0,:now,:aliveDue,0,:now,:now,:now
            )
            """)
        .param("id", value.id())
        .param("userId", value.userId())
        .param("mediaId", value.mediaItemId())
        .param("embyId", value.embyItemId())
        .param("planItemId", value.planItemId())
        .param("deviceId", value.deviceId())
        .param("playSessionId", value.playSessionId())
        .param("path", value.upstreamPath())
        .param("hls", value.hls() ? 1 : 0)
        .param("duration", value.durationMs())
        .param("startedPosition", value.startedPositionMs())
        .param("aliveDue", value.aliveCheckDueWatchMs())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void stopOpenForUser(String userId, Instant now) {
    jdbc.sql(
            "update watch_sessions set status='STOPPED', ended_at=:now, updated_at=:now "
                + "where user_id=:userId and status in ('ACTIVE','PAUSED')")
        .param("now", now.toEpochMilli())
        .param("userId", userId)
        .update();
  }

  @Override
  public Optional<SessionPlayback> find(String sessionId) {
    return jdbc.sql("select * from watch_sessions where id=:id and status in ('ACTIVE','PAUSED')")
        .param("id", sessionId)
        .query(
            (rs, row) ->
                new SessionPlayback(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("media_item_id"),
                    rs.getString("emby_item_id"),
                    rs.getString("plan_item_id"),
                    rs.getString("device_id"),
                    rs.getString("play_session_id"),
                    rs.getString("upstream_path"),
                    rs.getInt("hls") == 1,
                    rs.getLong("duration_ms"),
                    rs.getLong("started_position_ms"),
                    rs.getObject("alive_check_due_watch_ms") == null
                        ? null
                        : rs.getLong("alive_check_due_watch_ms")))
        .optional();
  }
}
