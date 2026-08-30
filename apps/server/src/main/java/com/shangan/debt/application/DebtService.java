package com.shangan.debt.application;

import com.shangan.debt.domain.LearningDebt;
import com.shangan.planning.domain.PlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 计划与学习模块使用的欠债应用接口。 */
public interface DebtService {
  List<LearningDebt> generate(
      String userId, LocalDate openedOn, String reason, List<PlanItem> items, Instant now);

  void reconcileRepayment(
      String userId, String debtId, String planItemId, long positiveDelta, Instant now);

  /** 按同一视频的绝对可信进度减少所有匹配的开放观看欠债。 */
  void reconcileOpenVideoDebt(
      String userId,
      String mediaItemId,
      long absoluteTrustedSeconds,
      boolean completed,
      Instant now);

  List<LearningDebt> openDebts(String userId);

  long openSeconds(String userId);
}
