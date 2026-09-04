package com.shangan.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.focus.application.MockExamService;
import com.shangan.focus.infrastructure.MockExamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 覆盖模拟考试重考：仅交卷或完成后可按快照时长重新开倒计时。 */
class MockExamServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void retakeRestartsDeadlineFromSnapshotDuration() {
    FakeRepository exams = new FakeRepository();
    exams.put(
        session("AWAITING_UPLOAD", NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(60)));
    MockExamService service = service(exams);

    MockExamService.SessionView view = service.retake("user-1", "exam-1");

    assertThat(view.status()).isEqualTo("RUNNING");
    assertThat(view.startedAt()).isEqualTo(NOW);
    assertThat(view.deadlineAt()).isEqualTo(NOW.plusSeconds(1800));
    assertThat(view.submittedAt()).isNull();
    assertThat(view.completedAt()).isNull();
  }

  @Test
  void runningSessionCannotRetake() {
    FakeRepository exams = new FakeRepository();
    exams.put(session("RUNNING", NOW, NOW.plusSeconds(1800), null));
    MockExamService service = service(exams);

    assertThatThrownBy(() -> service.retake("user-1", "exam-1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo("MOCK_EXAM_ILLEGAL_TRANSITION"));
  }

  private MockExamService service(FakeRepository exams) {
    return new MockExamService(exams, () -> "exam-1", CLOCK, "./build/mock-exam-test");
  }

  private MockExamRepository.SessionRow session(
      String status, Instant startedAt, Instant deadlineAt, Instant submittedAt) {
    return new MockExamRepository.SessionRow(
        "exam-1",
        "user-1",
        "item-1",
        "行测",
        1800,
        status,
        startedAt,
        deadlineAt,
        submittedAt,
        "COMPLETED".equals(status) ? submittedAt : null);
  }

  private static final class FakeRepository implements MockExamRepository {
    private final Map<String, SessionRow> sessions = new HashMap<>();
    private final List<AttachmentRow> attachments = new ArrayList<>();

    void put(SessionRow session) {
      sessions.put(session.id(), session);
    }

    @Override
    public Optional<PlanItemRow> findOwnedPlanItem(String userId, String planItemId) {
      return Optional.empty();
    }

    @Override
    public Optional<SessionRow> findByPlanItem(String userId, String planItemId) {
      return sessions.values().stream()
          .filter(item -> item.userId().equals(userId) && item.planItemId().equals(planItemId))
          .findFirst();
    }

    @Override
    public Optional<SessionRow> findOwnedSession(String userId, String sessionId) {
      SessionRow session = sessions.get(sessionId);
      if (session == null || !session.userId().equals(userId)) return Optional.empty();
      return Optional.of(session);
    }

    @Override
    public void insertSession(SessionRow session, Instant now) {
      sessions.put(session.id(), session);
    }

    @Override
    public void markAwaitingUpload(String userId, String sessionId, Instant submittedAt) {
      SessionRow current = sessions.get(sessionId);
      sessions.put(
          sessionId,
          new SessionRow(
              current.id(),
              current.userId(),
              current.planItemId(),
              current.name(),
              current.durationSeconds(),
              "AWAITING_UPLOAD",
              current.startedAt(),
              current.deadlineAt(),
              submittedAt,
              current.completedAt()));
    }

    @Override
    public void retake(String userId, String sessionId, Instant startedAt, Instant deadlineAt) {
      SessionRow current = sessions.get(sessionId);
      sessions.put(
          sessionId,
          new SessionRow(
              current.id(),
              current.userId(),
              current.planItemId(),
              current.name(),
              current.durationSeconds(),
              "RUNNING",
              startedAt,
              deadlineAt,
              null,
              null));
    }

    @Override
    public int countAttachments(String userId, String sessionId) {
      return attachments.size();
    }

    @Override
    public void insertAttachment(AttachmentRow attachment) {
      attachments.add(attachment);
    }

    @Override
    public List<AttachmentRow> findAttachments(String userId, String sessionId) {
      return List.copyOf(attachments);
    }

    @Override
    public void complete(String userId, String sessionId, String planItemId, Instant completedAt) {
      SessionRow current = sessions.get(sessionId);
      sessions.put(
          sessionId,
          new SessionRow(
              current.id(),
              current.userId(),
              current.planItemId(),
              current.name(),
              current.durationSeconds(),
              "COMPLETED",
              current.startedAt(),
              current.deadlineAt(),
              current.submittedAt(),
              completedAt));
    }
  }
}
