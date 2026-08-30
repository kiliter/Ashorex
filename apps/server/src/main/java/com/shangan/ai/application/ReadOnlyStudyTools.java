package com.shangan.ai.application;

import com.shangan.debt.application.DebtService;
import com.shangan.exam.application.ExamGoalService;
import com.shangan.exam.application.ExamProgressCalculator;
import com.shangan.planning.application.DailyPlanService;
import com.shangan.reporting.application.DailyReportService;
import com.shangan.reporting.application.WeeklyReportService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.springframework.stereotype.Component;

/** 唯一的内部 AI 工具注册对象；七个方法全部只读且名称由冻结规范限定。 */
@Component
public class ReadOnlyStudyTools {
  public static final List<String> ALLOWED_TOOL_NAMES =
      List.of(
          "get_today_plan_summary",
          "get_open_debt_summary",
          "get_exam_progress",
          "get_daily_report",
          "get_weekly_report",
          "search_video_transcript",
          "get_video_summary");

  private final DailyPlanService plans;
  private final DebtService debts;
  private final ExamGoalService exams;
  private final DailyReportService dailyReports;
  private final WeeklyReportService weeklyReports;
  private final VideoContextBuilder videos;
  private final Clock clock;

  public ReadOnlyStudyTools(
      DailyPlanService plans,
      DebtService debts,
      ExamGoalService exams,
      DailyReportService dailyReports,
      WeeklyReportService weeklyReports,
      VideoContextBuilder videos,
      Clock clock) {
    this.plans = plans;
    this.debts = debts;
    this.exams = exams;
    this.dailyReports = dailyReports;
    this.weeklyReports = weeklyReports;
    this.videos = videos;
    this.clock = clock;
  }

  @Tool(name = "get_today_plan_summary", value = "读取当前用户今日计划的状态和完成秒数")
  public DailyPlanService.PlanSummary getTodayPlanSummary(InvocationParameters parameters) {
    return plans.todaySummary(userId(parameters), timezone(parameters));
  }

  @Tool(name = "get_open_debt_summary", value = "读取当前用户开放欠债，只用于解释学习欠债")
  public DebtSummary getOpenDebtSummary(InvocationParameters parameters) {
    var values = debts.openDebts(userId(parameters));
    return new DebtSummary(
        values.size(),
        values.stream().mapToLong(value -> value.remainingSeconds()).sum(),
        values.stream()
            .map(
                value ->
                    new DebtItem(
                        value.debtType(), value.title(), value.remainingSeconds(), value.status()))
            .toList());
  }

  @Tool(name = "get_exam_progress", value = "读取当前用户考试目标的确定性学习进度")
  public ExamProgressCalculator.Progress getExamProgress(InvocationParameters parameters) {
    return exams.progress(userId(parameters));
  }

  @Tool(name = "get_daily_report", value = "读取当前用户今天的确定性日报")
  public DailyReportService.DailyReportView getDailyReport(InvocationParameters parameters) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timezone(parameters))));
    return dailyReports.generate(userId(parameters), today);
  }

  @Tool(name = "get_weekly_report", value = "读取当前用户本周的确定性周报")
  public WeeklyReportService.WeeklyReportView getWeeklyReport(InvocationParameters parameters) {
    LocalDate weekStart =
        LocalDate.now(clock.withZone(ZoneId.of(timezone(parameters))))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return weeklyReports.generate(userId(parameters), weekStart);
  }

  @Tool(name = "search_video_transcript", value = "搜索当前视频转写并返回相关时间片段")
  public VideoContextBuilder.TranscriptSearchResult searchVideoTranscript(
      String query, InvocationParameters parameters) {
    return videos.search(requiredMediaId(parameters), query);
  }

  @Tool(name = "get_video_summary", value = "读取当前视频的全局摘要和结构提纲")
  public VideoContextBuilder.VideoSummary getVideoSummary(InvocationParameters parameters) {
    return videos.getSummary(requiredMediaId(parameters));
  }

  private String userId(InvocationParameters parameters) {
    return parameters.get("userId");
  }

  private String timezone(InvocationParameters parameters) {
    return parameters.getOrDefault("timezone", "Asia/Shanghai");
  }

  private String requiredMediaId(InvocationParameters parameters) {
    String mediaItemId = parameters.get("mediaItemId");
    if (mediaItemId == null || mediaItemId.isBlank()) {
      throw new IllegalArgumentException("当前会话没有视频上下文");
    }
    return mediaItemId;
  }

  public record DebtSummary(int count, long totalRemainingSeconds, List<DebtItem> items) {}

  public record DebtItem(String debtType, String title, long remainingSeconds, String status) {}
}
