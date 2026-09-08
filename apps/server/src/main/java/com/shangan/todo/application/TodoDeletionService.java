package com.shangan.todo.application;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserTimeService;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.supervision.application.SupervisionService;
import com.shangan.todo.domain.DeletionReasonTag;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoType;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo 删除与删除台账。
 *
 * <p>删除任意 Todo 都必须填写原因标签与说明；台账保留删除时的进度快照与督学人快照， 并按规则通知主督学人（见 Spec 7.4 与 ADR-0028）。
 */
@Service
public class TodoDeletionService {

  private static final Logger log = LoggerFactory.getLogger(TodoDeletionService.class);
  private static final int BULK_DELETE_THRESHOLD = 3;
  private static final int HALF_DONE_PERMILLE = 500;

  private final TodoRepository todos;
  private final TodoService todoService;
  private final TodoAttachmentService attachments;
  private final CourseRepository courses;
  private final SupervisionService supervisions;
  private final NagPolicyResolver nagPolicies;
  private final UserTimeService userTime;
  private final EffectiveActionRecorder effectiveAction;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public TodoDeletionService(
      TodoRepository todos,
      TodoService todoService,
      TodoAttachmentService attachments,
      CourseRepository courses,
      SupervisionService supervisions,
      NagPolicyResolver nagPolicies,
      UserTimeService userTime,
      EffectiveActionRecorder effectiveAction,
      IdGenerator idGenerator,
      Clock clock) {
    this.todos = todos;
    this.todoService = todoService;
    this.attachments = attachments;
    this.courses = courses;
    this.supervisions = supervisions;
    this.nagPolicies = nagPolicies;
    this.userTime = userTime;
    this.effectiveAction = effectiveAction;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  /** 删除单条 Todo。 */
  @Transactional
  public DeletionOutcome delete(String userId, String todoId, DeleteCommand command) {
    return deleteAll(userId, List.of(todoId), command);
  }

  /** 批量删除，共用一份原因。 */
  @Transactional
  public DeletionOutcome deleteAll(String userId, List<String> todoIds, DeleteCommand command) {
    if (todoIds == null || todoIds.isEmpty()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "TODO_EMPTY_REQUEST", "没有要删除的待办");
    }
    DeletionReasonTag reasonTag = requireReasonTag(command.reasonTag());
    String reasonText = requireReasonText(command.reasonText(), command.minReasonLength());
    String supervisor = supervisions.primarySupervisorOf(userId).orElse(null);
    EffectiveNagPolicy policy = nagPolicies.resolve(userId);
    Instant now = clock.instant();
    List<Notification> notifications = new ArrayList<>();
    int deleted = 0;

    for (String todoId : todoIds) {
      Todo todo = todoService.requireOwned(userId, todoId);
      todos.insertDeletion(
          new TodoRepository.Deletion(
              idGenerator.nextId(),
              userId,
              todo.id(),
              todo.todoType(),
              todo.localDate(),
              todo.title(),
              todo.resourceId(),
              snapshotOf(todo),
              reasonTag,
              reasonText,
              supervisor,
              now));
      attachments.deleteAllOf(todo.id());
      todos.deleteProgressEventsOf(todo.id());
      todos.delete(todo.id());
      deleted++;
      notifications.addAll(rulesFor(todo, reasonTag, supervisor, policy));
    }

    int deletionsToday = todos.countDeletionsOn(userId, userTime.today(userId));
    if (policy.notifySupervisorOnBulkDelete()
        && deletionsToday >= BULK_DELETE_THRESHOLD
        && supervisor != null) {
      notifications.add(new Notification(NotificationRule.BULK_DELETE, supervisor, deletionsToday));
    }
    if (!notifications.isEmpty()) {
      log.info("学员 {} 的删除行为触发 {} 条督学提醒", userId, notifications.size());
    }
    effectiveAction.record(userId);
    return new DeletionOutcome(deleted, reasonTag, List.copyOf(notifications));
  }

  @Transactional(readOnly = true)
  public List<TodoRepository.Deletion> recent(int limit) {
    return todos.findAllDeletions(limit);
  }

  /**
   * 按策略开关产出督学提醒。
   *
   * <p>三条规则都受后台「触发督学提醒的规则」开关控制：管理员在催办策略页关掉某条， 这里就不再产生对应提醒，否则页面上的开关会变成无效控件。
   */
  private List<Notification> rulesFor(
      Todo todo, DeletionReasonTag reasonTag, String supervisorUserId, EffectiveNagPolicy policy) {
    if (supervisorUserId == null) {
      return List.of();
    }
    List<Notification> notifications = new ArrayList<>();
    if (policy.notifySupervisorOnGaveUp() && reasonTag == DeletionReasonTag.GAVE_UP) {
      notifications.add(new Notification(NotificationRule.GAVE_UP, supervisorUserId, 1));
    }
    if (policy.notifySupervisorOnHalfDoneDelete()
        && todo.todoType() == TodoType.COURSE
        && progressPermilleOf(todo) >= HALF_DONE_PERMILLE) {
      notifications.add(new Notification(NotificationRule.HALF_DONE_DELETE, supervisorUserId, 1));
    }
    return notifications;
  }

  /** 删除时的进度快照，便于事后复盘「删掉的时候做到哪了」。 */
  private String snapshotOf(Todo todo) {
    return "{\"status\":\"%s\",\"positionMs\":%d,\"page\":%d,\"watchedMs\":%d,\"focusedMs\":%d,\"focusState\":\"%s\",\"targetPermille\":%s}"
        .formatted(
            todo.status().name(),
            todo.progressPositionMs(),
            todo.progressPage(),
            todo.watchedMs(),
            todo.focusedMs(),
            todo.focusState().name(),
            todo.targetProgressPermille() == null
                ? "null"
                : String.valueOf(todo.targetProgressPermille()));
  }

  /**
   * 删除时的实际完成千分比，用于判定是否触发「删除半程课程」督学提醒。
   *
   * <p>必须按 `position / duration` 真实换算，不能拿目标值代替：目标只是用户想看多少， 与实际看了多少无关。资源已下架而读不到总量时按 0 处理，避免删除因此失败。
   */
  private int progressPermilleOf(Todo todo) {
    if (todo.resourceId() == null || todo.progressPositionMs() <= 0) {
      return 0;
    }
    return courses
        .findResourceById(todo.resourceId())
        .map(resource -> resource.progressPermille(todo.progressPositionMs(), todo.progressPage()))
        .orElse(0);
  }

  private DeletionReasonTag requireReasonTag(DeletionReasonTag reasonTag) {
    if (reasonTag == null) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_DELETE_REASON_REQUIRED", "必须选择删除原因");
    }
    return reasonTag;
  }

  private String requireReasonText(String reasonText, int minLength) {
    String normalized = reasonText == null ? "" : reasonText.trim();
    int required = Math.max(1, minLength);
    if (normalized.length() < required) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "TODO_DELETE_REASON_REQUIRED", "删除说明至少需要 " + required + " 个字");
    }
    return normalized;
  }

  /** 触发督学提醒的三条规则。 */
  public enum NotificationRule {
    BULK_DELETE,
    GAVE_UP,
    HALF_DONE_DELETE
  }

  /** 一条待投递的督学提醒。 */
  public record Notification(NotificationRule rule, String supervisorUserId, int count) {}

  /** 删除命令；{@code minReasonLength} 由催办策略提供。 */
  public record DeleteCommand(
      DeletionReasonTag reasonTag, String reasonText, int minReasonLength) {}

  /** 删除结果。 */
  public record DeletionOutcome(
      int deletedCount, DeletionReasonTag reasonTag, List<Notification> notifications) {}
}
