package com.shangan.reporting.application;

import com.shangan.catalog.application.CourseDeletionDerivedDataRefresher;
import com.shangan.catalog.domain.CourseDeletionGraph;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 按用户自然日重新计算日终结果和日报，确保已删除课程不再贡献历史聚合值。 */
@Component
public class ReportingCourseDeletionDerivedDataRefresher
    implements CourseDeletionDerivedDataRefresher {

  private final DayOutcomeService dayOutcomes;
  private final DailyReportService dailyReports;

  public ReportingCourseDeletionDerivedDataRefresher(
      DayOutcomeService dayOutcomes, DailyReportService dailyReports) {
    this.dayOutcomes = dayOutcomes;
    this.dailyReports = dailyReports;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void refresh(List<CourseDeletionGraph.AffectedDay> affectedDays) {
    // 去重后保持首次出现顺序，避免同一天因多种学习记录被重复重建。
    for (CourseDeletionGraph.AffectedDay day : new LinkedHashSet<>(affectedDays)) {
      dayOutcomes.settle(day.userId(), day.date(), ZoneId.of(day.timezone()));
      dailyReports.generate(day.userId(), day.date());
    }
  }
}
