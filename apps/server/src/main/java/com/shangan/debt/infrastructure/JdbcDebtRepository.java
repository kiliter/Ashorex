package com.shangan.debt.infrastructure;

import com.shangan.debt.domain.LearningDebt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用唯一约束保证同一原任务组成部分只生成一笔欠债。 */
@Repository
public class JdbcDebtRepository implements DebtRepository {
  private final JdbcClient jdbc;

  public JdbcDebtRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<LearningDebt> findOpenByUser(String userId) {
    return jdbc.sql(
            "select * from learning_debts where user_id=:userId "
                + "and status in ('OPEN','PARTIALLY_REPAID') order by opened_on, created_at")
        .param("userId", userId)
        .query(this::map)
        .list();
  }

  @Override
  public Optional<LearningDebt> findOwned(String userId, String debtId) {
    return jdbc.sql("select * from learning_debts where id=:id and user_id=:userId")
        .param("id", debtId)
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<LearningDebt> findOpenVideoByMedia(String userId, String mediaItemId) {
    return jdbc.sql(
            "select * from learning_debts where user_id=:userId and media_item_id=:mediaItemId "
                + "and debt_type='VIDEO_WATCH' and status in ('OPEN','PARTIALLY_REPAID')")
        .param("userId", userId)
        .param("mediaItemId", mediaItemId)
        .query(this::map)
        .list();
  }

  @Override
  public List<LearningDebt> findOpenQuizByMedia(String userId, String mediaItemId) {
    return jdbc.sql(
            "select * from learning_debts where user_id=:userId and media_item_id=:mediaItemId "
                + "and debt_type='QUIZ' and status in ('OPEN','PARTIALLY_REPAID')")
        .param("userId", userId)
        .param("mediaItemId", mediaItemId)
        .query(this::map)
        .list();
  }

  @Override
  public void insertIfAbsent(LearningDebt debt, Instant now) {
    jdbc.sql(
            """
            insert into learning_debts (
              id, user_id, source_plan_item_id, debt_type, media_item_id, title,
              original_seconds, remaining_seconds, baseline_completed_seconds,
              status, reason, opened_on, created_at, updated_at
            ) values (
              :id, :userId, :itemId, :type, :mediaId, :title,
              :original, :remaining, :baseline, 'OPEN', :reason, :openedOn, :now, :now
            ) on conflict(source_plan_item_id, debt_type) do nothing
            """)
        .param("id", debt.id())
        .param("userId", debt.userId())
        .param("itemId", debt.sourcePlanItemId())
        .param("type", debt.debtType())
        .param("mediaId", debt.mediaItemId())
        .param("title", debt.title())
        .param("original", debt.originalSeconds())
        .param("remaining", debt.remainingSeconds())
        .param("baseline", debt.baselineCompletedSeconds())
        .param("reason", debt.reason())
        .param("openedOn", debt.openedOn().toString())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public long sumOpenSeconds(String userId) {
    return jdbc.sql(
            "select coalesce(sum(remaining_seconds),0) from learning_debts "
                + "where user_id=:userId and status in ('OPEN','PARTIALLY_REPAID')")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  @Override
  public long repay(
      String repaymentId,
      String userId,
      String debtId,
      String planItemId,
      long seconds,
      String source,
      Instant now) {
    LearningDebt debt = findOwned(userId, debtId).orElseThrow();
    long applied = Math.min(Math.max(0, seconds), debt.remainingSeconds());
    if (applied == 0) return 0;
    long remaining = debt.remainingSeconds() - applied;
    jdbc.sql(
            "update learning_debts set remaining_seconds=:remaining, status=:status, "
                + "paid_at=:paidAt, updated_at=:now where id=:id")
        .param("remaining", remaining)
        .param("status", remaining == 0 ? "PAID" : "PARTIALLY_REPAID")
        .param("paidAt", remaining == 0 ? now.toEpochMilli() : null)
        .param("now", now.toEpochMilli())
        .param("id", debtId)
        .update();
    jdbc.sql(
            "insert into debt_repayments (id, debt_id, plan_item_id, repaid_seconds, "
                + "repayment_source, created_at) values (:id,:debtId,:itemId,:seconds,:source,:now)")
        .param("id", repaymentId)
        .param("debtId", debtId)
        .param("itemId", planItemId)
        .param("seconds", applied)
        .param("source", source)
        .param("now", now.toEpochMilli())
        .update();
    return applied;
  }

  private LearningDebt map(ResultSet rs, int row) throws SQLException {
    return new LearningDebt(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("source_plan_item_id"),
        rs.getString("debt_type"),
        rs.getString("media_item_id"),
        rs.getString("title"),
        rs.getLong("original_seconds"),
        rs.getLong("remaining_seconds"),
        rs.getLong("baseline_completed_seconds"),
        rs.getString("status"),
        rs.getString("reason"),
        LocalDate.parse(rs.getString("opened_on")),
        rs.getObject("paid_at") == null ? null : Instant.ofEpochMilli(rs.getLong("paid_at")));
  }
}
