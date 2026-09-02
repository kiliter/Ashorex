package com.shangan.catalog.application;

import com.shangan.catalog.domain.CourseDeletionGraph;
import java.util.List;

/** 删除原始学习记录后重新生成受影响日期的确定性派生数据。 */
@FunctionalInterface
public interface CourseDeletionDerivedDataRefresher {
  void refresh(List<CourseDeletionGraph.AffectedDay> affectedDays);
}
