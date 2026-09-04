package com.shangan.planning.infrastructure;

import com.shangan.planning.domain.DailyPlan;
import com.shangan.planning.domain.PlanItem;
import com.shangan.planning.domain.PlanStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用显式 SQL 保存计划及任务进度。 */
@Repository
public class JdbcPlanningRepository implements PlanningRepository {
  private final JdbcClient jdbc;

  public JdbcPlanningRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<DailyPlan> findPlan(String userId, LocalDate date) {
    return jdbc.sql("select * from daily_plans where user_id = :userId and plan_date = :date")
        .params(Map.of("userId", userId, "date", date.toString()))
        .query((rs, row) -> mapPlan(rs))
        .optional();
  }

  @Override
  public Optional<DailyPlan> findOwnedPlan(String userId, String planId) {
    return jdbc.sql("select * from daily_plans where id = :id and user_id = :userId")
        .params(Map.of("id", planId, "userId", userId))
        .query((rs, row) -> mapPlan(rs))
        .optional();
  }

  @Override
  public List<PlanItem> findItems(String planId) {
    return jdbc.sql("select * from daily_plan_items where plan_id = :id order by sort_order, id")
        .param("id", planId)
        .query(this::mapItem)
        .list();
  }

  @Override
  public Optional<PlanItem> findOwnedItem(String userId, String itemId) {
    return jdbc.sql(
            "select i.* from daily_plan_items i join daily_plans p on p.id = i.plan_id "
                + "where i.id = :itemId and p.user_id = :userId")
        .params(Map.of("itemId", itemId, "userId", userId))
        .query(this::mapItem)
        .optional();
  }

  @Override
  public boolean isActiveBattleOrder(String planId) {
    return jdbc.sql("select count(*) from daily_plans where id=:id and lifecycle_status='ACTIVE'")
            .param("id", planId)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public void updatePlanState(DailyPlan plan, Instant now) {
    jdbc.sql(
            "update daily_plans set status=:status, "
                + "lifecycle_status=case when :status in "
                + "('COMPLETED','CLOSED_WITH_DEBT','ABANDONED') "
                + "then :status else lifecycle_status end, "
                + "locked_at=:lockedAt, closed_at=:closedAt, updated_at=:now where id=:id")
        .param("status", plan.status().name())
        .param("lockedAt", epoch(plan.lockedAt()))
        .param("closedAt", epoch(plan.closedAt()))
        .param("now", now.toEpochMilli())
        .param("id", plan.id())
        .update();
  }

  @Override
  public List<DuePlan> findLockedPlans() {
    return jdbc.sql(
            "select p.id, p.user_id, p.plan_date, u.timezone, u.day_end_local_time "
                + "from daily_plans p join users u on u.id=p.user_id where p.status='LOCKED'")
        .query(
            (rs, row) ->
                new DuePlan(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    LocalDate.parse(rs.getString("plan_date")),
                    rs.getString("timezone"),
                    rs.getString("day_end_local_time")))
        .list();
  }

  @Override
  public ProgressUpdate updateAbsoluteProgress(
      String userId, String itemId, long absolute, Instant now) {
    PlanItem before = findOwnedItem(userId, itemId).orElseThrow();
    long next = Math.min(before.plannedSeconds(), Math.max(before.completedSeconds(), absolute));
    boolean watchComplete =
        before.watchCompleted()
            || (before.itemType().equals("VIDEO") && next >= before.plannedSeconds());
    jdbc.sql(
            "update daily_plan_items set completed_seconds=:completed, watch_completed=:watched, "
                + "updated_at=:now where id=:id")
        .params(
            Map.of(
                "completed",
                next,
                "watched",
                watchComplete ? 1 : 0,
                "now",
                now.toEpochMilli(),
                "id",
                itemId))
        .update();
    markItemCompletedIfSatisfied(itemId, now);
    PlanItem after = findOwnedItem(userId, itemId).orElseThrow();
    return new ProgressUpdate(before, after, next - before.completedSeconds());
  }

  @Override
  public ProgressUpdate updateVideoWatchProgress(
      String userId, String itemId, long absolute, boolean watchCompleted, Instant now) {
    PlanItem before = findOwnedItem(userId, itemId).orElseThrow();
    long next = Math.min(before.plannedSeconds(), Math.max(before.completedSeconds(), absolute));
    if (before.itemType().equals("VIDEO")) {
      boolean watched = before.watchCompleted() || watchCompleted;
      jdbc.sql(
              "update daily_plan_items set completed_seconds=:completed, watch_completed=:watched, "
                  + "updated_at=:now where id=:id and item_type='VIDEO'")
          .param("completed", next)
          .param("watched", watched ? 1 : 0)
          .param("now", now.toEpochMilli())
          .param("id", itemId)
          .update();
    } else if (before.itemType().equals("DEBT_REPAYMENT")) {
      jdbc.sql(
              "update daily_plan_items set completed_seconds=:completed, updated_at=:now "
                  + "where id=:id and item_type='DEBT_REPAYMENT'")
          .param("completed", next)
          .param("now", now.toEpochMilli())
          .param("id", itemId)
          .update();
    }
    markItemCompletedIfSatisfied(itemId, now);
    PlanItem after = findOwnedItem(userId, itemId).orElseThrow();
    return new ProgressUpdate(before, after, next - before.completedSeconds());
  }

  @Override
  public ProgressUpdate markQuizCompleted(String userId, String itemId, Instant now) {
    PlanItem before = findOwnedItem(userId, itemId).orElseThrow();
    jdbc.sql("update daily_plan_items set quiz_completed=1, updated_at=:now where id=:id")
        .params(Map.of("now", now.toEpochMilli(), "id", itemId))
        .update();
    markItemCompletedIfSatisfied(itemId, now);
    PlanItem after = findOwnedItem(userId, itemId).orElseThrow();
    return new ProgressUpdate(before, after, 0);
  }

  /** 任务完成判定只在本仓储内部使用，避免应用层出现第二份完成规则。 */
  private void markItemCompletedIfSatisfied(String itemId, Instant now) {
    jdbc.sql(
            """
            update daily_plan_items set status='COMPLETED', completed_at=:now, updated_at=:now
            where id=:id and status='PENDING' and (
              (item_type='VIDEO' and watch_completed=1 and (quiz_required=0 or quiz_completed=1))
              or (item_type in ('FOCUS','QUIZ','DEBT_REPAYMENT') and completed_seconds >= planned_seconds)
            )
            """)
        .params(Map.of("now", now.toEpochMilli(), "id", itemId))
        .update();
  }

  private DailyPlan mapPlan(ResultSet rs) throws SQLException {
    String id = rs.getString("id");
    LinkedHashSet<String> itemIds =
        new LinkedHashSet<>(
            jdbc.sql("select id from daily_plan_items where plan_id=:id order by sort_order, id")
                .param("id", id)
                .query(String.class)
                .list());
    return DailyPlan.restore(
        id,
        rs.getString("user_id"),
        LocalDate.parse(rs.getString("plan_date")),
        PlanStatus.valueOf(rs.getString("status")),
        itemIds,
        instant(rs, "locked_at"),
        instant(rs, "closed_at"));
  }

  private PlanItem mapItem(ResultSet rs, int row) throws SQLException {
    return new PlanItem(
        rs.getString("id"),
        rs.getString("plan_id"),
        rs.getString("item_kind") == null ? rs.getString("item_type") : rs.getString("item_kind"),
        rs.getString("title"),
        rs.getString("media_item_id"),
        rs.getString("debt_id"),
        rs.getLong("planned_seconds"),
        rs.getLong("completed_seconds"),
        rs.getInt("watch_completed") == 1,
        rs.getInt("quiz_required") == 1,
        rs.getInt("quiz_completed") == 1,
        rs.getString("status"),
        rs.getInt("sort_order"),
        instant(rs, "completed_at"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column) == null ? null : Instant.ofEpochMilli(rs.getLong(column));
  }

  private Long epoch(Instant value) {
    return value == null ? null : value.toEpochMilli();
  }
}
