package com.shangan.reporting.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 把日报 SQL 聚合封装在 reporting 模块内，避免 Controller 或应用服务直接访问 JDBC。 */
@Repository
public class JdbcReportingRepository implements ReportingRepository {
  private final JdbcClient jdbc;

  public JdbcReportingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String timezone(String userId) {
    return jdbc.sql("select timezone from users where id=:userId")
        .param("userId", userId)
        .query(String.class)
        .optional()
        .orElse("Asia/Shanghai");
  }

  @Override
  public RawDailyMetrics aggregate(String userId, LocalDate date, Instant start, Instant end) {
    PlanStats plan =
        jdbc.sql(
                """
                select p.status,
                  coalesce(sum(i.planned_seconds),0) planned,
                  coalesce(sum(case when i.status='COMPLETED' then 1 else 0 end),0) completed,
                  count(i.id) total
                from daily_plans p left join daily_plan_items i on i.plan_id=p.id
                where p.user_id=:userId and p.plan_date=:date
                group by p.id,p.status
                """)
            .param("userId", userId)
            .param("date", date.toString())
            .query(
                (rs, row) ->
                    new PlanStats(
                        rs.getString("status"),
                        rs.getLong("planned"),
                        rs.getInt("completed"),
                        rs.getInt("total")))
            .optional()
            .orElse(new PlanStats("NONE", 0, 0, 0));
    long videoSeconds =
        scalarLong(
            "select coalesce(sum(verified_watch_ms),0)/1000 from watch_sessions "
                + "where user_id=:userId and started_at>=:start and started_at<:end",
            userId,
            start,
            end);
    long focusSeconds =
        scalarLong(
            "select coalesce(sum(actual_seconds),0) from focus_sessions "
                + "where user_id=:userId and started_at>=:start and started_at<:end",
            userId,
            start,
            end);
    int videoCompleted =
        (int)
            scalarLong(
                "select count(*) from video_progress where user_id=:userId "
                    + "and completed_at>=:start and completed_at<:end",
                userId,
                start,
                end);
    AnswerStats answers =
        jdbc.sql(
                """
                select count(a.id) total,
                  coalesce(sum(case when a.correct=1 then 1 else 0 end),0) correct
                from quiz_answers a join quiz_attempts q on q.id=a.attempt_id
                where q.user_id=:userId and q.submitted_at>=:start and q.submitted_at<:end
                """)
            .param("userId", userId)
            .param("start", start.toEpochMilli())
            .param("end", end.toEpochMilli())
            .query((rs, row) -> new AnswerStats(rs.getInt("total"), rs.getInt("correct")))
            .single();
    int aliveFailures =
        (int)
            scalarLong(
                """
                select count(*) from alive_checks a
                join watch_sessions w on w.id=a.watch_session_id
                where w.user_id=:userId and a.status='FAILED'
                  and a.required_at>=:start and a.required_at<:end
                """,
                userId,
                start,
                end);
    Abandonment abandonment =
        jdbc.sql(
                """
                select a.created_at,a.reason_text from plan_abandonments a
                join daily_plans p on p.id=a.plan_id
                where p.user_id=:userId and p.plan_date=:date
                """)
            .param("userId", userId)
            .param("date", date.toString())
            .query(
                (rs, row) ->
                    new Abandonment(
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        rs.getString("reason_text")))
            .optional()
            .orElse(null);
    long newDebt =
        scalarLong(
            "select coalesce(sum(original_seconds),0) from learning_debts "
                + "where user_id=:userId and created_at>=:start and created_at<:end",
            userId,
            start,
            end);
    long repaidDebt =
        scalarLong(
            """
            select coalesce(sum(r.repaid_seconds),0) from debt_repayments r
            join learning_debts d on d.id=r.debt_id
            where d.user_id=:userId and r.created_at>=:start and r.created_at<:end
            """,
            userId,
            start,
            end);
    long openDebt =
        jdbc.sql(
                "select coalesce(sum(remaining_seconds),0) from learning_debts "
                    + "where user_id=:userId and status in ('OPEN','PARTIALLY_REPAID')")
            .param("userId", userId)
            .query(Long.class)
            .single();
    return new RawDailyMetrics(
        plan.status(),
        plan.planned(),
        plan.completed(),
        plan.total(),
        videoSeconds,
        focusSeconds,
        videoCompleted,
        answers.total(),
        answers.correct(),
        aliveFailures,
        abandonment != null,
        abandonment == null ? null : abandonment.at(),
        abandonment == null ? "" : abandonment.reason(),
        newDebt,
        repaidDebt,
        openDebt);
  }

  @Override
  public boolean debtGrewOnEachOfThreeDays(String userId, LocalDate date) {
    return jdbc.sql(
                """
            select count(*) from (
              select opened_on from learning_debts
              where user_id=:userId and opened_on between :startDate and :endDate
              group by opened_on having sum(original_seconds)>0
            )
            """)
            .param("userId", userId)
            .param("startDate", date.minusDays(2).toString())
            .param("endDate", date.toString())
            .query(Integer.class)
            .single()
        == 3;
  }

  @Override
  public void upsert(
      String id,
      String userId,
      LocalDate date,
      String payloadJson,
      String judgmentText,
      Instant generatedAt) {
    jdbc.sql(
            """
            insert into daily_reports (id,user_id,report_date,payload_json,judgment_text,generated_at)
            values (:id,:userId,:date,:payload,:judgment,:generatedAt)
            on conflict(user_id,report_date) do update set
              payload_json=excluded.payload_json,judgment_text=excluded.judgment_text,
              generated_at=excluded.generated_at
            """)
        .param("id", id)
        .param("userId", userId)
        .param("date", date.toString())
        .param("payload", payloadJson)
        .param("judgment", judgmentText)
        .param("generatedAt", generatedAt.toEpochMilli())
        .update();
  }

  @Override
  public Optional<Snapshot> find(String userId, LocalDate date) {
    return jdbc.sql(
            "select payload_json,judgment_text,generated_at from daily_reports "
                + "where user_id=:userId and report_date=:date")
        .param("userId", userId)
        .param("date", date.toString())
        .query(
            (rs, row) ->
                new Snapshot(
                    rs.getString("payload_json"),
                    rs.getString("judgment_text"),
                    Instant.ofEpochMilli(rs.getLong("generated_at"))))
        .optional();
  }

  @Override
  public List<ReportCandidate> terminalPlans() {
    return jdbc.sql(
            "select user_id,plan_date from daily_plans "
                + "where status in ('COMPLETED','ABANDONED','CLOSED_WITH_DEBT')")
        .query(
            (rs, row) ->
                new ReportCandidate(
                    rs.getString("user_id"), LocalDate.parse(rs.getString("plan_date"))))
        .list();
  }

  @Override
  public List<UserTimezone> users() {
    return jdbc.sql("select id,timezone from users where enabled=1")
        .query((rs, row) -> new UserTimezone(rs.getString("id"), rs.getString("timezone")))
        .list();
  }

  private long scalarLong(String sql, String userId, Instant start, Instant end) {
    return jdbc.sql(sql)
        .param("userId", userId)
        .param("start", start.toEpochMilli())
        .param("end", end.toEpochMilli())
        .query(Long.class)
        .single();
  }

  private record PlanStats(String status, long planned, int completed, int total) {}

  private record AnswerStats(int total, int correct) {}

  private record Abandonment(Instant at, String reason) {}
}
