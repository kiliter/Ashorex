package com.shangan.focus.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 模拟考试会话与试卷附件持久化边界。 */
public interface MockExamRepository {
  Optional<PlanItemRow> findOwnedPlanItem(String userId, String planItemId);

  Optional<SessionRow> findByPlanItem(String userId, String planItemId);

  Optional<SessionRow> findOwnedSession(String userId, String sessionId);

  void insertSession(SessionRow session, Instant now);

  void markAwaitingUpload(String userId, String sessionId, Instant submittedAt);

  int countAttachments(String userId, String sessionId);

  void insertAttachment(AttachmentRow attachment);

  List<AttachmentRow> findAttachments(String userId, String sessionId);

  void complete(String userId, String sessionId, String planItemId, Instant completedAt);

  record PlanItemRow(String id, String name, long durationSeconds, String status) {}

  record SessionRow(
      String id,
      String userId,
      String planItemId,
      String name,
      long durationSeconds,
      String status,
      Instant startedAt,
      Instant deadlineAt,
      Instant submittedAt,
      Instant completedAt) {}

  record AttachmentRow(
      String id,
      String sessionId,
      String userId,
      String storagePath,
      String originalFilename,
      String contentType,
      long sizeBytes,
      String sha256,
      int sortOrder,
      Instant createdAt) {}
}
