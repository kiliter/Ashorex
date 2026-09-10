package com.shangan.todo.infrastructure;

import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.FocusState;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoStatus;
import com.shangan.todo.domain.TodoType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Todo 聚合的 SQLite 持久化实现。 */
@Repository
public class JdbcTodoRepository implements TodoRepository {

  private static final String SELECT_TODO =
      """
      SELECT id, user_id, local_date, todo_type, title, note, sort_order, status,
             resource_id, target_progress_permille, progress_position_ms, progress_page,
             watched_ms, planned_seconds, focus_state, focus_started_at, focused_ms,
             require_evidence, completed_at, backfilled, backfill_note,
             supervisor_user_id_snapshot, focus_attempt_base_ms
        FROM todos
      """;

  private final JdbcClient jdbcClient;

  public JdbcTodoRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 只查询 Todo 内的播放轮次，不联动 lesson_watch_states。 */
  @Override
  public Map<String, PlaybackSession> playbackSessions(List<String> ids) {
    Map<String, PlaybackSession> result = new LinkedHashMap<>();
    if (ids.isEmpty()) return result;
    jdbcClient
        .sql(
            "SELECT id, playback_epoch, playback_resume_ms, playback_reset_id FROM todos WHERE id IN (:ids)")
        .param("ids", ids)
        .query(
            (row, index) -> {
              result.put(
                  row.getString("id"),
                  new PlaybackSession(
                      row.getLong("playback_epoch"),
                      row.getLong("playback_resume_ms"),
                      row.getString("playback_reset_id")));
              return 0;
            })
        .list();
    return result;
  }

  /** CAS 原子开启复习轮次；不更新完成、时长或课时历史字段。 */
  @Override
  public boolean resetPlayback(String id, long expected, String request, Instant now) {
    return jdbcClient
            .sql(
                """
        UPDATE todos SET progress_position_ms = 0, playback_epoch = playback_epoch + 1,
          playback_resume_ms = 0, playback_resume_seq = -1, playback_reset_id = :request,
          updated_at = :now
        WHERE id = :id AND playback_epoch = :expected
        """)
            .param("request", request)
            .param("now", now.toEpochMilli())
            .param("id", id)
            .param("expected", expected)
            .update()
        == 1;
  }

  /** 同一轮保留最新已确认报告的位置；历史事件不能覆盖新轮次。 */
  @Override
  public void rememberPlayback(String id, long epoch, long sequence, long position) {
    jdbcClient
        .sql(
            """
        UPDATE todos SET playback_resume_ms = :position, playback_resume_seq = :sequence
        WHERE id = :id AND playback_epoch = :epoch AND playback_resume_seq < :sequence
        """)
        .param("position", Math.max(0, position))
        .param("sequence", sequence)
        .param("id", id)
        .param("epoch", epoch)
        .update();
  }

  /** 历史 Todo、删除台账和累计观看状态均只按当前用户的稳定资源 ID 判断。 */
  @Override
  public boolean hasCourseHistory(String userId, String resourceId) {
    return jdbcClient
        .sql(
            """
        SELECT EXISTS(SELECT 1 FROM todos WHERE user_id = :user AND resource_id = :resource)
            OR EXISTS(SELECT 1 FROM lesson_watch_states WHERE user_id = :user AND resource_id = :resource)
            OR EXISTS(SELECT 1 FROM todo_deletions WHERE user_id = :user AND resource_id = :resource)
        """)
        .param("user", userId)
        .param("resource", resourceId)
        .query(Boolean.class)
        .single();
  }

  /** 标记只用于展示，课程类型与状态协议保持兼容。 */
  @Override
  public void markReview(String todoId) {
    jdbcClient.sql("UPDATE todos SET is_review = 1 WHERE id = :id").param("id", todoId).update();
  }

  @Override
  public Set<String> reviewTodoIds(List<String> todoIds) {
    if (todoIds.isEmpty()) return Set.of();
    return Set.copyOf(
        jdbcClient
            .sql("SELECT id FROM todos WHERE id IN (:ids) AND is_review = 1")
            .param("ids", todoIds)
            .query(String.class)
            .list());
  }

  /** 占位和最终结果都随应用事务提交；失败回滚不会留下空回执。 */
  @Override
  public void reserveCourseAddition(String userId, String requestId, String fingerprint) {
    jdbcClient
        .sql(
            """
        INSERT INTO course_addition_receipts(user_id, request_id, fingerprint)
        VALUES (:user, :request, :fingerprint) ON CONFLICT(user_id, request_id) DO NOTHING
        """)
        .param("user", userId)
        .param("request", requestId)
        .param("fingerprint", fingerprint)
        .update();
  }

  @Override
  public Optional<CourseAdditionReceipt> courseAdditionReceipt(String userId, String requestId) {
    return jdbcClient
        .sql(
            "SELECT fingerprint, result_json FROM course_addition_receipts WHERE user_id = :user AND request_id = :request")
        .param("user", userId)
        .param("request", requestId)
        .query((rs, row) -> new CourseAdditionReceipt(rs.getString(1), rs.getString(2)))
        .optional();
  }

  @Override
  public void completeCourseAddition(String userId, String requestId, String resultJson) {
    jdbcClient
        .sql(
            "UPDATE course_addition_receipts SET result_json = :result WHERE user_id = :user AND request_id = :request")
        .param("result", resultJson)
        .param("user", userId)
        .param("request", requestId)
        .update();
  }

  /** 幂等标识只在所属 Todo 内查询，归属由应用层先校验。 */
  @Override
  public Optional<String> focusRequestAction(String todoId, String requestId) {
    return jdbcClient
        .sql(
            "SELECT focus_action FROM todo_progress_events WHERE todo_id = :todo AND focus_request_id = :request")
        .param("todo", todoId)
        .param("request", requestId)
        .query(String.class)
        .optional();
  }

  /** 专注无客户端进度队列，事务内分配流水序号并记录实际增量。 */
  @Override
  public void recordFocusAction(
      Todo before, Todo after, String action, String requestId, Instant now) {
    String eventType =
        switch (action) {
          case "pause", "stop" -> "FOCUS_PAUSE";
          case "finish" -> "FOCUS_FINISH";
          case "abandon" -> "FOCUS_ABANDON";
          default -> "FOCUS_TICK";
        };
    jdbcClient
        .sql(
            """
        INSERT INTO todo_progress_events
          (id, todo_id, user_id, client_seq, event_type, delta_focused_ms,
           occurred_at, created_at, focus_action, focus_from_state, focus_to_state,
           focus_total_ms, focus_request_id)
        VALUES (:id, :todo, :user,
          (SELECT COALESCE(MAX(client_seq), -1) + 1 FROM todo_progress_events WHERE todo_id = :todo),
          :type, :delta, :now, :now, :action, :before, :after, :total, :request)
        """)
        .param("id", java.util.UUID.randomUUID().toString())
        .param("todo", before.id())
        .param("user", before.userId())
        .param("type", eventType)
        .param("delta", Math.max(0, after.focusedMs() - before.focusedMs()))
        .param("now", now.toEpochMilli())
        .param("action", action)
        .param("before", before.focusState().name())
        .param("after", after.focusState().name())
        .param("total", after.focusedMs())
        .param("request", requestId)
        .update();
  }

  @Override
  public Optional<Todo> findById(String id) {
    return jdbcClient
        .sql(SELECT_TODO + " WHERE id = :id")
        .param("id", id)
        .query(this::mapTodo)
        .optional();
  }

  @Override
  public List<Todo> findByUserAndDate(String userId, LocalDate localDate) {
    return jdbcClient
        .sql(
            SELECT_TODO + " WHERE user_id = :userId AND local_date = :date ORDER BY sort_order, id")
        .param("userId", userId)
        .param("date", localDate.toString())
        .query(this::mapTodo)
        .list();
  }

  @Override
  public List<Todo> findByUserBetween(
      String userId, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbcClient
        .sql(
            SELECT_TODO
                + """
                 WHERE user_id = :userId
                   AND local_date >= :from
                   AND local_date <= :to
                 ORDER BY local_date, sort_order, id
                """)
        .param("userId", userId)
        .param("from", fromInclusive.toString())
        .param("to", toInclusive.toString())
        .query(this::mapTodo)
        .list();
  }

  @Override
  public List<Todo> findPendingBefore(String userId, LocalDate beforeExclusive) {
    return jdbcClient
        .sql(
            SELECT_TODO
                + """
                 WHERE user_id = :userId
                   AND local_date < :before
                   AND status <> 'DONE'
                 ORDER BY local_date, sort_order, id
                """)
        .param("userId", userId)
        .param("before", beforeExclusive.toString())
        .query(this::mapTodo)
        .list();
  }

  @Override
  public int countPendingOn(String userId, LocalDate localDate) {
    Long count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM todos
                 WHERE user_id = :userId AND local_date = :date AND status <> 'DONE'
                """)
            .param("userId", userId)
            .param("date", localDate.toString())
            .query(Long.class)
            .single();
    return count == null ? 0 : count.intValue();
  }

  @Override
  public int nextSortOrder(String userId, LocalDate localDate) {
    Long max =
        jdbcClient
            .sql(
                """
                SELECT COALESCE(max(sort_order), -1) FROM todos
                 WHERE user_id = :userId AND local_date = :date
                """)
            .param("userId", userId)
            .param("date", localDate.toString())
            .query(Long.class)
            .single();
    return max == null ? 0 : max.intValue() + 1;
  }

  @Override
  public void insert(Todo todo, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO todos (
                id, user_id, local_date, todo_type, title, note, sort_order, status,
                resource_id, target_progress_permille, progress_position_ms, progress_page,
                watched_ms, planned_seconds, focus_state, focus_started_at, focused_ms,
                require_evidence, completed_at, backfilled, backfill_note,
                supervisor_user_id_snapshot, created_at, updated_at
            ) VALUES (
                :id, :userId, :localDate, :todoType, :title, :note, :sortOrder, :status,
                :resourceId, :target, :positionMs, :page,
                :watchedMs, :plannedSeconds, :focusState, :focusStartedAt, :focusedMs,
                :requireEvidence, :completedAt, :backfilled, :backfillNote,
                :supervisor, :now, :now
            )
            """)
        .param("id", todo.id())
        .param("userId", todo.userId())
        .param("localDate", todo.localDate().toString())
        .param("todoType", todo.todoType().name())
        .param("title", todo.title())
        .param("note", todo.note())
        .param("sortOrder", todo.sortOrder())
        .param("status", todo.status().name())
        .param("resourceId", todo.resourceId())
        .param("target", todo.targetProgressPermille())
        .param("positionMs", todo.progressPositionMs())
        .param("page", todo.progressPage())
        .param("watchedMs", todo.watchedMs())
        .param("plannedSeconds", todo.plannedSeconds())
        .param("focusState", todo.focusState().name())
        .param("focusStartedAt", millis(todo.focusStartedAt()))
        .param("focusedMs", todo.focusedMs())
        .param("requireEvidence", todo.requireEvidence() ? 1 : 0)
        .param("completedAt", millis(todo.completedAt()))
        .param("backfilled", todo.backfilled() ? 1 : 0)
        .param("backfillNote", todo.backfillNote())
        .param("supervisor", todo.supervisorUserIdSnapshot())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void updateEditableFields(
      String todoId,
      String title,
      String note,
      Integer targetProgressPermille,
      Integer plannedSeconds,
      boolean requireEvidence,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET title = :title,
                   note = :note,
                   target_progress_permille = COALESCE(:target, target_progress_permille),
                   planned_seconds = COALESCE(:plannedSeconds, planned_seconds),
                   require_evidence = :requireEvidence,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("title", title)
        .param("note", note)
        .param("target", targetProgressPermille)
        .param("plannedSeconds", plannedSeconds)
        .param("requireEvidence", requireEvidence ? 1 : 0)
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateSortOrder(String todoId, int sortOrder, Instant now) {
    jdbcClient
        .sql("UPDATE todos SET sort_order = :sortOrder, updated_at = :now WHERE id = :id")
        .param("sortOrder", sortOrder)
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateLocalDate(String todoId, LocalDate localDate, int sortOrder, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET local_date = :localDate, sort_order = :sortOrder, updated_at = :now
             WHERE id = :id
            """)
        .param("localDate", localDate.toString())
        .param("sortOrder", sortOrder)
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateCompletionSupervisor(String todoId, String supervisorId) {
    jdbcClient
        .sql("UPDATE todos SET supervisor_user_id_snapshot = :supervisor WHERE id = :id")
        .param("supervisor", supervisorId)
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateStatus(String todoId, String status, Instant completedAt, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET status = :status, completed_at = :completedAt, updated_at = :now
             WHERE id = :id
            """)
        .param("status", status)
        .param("completedAt", millis(completedAt))
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateProgress(
      String todoId,
      long positionMs,
      int positionPage,
      long watchedMs,
      String status,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET progress_position_ms = :positionMs,
                   progress_page = :page,
                   watched_ms = :watchedMs,
                   status = :status,
                   completed_at = CASE WHEN :status = 'DONE' AND completed_at IS NULL
                                       THEN :now ELSE completed_at END,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("positionMs", positionMs)
        .param("page", positionPage)
        .param("watchedMs", watchedMs)
        .param("status", status)
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateFocus(
      String todoId,
      String focusState,
      Instant focusStartedAt,
      long focusedMs,
      String status,
      Instant completedAt,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET focus_attempt_base_ms = CASE WHEN :focusState = 'RUNNING' AND focus_state IN ('IDLE', 'STOPPED')
                                                THEN focused_ms ELSE focus_attempt_base_ms END,
                   focus_state = :focusState,
                   focus_started_at = :startedAt,
                   focused_ms = :focusedMs,
                   status = :status,
                   completed_at = :completedAt,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("focusState", focusState)
        .param("startedAt", millis(focusStartedAt))
        .param("focusedMs", focusedMs)
        .param("status", status)
        .param("completedAt", millis(completedAt))
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void markBackfilled(String todoId, String note, Instant completedAt, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE todos
               SET status = 'DONE',
                   backfilled = 1,
                   backfill_note = :note,
                   completed_at = :completedAt,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("note", note)
        .param("completedAt", completedAt.toEpochMilli())
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void updateNote(String todoId, String note, Instant now) {
    jdbcClient
        .sql("UPDATE todos SET note = :note, updated_at = :now WHERE id = :id")
        .param("note", note)
        .param("now", now.toEpochMilli())
        .param("id", todoId)
        .update();
  }

  @Override
  public void delete(String todoId) {
    jdbcClient.sql("DELETE FROM todos WHERE id = :id").param("id", todoId).update();
  }

  @Override
  public Optional<Todo> findRunningFocus(String userId) {
    return jdbcClient
        .sql(SELECT_TODO + " WHERE user_id = :userId AND focus_state = 'RUNNING'")
        .param("userId", userId)
        .query(this::mapTodo)
        .optional();
  }

  @Override
  public void replaceNoteTags(String todoId, Set<NoteTag> tags) {
    jdbcClient
        .sql("DELETE FROM todo_note_tags WHERE todo_id = :todoId")
        .param("todoId", todoId)
        .update();
    for (NoteTag tag : tags) {
      jdbcClient
          .sql(
              """
              INSERT INTO todo_note_tags (todo_id, tag) VALUES (:todoId, :tag)
              ON CONFLICT(todo_id, tag) DO NOTHING
              """)
          .param("todoId", todoId)
          .param("tag", tag.name())
          .update();
    }
  }

  @Override
  public Set<NoteTag> noteTagsOf(String todoId) {
    Set<NoteTag> tags = EnumSet.noneOf(NoteTag.class);
    jdbcClient
        .sql("SELECT tag FROM todo_note_tags WHERE todo_id = :todoId")
        .param("todoId", todoId)
        .query(String.class)
        .list()
        .forEach(value -> tags.add(NoteTag.valueOf(value)));
    return tags;
  }

  @Override
  public Map<String, Set<NoteTag>> noteTagsOf(List<String> todoIds) {
    if (todoIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<NoteTag>> result = new LinkedHashMap<>();
    jdbcClient
        .sql("SELECT todo_id, tag FROM todo_note_tags WHERE todo_id IN (:ids)")
        .param("ids", todoIds)
        .query(
            (row, rowNumber) -> {
              result
                  .computeIfAbsent(row.getString("todo_id"), key -> EnumSet.noneOf(NoteTag.class))
                  .add(NoteTag.valueOf(row.getString("tag")));
              return null;
            })
        .list();
    return result;
  }

  @Override
  public List<TagCount> countNoteTags(String userId, Instant fromInclusive, Instant toExclusive) {
    return jdbcClient
        .sql(
            """
            SELECT nt.tag AS tag, count(*) AS tag_count
              FROM todo_note_tags nt
              JOIN todos t ON t.id = nt.todo_id
             WHERE t.user_id = :userId
               AND t.completed_at >= :from
               AND t.completed_at < :to
             GROUP BY nt.tag
             ORDER BY tag_count DESC
            """)
        .param("userId", userId)
        .param("from", fromInclusive.toEpochMilli())
        .param("to", toExclusive.toEpochMilli())
        .query(
            (row, rowNumber) ->
                new TagCount(NoteTag.valueOf(row.getString("tag")), row.getInt("tag_count")))
        .list();
  }

  @Override
  public void insertAttachment(Attachment attachment) {
    jdbcClient
        .sql(
            """
            INSERT INTO todo_attachments (
                id, todo_id, user_id, storage_path, original_filename,
                content_type, size_bytes, sha256, sort_order, created_at
            ) VALUES (
                :id, :todoId, :userId, :path, :filename,
                :contentType, :sizeBytes, :sha256, :sortOrder, :createdAt
            )
            """)
        .param("id", attachment.id())
        .param("todoId", attachment.todoId())
        .param("userId", attachment.userId())
        .param("path", attachment.storagePath())
        .param("filename", attachment.originalFilename())
        .param("contentType", attachment.contentType())
        .param("sizeBytes", attachment.sizeBytes())
        .param("sha256", attachment.sha256())
        .param("sortOrder", attachment.sortOrder())
        .param("createdAt", attachment.createdAt().toEpochMilli())
        .update();
  }

  @Override
  public List<Attachment> attachmentsOf(String todoId) {
    return jdbcClient
        .sql(
            """
            SELECT id, todo_id, user_id, storage_path, original_filename, content_type,
                   size_bytes, sha256, sort_order, created_at
              FROM todo_attachments
             WHERE todo_id = :todoId
             ORDER BY sort_order, id
            """)
        .param("todoId", todoId)
        .query(this::mapAttachment)
        .list();
  }

  @Override
  public Map<String, Integer> attachmentCounts(List<String> todoIds) {
    if (todoIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbcClient
        .sql(
            """
            SELECT todo_id, count(*) AS attachment_count
              FROM todo_attachments
             WHERE todo_id IN (:ids)
             GROUP BY todo_id
            """)
        .param("ids", todoIds)
        .query(
            (row, rowNumber) -> {
              counts.put(row.getString("todo_id"), row.getInt("attachment_count"));
              return null;
            })
        .list();
    return counts;
  }

  @Override
  public Optional<Attachment> findAttachment(String attachmentId) {
    return jdbcClient
        .sql(
            """
            SELECT id, todo_id, user_id, storage_path, original_filename, content_type,
                   size_bytes, sha256, sort_order, created_at
              FROM todo_attachments
             WHERE id = :id
            """)
        .param("id", attachmentId)
        .query(this::mapAttachment)
        .optional();
  }

  @Override
  public void deleteAttachment(String attachmentId) {
    jdbcClient
        .sql("DELETE FROM todo_attachments WHERE id = :id")
        .param("id", attachmentId)
        .update();
  }

  @Override
  public void deleteAttachmentsOf(String todoId) {
    jdbcClient
        .sql("DELETE FROM todo_attachments WHERE todo_id = :todoId")
        .param("todoId", todoId)
        .update();
  }

  @Override
  public boolean progressEventExists(String todoId, long clientSeq) {
    Long count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM todo_progress_events
                 WHERE todo_id = :todoId AND client_seq = :seq
                """)
            .param("todoId", todoId)
            .param("seq", clientSeq)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  @Override
  public void insertProgressEvent(ProgressEvent event) {
    jdbcClient
        .sql(
            """
            INSERT INTO todo_progress_events (
                id, todo_id, user_id, client_seq, event_type, position_ms, position_page,
                delta_watched_ms, delta_focused_ms, app_state, occurred_at, created_at
            ) VALUES (
                :id, :todoId, :userId, :seq, :eventType, :positionMs, :page,
                :deltaWatched, :deltaFocused, :appState, :occurredAt, :createdAt
            )
            """)
        .param("id", event.id())
        .param("todoId", event.todoId())
        .param("userId", event.userId())
        .param("seq", event.clientSeq())
        .param("eventType", event.eventType())
        .param("positionMs", event.positionMs())
        .param("page", event.positionPage())
        .param("deltaWatched", event.deltaWatchedMs())
        .param("deltaFocused", event.deltaFocusedMs())
        .param("appState", event.appState())
        .param("occurredAt", event.occurredAt().toEpochMilli())
        .param("createdAt", event.createdAt().toEpochMilli())
        .update();
  }

  @Override
  public void deleteProgressEventsOf(String todoId) {
    jdbcClient
        .sql("DELETE FROM todo_progress_events WHERE todo_id = :todoId")
        .param("todoId", todoId)
        .update();
  }

  @Override
  public List<DurationBucket> aggregateDurations(
      String userId, Instant fromInclusive, Instant toExclusive) {
    return jdbcClient
        .sql(
            """
            SELECT occurred_at, delta_watched_ms, delta_focused_ms, todo_local_date
              FROM todo_progress_events
             WHERE user_id = :userId
               AND occurred_at >= :from
               AND occurred_at < :to
             ORDER BY occurred_at
            """)
        .param("userId", userId)
        .param("from", fromInclusive.toEpochMilli())
        .param("to", toExclusive.toEpochMilli())
        .query(
            (row, rowNumber) ->
                new DurationBucket(
                    Instant.ofEpochMilli(row.getLong("occurred_at")),
                    row.getLong("delta_watched_ms"),
                    row.getLong("delta_focused_ms"),
                    row.getString("todo_local_date") == null
                        ? null
                        : LocalDate.parse(row.getString("todo_local_date"))))
        .list();
  }

  /** 历史未完成项可继续执行；本日已完成项保留作还债记录。 */
  @Override
  public List<Todo> findRepaymentTodos(String userId, LocalDate date, Instant from, Instant to) {
    return jdbcClient
        .sql(
            SELECT_TODO
                + """
        WHERE user_id = :user AND (
          (local_date < :date AND status <> 'DONE') OR
          (completed_local_date < :date AND completed_at >= :from AND completed_at < :to))
        ORDER BY local_date, sort_order, id
        """)
        .param("user", userId)
        .param("date", date.toString())
        .param("from", from.toEpochMilli())
        .param("to", to.toEpochMilli())
        .query(this::mapTodo)
        .list();
  }

  /** 完成分类读取快照，不能读取可能已经顺延的当前计划日期。 */
  @Override
  public List<CompletionBucket> findCompletions(String userId, Instant from, Instant to) {
    return jdbcClient
        .sql(
            """
        SELECT completed_at, completed_local_date FROM todos
        WHERE user_id = :user AND completed_at >= :from AND completed_at < :to
          AND completed_local_date IS NOT NULL
        """)
        .param("user", userId)
        .param("from", from.toEpochMilli())
        .param("to", to.toEpochMilli())
        .query(
            (row, number) ->
                new CompletionBucket(
                    Instant.ofEpochMilli(row.getLong("completed_at")),
                    LocalDate.parse(row.getString("completed_local_date"))))
        .list();
  }

  @Override
  public List<ResourceWatchBucket> aggregateResourceWatchedMs(
      String userId, Instant fromInclusive, Instant toExclusive) {
    // 只使用事件时间及增量，不能把 Todo 的跨日累计值记到顺延后的日期。
    return jdbcClient
        .sql(
            """
        SELECT t.resource_id, SUM(e.delta_watched_ms) AS watched_ms
          FROM todo_progress_events e JOIN todos t ON t.id = e.todo_id
         WHERE e.user_id = :userId AND e.occurred_at >= :from AND e.occurred_at < :to
           AND t.resource_id IS NOT NULL AND e.delta_watched_ms > 0
         GROUP BY t.resource_id
        """)
        .param("userId", userId)
        .param("from", fromInclusive.toEpochMilli())
        .param("to", toExclusive.toEpochMilli())
        .query(
            (row, number) ->
                new ResourceWatchBucket(row.getString("resource_id"), row.getLong("watched_ms")))
        .list();
  }

  @Override
  public void upsertWatchState(
      String userId,
      String resourceId,
      long maxPositionMs,
      int maxPositionPage,
      long addedWatchedMs,
      int addedCompleted,
      Instant lastWatchedAt) {
    jdbcClient
        .sql(
            """
            INSERT INTO lesson_watch_states (
                user_id, resource_id, max_position_ms, max_position_page,
                total_watched_ms, completed_count, last_watched_at
            ) VALUES (
                :userId, :resourceId, :maxPositionMs, :maxPositionPage,
                :addedWatchedMs, :addedCompleted, :lastWatchedAt
            )
            ON CONFLICT(user_id, resource_id) DO UPDATE SET
                max_position_ms = MAX(max_position_ms, excluded.max_position_ms),
                max_position_page = MAX(max_position_page, excluded.max_position_page),
                total_watched_ms = total_watched_ms + excluded.total_watched_ms,
                completed_count = completed_count + excluded.completed_count,
                last_watched_at = excluded.last_watched_at
            """)
        .param("userId", userId)
        .param("resourceId", resourceId)
        .param("maxPositionMs", maxPositionMs)
        .param("maxPositionPage", maxPositionPage)
        .param("addedWatchedMs", addedWatchedMs)
        .param("addedCompleted", addedCompleted)
        .param("lastWatchedAt", lastWatchedAt.toEpochMilli())
        .update();
  }

  @Override
  public Map<String, WatchState> watchStatesOf(String userId, List<String> resourceIds) {
    if (resourceIds.isEmpty()) {
      return Map.of();
    }
    Map<String, WatchState> result = new LinkedHashMap<>();
    jdbcClient
        .sql(
            """
            SELECT resource_id, max_position_ms, max_position_page,
                   total_watched_ms, completed_count, last_watched_at
              FROM lesson_watch_states
             WHERE user_id = :userId AND resource_id IN (:ids)
            """)
        .param("userId", userId)
        .param("ids", resourceIds)
        .query(
            (row, rowNumber) -> {
              WatchState state = mapWatchState(row);
              result.put(state.resourceId(), state);
              return null;
            })
        .list();
    return result;
  }

  @Override
  public Map<String, WatchState> watchStatesOfCourse(String userId, String courseId) {
    Map<String, WatchState> result = new LinkedHashMap<>();
    jdbcClient
        .sql(
            """
            SELECT s.resource_id, s.max_position_ms, s.max_position_page,
                   s.total_watched_ms, s.completed_count, s.last_watched_at
              FROM lesson_watch_states s
              JOIN learning_resources r ON r.id = s.resource_id
             WHERE s.user_id = :userId AND r.course_id = :courseId
            """)
        .param("userId", userId)
        .param("courseId", courseId)
        .query(
            (row, rowNumber) -> {
              WatchState state = mapWatchState(row);
              result.put(state.resourceId(), state);
              return null;
            })
        .list();
    return result;
  }

  @Override
  public void insertDeletion(Deletion deletion) {
    jdbcClient
        .sql(
            """
            INSERT INTO todo_deletions (
                id, user_id, todo_id, todo_type, local_date, title_snapshot, resource_id,
                progress_snapshot_json, reason_tag, reason_text,
                supervisor_user_id_snapshot, deleted_at
            ) VALUES (
                :id, :userId, :todoId, :todoType, :localDate, :title, :resourceId,
                :snapshot, :reasonTag, :reasonText, :supervisor, :deletedAt
            )
            """)
        .param("id", deletion.id())
        .param("userId", deletion.userId())
        .param("todoId", deletion.todoId())
        .param("todoType", deletion.todoType().name())
        .param("localDate", deletion.localDate().toString())
        .param("title", deletion.titleSnapshot())
        .param("resourceId", deletion.resourceId())
        .param("snapshot", deletion.progressSnapshotJson())
        .param("reasonTag", deletion.reasonTag().name())
        .param("reasonText", deletion.reasonText())
        .param("supervisor", deletion.supervisorUserIdSnapshot())
        .param("deletedAt", deletion.deletedAt().toEpochMilli())
        .update();
  }

  @Override
  public List<Deletion> findDeletions(String userId, Instant fromInclusive, Instant toExclusive) {
    return jdbcClient
        .sql(
            selectDeletion()
                + """
                 WHERE user_id = :userId AND deleted_at >= :from AND deleted_at < :to
                 ORDER BY deleted_at DESC
                """)
        .param("userId", userId)
        .param("from", fromInclusive.toEpochMilli())
        .param("to", toExclusive.toEpochMilli())
        .query(this::mapDeletion)
        .list();
  }

  @Override
  public List<Deletion> findAllDeletions(int limit) {
    return jdbcClient
        .sql(selectDeletion() + " ORDER BY deleted_at DESC LIMIT :limit")
        .param("limit", limit)
        .query(this::mapDeletion)
        .list();
  }

  @Override
  public int countDeletionsOn(String userId, LocalDate localDate) {
    Long count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM todo_deletions
                 WHERE user_id = :userId AND local_date = :date
                """)
            .param("userId", userId)
            .param("date", localDate.toString())
            .query(Long.class)
            .single();
    return count == null ? 0 : count.intValue();
  }

  private String selectDeletion() {
    return """
        SELECT id, user_id, todo_id, todo_type, local_date, title_snapshot, resource_id,
               progress_snapshot_json, reason_tag, reason_text,
               supervisor_user_id_snapshot, deleted_at
          FROM todo_deletions
        """;
  }

  private Long millis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }

  private Todo mapTodo(ResultSet row, int rowNumber) throws SQLException {
    return new Todo(
        row.getString("id"),
        row.getString("user_id"),
        LocalDate.parse(row.getString("local_date")),
        TodoType.valueOf(row.getString("todo_type")),
        row.getString("title"),
        row.getString("note"),
        row.getInt("sort_order"),
        TodoStatus.valueOf(row.getString("status")),
        row.getString("resource_id"),
        nullableInt(row, "target_progress_permille"),
        row.getLong("progress_position_ms"),
        row.getInt("progress_page"),
        row.getLong("watched_ms"),
        nullableInt(row, "planned_seconds"),
        FocusState.valueOf(row.getString("focus_state")),
        nullableInstant(row, "focus_started_at"),
        row.getLong("focused_ms"),
        row.getInt("require_evidence") == 1,
        nullableInstant(row, "completed_at"),
        row.getInt("backfilled") == 1,
        row.getString("backfill_note"),
        row.getString("supervisor_user_id_snapshot"),
        row.getLong("focus_attempt_base_ms"));
  }

  private Attachment mapAttachment(ResultSet row, int rowNumber) throws SQLException {
    return new Attachment(
        row.getString("id"),
        row.getString("todo_id"),
        row.getString("user_id"),
        row.getString("storage_path"),
        row.getString("original_filename"),
        row.getString("content_type"),
        row.getLong("size_bytes"),
        row.getString("sha256"),
        row.getInt("sort_order"),
        Instant.ofEpochMilli(row.getLong("created_at")));
  }

  private WatchState mapWatchState(ResultSet row) throws SQLException {
    return new WatchState(
        row.getString("resource_id"),
        row.getLong("max_position_ms"),
        row.getInt("max_position_page"),
        row.getLong("total_watched_ms"),
        row.getInt("completed_count"),
        nullableInstant(row, "last_watched_at"));
  }

  private Deletion mapDeletion(ResultSet row, int rowNumber) throws SQLException {
    return new Deletion(
        row.getString("id"),
        row.getString("user_id"),
        row.getString("todo_id"),
        TodoType.valueOf(row.getString("todo_type")),
        LocalDate.parse(row.getString("local_date")),
        row.getString("title_snapshot"),
        row.getString("resource_id"),
        row.getString("progress_snapshot_json"),
        DeletionReasonTag.valueOf(row.getString("reason_tag")),
        row.getString("reason_text"),
        row.getString("supervisor_user_id_snapshot"),
        Instant.ofEpochMilli(row.getLong("deleted_at")));
  }

  private Instant nullableInstant(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : Instant.ofEpochMilli(value);
  }

  private Integer nullableInt(ResultSet row, String column) throws SQLException {
    int value = row.getInt(column);
    return row.wasNull() ? null : value;
  }
}
