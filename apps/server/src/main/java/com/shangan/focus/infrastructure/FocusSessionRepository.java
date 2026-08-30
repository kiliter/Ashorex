package com.shangan.focus.infrastructure;

import com.shangan.focus.domain.FocusSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 专注会话持久化边界。 */
public interface FocusSessionRepository {
  Optional<FocusSession> findOwned(String userId, String sessionId);

  Optional<FocusSession> findActive(String userId);

  List<FocusSession> findActiveByPlan(String userId, String planId);

  void insert(FocusSession session, Instant now);

  void update(FocusSession session, Instant now);
}
