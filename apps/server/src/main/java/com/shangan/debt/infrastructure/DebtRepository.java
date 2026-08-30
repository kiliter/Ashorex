package com.shangan.debt.infrastructure;

import com.shangan.debt.domain.LearningDebt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 欠债台账和偿还明细持久化边界。 */
public interface DebtRepository {
  List<LearningDebt> findOpenByUser(String userId);

  Optional<LearningDebt> findOwned(String userId, String debtId);

  List<LearningDebt> findOpenVideoByMedia(String userId, String mediaItemId);

  void insertIfAbsent(LearningDebt debt, Instant now);

  long sumOpenSeconds(String userId);

  long repay(
      String repaymentId,
      String userId,
      String debtId,
      String planItemId,
      long seconds,
      String source,
      Instant now);
}
