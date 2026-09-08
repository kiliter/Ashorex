package com.shangan.goal.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 考试目标聚合；只做倒计时看板，不绑定任何课程（见 Spec 5.2）。 */
public record ExamGoal(
    String id, String userId, String name, LocalDate examDate, String note, boolean primary) {

  /** 距考试剩余天数，按用户本地日期计算；已过期返回负数。 */
  public long daysRemaining(LocalDate today) {
    return ChronoUnit.DAYS.between(today, examDate);
  }

  /** 按剩余天数给出紧急度分档，供客户端着色，且不依赖颜色单独表达。 */
  public GoalUrgency urgency(LocalDate today) {
    long days = daysRemaining(today);
    if (days < 0) {
      return GoalUrgency.EXPIRED;
    }
    if (days <= 30) {
      return GoalUrgency.URGENT;
    }
    if (days <= 60) {
      return GoalUrgency.SOON;
    }
    return GoalUrgency.NORMAL;
  }
}
