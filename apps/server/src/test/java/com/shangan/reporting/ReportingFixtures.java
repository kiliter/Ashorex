package com.shangan.reporting;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 报表集成测试共享的确定性原始数据。 */
final class ReportingFixtures {
  private ReportingFixtures() {}

  static void clear(JdbcClient jdbc) {
    for (String table :
        List.of(
            "daily_reports",
            "daily_day_outcomes",
            "focus_sessions",
            "quiz_answers",
            "quiz_attempts",
            "question_options",
            "questions",
            "alive_checks",
            "lesson_review_events",
            "watch_sessions",
            "video_progress",
            "debt_repayments",
            "learning_debts",
            "plan_abandonments",
            "daily_plan_items",
            "daily_plans",
            "media_items",
            "courses",
            "refresh_tokens",
            "users")) {
      jdbc.sql("delete from " + table).update();
    }
  }

  static void insertCompleteDay(JdbcClient jdbc) {
    long started = Instant.parse("2026-08-30T00:00:00Z").toEpochMilli();
    long abandoned = Instant.parse("2026-08-30T14:00:00Z").toEpochMilli();
    jdbc.sql(
            "insert into users (id,username,password_hash,display_name,role,timezone,alive_check_level,day_end_local_time,enabled,created_at,updated_at) "
                + "values ('user-1','learner','x','学习者','USER','UTC','OFF','23:59',1,1,1)")
        .update();
    jdbc.sql(
            "insert into courses (id,name,emby_parent_item_id,created_at,updated_at) values ('course-1','行测','parent-1',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) values ('media-1','course-1','emby-1','资料分析',600000,1,1)")
        .update();
    jdbc.sql(
            "insert into daily_plans (id,user_id,plan_date,status,locked_at,closed_at,created_at,updated_at) values ('plan-1','user-1','2026-08-30','ABANDONED',:started,:abandoned,:started,:abandoned)")
        .param("started", started)
        .param("abandoned", abandoned)
        .update();
    jdbc.sql(
            "insert into daily_plan_items (id,plan_id,item_type,title,media_item_id,planned_seconds,completed_seconds,watch_completed,quiz_required,quiz_completed,status,sort_order,completed_at,created_at,updated_at) values "
                + "('item-video','plan-1','VIDEO','资料分析','media-1',1000,1000,1,0,0,'COMPLETED',0,:done,:started,:done),"
                + "('item-focus','plan-1','FOCUS','申论练习',null,500,300,0,0,0,'PENDING',1,null,:started,:abandoned),"
                + "('item-review','plan-1','VIDEO','资料分析复习','media-1',600,0,0,0,0,'PENDING',2,null,:started,:abandoned)")
        .param("done", started + 3_600_000)
        .param("started", started)
        .param("abandoned", abandoned)
        .update();
    jdbc.sql("update daily_plan_items set item_kind='REVIEW_SHORTCUT' where id='item-review'")
        .update();
    jdbc.sql(
            "insert into daily_day_outcomes (id,user_id,outcome_date,outcome,generated_at) "
                + "values ('outcome-1','user-1','2026-08-30','CLOSED_WITH_DEBT',:at)")
        .param("at", abandoned)
        .update();
    jdbc.sql(
            "insert into plan_abandonments (id,plan_id,user_id,reason_code,reason_text,remaining_seconds,created_at) values ('abandon-1','plan-1','user-1','OPEN_PALM','今天状态很差',700,:abandoned)")
        .param("abandoned", abandoned)
        .update();
    jdbc.sql(
            "insert into watch_sessions (id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,play_session_id,upstream_path,hls,duration_ms,started_position_ms,last_reported_position_ms,max_verified_position_ms,verified_watch_ms,last_sequence,last_heartbeat_at,alive_check_pending,started_at,ended_at,created_at,updated_at,synced_verified_watch_ms) values ('watch-1','user-1','media-1','emby-1','item-video','device-1','COMPLETED','play-1','/stream',0,600000,0,600000,600000,600000,1,:ended,0,:started,:ended,:started,:ended,600000)")
        .param("started", started)
        .param("ended", started + 3_600_000)
        .update();
    jdbc.sql(
            "insert into watch_sessions (id,user_id,media_item_id,emby_item_id,plan_item_id,device_id,status,play_session_id,upstream_path,hls,duration_ms,started_position_ms,last_reported_position_ms,max_verified_position_ms,verified_watch_ms,last_sequence,last_heartbeat_at,alive_check_pending,started_at,ended_at,created_at,updated_at,synced_verified_watch_ms) values ('watch-review','user-1','media-1','emby-1','item-review','device-1','STOPPED','play-review','/stream',0,600000,600000,300000,600000,120000,1,:ended,0,:started,:ended,:started,:ended,120000)")
        .param("started", started + 7_000_000)
        .param("ended", started + 7_120_000)
        .update();
    jdbc.sql(
            "insert into lesson_review_events (id,user_id,media_item_id,watch_session_id,reviewed_on,created_at) "
                + "values ('review-1','user-1','media-1','watch-review','2026-08-30',:at)")
        .param("at", started + 7_010_000)
        .update();
    jdbc.sql(
            "insert into video_progress (id,user_id,media_item_id,max_verified_position_ms,verified_watch_ms,completed_at,last_watched_at,created_at,updated_at) values ('progress-1','user-1','media-1',600000,600000,:done,:done,:started,:done)")
        .param("done", started + 3_600_000)
        .param("started", started)
        .update();
    jdbc.sql(
            "insert into alive_checks (id,watch_session_id,required_at,status,created_at) values ('alive-1','watch-1',:at,'FAILED',:at)")
        .param("at", started + 1_800_000)
        .update();
    jdbc.sql(
            "insert into focus_sessions (id,user_id,plan_item_id,focus_type,status,planned_seconds,actual_seconds,started_at,ended_at,created_at,updated_at) values ('focus-1','user-1','item-focus','PRACTICE','CANCELLED',500,300,:started,:ended,:started,:ended)")
        .param("started", started + 4_000_000)
        .param("ended", started + 4_300_000)
        .update();
    jdbc.sql(
            "insert into questions (id,media_item_id,question_type,content,explanation,enabled,sort_order,created_at,updated_at) values "
                + "('question-1','media-1','SINGLE_CHOICE','题目一','解析一',1,0,1,1),"
                + "('question-2','media-1','TRUE_FALSE','题目二','解析二',1,1,1,1)")
        .update();
    jdbc.sql(
            "insert into question_options (id,question_id,content,correct,sort_order) values "
                + "('option-a','question-1','A',1,0),('option-b','question-1','B',0,1),"
                + "('option-true','question-2','正确',1,0),('option-false','question-2','错误',0,1)")
        .update();
    jdbc.sql(
            "insert into quiz_attempts (id,user_id,media_item_id,score,correct_count,total_count,duration_ms,submitted_at,created_at) values ('attempt-1','user-1','media-1',50,1,2,10000,:at,:at)")
        .param("at", started + 5_000_000)
        .update();
    jdbc.sql(
            "insert into quiz_answers (id,attempt_id,question_id,selected_option_id,correct,duration_ms,created_at) values "
                + "('answer-1','attempt-1','question-1','option-a',1,5000,:at),"
                + "('answer-2','attempt-1','question-2','option-false',0,5000,:at)")
        .param("at", started + 5_000_000)
        .update();
    jdbc.sql(
            "insert into learning_debts (id,user_id,source_plan_item_id,debt_type,title,original_seconds,remaining_seconds,baseline_completed_seconds,status,reason,opened_on,created_at,updated_at) values ('debt-1','user-1','item-focus','FOCUS','申论练习',700,600,0,'PARTIALLY_REPAID','ABANDONED','2026-08-30',:at,:at)")
        .param("at", abandoned)
        .update();
    jdbc.sql(
            "insert into debt_repayments (id,debt_id,repaid_seconds,repayment_source,created_at) values ('repay-1','debt-1',100,'PLAN_ITEM',:at)")
        .param("at", abandoned + 1000)
        .update();
  }
}
