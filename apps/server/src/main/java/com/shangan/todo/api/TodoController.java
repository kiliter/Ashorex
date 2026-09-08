package com.shangan.todo.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.todo.application.FocusService;
import com.shangan.todo.application.TodoAttachmentService;
import com.shangan.todo.application.TodoCompletionService;
import com.shangan.todo.application.TodoCompletionService.CompleteCommand;
import com.shangan.todo.application.TodoDeletionService;
import com.shangan.todo.application.TodoDeletionService.DeleteCommand;
import com.shangan.todo.application.TodoDeletionService.DeletionOutcome;
import com.shangan.todo.application.TodoProgressService;
import com.shangan.todo.application.TodoProgressService.ProgressReport;
import com.shangan.todo.application.TodoProgressService.ProgressResult;
import com.shangan.todo.application.TodoService;
import com.shangan.todo.application.TodoService.CreateTodoCommand;
import com.shangan.todo.application.TodoService.PatchTodoCommand;
import com.shangan.todo.application.TodoViewService;
import com.shangan.todo.application.TodoViewService.DayView;
import com.shangan.todo.application.TodoViewService.PendingSummary;
import com.shangan.todo.application.TodoViewService.RangeView;
import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.NoteTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.infrastructure.TodoRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Todo 全链路 API：视图、增删改、进度上报、完成、专注与顺延。 */
@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {

  private final TodoService todoService;
  private final TodoViewService views;
  private final TodoProgressService progress;
  private final TodoCompletionService completion;
  private final FocusService focus;
  private final TodoDeletionService deletions;
  private final TodoAttachmentService attachments;
  private final NagPolicyResolver nagPolicies;

  public TodoController(
      TodoService todoService,
      TodoViewService views,
      TodoProgressService progress,
      TodoCompletionService completion,
      FocusService focus,
      TodoDeletionService deletions,
      TodoAttachmentService attachments,
      NagPolicyResolver nagPolicies) {
    this.todoService = todoService;
    this.views = views;
    this.progress = progress;
    this.completion = completion;
    this.focus = focus;
    this.deletions = deletions;
    this.attachments = attachments;
    this.nagPolicies = nagPolicies;
  }

  @GetMapping
  Object list(
      CurrentUser currentUser,
      @RequestParam(defaultValue = "DAY") String view,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String weekStart,
      @RequestParam(required = false) String month) {
    return switch (view.toUpperCase(java.util.Locale.ROOT)) {
      case "WEEK" -> week(currentUser, weekStart);
      case "MONTH" -> month(currentUser, month);
      default -> day(currentUser, date);
    };
  }

  private DayView day(CurrentUser currentUser, String date) {
    return views.day(currentUser.userId(), date);
  }

  private RangeView week(CurrentUser currentUser, String weekStart) {
    return views.week(currentUser.userId(), weekStart);
  }

  private RangeView month(CurrentUser currentUser, String month) {
    return views.month(currentUser.userId(), month);
  }

  @GetMapping("/pending-summary")
  PendingSummary pendingSummary(CurrentUser currentUser) {
    return views.pendingSummary(currentUser.userId());
  }

  @PostMapping
  List<Todo> create(CurrentUser currentUser, @Valid @RequestBody CreateTodosRequest request) {
    return todoService.create(currentUser.userId(), request.items());
  }

  /** 课程批量添加先预览冲突与历史，不写入学习数据。 */
  @PostMapping("/course-additions/preview")
  TodoService.CourseAdditionPreview previewCourses(
      CurrentUser user, @Valid @RequestBody CreateTodosRequest request) {
    return todoService.previewCourseAdditions(user.userId(), request.items());
  }

  /** 仅顺延明确确认的历史 ID，其余重复项逐条跳过。 */
  @PostMapping("/course-additions")
  TodoService.CourseAdditionResult addCourses(
      CurrentUser user, @Valid @RequestBody CourseAdditionsRequest request) {
    return todoService.addCourses(user.userId(), request.items(), request.reuseTodoIds());
  }

  @PatchMapping("/{todoId}")
  Todo patch(
      CurrentUser currentUser, @PathVariable String todoId, @RequestBody PatchTodoRequest request) {
    return todoService.patch(
        currentUser.userId(),
        todoId,
        new PatchTodoCommand(
            request.title(),
            request.note(),
            request.targetProgressPermille(),
            request.plannedSeconds(),
            request.requireEvidence(),
            request.noteTags()));
  }

  @PostMapping("/reorder")
  ResponseEntity<Void> reorder(
      CurrentUser currentUser, @Valid @RequestBody ReorderRequest request) {
    todoService.reorder(currentUser.userId(), request.localDate(), request.orderedIds());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{todoId}/progress")
  ProgressResult report(
      CurrentUser currentUser,
      @PathVariable String todoId,
      @Valid @RequestBody ProgressRequest request) {
    return progress.report(
        currentUser.userId(),
        todoId,
        new ProgressReport(
            request.clientSeq(),
            request.occurredAt(),
            request.eventType(),
            request.positionMs(),
            request.positionPage(),
            request.deltaWatchedMs() == null ? 0 : request.deltaWatchedMs(),
            request.deltaFocusedMs() == null ? 0 : request.deltaFocusedMs(),
            request.appState()));
  }

  @PostMapping("/{todoId}/complete")
  Todo complete(
      CurrentUser currentUser,
      @PathVariable String todoId,
      @RequestBody(required = false) CompleteRequest request) {
    CompleteRequest body = request == null ? new CompleteRequest(null, null, null) : request;
    return completion.complete(
        currentUser.userId(),
        todoId,
        new CompleteCommand(body.note(), body.noteTags(), body.backfill()));
  }

  @PostMapping("/{todoId}/annotate")
  Todo annotate(
      CurrentUser currentUser, @PathVariable String todoId, @RequestBody AnnotateRequest request) {
    return completion.annotate(currentUser.userId(), todoId, request.note(), request.noteTags());
  }

  /** 专注操作的请求标识可选，旧客户端继续兼容；新客户端重试复用同一标识。 */
  @PostMapping("/{todoId}/focus/{action}")
  Todo focusAction(
      CurrentUser currentUser,
      @PathVariable String todoId,
      @PathVariable String action,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String requestId) {
    return focus.execute(currentUser.userId(), todoId, action, requestId);
  }

  /**
   * 读取某条 Todo 的附件列表（原型 3-3 / 1-9 的缩略图与凭证列表）。
   *
   * <p>归属校验在 {@link com.shangan.todo.application.TodoAttachmentService#list} 内经 {@code
   * TodoService.requireOwned} 完成：他人的 todoId 一律按「不存在」处理并返回 404，不泄露该 Todo 是否存在。
   *
   * <p>响应刻意不含 {@code storagePath} 与 {@code sha256}：前者是服务器文件系统相对路径， 会暴露 {@code
   * DATA_DIR/attachments/{userId}/} 的目录结构；后者对客户端渲染没有用途。
   */
  @GetMapping("/{todoId}/attachments")
  List<AttachmentView> attachments(CurrentUser currentUser, @PathVariable String todoId) {
    return attachments.list(currentUser.userId(), todoId).stream()
        .map(attachment -> AttachmentView.of(todoId, attachment))
        .toList();
  }

  /**
   * 上传一个附件，是「完成凭证必填」（{@code requireEvidence}）唯一的解除入口。
   *
   * <p>没有这个端点时，用户一旦开启 requireEvidence 就永远无法完成该 Todo：完成接口会一直抛 {@code
   * TODO_EVIDENCE_REQUIRED}，而客户端没有任何途径补齐凭证。
   *
   * <p>归属校验在 {@link TodoAttachmentService#upload} 内经 {@code TodoService.requireOwned} 完成；他人的 todoId
   * 一律按 404 处理。大小、类型与数量三道限制也全部由服务端裁决，分别返回 {@code ATTACHMENT_TOO_LARGE}、{@code
   * ATTACHMENT_TYPE_UNSUPPORTED} 与 {@code ATTACHMENT_LIMIT_REACHED}。
   *
   * <p>响应复用只读投影 {@link AttachmentView}，同样不含 {@code storagePath} 与 {@code sha256}。
   */
  @PostMapping(path = "/{todoId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  AttachmentView uploadAttachment(
      CurrentUser currentUser,
      @PathVariable String todoId,
      @RequestPart("file") MultipartFile file) {
    try (InputStream content = file.getInputStream()) {
      TodoRepository.Attachment saved =
          attachments.upload(
              currentUser.userId(),
              todoId,
              file.getOriginalFilename(),
              file.getContentType(),
              file.getSize(),
              content);
      return AttachmentView.of(todoId, saved);
    } catch (java.io.IOException exception) {
      throw new com.shangan.common.api.BusinessException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "ATTACHMENT_READ_FAILED",
          "附件读取失败");
    }
  }

  /**
   * 删除单个附件，返回 204。
   *
   * <p>两层归属校验与下载端点一致：先经 {@code list} 确认 Todo 属于当前用户，再确认该附件确实挂在这条 Todo 上； 任一层不成立都按 404 {@code
   * ATTACHMENT_NOT_FOUND} 处理，不泄露附件是否存在于其他用户名下。
   */
  @DeleteMapping("/{todoId}/attachments/{attachmentId}")
  ResponseEntity<Void> deleteAttachment(
      CurrentUser currentUser, @PathVariable String todoId, @PathVariable String attachmentId) {
    TodoRepository.Attachment attachment =
        attachments.list(currentUser.userId(), todoId).stream()
            .filter(item -> item.id().equals(attachmentId))
            .findFirst()
            .orElseThrow(
                () ->
                    new com.shangan.common.api.BusinessException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "ATTACHMENT_NOT_FOUND",
                        "附件不存在"));
    attachments.delete(currentUser.userId(), attachment.id());
    return ResponseEntity.noContent().build();
  }

  /**
   * 下载单个附件字节流，是上面列表里 {@code downloadUrl} 指向的端点。
   *
   * <p>两层归属校验：先经 {@code list} 确认 Todo 属于当前用户，再确认该附件确实挂在这条 Todo 上； 只要有一层不成立就按 404 处理。附件行本身还带 {@code
   * user_id}，{@code locate} 会再校验一次。
   *
   * <p>不设置 {@code Content-Disposition: attachment}，因为原型 3-3 需要在页面内直接渲染缩略图。
   */
  @GetMapping("/{todoId}/attachments/{attachmentId}/content")
  ResponseEntity<Resource> attachmentContent(
      CurrentUser currentUser, @PathVariable String todoId, @PathVariable String attachmentId) {
    TodoRepository.Attachment attachment =
        attachments.list(currentUser.userId(), todoId).stream()
            .filter(item -> item.id().equals(attachmentId))
            .findFirst()
            .orElseThrow(
                () ->
                    new com.shangan.common.api.BusinessException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "ATTACHMENT_NOT_FOUND",
                        "附件不存在"));
    Path path = attachments.locate(currentUser.userId(), attachment.id());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(attachment.contentType()))
        .contentLength(attachment.sizeBytes())
        .body(new FileSystemResource(path));
  }

  @PostMapping("/{todoId}/defer")
  Todo defer(
      CurrentUser currentUser, @PathVariable String todoId, @RequestBody DeferRequest request) {
    return todoService.defer(currentUser.userId(), todoId, request.targetDate());
  }

  @PostMapping("/batch-defer")
  BatchResult batchDefer(CurrentUser currentUser, @Valid @RequestBody BatchDeferRequest request) {
    int moved = todoService.deferAll(currentUser.userId(), request.todoIds(), request.targetDate());
    return new BatchResult(moved);
  }

  @DeleteMapping("/{todoId}")
  DeletionOutcome delete(
      CurrentUser currentUser,
      @PathVariable String todoId,
      @Valid @RequestBody DeleteRequest request) {
    return deletions.delete(currentUser.userId(), todoId, toCommand(currentUser, request));
  }

  @PostMapping("/batch-delete")
  DeletionOutcome batchDelete(
      CurrentUser currentUser, @Valid @RequestBody BatchDeleteRequest request) {
    return deletions.deleteAll(
        currentUser.userId(),
        request.todoIds(),
        toCommand(currentUser, new DeleteRequest(request.reasonTag(), request.reasonText())));
  }

  private DeleteCommand toCommand(CurrentUser currentUser, DeleteRequest request) {
    int minLength = nagPolicies.resolve(currentUser.userId()).minReasonLength();
    return new DeleteCommand(request.reasonTag(), request.reasonText(), minLength);
  }

  record CourseAdditionsRequest(
      @NotEmpty List<CreateTodoCommand> items, List<String> reuseTodoIds) {}

  record CreateTodosRequest(@NotEmpty List<CreateTodoCommand> items) {}

  record PatchTodoRequest(
      String title,
      String note,
      Integer targetProgressPermille,
      Integer plannedSeconds,
      Boolean requireEvidence,
      List<NoteTag> noteTags) {}

  record ReorderRequest(String localDate, @NotEmpty List<String> orderedIds) {}

  record ProgressRequest(
      long clientSeq,
      Instant occurredAt,
      String eventType,
      Long positionMs,
      Integer positionPage,
      Long deltaWatchedMs,
      Long deltaFocusedMs,
      String appState) {}

  record CompleteRequest(String note, List<NoteTag> noteTags, Boolean backfill) {}

  record AnnotateRequest(String note, List<NoteTag> noteTags) {}

  record DeferRequest(@NotBlank String targetDate) {}

  record BatchDeferRequest(@NotEmpty List<String> todoIds, @NotBlank String targetDate) {}

  record DeleteRequest(DeletionReasonTag reasonTag, String reasonText) {}

  record BatchDeleteRequest(
      @NotEmpty List<String> todoIds, DeletionReasonTag reasonTag, String reasonText) {}

  record BatchResult(int affected) {}

  /**
   * 附件只读视图。
   *
   * <p>安全边界：只投影客户端渲染需要的字段。{@code storagePath}（服务器相对路径）与 {@code sha256} 一律不出现在响应里。
   */
  record AttachmentView(
      String id,
      String filename,
      String contentType,
      long sizeBytes,
      int sortOrder,
      Instant createdAt,
      String downloadUrl) {

    static AttachmentView of(String todoId, TodoRepository.Attachment attachment) {
      return new AttachmentView(
          attachment.id(),
          attachment.originalFilename(),
          attachment.contentType(),
          attachment.sizeBytes(),
          attachment.sortOrder(),
          attachment.createdAt(),
          "/api/v1/todos/" + todoId + "/attachments/" + attachment.id() + "/content");
    }
  }
}
