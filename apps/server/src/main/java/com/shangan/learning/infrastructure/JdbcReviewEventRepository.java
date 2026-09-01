package com.shangan.learning.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 以观看会话唯一约束保证一次复习会话只生成一条审计事件。 */
@Repository
public class JdbcReviewEventRepository implements ReviewEventRepository {
  private final JdbcClient jdbc;

  public JdbcReviewEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertIfAbsent(
      String id,
      String userId,
      String mediaItemId,
      String watchSessionId,
      LocalDate reviewedOn,
      Instant createdAt) {
    jdbc.sql(
            """
            insert into lesson_review_events (
              id,user_id,media_item_id,watch_session_id,reviewed_on,created_at
            ) values (:id,:userId,:mediaId,:sessionId,:reviewedOn,:now)
            on conflict(watch_session_id) do nothing
            """)
        .param("id", id)
        .param("userId", userId)
        .param("mediaId", mediaItemId)
        .param("sessionId", watchSessionId)
        .param("reviewedOn", reviewedOn.toString())
        .param("now", createdAt.toEpochMilli())
        .update();
  }
}
