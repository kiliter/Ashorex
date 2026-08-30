package com.shangan.learning.infrastructure;

import com.shangan.exam.application.ExamLearningProgressPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 幂等合并绝对最大位置，并为考试目标提供只读的可信完成课时数。 */
@Repository
public class JdbcVideoProgressRepository
    implements VideoProgressRepository, ExamLearningProgressPort {
  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcVideoProgressRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Optional<Progress> find(String userId, String mediaItemId) {
    return jdbc.sql(
            "select * from video_progress where user_id=:userId and media_item_id=:mediaItemId")
        .param("userId", userId)
        .param("mediaItemId", mediaItemId)
        .query(this::map)
        .optional();
  }

  @Override
  public Progress synchronize(
      String id,
      String userId,
      String mediaItemId,
      long absoluteMaximumMs,
      long verifiedWatchDeltaMs,
      boolean completed,
      Instant now) {
    jdbc.sql(
            """
            insert into video_progress (
              id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,
              completed_at,last_watched_at,created_at,updated_at
            ) values (
              :id,:userId,:mediaItemId,:maximum,:watchDelta,:completedAt,:now,:now,:now
            ) on conflict(user_id,media_item_id) do update set
              max_verified_position_ms=max(video_progress.max_verified_position_ms,excluded.max_verified_position_ms),
              verified_watch_ms=video_progress.verified_watch_ms + excluded.verified_watch_ms,
              completed_at=case
                when video_progress.completed_at is not null then video_progress.completed_at
                else excluded.completed_at
              end,
              last_watched_at=:now,
              updated_at=:now
            """)
        .param("id", id)
        .param("userId", userId)
        .param("mediaItemId", mediaItemId)
        .param("maximum", Math.max(0, absoluteMaximumMs))
        .param("watchDelta", Math.max(0, verifiedWatchDeltaMs))
        .param("completedAt", completed ? now.toEpochMilli() : null)
        .param("now", now.toEpochMilli())
        .update();
    return find(userId, mediaItemId).orElseThrow();
  }

  @Override
  public Completion completionFor(String userId, List<String> courseIds) {
    if (courseIds.isEmpty()) return new Completion(0, 0);
    Instant sevenDaysAgo = clock.instant().minus(Duration.ofDays(7));
    Counts counts =
        jdbc.sql(
                """
                select count(*) as completed,
                  coalesce(sum(case when vp.completed_at >= :sevenDaysAgo then 1 else 0 end),0) as recent
                from video_progress vp
                join media_items m on m.id=vp.media_item_id
                where vp.user_id=:userId and vp.completed_at is not null and m.course_id in (:courseIds)
                """)
            .param("sevenDaysAgo", sevenDaysAgo.toEpochMilli())
            .param("userId", userId)
            .param("courseIds", courseIds)
            .query((rs, row) -> new Counts(rs.getInt("completed"), rs.getInt("recent")))
            .single();
    return new Completion(counts.completed(), counts.recent());
  }

  private Progress map(ResultSet rs, int row) throws SQLException {
    return new Progress(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("media_item_id"),
        rs.getLong("max_verified_position_ms"),
        rs.getLong("verified_watch_ms"),
        rs.getObject("completed_at") == null
            ? null
            : Instant.ofEpochMilli(rs.getLong("completed_at")));
  }

  private record Counts(int completed, int recent) {}
}
