package com.shangan.admin;

import com.shangan.ai.content.application.ContentGenerationJobService;
import com.shangan.ai.content.domain.ContentGenerationJob;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.LessonStudyContentImportService;
import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 管理员创建、筛选、查看和重试课程内容任务。 */
@Controller
public class ContentJobAdminController {

  private final ContentGenerationJobService jobs;
  private final CourseSyncService courses;
  private final LessonStudyContentImportService contents;

  public ContentJobAdminController(
      ContentGenerationJobService jobs,
      CourseSyncService courses,
      LessonStudyContentImportService contents) {
    this.jobs = jobs;
    this.courses = courses;
    this.contents = contents;
  }

  @GetMapping("/admin/content-jobs")
  String list(
      @RequestParam(defaultValue = "") String courseId,
      @RequestParam(defaultValue = "") String status,
      Model model) {
    model.addAttribute("courses", courses.listAdminCourses());
    model.addAttribute("mainTasks", mainTaskRows(courseId, status));
    model.addAttribute("selectedCourseId", courseId);
    model.addAttribute("selectedStatus", status);
    model.addAttribute("stats", jobs.stats());
    return "admin/content-jobs";
  }

  /** 返回课程分组和队列统计的最新快照，供首页局部刷新。 */
  @ResponseBody
  @GetMapping(value = "/admin/content-jobs/live", produces = MediaType.APPLICATION_JSON_VALUE)
  ContentJobListLiveResponse liveList(
      @RequestParam(defaultValue = "") String courseId,
      @RequestParam(defaultValue = "") String status) {
    return new ContentJobListLiveResponse(mainTaskRows(courseId, status), jobs.stats());
  }

  /** 一门课程的任务按课时和排队时间合并，三个阶段在同一条工作流中展示。 */
  @GetMapping("/admin/content-jobs/courses/{courseId}")
  String courseWorkflows(@PathVariable String courseId, Model model) {
    model.addAttribute("course", courses.getAdminCourse(courseId));
    model.addAttribute("workflows", workflowGroups(courseId));
    model.addAttribute("stats", jobs.stats());
    return "admin/content-job-course";
  }

  /** 课程工作流列表局部轮询接口，不重新渲染页面头部和导航。 */
  @ResponseBody
  @GetMapping(
      value = "/admin/content-jobs/courses/{courseId}/live",
      produces = MediaType.APPLICATION_JSON_VALUE)
  CourseWorkflowLiveResponse liveCourseWorkflows(@PathVariable String courseId) {
    return new CourseWorkflowLiveResponse(workflowGroups(courseId), jobs.stats());
  }

  /** 工作流详情一次展示转写、摘要、出题三个阶段及其合并日志。 */
  @GetMapping("/admin/content-jobs/workflows/{courseId}/{lessonId}/{queuedAt}")
  String workflowDetail(
      @PathVariable String courseId,
      @PathVariable String lessonId,
      @PathVariable long queuedAt,
      Model model) {
    var workflow = workflowDetailView(courseId, lessonId, queuedAt);
    model.addAttribute("course", courses.getAdminCourse(courseId));
    model.addAttribute("lesson", courses.getAdminLesson(lessonId));
    model.addAttribute("workflow", workflow.workflow());
    model.addAttribute("stages", workflow.stages());
    model.addAttribute("logs", workflow.logs());
    return "admin/content-job-detail";
  }

  /** 工作流详情轮询一次返回三个阶段和合并日志。 */
  @ResponseBody
  @GetMapping(
      value = "/admin/content-jobs/workflows/{courseId}/{lessonId}/{queuedAt}/live",
      produces = MediaType.APPLICATION_JSON_VALUE)
  WorkflowLiveResponse liveWorkflow(
      @PathVariable String courseId, @PathVariable String lessonId, @PathVariable long queuedAt) {
    return workflowDetailView(courseId, lessonId, queuedAt);
  }

  @GetMapping("/admin/content-jobs/{jobId}")
  String detail(@PathVariable String jobId, Model model) {
    ContentGenerationJob job = jobs.detail(jobId);
    return workflowRedirect(job);
  }

  /** 返回任务详情页局部刷新所需的最新状态和安全阶段日志，不重新渲染完整页面。 */
  @ResponseBody
  @GetMapping(
      value = "/admin/content-jobs/{jobId}/live",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ContentJobLiveResponse live(@PathVariable String jobId) {
    ContentGenerationJob job = jobs.detail(jobId);
    return new ContentJobLiveResponse(
        ContentJobView.from(job), jobs.logs(jobId).stream().map(ContentJobLogView::from).toList());
  }

  @PostMapping("/admin/content-jobs/{jobId}/retry")
  String retry(@PathVariable String jobId, Principal principal) {
    ContentGenerationJob retried = jobs.detail(jobs.retry(jobId, principal.getName()).jobId());
    return workflowRedirect(retried);
  }

  /** 工作流详情页使用 JSON 重试，避免操作后刷新整张管理台页面。 */
  @ResponseBody
  @PostMapping(
      value = "/admin/content-jobs/{jobId}/retry-live",
      produces = MediaType.APPLICATION_JSON_VALUE)
  RetryWorkflowResponse retryLive(@PathVariable String jobId, Principal principal) {
    ContentGenerationJob retried = jobs.detail(jobs.retry(jobId, principal.getName()).jobId());
    return new RetryWorkflowResponse(true, "失败阶段已重新排队", workflowPath(retried));
  }

  @PostMapping("/admin/lessons/{lessonId}/transcribe")
  String transcribeLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "false") boolean overwrite,
      Principal principal) {
    return enqueueLesson(
        lessonId, ContentGenerationJob.Type.TRANSCRIBE, overwrite, 5, principal.getName());
  }

  @PostMapping("/admin/lessons/{lessonId}/summarize")
  String summarizeLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "false") boolean overwrite,
      Principal principal) {
    return enqueueLesson(
        lessonId, ContentGenerationJob.Type.SUMMARIZE, overwrite, 5, principal.getName());
  }

  @PostMapping("/admin/lessons/{lessonId}/generate-quiz")
  String generateQuizForLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "5") int questionCount,
      Principal principal) {
    return enqueueLesson(
        lessonId,
        ContentGenerationJob.Type.GENERATE_QUIZ,
        false,
        questionCount,
        principal.getName());
  }

  /** 单次点击排入转写、摘要和出题工作流，页面通过 Toast 接收结果而不整页刷新。 */
  @ResponseBody
  @PostMapping(
      value = "/admin/lessons/{lessonId}/ai-workflow",
      produces = MediaType.APPLICATION_JSON_VALUE)
  WorkflowEnqueueResponse aiLesson(
      @PathVariable String lessonId,
      @RequestParam(defaultValue = "5") int questionCount,
      Principal principal) {
    var result = jobs.enqueueLessonWorkflow(lessonId, questionCount, principal.getName());
    return new WorkflowEnqueueResponse(
        true,
        result.createdCount() == 0
            ? "该课时已有完整内容或相同任务正在执行，无需重复排队"
            : "AI 工作流已排队，共创建 " + result.createdCount() + " 个阶段任务",
        result.createdCount(),
        result.skippedCount());
  }

  @PostMapping("/admin/courses/{courseId}/transcribe")
  String transcribeCourse(@PathVariable String courseId, Principal principal) {
    jobs.enqueueCourse(courseId, ContentGenerationJob.Type.TRANSCRIBE, 5, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  @PostMapping("/admin/courses/{courseId}/summarize")
  String summarizeCourse(@PathVariable String courseId, Principal principal) {
    jobs.enqueueCourse(courseId, ContentGenerationJob.Type.SUMMARIZE, 5, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/lessons";
  }

  @PostMapping("/admin/courses/{courseId}/generate-quiz")
  String generateQuizForCourse(
      @PathVariable String courseId,
      @RequestParam(defaultValue = "5") int questionCount,
      Principal principal) {
    jobs.enqueueCourse(
        courseId, ContentGenerationJob.Type.GENERATE_QUIZ, questionCount, principal.getName());
    return "redirect:/admin/courses/" + courseId + "/quiz-drafts";
  }

  /** 课程批量工作流仍按课时顺序串行执行，不在请求线程等待外部服务。 */
  @ResponseBody
  @PostMapping(
      value = "/admin/courses/{courseId}/ai-workflow",
      produces = MediaType.APPLICATION_JSON_VALUE)
  WorkflowEnqueueResponse aiCourse(
      @PathVariable String courseId,
      @RequestParam(defaultValue = "5") int questionCount,
      @RequestParam(name = "lessonId", required = false) List<String> lessonIds,
      Principal principal) {
    var result =
        jobs.enqueueLessonsWorkflow(courseId, lessonIds, questionCount, principal.getName());
    return new WorkflowEnqueueResponse(
        true,
        result.createdCount() == 0
            ? "课程内容已完整或相同任务正在执行，无需重复排队"
            : "批量 AI 工作流已排队，覆盖 "
                + result.lessonCount()
                + " 个课时，共创建 "
                + result.createdCount()
                + " 个阶段任务",
        result.createdCount(),
        result.skippedCount());
  }

  @GetMapping("/admin/lessons/{lessonId}/study-content")
  String studyContent(@PathVariable String lessonId, Model model) {
    var lesson = courses.getAdminLesson(lessonId);
    model.addAttribute("lesson", lesson);
    model.addAttribute("course", courses.getAdminCourse(lesson.courseId()));
    model.addAttribute("content", contents.findByLessonId(lessonId).orElse(null));
    return "admin/lesson-study-content";
  }

  /** 将课程领域对象和任务聚合结果组合为管理台只读视图。 */
  private List<CourseTaskGroupView> courseGroups() {
    Map<String, String> courseNames =
        courses.listAdminCourses().stream()
            .collect(Collectors.toMap(course -> course.id(), course -> course.name()));
    return jobs.courseTaskSummaries().stream()
        .map(
            summary ->
                CourseTaskGroupView.from(
                    summary, courseNames.getOrDefault(summary.courseId(), "未知课程")))
        .toList();
  }

  /** 将课时标题补充到工作流聚合结果中，页面不显示不可读 UUID。 */
  private List<WorkflowGroupView> workflowGroups(String courseId) {
    Map<String, String> lessonTitles =
        courses.listAdminLessons(courseId).stream()
            .collect(Collectors.toMap(lesson -> lesson.id(), lesson -> lesson.title()));
    return jobs.workflowSummaries(courseId, 500).stream()
        .map(
            summary ->
                WorkflowGroupView.from(
                    courseId, lessonTitles.getOrDefault(summary.mediaItemId(), "未知课时"), summary))
        .toList();
  }

  /** 精确组装一个工作流详情，并按发生时间合并三个阶段日志。 */
  private WorkflowLiveResponse workflowDetailView(String courseId, String lessonId, long queuedAt) {
    Instant queuedAtInstant = Instant.ofEpochMilli(queuedAt);
    List<ContentGenerationJob> workflow = jobs.workflow(courseId, lessonId, queuedAtInstant);
    String lessonTitle = courses.getAdminLesson(lessonId).title();
    List<WorkflowLogView> logs =
        workflow.stream()
            .flatMap(job -> jobs.logs(job.id()).stream().map(log -> WorkflowLogView.from(job, log)))
            .sorted(
                Comparator.comparing(WorkflowLogView::occurredAt)
                    .thenComparing(WorkflowLogView::jobId))
            .toList();
    return new WorkflowLiveResponse(
        WorkflowGroupView.fromJobs(courseId, lessonTitle, workflow),
        workflow.stream().map(ContentJobView::from).toList(),
        logs);
  }

  /** 旧的单任务链接统一跳入所属工作流详情，兼容已有书签和日志入口。 */
  private String workflowRedirect(ContentGenerationJob job) {
    return "redirect:" + workflowPath(job);
  }

  /** 生成工作流详情相对路径，供服务端重定向和局部 JSON 操作共用。 */
  private String workflowPath(ContentGenerationJob job) {
    return "/admin/content-jobs/workflows/"
        + job.courseId()
        + "/"
        + job.mediaItemId()
        + "/"
        + job.queuedAt().toEpochMilli();
  }

  private String enqueueLesson(
      String lessonId,
      ContentGenerationJob.Type type,
      boolean overwrite,
      int questionCount,
      String principal) {
    var result = jobs.enqueueLesson(lessonId, type, overwrite, questionCount, principal);
    return result.created()
        ? "redirect:/admin/content-jobs/" + result.jobId()
        : "redirect:/admin/content-jobs";
  }

  /** 将同一次“AI 一下”的阶段任务合并成主任务，并在合并后按主任务状态筛选。 */
  private List<ContentTaskRowView> mainTaskRows(String courseId, String status) {
    return courses.listAdminCourses().stream()
        .filter(course -> courseId.isBlank() || course.id().equals(courseId))
        .flatMap(
            course ->
                workflowGroups(course.id()).stream()
                    .map(workflow -> ContentTaskRowView.from(course.name(), workflow)))
        .filter(task -> status.isBlank() || task.status().equals(status))
        .sorted(Comparator.comparingLong(ContentTaskRowView::queuedAt).reversed())
        .limit(500)
        .toList();
  }

  /** 主任务列表轮询响应；子任务数据只在详情页展开。 */
  private record ContentJobListLiveResponse(
      List<ContentTaskRowView> tasks,
      com.shangan.ai.content.infrastructure.ContentGenerationJobRepository.QueueStats stats) {}

  /** 管理台主任务行，一个课时的一次生成工作流只占一行。 */
  private record ContentTaskRowView(
      String courseId,
      String courseName,
      String lessonId,
      String lessonTitle,
      long queuedAt,
      String queuedAtLabel,
      String status,
      String statusLabel,
      String totalDurationLabel,
      int stageCount,
      int finishedStageCount,
      String stageProgressLabel) {

    /** 汇总已创建和已结束的子任务数量，失败子任务也属于已结束但由主状态单独提示。 */
    private static ContentTaskRowView from(String courseName, WorkflowGroupView workflow) {
      int stageCount =
          (int) workflow.stages().stream().filter(stage -> stage.jobId() != null).count();
      int finishedStageCount =
          (int)
              workflow.stages().stream()
                  .filter(stage -> stage.jobId() != null)
                  .filter(
                      stage ->
                          java.util.Set.of("READY", "READY_FOR_REVIEW", "FAILED")
                              .contains(stage.status()))
                  .count();
      return new ContentTaskRowView(
          workflow.courseId(),
          courseName,
          workflow.lessonId(),
          workflow.lessonTitle(),
          workflow.queuedAt(),
          workflow.queuedAtLabel(),
          workflow.status(),
          workflow.statusLabel(),
          workflow.totalDurationLabel(),
          stageCount,
          finishedStageCount,
          finishedStageCount + " / " + stageCount);
    }
  }

  /** 课程工作流列表轮询响应。 */
  private record CourseWorkflowLiveResponse(
      List<WorkflowGroupView> workflows,
      com.shangan.ai.content.infrastructure.ContentGenerationJobRepository.QueueStats stats) {}

  /** 工作流详情轮询响应。 */
  private record WorkflowLiveResponse(
      WorkflowGroupView workflow, List<ContentJobView> stages, List<WorkflowLogView> logs) {}

  /** 局部重试返回新任务所属工作流位置。 */
  private record RetryWorkflowResponse(boolean success, String message, String workflowUrl) {}

  /** 第一层课程任务卡片。 */
  private record CourseTaskGroupView(
      String courseId,
      String courseName,
      long workflowCount,
      long taskCount,
      long queuedCount,
      long runningCount,
      long failedCount,
      String lastQueuedAtLabel) {

    /** 从数据库聚合结果创建课程卡片。 */
    private static CourseTaskGroupView from(
        com.shangan.ai.content.infrastructure.ContentGenerationJobRepository.CourseTaskSummary
            summary,
        String courseName) {
      return new CourseTaskGroupView(
          summary.courseId(),
          courseName,
          summary.workflowCount(),
          summary.taskCount(),
          summary.queuedCount(),
          summary.runningCount(),
          summary.failedCount(),
          AdminDisplayFormatter.dateTime(summary.lastQueuedAt()));
    }
  }

  /** 一个课时在同一排队时间创建的三个阶段工作流。 */
  private record WorkflowGroupView(
      String courseId,
      String lessonId,
      String lessonTitle,
      long queuedAt,
      String queuedAtLabel,
      String status,
      String statusLabel,
      String totalDurationLabel,
      List<WorkflowStageView> stages) {

    /** 从 Repository 聚合行创建课程列表中的工作流。 */
    private static WorkflowGroupView from(
        String courseId,
        String lessonTitle,
        com.shangan.ai.content.infrastructure.ContentGenerationJobRepository.WorkflowTaskSummary
            summary) {
      List<WorkflowStageView> stages =
          List.of(
              WorkflowStageView.from(
                  ContentGenerationJob.Type.TRANSCRIBE,
                  summary.transcribeJobId(),
                  summary.transcribeStatus(),
                  summary.transcribeTotalMs()),
              WorkflowStageView.from(
                  ContentGenerationJob.Type.SUMMARIZE,
                  summary.summarizeJobId(),
                  summary.summarizeStatus(),
                  summary.summarizeTotalMs()),
              WorkflowStageView.from(
                  ContentGenerationJob.Type.GENERATE_QUIZ,
                  summary.quizJobId(),
                  summary.quizStatus(),
                  summary.quizTotalMs()));
      return create(
          courseId,
          summary.mediaItemId(),
          lessonTitle,
          summary.queuedAt(),
          stages,
          stages.stream()
              .map(WorkflowStageView::durationMs)
              .filter(java.util.Objects::nonNull)
              .mapToLong(Long::longValue)
              .sum());
    }

    /** 从详情页的三个真实任务快照创建同一工作流头部。 */
    private static WorkflowGroupView fromJobs(
        String courseId, String lessonTitle, List<ContentGenerationJob> jobs) {
      Map<ContentGenerationJob.Type, ContentGenerationJob> byType =
          jobs.stream().collect(Collectors.toMap(ContentGenerationJob::type, Function.identity()));
      List<WorkflowStageView> stages =
          java.util.Arrays.stream(ContentGenerationJob.Type.values())
              .map(
                  type -> {
                    ContentGenerationJob job = byType.get(type);
                    return job == null
                        ? WorkflowStageView.missing(type)
                        : WorkflowStageView.from(
                            type, job.id(), job.status().name(), job.totalMs());
                  })
              .toList();
      ContentGenerationJob first = jobs.getFirst();
      long totalMs =
          jobs.stream()
              .map(ContentGenerationJob::totalMs)
              .filter(java.util.Objects::nonNull)
              .mapToLong(Long::longValue)
              .sum();
      return create(courseId, first.mediaItemId(), lessonTitle, first.queuedAt(), stages, totalMs);
    }

    /** 统一计算工作流总状态，失败优先于执行中和等待。 */
    private static WorkflowGroupView create(
        String courseId,
        String lessonId,
        String lessonTitle,
        Instant queuedAt,
        List<WorkflowStageView> stages,
        long totalMs) {
      String status;
      String statusLabel;
      if (stages.stream().anyMatch(stage -> "FAILED".equals(stage.status()))) {
        status = "FAILED";
        statusLabel = "存在失败";
      } else if (stages.stream().anyMatch(WorkflowStageView::running)) {
        status = "RUNNING";
        statusLabel = "执行中";
      } else if (stages.stream().anyMatch(stage -> "QUEUED".equals(stage.status()))) {
        status = "QUEUED";
        statusLabel = "等待执行";
      } else {
        status = "COMPLETED";
        statusLabel = "已结束";
      }
      return new WorkflowGroupView(
          courseId,
          lessonId,
          lessonTitle,
          queuedAt.toEpochMilli(),
          AdminDisplayFormatter.dateTime(queuedAt),
          status,
          statusLabel,
          AdminDisplayFormatter.duration(totalMs == 0 ? null : totalMs),
          stages);
    }
  }

  /** 工作流中的单个阶段；未创建也作为占位显示，保证顺序清晰。 */
  private record WorkflowStageView(
      String type,
      String typeLabel,
      String jobId,
      String status,
      String statusLabel,
      Long durationMs,
      String durationLabel,
      boolean retryable) {

    /** 将可空阶段聚合字段转换为可直接渲染的中文状态。 */
    private static WorkflowStageView from(
        ContentGenerationJob.Type type, String jobId, String status, Long durationMs) {
      if (jobId == null || status == null) return missing(type);
      ContentGenerationJob.Status jobStatus = ContentGenerationJob.Status.valueOf(status);
      return new WorkflowStageView(
          type.name(),
          type.label(),
          jobId,
          status,
          jobStatus.label(),
          durationMs,
          AdminDisplayFormatter.duration(durationMs),
          jobStatus == ContentGenerationJob.Status.FAILED);
    }

    private static WorkflowStageView missing(ContentGenerationJob.Type type) {
      return new WorkflowStageView(
          type.name(), type.label(), null, "NOT_CREATED", "未创建", null, "—", false);
    }

    /** 执行态阶段用于工作流整体状态计算。 */
    private boolean running() {
      return java.util.Set.of("FETCHING_AUDIO", "TRANSCRIBING", "SUMMARIZING", "GENERATING_QUIZ")
          .contains(status);
    }
  }

  /** 任务详情页轮询响应；任务快照和日志均不包含正文、Prompt 或外部服务密钥。 */
  private record ContentJobLiveResponse(ContentJobView job, List<ContentJobLogView> logs) {}

  /** 管理台任务 DTO 同时提供稳定枚举值和中文显示文本。 */
  private record ContentJobView(
      String id,
      String courseId,
      String mediaItemId,
      String type,
      String typeLabel,
      String status,
      String statusLabel,
      Instant queuedAt,
      String queuedAtLabel,
      String startedAtLabel,
      String finishedAtLabel,
      Long fetchMs,
      String fetchDurationLabel,
      Long transcribeMs,
      String transcribeDurationLabel,
      Long summarizeMs,
      String summarizeDurationLabel,
      Long quizGenerateMs,
      String quizGenerateDurationLabel,
      String stageDurationLabel,
      Long totalMs,
      String totalDurationLabel,
      String asrModel,
      String llmModel,
      Integer llmContextLength,
      Integer llmMaxCompletionTokens,
      Integer promptTokens,
      Integer completionTokens,
      int attempt,
      String errorCode,
      String errorMessage) {

    /** 从领域快照创建只包含页面所需字段的安全 DTO。 */
    private static ContentJobView from(ContentGenerationJob job) {
      return new ContentJobView(
          job.id(),
          job.courseId(),
          job.mediaItemId(),
          job.type().name(),
          job.type().label(),
          job.status().name(),
          job.status().label(),
          job.queuedAt(),
          AdminDisplayFormatter.dateTime(job.queuedAt()),
          AdminDisplayFormatter.dateTime(job.startedAt()),
          AdminDisplayFormatter.dateTime(job.finishedAt()),
          job.fetchMs(),
          AdminDisplayFormatter.duration(job.fetchMs()),
          job.transcribeMs(),
          AdminDisplayFormatter.duration(job.transcribeMs()),
          job.summarizeMs(),
          AdminDisplayFormatter.duration(job.summarizeMs()),
          job.quizGenerateMs(),
          AdminDisplayFormatter.duration(job.quizGenerateMs()),
          AdminDisplayFormatter.duration(stageDuration(job)),
          job.totalMs(),
          AdminDisplayFormatter.duration(job.totalMs()),
          job.asrModel(),
          job.llmModel(),
          job.llmContextLength(),
          job.llmMaxCompletionTokens(),
          job.promptTokens(),
          job.completionTokens(),
          job.attempt(),
          job.errorCode(),
          job.errorMessage());
    }

    /** 选择当前任务类型对应的主要阶段耗时。 */
    private static Long stageDuration(ContentGenerationJob job) {
      return switch (job.type()) {
        case TRANSCRIBE -> job.transcribeMs();
        case SUMMARIZE -> job.summarizeMs();
        case GENERATE_QUIZ -> job.quizGenerateMs();
      };
    }
  }

  /** 管理台日志 DTO 将级别和阶段转换为中文，但保留原始值供前端决定样式。 */
  private record ContentJobLogView(
      Instant occurredAt,
      String occurredAtLabel,
      String level,
      String levelLabel,
      String stage,
      String stageLabel,
      String message) {

    /** 从安全日志创建页面 DTO。 */
    private static ContentJobLogView from(ContentGenerationJob.Log log) {
      return new ContentJobLogView(
          log.occurredAt(),
          AdminDisplayFormatter.dateTime(log.occurredAt()),
          log.level(),
          ContentJobDisplayLabels.level(log.level()),
          log.stage(),
          ContentJobDisplayLabels.stage(log.stage()),
          log.message());
    }
  }

  /** 合并日志保留所属阶段，管理员能在一个时间线中定位三段工作。 */
  private record WorkflowLogView(
      String jobId,
      String jobType,
      String jobTypeLabel,
      Instant occurredAt,
      String occurredAtLabel,
      String level,
      String levelLabel,
      String stage,
      String stageLabel,
      String message) {

    /** 将日志和所属任务组合成不含正文及密钥的工作流日志。 */
    private static WorkflowLogView from(ContentGenerationJob job, ContentGenerationJob.Log log) {
      return new WorkflowLogView(
          job.id(),
          job.type().name(),
          job.type().label(),
          log.occurredAt(),
          AdminDisplayFormatter.dateTime(log.occurredAt()),
          log.level(),
          ContentJobDisplayLabels.level(log.level()),
          log.stage(),
          ContentJobDisplayLabels.stage(log.stage()),
          log.message());
    }
  }

  /** AI 工作流异步排队结果，不返回模型配置或外部服务密钥。 */
  private record WorkflowEnqueueResponse(
      boolean success, String message, int createdCount, int skippedCount) {}
}
