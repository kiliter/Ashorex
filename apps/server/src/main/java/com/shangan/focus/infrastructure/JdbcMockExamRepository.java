package com.shangan.focus.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用显式所有权连接查询模拟考试，防止通过附件或会话 ID 越权读取。 */
@Repository
public class JdbcMockExamRepository implements MockExamRepository {
  private final JdbcClient jdbc;

  public JdbcMockExamRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PlanItemRow> findOwnedPlanItem(String userId, String planItemId) {
    return jdbc.sql(
            """
            select i.id,i.mock_exam_name_snapshot,i.planned_seconds,i.status
            from daily_plan_items i join daily_plans p on p.id=i.plan_id
            where i.id=:itemId and p.user_id=:userId and i.item_kind='MOCK_EXAM'
              and p.lifecycle_status='ACTIVE'
            """)
        .params(Map.of("itemId", planItemId, "userId", userId))
        .query(
            (rs, row) ->
                new PlanItemRow(
                    rs.getString("id"),
                    rs.getString("mock_exam_name_snapshot"),
                    rs.getLong("planned_seconds"),
                    rs.getString("status")))
        .optional();
  }

  @Override
  public Optional<SessionRow> findByPlanItem(String userId, String planItemId) {
    return jdbc.sql(
            "select * from mock_exam_sessions where user_id=:userId and plan_item_id=:itemId")
        .params(Map.of("userId", userId, "itemId", planItemId))
        .query(this::mapSession)
        .optional();
  }

  @Override
  public Optional<SessionRow> findOwnedSession(String userId, String sessionId) {
    return jdbc.sql("select * from mock_exam_sessions where user_id=:userId and id=:id")
        .params(Map.of("userId", userId, "id", sessionId))
        .query(this::mapSession)
        .optional();
  }

  @Override
  public void insertSession(SessionRow session, Instant now) {
    jdbc.sql(
            """
            insert into mock_exam_sessions (
              id,user_id,plan_item_id,name_snapshot,duration_seconds_snapshot,status,
              started_at,deadline_at,created_at,updated_at
            ) values (:id,:userId,:itemId,:name,:duration,'RUNNING',:started,:deadline,:now,:now)
            """)
        .param("id", session.id())
        .param("userId", session.userId())
        .param("itemId", session.planItemId())
        .param("name", session.name())
        .param("duration", session.durationSeconds())
        .param("started", session.startedAt().toEpochMilli())
        .param("deadline", session.deadlineAt().toEpochMilli())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void markAwaitingUpload(String userId, String sessionId, Instant submittedAt) {
    jdbc.sql(
            """
            update mock_exam_sessions
            set status='AWAITING_UPLOAD',submitted_at=coalesce(submitted_at,:submitted),updated_at=:submitted
            where id=:id and user_id=:userId and status='RUNNING'
            """)
        .param("submitted", submittedAt.toEpochMilli())
        .param("id", sessionId)
        .param("userId", userId)
        .update();
  }

  @Override
  public void retake(String userId, String sessionId, Instant startedAt, Instant deadlineAt) {
    jdbc.sql(
            """
            update mock_exam_sessions
            set status='RUNNING',started_at=:started,deadline_at=:deadline,
                submitted_at=null,completed_at=null,updated_at=:started
            where id=:id and user_id=:userId and status in ('AWAITING_UPLOAD','COMPLETED')
            """)
        .param("started", startedAt.toEpochMilli())
        .param("deadline", deadlineAt.toEpochMilli())
        .param("id", sessionId)
        .param("userId", userId)
        .update();
  }

  @Override
  public int countAttachments(String userId, String sessionId) {
    return jdbc.sql(
            "select count(*) from mock_exam_attachments where user_id=:userId and session_id=:id")
        .params(Map.of("userId", userId, "id", sessionId))
        .query(Integer.class)
        .single();
  }

  @Override
  public void insertAttachment(AttachmentRow attachment) {
    jdbc.sql(
            """
            insert into mock_exam_attachments (
              id,session_id,user_id,storage_path,original_filename,content_type,
              size_bytes,sha256,sort_order,created_at
            ) values (:id,:sessionId,:userId,:path,:filename,:contentType,:size,:sha,:sortOrder,:now)
            """)
        .param("id", attachment.id())
        .param("sessionId", attachment.sessionId())
        .param("userId", attachment.userId())
        .param("path", attachment.storagePath())
        .param("filename", attachment.originalFilename())
        .param("contentType", attachment.contentType())
        .param("size", attachment.sizeBytes())
        .param("sha", attachment.sha256())
        .param("sortOrder", attachment.sortOrder())
        .param("now", attachment.createdAt().toEpochMilli())
        .update();
  }

  @Override
  public List<AttachmentRow> findAttachments(String userId, String sessionId) {
    return jdbc.sql(
            "select * from mock_exam_attachments where user_id=:userId and session_id=:id "
                + "order by sort_order,id")
        .params(Map.of("userId", userId, "id", sessionId))
        .query(this::mapAttachment)
        .list();
  }

  @Override
  public void complete(String userId, String sessionId, String planItemId, Instant completedAt) {
    jdbc.sql(
            """
            update mock_exam_sessions
            set status='COMPLETED',completed_at=:now,updated_at=:now
            where id=:id and user_id=:userId and status='AWAITING_UPLOAD'
            """)
        .param("now", completedAt.toEpochMilli())
        .param("id", sessionId)
        .param("userId", userId)
        .update();
    jdbc.sql(
            """
            update daily_plan_items
            set completed_seconds=planned_seconds,status='COMPLETED',completed_at=:now,updated_at=:now
            where id=:itemId and item_kind='MOCK_EXAM'
            """)
        .params(Map.of("now", completedAt.toEpochMilli(), "itemId", planItemId))
        .update();
  }

  private SessionRow mapSession(ResultSet rs, int row) throws SQLException {
    return new SessionRow(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("plan_item_id"),
        rs.getString("name_snapshot"),
        rs.getLong("duration_seconds_snapshot"),
        rs.getString("status"),
        Instant.ofEpochMilli(rs.getLong("started_at")),
        Instant.ofEpochMilli(rs.getLong("deadline_at")),
        instant(rs, "submitted_at"),
        instant(rs, "completed_at"));
  }

  private AttachmentRow mapAttachment(ResultSet rs, int row) throws SQLException {
    return new AttachmentRow(
        rs.getString("id"),
        rs.getString("session_id"),
        rs.getString("user_id"),
        rs.getString("storage_path"),
        rs.getString("original_filename"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getInt("sort_order"),
        Instant.ofEpochMilli(rs.getLong("created_at")));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column) == null ? null : Instant.ofEpochMilli(rs.getLong(column));
  }
}
