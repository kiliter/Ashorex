package com.shangan.nag.infrastructure;

import com.shangan.nag.domain.EffectiveNagPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 催办策略的 SQLite 实现。 */
@Repository
public class JdbcNagPolicyRepository implements NagPolicyRepository {

  private static final String SELECT =
      """
      SELECT id, scope, user_id, scan_interval_minutes, presence_grace_seconds,
             heartbeat_interval_seconds, first_threshold_minutes, repeat_interval_minutes,
             daily_max, fullscreen_timeout_minutes, quiet_start, quiet_end,
             min_pending, min_reason_length, channel_fullscreen_enabled,
             channel_serverchan_enabled, message_template,
             notify_supervisor_on_bulk_delete, notify_supervisor_on_gave_up,
             notify_supervisor_on_half_done_delete
        FROM nag_policies
      """;

  private final JdbcClient jdbcClient;

  public JdbcNagPolicyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 只读取全局行，旧数据库由追加迁移补齐 SSE 默认值。 */
  @Override
  public Optional<com.shangan.nag.domain.NagTransportMode> findTransportMode() {
    return jdbcClient
        .sql("SELECT transport_mode FROM nag_policies WHERE scope = 'GLOBAL'")
        .query(String.class)
        .optional()
        .map(com.shangan.nag.domain.NagTransportMode::valueOf);
  }

  @Override
  public void saveTransportMode(com.shangan.nag.domain.NagTransportMode mode, Instant now) {
    jdbcClient
        .sql(
            "UPDATE nag_policies SET transport_mode = :mode, updated_at = :now WHERE scope = 'GLOBAL'")
        .param("mode", mode.name())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public Optional<StoredPolicy> findGlobal() {
    return jdbcClient.sql(SELECT + " WHERE scope = 'GLOBAL'").query(this::map).optional();
  }

  @Override
  public Optional<StoredPolicy> findByUser(String userId) {
    return jdbcClient
        .sql(SELECT + " WHERE scope = 'USER' AND user_id = :userId")
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<StoredPolicy> findAllUserOverrides() {
    return jdbcClient
        .sql(SELECT + " WHERE scope = 'USER' ORDER BY user_id")
        .query(this::map)
        .list();
  }

  @Override
  public void saveGlobal(EffectiveNagPolicy policy, Instant now) {
    jdbcClient
        .sql(updateSql() + " WHERE scope = 'GLOBAL'")
        .paramSource(params(policy, now))
        .update();
  }

  @Override
  public void saveUserOverride(String userId, EffectiveNagPolicy policy, Instant now) {
    int updated =
        jdbcClient
            .sql(updateSql() + " WHERE scope = 'USER' AND user_id = :userId")
            .paramSource(params(policy, now))
            .param("userId", userId)
            .update();
    if (updated == 0) {
      jdbcClient
          .sql(
              """
              INSERT INTO nag_policies (
                  id, scope, user_id, scan_interval_minutes, presence_grace_seconds,
                  heartbeat_interval_seconds, first_threshold_minutes, repeat_interval_minutes,
                  daily_max, fullscreen_timeout_minutes, quiet_start, quiet_end,
                  min_pending, min_reason_length, channel_fullscreen_enabled,
                  channel_serverchan_enabled, message_template,
                  notify_supervisor_on_bulk_delete, notify_supervisor_on_gave_up,
                  notify_supervisor_on_half_done_delete, updated_at
              ) VALUES (
                  :id, 'USER', :userId, :scanInterval, :presenceGrace,
                  :heartbeatInterval, :firstThreshold, :repeatInterval,
                  :dailyMax, :fullscreenTimeout, :quietStart, :quietEnd,
                  :minPending, :minReasonLength, :channelFullscreen,
                  :channelServerchan, :messageTemplate,
                  :notifyBulk, :notifyGaveUp, :notifyHalfDone, :now
              )
              """)
          .paramSource(params(policy, now))
          .param("id", "nag-policy-" + userId)
          .param("userId", userId)
          .update();
    }
  }

  @Override
  public void deleteUserOverride(String userId) {
    jdbcClient
        .sql("DELETE FROM nag_policies WHERE scope = 'USER' AND user_id = :userId")
        .param("userId", userId)
        .update();
  }

  private String updateSql() {
    return """
        UPDATE nag_policies SET
            scan_interval_minutes = :scanInterval,
            presence_grace_seconds = :presenceGrace,
            heartbeat_interval_seconds = :heartbeatInterval,
            first_threshold_minutes = :firstThreshold,
            repeat_interval_minutes = :repeatInterval,
            daily_max = :dailyMax,
            fullscreen_timeout_minutes = :fullscreenTimeout,
            quiet_start = :quietStart,
            quiet_end = :quietEnd,
            min_pending = :minPending,
            min_reason_length = :minReasonLength,
            channel_fullscreen_enabled = :channelFullscreen,
            channel_serverchan_enabled = :channelServerchan,
            message_template = :messageTemplate,
            notify_supervisor_on_bulk_delete = :notifyBulk,
            notify_supervisor_on_gave_up = :notifyGaveUp,
            notify_supervisor_on_half_done_delete = :notifyHalfDone,
            updated_at = :now
        """;
  }

  private org.springframework.jdbc.core.namedparam.MapSqlParameterSource params(
      EffectiveNagPolicy policy, Instant now) {
    return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
        .addValue("scanInterval", policy.scanIntervalMinutes())
        .addValue("presenceGrace", policy.presenceGraceSeconds())
        .addValue("heartbeatInterval", policy.heartbeatIntervalSeconds())
        .addValue("firstThreshold", policy.firstThresholdMinutes())
        .addValue("repeatInterval", policy.repeatIntervalMinutes())
        .addValue("dailyMax", policy.dailyMax())
        .addValue("fullscreenTimeout", policy.fullscreenTimeoutMinutes())
        .addValue("quietStart", policy.quietStart().toString())
        .addValue("quietEnd", policy.quietEnd().toString())
        .addValue("minPending", policy.minPending())
        .addValue("minReasonLength", policy.minReasonLength())
        .addValue("channelFullscreen", policy.channelFullscreenEnabled() ? 1 : 0)
        .addValue("channelServerchan", policy.channelServerchanEnabled() ? 1 : 0)
        .addValue("messageTemplate", policy.messageTemplate())
        .addValue("notifyBulk", policy.notifySupervisorOnBulkDelete() ? 1 : 0)
        .addValue("notifyGaveUp", policy.notifySupervisorOnGaveUp() ? 1 : 0)
        .addValue("notifyHalfDone", policy.notifySupervisorOnHalfDoneDelete() ? 1 : 0)
        .addValue("now", now.toEpochMilli());
  }

  private StoredPolicy map(ResultSet row, int rowNumber) throws SQLException {
    EffectiveNagPolicy policy =
        new EffectiveNagPolicy(
            row.getInt("scan_interval_minutes"),
            row.getInt("presence_grace_seconds"),
            row.getInt("heartbeat_interval_seconds"),
            row.getInt("first_threshold_minutes"),
            row.getInt("repeat_interval_minutes"),
            row.getInt("daily_max"),
            row.getInt("fullscreen_timeout_minutes"),
            LocalTime.parse(row.getString("quiet_start")),
            LocalTime.parse(row.getString("quiet_end")),
            row.getInt("min_pending"),
            row.getInt("min_reason_length"),
            row.getInt("channel_fullscreen_enabled") == 1,
            row.getInt("channel_serverchan_enabled") == 1,
            row.getString("message_template"),
            row.getInt("notify_supervisor_on_bulk_delete") == 1,
            row.getInt("notify_supervisor_on_gave_up") == 1,
            row.getInt("notify_supervisor_on_half_done_delete") == 1);
    return new StoredPolicy(
        row.getString("id"), row.getString("scope"), row.getString("user_id"), policy);
  }
}
