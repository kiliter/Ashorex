package com.shangan.todo.infrastructure;

import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Todo 聚合的持久化边界，覆盖 Todo 行、备注标签、附件、进度流水、课时累计与删除台账。 */
public interface TodoRepository {

  /** 查询幂等请求已执行的操作，重复请求不得重复计时。 */
  Optional<String> focusRequestAction(String todoId, String requestId);

  /** 与状态变更处于同一事务，复用现有流水的统计和级联生命周期。 */
  void recordFocusAction(Todo before, Todo after, String action, String requestId, Instant now);

  /** 已安排或观看过的课时可标记复习，查询始终限定当前用户。 */
  boolean hasCourseHistory(String userId, String resourceId);

  /** 批量读取持久复习标记，避免列表逐行查库。 */
  Set<String> reviewTodoIds(List<String> todoIds);

  /** 新建后在同一事务中标记复习，不重写历史项。 */
  void markReview(String todoId);

  /** 先取得写锁再读取幂等结果，防止并发请求重复生成一批待办。 */
  void reserveCourseAddition(String userId, String requestId, String fingerprint);

  Optional<CourseAdditionReceipt> courseAdditionReceipt(String userId, String requestId);

  void completeCourseAddition(String userId, String requestId, String resultJson);

  record CourseAdditionReceipt(String fingerprint, String resultJson) {}

  /** 复习轮次与恢复位置独立于课时累计记录，列表批量读取。 */
  Map<String, PlaybackSession> playbackSessions(List<String> todoIds);

  /** 乐观版本和请求标识保证确认重试不重复清零，只更新当前 Todo 的播放字段。 */
  boolean resetPlayback(String todoId, long expectedEpoch, String requestId, Instant now);

  /** 只接受本轮较新的序号，防止迟到报告回写续播位置。 */
  void rememberPlayback(String todoId, long epoch, long sequence, long positionMs);

  record PlaybackSession(long epoch, long resumePositionMs, String resetId) {}

  // ---------- Todo 行 ----------

  Optional<Todo> findById(String id);

  List<Todo> findByUserAndDate(String userId, LocalDate localDate);

  /** 某用户在一段日期区间内的全部 Todo，用于周月视图聚合。 */
  List<Todo> findByUserBetween(String userId, LocalDate fromInclusive, LocalDate toInclusive);

  /** 跨日期未完成 Todo，按日期升序，用于未完成汇总。 */
  List<Todo> findPendingBefore(String userId, LocalDate beforeExclusive);

  int countPendingOn(String userId, LocalDate localDate);

  int nextSortOrder(String userId, LocalDate localDate);

  void insert(Todo todo, Instant now);

  void updateEditableFields(
      String todoId,
      String title,
      String note,
      Integer targetProgressPermille,
      Integer plannedSeconds,
      boolean requireEvidence,
      Instant now);

  void updateSortOrder(String todoId, int sortOrder, Instant now);

  void updateLocalDate(String todoId, LocalDate localDate, int sortOrder, Instant now);

  /** 完成事件写入时更新督学人快照。 */
  void updateCompletionSupervisor(String todoId, String supervisorId);

  void updateStatus(String todoId, String status, Instant completedAt, Instant now);

  void updateProgress(
      String todoId, long positionMs, int positionPage, long watchedMs, String status, Instant now);

  void updateFocus(
      String todoId,
      String focusState,
      Instant focusStartedAt,
      long focusedMs,
      String status,
      Instant completedAt,
      Instant now);

  void markBackfilled(String todoId, String note, Instant completedAt, Instant now);

  void updateNote(String todoId, String note, Instant now);

  void delete(String todoId);

  /** 某用户是否已有处于 RUNNING 的专注 Todo。 */
  Optional<Todo> findRunningFocus(String userId);

  // ---------- 备注标签 ----------

  void replaceNoteTags(String todoId, Set<NoteTag> tags);

  Set<NoteTag> noteTagsOf(String todoId);

  Map<String, Set<NoteTag>> noteTagsOf(List<String> todoIds);

  /** 按标签统计次数，用于数据 Tab 聚合。 */
  List<TagCount> countNoteTags(String userId, Instant fromInclusive, Instant toExclusive);

  // ---------- 附件 ----------

  void insertAttachment(Attachment attachment);

  List<Attachment> attachmentsOf(String todoId);

  Map<String, Integer> attachmentCounts(List<String> todoIds);

  Optional<Attachment> findAttachment(String attachmentId);

  void deleteAttachment(String attachmentId);

  void deleteAttachmentsOf(String todoId);

  // ---------- 进度流水 ----------

  boolean progressEventExists(String todoId, long clientSeq);

  void insertProgressEvent(ProgressEvent event);

  void deleteProgressEventsOf(String todoId);

  /** 时长流水聚合，用于日周月统计。 */
  List<DurationBucket> aggregateDurations(
      String userId, Instant fromInclusive, Instant toExclusive);

  /** 按实际观看事件时间聚合课时，顺延 Todo 不改变历史排行。 */
  List<ResourceWatchBucket> aggregateResourceWatchedMs(
      String userId, Instant fromInclusive, Instant toExclusive);

  /** 一个课时在查询窗口内的实际观看时长。 */
  record ResourceWatchBucket(String resourceId, long watchedMs) {}

  // ---------- 课时累计状态 ----------

  void upsertWatchState(
      String userId,
      String resourceId,
      long maxPositionMs,
      int maxPositionPage,
      long addedWatchedMs,
      int addedCompleted,
      Instant lastWatchedAt);

  Map<String, WatchState> watchStatesOf(String userId, List<String> resourceIds);

  Map<String, WatchState> watchStatesOfCourse(String userId, String courseId);

  // ---------- 删除台账 ----------

  void insertDeletion(Deletion deletion);

  List<Deletion> findDeletions(String userId, Instant fromInclusive, Instant toExclusive);

  List<Deletion> findAllDeletions(int limit);

  int countDeletionsOn(String userId, LocalDate localDate);

  /** 附件元数据；storage_path 为服务端生成的相对路径。 */
  record Attachment(
      String id,
      String todoId,
      String userId,
      String storagePath,
      String originalFilename,
      String contentType,
      long sizeBytes,
      String sha256,
      int sortOrder,
      Instant createdAt) {}

  /** 一次进度上报流水。 */
  record ProgressEvent(
      String id,
      String todoId,
      String userId,
      long clientSeq,
      String eventType,
      Long positionMs,
      Integer positionPage,
      long deltaWatchedMs,
      long deltaFocusedMs,
      String appState,
      Instant occurredAt,
      Instant createdAt) {}

  /** 跨 Todo 的课时累计状态。 */
  record WatchState(
      String resourceId,
      long maxPositionMs,
      int maxPositionPage,
      long totalWatchedMs,
      int completedCount,
      Instant lastWatchedAt) {}

  /** 删除台账行。 */
  record Deletion(
      String id,
      String userId,
      String todoId,
      TodoType todoType,
      LocalDate localDate,
      String titleSnapshot,
      String resourceId,
      String progressSnapshotJson,
      DeletionReasonTag reasonTag,
      String reasonText,
      String supervisorUserIdSnapshot,
      Instant deletedAt) {}

  /** 按本地日期归集的时长聚合结果。 */
  record DurationBucket(Instant occurredAt, long watchedMs, long focusedMs, LocalDate plannedDate) {
    /** 兼容没有计划日期的旧调用；未知归属不推测为还债。 */
    public DurationBucket(Instant occurredAt, long watchedMs, long focusedMs) {
      this(occurredAt, watchedMs, focusedMs, null);
    }
  }

  /** 查询当日历史未完成项和本日完成的历史项；原计划日期不变。 */
  List<Todo> findRepaymentTodos(String userId, LocalDate date, Instant from, Instant to);

  /** 完成时的原计划日期快照，用于区分实际完成日的还债记录。 */
  record CompletionBucket(Instant completedAt, LocalDate plannedDate) {}

  List<CompletionBucket> findCompletions(String userId, Instant from, Instant to);

  /** 备注标签计数。 */
  record TagCount(NoteTag tag, int count) {}
}
