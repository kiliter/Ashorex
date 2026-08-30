package com.shangan.exam.infrastructure;

import com.shangan.exam.domain.ExamGoal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 SQLite 保存用户唯一考试目标及其参与进度计算的课程。 */
@Repository
public class JdbcExamGoalRepository implements ExamGoalRepository {

  private final JdbcClient jdbc;

  public JdbcExamGoalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ExamGoal> findByUserId(String userId) {
    return jdbc.sql("select * from exam_goals where user_id = :userId")
        .param("userId", userId)
        .query((rs, row) -> mapGoal(rs))
        .optional();
  }

  @Override
  public void save(ExamGoal goal, Instant now) {
    jdbc.sql(
            """
            insert into exam_goals (
              id, user_id, name, exam_date, target_completion_date,
              review_buffer_days, timezone, created_at, updated_at
            ) values (
              :id, :userId, :name, :examDate, :targetDate,
              :bufferDays, :timezone, :now, :now
            )
            on conflict(user_id) do update set
              name = excluded.name,
              exam_date = excluded.exam_date,
              target_completion_date = excluded.target_completion_date,
              review_buffer_days = excluded.review_buffer_days,
              timezone = excluded.timezone,
              updated_at = excluded.updated_at
            """)
        .params(
            Map.of(
                "id", goal.id(),
                "userId", goal.userId(),
                "name", goal.name(),
                "examDate", goal.examDate().toString(),
                "targetDate", goal.targetCompletionDate().toString(),
                "bufferDays", goal.reviewBufferDays(),
                "timezone", goal.timezone(),
                "now", now.toEpochMilli()))
        .update();
    jdbc.sql("delete from exam_goal_courses where exam_goal_id = :goalId")
        .param("goalId", goal.id())
        .update();
    for (String courseId : goal.courseIds()) {
      jdbc.sql(
              "insert into exam_goal_courses (exam_goal_id, course_id) "
                  + "values (:goalId, :courseId)")
          .params(Map.of("goalId", goal.id(), "courseId", courseId))
          .update();
    }
  }

  private ExamGoal mapGoal(ResultSet rs) throws SQLException {
    String goalId = rs.getString("id");
    List<String> courseIds =
        jdbc.sql(
                "select course_id from exam_goal_courses "
                    + "where exam_goal_id = :goalId order by course_id")
            .param("goalId", goalId)
            .query(String.class)
            .list();
    return new ExamGoal(
        goalId,
        rs.getString("user_id"),
        rs.getString("name"),
        LocalDate.parse(rs.getString("exam_date")),
        LocalDate.parse(rs.getString("target_completion_date")),
        rs.getInt("review_buffer_days"),
        rs.getString("timezone"),
        courseIds);
  }
}
