package com.shangan.learning.application;

import com.shangan.learning.infrastructure.WatchSessionRepository;
import com.shangan.planning.application.ActiveLearningCloser;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 计划关闭前结束其全部活动观看会话，确保欠债基于最终可信进度计算。 */
@Component
public class WatchSessionPlanCloser implements ActiveLearningCloser {
  private final WatchSessionRepository sessions;
  private final WatchSessionService service;

  public WatchSessionPlanCloser(WatchSessionRepository sessions, WatchSessionService service) {
    this.sessions = sessions;
    this.service = service;
  }

  @Override
  public void closeForPlan(String userId, String planId, Instant closedAt) {
    sessions
        .findOpenForPlan(userId, planId)
        .forEach(session -> service.stopAt(userId, session.id(), closedAt));
  }
}
