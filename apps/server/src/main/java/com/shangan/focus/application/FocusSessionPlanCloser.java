package com.shangan.focus.application;

import com.shangan.planning.application.ActiveLearningCloser;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 计划关闭前取消所有关联专注会话，并先把有效时长同步回计划。 */
@Component
public class FocusSessionPlanCloser implements ActiveLearningCloser {
  private final FocusSessionService focus;

  public FocusSessionPlanCloser(FocusSessionService focus) {
    this.focus = focus;
  }

  @Override
  public void closeForPlan(String userId, String planId, Instant closedAt) {
    for (var session : focus.activeForPlan(userId, planId)) {
      focus.cancelAt(userId, session.id(), closedAt);
    }
  }
}
