package com.shangan.debt.application;

import com.shangan.debt.domain.LearningDebt;
import com.shangan.planning.domain.PlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

  /** 完整答题后一次性结清同视频的全部开放 QUIZ 欠债。 */
  void settleOpenQuizDebt(String userId, String mediaItemId, Instant now);

  List<LearningDebt> openDebts(String userId);

  /** 读取指定用户拥有的欠债，包含已结清记录，供还债任务做稳定关联校验。 */
  Optional<LearningDebt> findDebt(String userId, String debtId);

  long openSeconds(String userId);
}
