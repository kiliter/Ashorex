package com.shangan.reporting.application;

import com.shangan.reporting.infrastructure.ReportingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟刷新终态计划，并补算所有用户刚结束的前一用户本地日。 */
@Component
public class DailyReportGenerationScheduler {
  private final ReportingRepository reports;
  private final DailyReportService daily;
  private final Clock clock;

  public DailyReportGenerationScheduler(
      ReportingRepository reports, DailyReportService daily, Clock clock) {
    this.reports = reports;
    this.daily = daily;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60_000)
  public void materializeReports() {
    Set<ReportingRepository.ReportCandidate> candidates =
        new LinkedHashSet<>(reports.terminalPlans());
    for (var user : reports.users()) {
      LocalDate localToday = LocalDate.now(clock.withZone(ZoneId.of(user.timezone())));
      candidates.add(
          new ReportingRepository.ReportCandidate(user.userId(), localToday.minusDays(1)));
    }
    for (var candidate : candidates) {
      daily.generate(candidate.userId(), candidate.date());
    }
  }
}
