package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.CourseDeletionGraph;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用显式叶子到根顺序删除课程关联图，现有 RESTRICT 外键继续充当遗漏保护。 */
@Repository
public class JdbcCourseDeletionRepository implements CourseDeletionRepository {

  private static final String MEDIA_SCOPE =
      "select id from media_items where course_id in (:courseIds)";
  private static final String DIRECT_PLAN_SCOPE =
      "select id from daily_plan_items where media_item_id in (" + MEDIA_SCOPE + ")";
  private static final String DEBT_SCOPE =
      "select id from learning_debts where media_item_id in ("
          + MEDIA_SCOPE
          + ") or source_plan_item_id in ("
          + DIRECT_PLAN_SCOPE
          + ")";
  private static final String PLAN_SCOPE =
      "select id from daily_plan_items where media_item_id in ("
          + MEDIA_SCOPE
          + ") or debt_id in ("
          + DEBT_SCOPE
          + ")";
  private static final String WATCH_SCOPE =
      "select id from watch_sessions where media_item_id in ("
          + MEDIA_SCOPE
          + ") or plan_item_id in ("
          + PLAN_SCOPE
          + ")";
  private static final String QUESTION_SCOPE =
      "select id from questions where media_item_id in (" + MEDIA_SCOPE + ")";
  private static final String ATTEMPT_SCOPE =
      "select id from quiz_attempts where media_item_id in (" + MEDIA_SCOPE + ")";
  private static final String JOB_SCOPE =
      "select id from content_generation_jobs where course_id in (:courseIds) "
          + "or media_item_id in ("
          + MEDIA_SCOPE
          + ")";
  private static final String DRAFT_SCOPE =
      "select id from quiz_generation_drafts where course_id in (:courseIds) "
          + "or media_item_id in ("
          + MEDIA_SCOPE
          + ") or job_id in ("
          + JOB_SCOPE
          + ")";
  private static final String MOCK_SESSION_SCOPE =
      "select id from mock_exam_sessions where plan_item_id in (" + PLAN_SCOPE + ")";

  private final JdbcClient jdbc;

  public JdbcCourseDeletionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CourseDeletionGraph.Impact inspect(List<String> courseIds) {
    List<CourseDeletionGraph.AffectedDay> affectedDays = affectedDays(courseIds);
    int derivedSnapshots = 0;
    for (CourseDeletionGraph.AffectedDay day : affectedDays) {
      derivedSnapshots +=
          scalar(
              "select count(*) from daily_reports where user_id=:userId and report_date=:date",
              day);
      derivedSnapshots +=
          scalar(
              "select count(*) from daily_day_outcomes where user_id=:userId and outcome_date=:date",
              day);
    }
    derivedSnapshots +=
        count(
            "select count(*) from daily_plan_revisions where plan_id in "
                + "(select distinct plan_id from daily_plan_items where id in ("
                + PLAN_SCOPE
                + "))",
            courseIds);

    return new CourseDeletionGraph.Impact(
        count("select count(*) from courses where id in (:courseIds)", courseIds),
        count("select count(*) from media_items where course_id in (:courseIds)", courseIds),
        count(
            "select count(*) from media_item_source_mappings where media_item_id in ("
                + MEDIA_SCOPE
                + ")",
            courseIds),
        count(
            "select (select count(*) from exam_goal_courses where course_id in (:courseIds)) + "
                + "(select count(*) from course_removal_audits where course_id in (:courseIds))",
            courseIds),
        count("select count(*) from daily_plan_items where id in (" + PLAN_SCOPE + ")", courseIds),
        count(
            "select (select count(*) from learning_debts where id in ("
                + DEBT_SCOPE
                + ")) + (select count(*) from debt_repayments where debt_id in ("
                + DEBT_SCOPE
                + ") or plan_item_id in ("
                + PLAN_SCOPE
                + "))",
            courseIds),
        count(
            "select (select count(*) from watch_sessions where id in ("
                + WATCH_SCOPE
                + ")) + (select count(*) from alive_checks where watch_session_id in ("
                + WATCH_SCOPE
                + "))",
            courseIds),
        count(
            "select count(*) from video_progress where media_item_id in (" + MEDIA_SCOPE + ")",
            courseIds),
        count(
            "select (select count(*) from questions where id in ("
                + QUESTION_SCOPE
                + ")) + (select count(*) from question_options where question_id in ("
                + QUESTION_SCOPE
                + "))",
            courseIds),
        count(
            "select (select count(*) from quiz_attempts where id in ("
                + ATTEMPT_SCOPE
                + ")) + (select count(*) from quiz_answers where attempt_id in ("
                + ATTEMPT_SCOPE
                + ") or question_id in ("
                + QUESTION_SCOPE
                + "))",
            courseIds),
        count(
            "select count(*) from lesson_study_contents where media_item_id in ("
                + MEDIA_SCOPE
                + ")",
            courseIds),
        count(
            "select (select count(*) from content_generation_jobs where id in ("
                + JOB_SCOPE
                + ")) + (select count(*) from content_generation_job_logs where job_id in ("
                + JOB_SCOPE
                + "))",
            courseIds),
        count(
            "select (select count(*) from quiz_generation_drafts where id in ("
                + DRAFT_SCOPE
                + ")) + (select count(*) from quiz_generation_draft_items where draft_id in ("
                + DRAFT_SCOPE
                + ")) + (select count(*) from quiz_generation_draft_options where draft_item_id in "
                + "(select id from quiz_generation_draft_items where draft_id in ("
                + DRAFT_SCOPE
                + ")))",
            courseIds),
        count(
            "select count(*) from lesson_review_events where media_item_id in ("
                + MEDIA_SCOPE
                + ") or watch_session_id in ("
                + WATCH_SCOPE
                + ")",
            courseIds),
        count(
            "select (select count(*) from focus_sessions where media_item_id in ("
                + MEDIA_SCOPE
                + ") or plan_item_id in ("
                + PLAN_SCOPE
                + ")) + (select count(*) from mock_exam_sessions where id in ("
                + MOCK_SESSION_SCOPE
                + "))",
            courseIds),
        count(
            "select count(*) from mock_exam_attachments where session_id in ("
                + MOCK_SESSION_SCOPE
                + ")",
            courseIds),
        derivedSnapshots);
  }

  @Override
  public CourseDeletionGraph.DeletionResult deleteGraph(List<String> courseIds) {
    List<CourseDeletionGraph.AffectedDay> affectedDays = affectedDays(courseIds);
    List<String> attachmentPaths =
        jdbc.sql(
                "select storage_path from mock_exam_attachments where session_id in ("
                    + MOCK_SESSION_SCOPE
                    + ")")
            .param("courseIds", courseIds)
            .query(String.class)
            .list();

    // 先失效包含完整 JSON 的修订与确定性派生快照，避免删除后继续展示旧统计。
    update(
        "delete from daily_plan_revisions where plan_id in "
            + "(select distinct plan_id from daily_plan_items where id in ("
            + PLAN_SCOPE
            + "))",
        courseIds);
    deleteDerivedSnapshots(affectedDays);

    // 题目草稿可能引用已发布正式题目，因此必须先于正式题库删除。
    update("delete from quiz_generation_drafts where id in (" + DRAFT_SCOPE + ")", courseIds);
    update(
        "delete from quiz_answers where attempt_id in ("
            + ATTEMPT_SCOPE
            + ") or question_id in ("
            + QUESTION_SCOPE
            + ")",
        courseIds);
    update("delete from quiz_attempts where id in (" + ATTEMPT_SCOPE + ")", courseIds);
    update("delete from questions where id in (" + QUESTION_SCOPE + ")", courseIds);

    // 内容任务日志由任务外键级联，但应用层仍明确删除任务根，禁止 Worker 继续回写。
    update(
        "delete from content_generation_job_logs where job_id in (" + JOB_SCOPE + ")", courseIds);
    update("delete from content_generation_jobs where id in (" + JOB_SCOPE + ")", courseIds);

    update(
        "delete from lesson_review_events where media_item_id in ("
            + MEDIA_SCOPE
            + ") or watch_session_id in ("
            + WATCH_SCOPE
            + ")",
        courseIds);
    update("delete from alive_checks where watch_session_id in (" + WATCH_SCOPE + ")", courseIds);
    update("delete from watch_sessions where id in (" + WATCH_SCOPE + ")", courseIds);
    update("delete from video_progress where media_item_id in (" + MEDIA_SCOPE + ")", courseIds);

    update(
        "delete from mock_exam_attachments where session_id in (" + MOCK_SESSION_SCOPE + ")",
        courseIds);
    update("delete from mock_exam_sessions where id in (" + MOCK_SESSION_SCOPE + ")", courseIds);
    update(
        "delete from focus_sessions where media_item_id in ("
            + MEDIA_SCOPE
            + ") or plan_item_id in ("
            + PLAN_SCOPE
            + ")",
        courseIds);

    update(
        "delete from debt_repayments where debt_id in ("
            + DEBT_SCOPE
            + ") or plan_item_id in ("
            + PLAN_SCOPE
            + ")",
        courseIds);
    // 还债计划项目通过无外键的 debt_id 关联目标欠债，必须在欠债仍存在时先删除。
    update("delete from daily_plan_items where debt_id in (" + DEBT_SCOPE + ")", courseIds);
    update("delete from learning_debts where id in (" + DEBT_SCOPE + ")", courseIds);
    update("delete from daily_plan_items where media_item_id in (" + MEDIA_SCOPE + ")", courseIds);

    update(
        "delete from lesson_study_contents where media_item_id in (" + MEDIA_SCOPE + ")",
        courseIds);
    update(
        "delete from media_item_source_mappings where media_item_id in (" + MEDIA_SCOPE + ")",
        courseIds);
    update("delete from exam_goal_courses where course_id in (:courseIds)", courseIds);
    update("delete from course_removal_audits where course_id in (:courseIds)", courseIds);
    update("delete from media_items where course_id in (:courseIds)", courseIds);
    update("delete from courses where id in (:courseIds) and enabled=0", courseIds);

    return new CourseDeletionGraph.DeletionResult(attachmentPaths, affectedDays);
  }

  /** 汇总原始数据实际贡献的用户自然日，供日报和日终结果精确重建。 */
  private List<CourseDeletionGraph.AffectedDay> affectedDays(List<String> courseIds) {
    Set<CourseDeletionGraph.AffectedDay> days = new LinkedHashSet<>();
    days.addAll(
        jdbc.sql(
                "select distinct dp.user_id user_id, dp.plan_date affected_date, u.timezone timezone "
                    + "from daily_plans dp join daily_plan_items pi on pi.plan_id=dp.id "
                    + "join users u on u.id=dp.user_id where pi.id in ("
                    + PLAN_SCOPE
                    + ") union select distinct d.user_id, d.opened_on, u.timezone "
                    + "from learning_debts d join users u on u.id=d.user_id where d.id in ("
                    + DEBT_SCOPE
                    + ") union select distinct r.user_id, r.reviewed_on, u.timezone "
                    + "from lesson_review_events r join users u on u.id=r.user_id "
                    + "where r.media_item_id in ("
                    + MEDIA_SCOPE
                    + ")")
            .param("courseIds", courseIds)
            .query(
                (rs, row) ->
                    new CourseDeletionGraph.AffectedDay(
                        rs.getString("user_id"),
                        LocalDate.parse(rs.getString("affected_date")),
                        rs.getString("timezone")))
            .list());

    List<TimedActivity> activities =
        jdbc.sql(
                "select w.user_id, u.timezone, w.started_at occurred_at from watch_sessions w "
                    + "join users u on u.id=w.user_id where w.id in ("
                    + WATCH_SCOPE
                    + ") union all select a.user_id,u.timezone,a.submitted_at from quiz_attempts a "
                    + "join users u on u.id=a.user_id where a.id in ("
                    + ATTEMPT_SCOPE
                    + ") union all select f.user_id,u.timezone,f.started_at from focus_sessions f "
                    + "join users u on u.id=f.user_id where f.media_item_id in ("
                    + MEDIA_SCOPE
                    + ") or f.plan_item_id in ("
                    + PLAN_SCOPE
                    + ") union all select v.user_id,u.timezone,v.completed_at from video_progress v "
                    + "join users u on u.id=v.user_id where v.media_item_id in ("
                    + MEDIA_SCOPE
                    + ") and v.completed_at is not null union all "
                    + "select w.user_id,u.timezone,a.required_at from alive_checks a "
                    + "join watch_sessions w on w.id=a.watch_session_id join users u on u.id=w.user_id "
                    + "where w.id in ("
                    + WATCH_SCOPE
                    + ") union all select d.user_id,u.timezone,r.created_at from debt_repayments r "
                    + "join learning_debts d on d.id=r.debt_id join users u on u.id=d.user_id "
                    + "where r.debt_id in ("
                    + DEBT_SCOPE
                    + ") or r.plan_item_id in ("
                    + PLAN_SCOPE
                    + ")")
            .param("courseIds", courseIds)
            .query(
                (rs, row) ->
                    new TimedActivity(
                        rs.getString("user_id"),
                        rs.getString("timezone"),
                        rs.getLong("occurred_at")))
            .list();
    for (TimedActivity activity : activities) {
      LocalDate date =
          Instant.ofEpochMilli(activity.occurredAt())
              .atZone(ZoneId.of(activity.timezone()))
              .toLocalDate();
      days.add(new CourseDeletionGraph.AffectedDay(activity.userId(), date, activity.timezone()));
    }
    return List.copyOf(days);
  }

  private void deleteDerivedSnapshots(List<CourseDeletionGraph.AffectedDay> days) {
    for (CourseDeletionGraph.AffectedDay day : days) {
      jdbc.sql("delete from daily_reports where user_id=:userId and report_date=:date")
          .param("userId", day.userId())
          .param("date", day.date().toString())
          .update();
      jdbc.sql("delete from daily_day_outcomes where user_id=:userId and outcome_date=:date")
          .param("userId", day.userId())
          .param("date", day.date().toString())
          .update();
    }
  }

  private int count(String sql, List<String> courseIds) {
    return jdbc.sql(sql).param("courseIds", courseIds).query(Integer.class).single();
  }

  private int scalar(String sql, CourseDeletionGraph.AffectedDay day) {
    return jdbc.sql(sql)
        .param("userId", day.userId())
        .param("date", day.date().toString())
        .query(Integer.class)
        .single();
  }

  private void update(String sql, List<String> courseIds) {
    jdbc.sql(sql).param("courseIds", courseIds).update();
  }

  private record TimedActivity(String userId, String timezone, long occurredAt) {}
}
