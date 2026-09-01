package com.shangan.reporting.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 日终查询排除复习快捷入口，复习审计不会把无学习日变成自由学习日。 */
@Repository
public class JdbcDayOutcomeRepository implements DayOutcomeRepository {
  private final JdbcClient jdbc;

  public JdbcDayOutcomeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<UserDaySettings> users() {
    return jdbc.sql("select id,timezone,day_end_local_time from users where enabled=1")
        .query(
            (rs, row) ->
                new UserDaySettings(
                    rs.getString("id"),
                    rs.getString("timezone"),
                    rs.getString("day_end_local_time")))
        .list();
  }

  @Override
  public Optional<LocalDate> latestOutcomeDate(String userId) {
    return jdbc.sql("select max(outcome_date) from daily_day_outcomes where user_id=:userId")
        .param("userId", userId)
        .query(String.class)
        .optional()
        .filter(value -> !value.isBlank())
        .map(LocalDate::parse);
  }

  @Override
  public Optional<PlanDay> findPlan(String userId, LocalDate date) {
    return jdbc.sql(
            """
            select p.id,p.lifecycle_status,
              coalesce(sum(case when coalesce(i.item_kind,i.item_type)<>'REVIEW_SHORTCUT'
                then 1 else 0 end),0) accountable_items
            from daily_plans p left join daily_plan_items i on i.plan_id=p.id
            where p.user_id=:userId and p.plan_date=:date
            group by p.id,p.lifecycle_status
            """)
        .params(Map.of("userId", userId, "date", date.toString()))
        .query(
            (rs, row) ->
                new PlanDay(
                    rs.getString("id"),
                    rs.getString("lifecycle_status"),
                    rs.getInt("accountable_items")))
        .optional();
  }

  @Override
  public boolean hasEffectiveActivity(String userId, Instant start, Instant end) {
    int count =
        jdbc.sql(
                """
                select (
                  select count(*) from watch_sessions w
                  left join daily_plan_items i on i.id=w.plan_item_id
                  where w.user_id=:userId and w.started_at>=:start and w.started_at<:end
                    and w.verified_watch_ms>0
                    and coalesce(i.item_kind,'')<>'REVIEW_SHORTCUT'
                ) + (
                  select count(*) from focus_sessions f
                  where f.user_id=:userId and f.started_at>=:start and f.started_at<:end
                    and f.actual_seconds>0
                ) + (
                  select count(*) from mock_exam_sessions m
                  where m.user_id=:userId and m.started_at>=:start and m.started_at<:end
                )
                """)
            .param("userId", userId)
            .param("start", start.toEpochMilli())
            .param("end", end.toEpochMilli())
            .query(Integer.class)
            .single();
    return count > 0;
  }

  @Override
  public void upsert(
      String id, String userId, LocalDate date, String outcome, Instant generatedAt) {
    jdbc.sql(
            """
            insert into daily_day_outcomes (id,user_id,outcome_date,outcome,generated_at)
            values (:id,:userId,:date,:outcome,:now)
            on conflict(user_id,outcome_date) do update set
              outcome=excluded.outcome,generated_at=excluded.generated_at
            """)
        .param("id", id)
        .param("userId", userId)
        .param("date", date.toString())
        .param("outcome", outcome)
        .param("now", generatedAt.toEpochMilli())
        .update();
  }
}
