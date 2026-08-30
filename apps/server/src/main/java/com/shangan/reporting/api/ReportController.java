package com.shangan.reporting.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.reporting.application.DailyReportService;
import com.shangan.reporting.application.WeeklyReportService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 日报和周报读取接口会按当前原始行确定性刷新，不返回陈旧客户端计算值。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final DailyReportService daily;
  private final WeeklyReportService weekly;

  public ReportController(DailyReportService daily, WeeklyReportService weekly) {
    this.daily = daily;
    this.weekly = weekly;
  }

  @GetMapping("/daily")
  DailyReportService.DailyReportView daily(CurrentUser user, @RequestParam LocalDate date) {
    return daily.generate(user.userId(), date);
  }

  @GetMapping("/weekly")
  WeeklyReportService.WeeklyReportView weekly(CurrentUser user, @RequestParam LocalDate weekStart) {
    return weekly.generate(user.userId(), weekStart);
  }
}
