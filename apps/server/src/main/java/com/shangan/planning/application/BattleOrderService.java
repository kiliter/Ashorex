package com.shangan.planning.application;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.focus.application.MockExamPresetService;
import com.shangan.planning.infrastructure.BattleOrderRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 今日作战单应用服务：完整校验后在一个事务中替换可修改项目并保存修订快照。 */
@Service
public class BattleOrderService {
  private final BattleOrderRepository orders;
  private final CatalogQueryService catalog;
  private final MockExamPresetService presets;
  private final List<VideoTaskRequirementPort> videoRequirements;
  private final IdGenerator ids;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public BattleOrderService(
      BattleOrderRepository orders,
      CatalogQueryService catalog,
      MockExamPresetService presets,
      List<VideoTaskRequirementPort> videoRequirements,
      IdGenerator ids,
      ObjectMapper objectMapper,
      Clock clock) {
    this.orders = orders;
    this.catalog = catalog;
    this.presets = presets;
    this.videoRequirements = videoRequirements;
    this.ids = ids;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PlanView get(String userId, LocalDate date) {
    return orders
        .findPlan(userId, date)
        .map(plan -> view(plan, orders.findItems(plan.id())))
        .orElse(new PlanView(null, date, "NONE", 0, List.of()));
  }

  @Transactional
  public PlanView save(String userId, LocalDate date, SaveCommand command) {
    if (command == null || command.items() == null || command.expectedVersion() < 0) {
      throw invalid(HttpStatus.BAD_REQUEST, "BATTLE_ORDER_INVALID", "作战单内容无效");
    }

    var now = clock.instant();
    BattleOrderRepository.PlanRow plan =
        orders
            .findPlan(userId, date)
            .orElseGet(
                () -> {
                  if (command.expectedVersion() != 0) throw versionConflict();
                  String planId = ids.nextId();
                  orders.insertPlan(planId, userId, date, now);
                  return orders.findPlan(userId, date).orElseThrow();
                });
    if (plan.version() != command.expectedVersion()) throw versionConflict();
    if (!List.of("DRAFT", "ACTIVE").contains(plan.lifecycleStatus())) {
      throw invalid(HttpStatus.CONFLICT, "PLAN_ALREADY_CLOSED", "今日作战单已经结算");
    }

    List<BattleOrderRepository.ItemRow> current = orders.findItems(plan.id());
    Map<String, BattleOrderRepository.ItemRow> currentById = new HashMap<>();
    current.forEach(item -> currentById.put(item.id(), item));
    validateCommandIdentity(command.items(), currentById);

    Set<String> suppliedIds = new HashSet<>();
    command.items().stream()
        .map(ItemCommand::existingItemId)
        .filter(java.util.Objects::nonNull)
        .forEach(suppliedIds::add);
    current.stream()
        .filter(BattleOrderRepository.ItemRow::immutable)
        .filter(item -> !suppliedIds.contains(item.id()))
        .findFirst()
        .ifPresent(
            item -> {
              throw immutableItem(item.title());
            });

    List<PreparedItem> prepared = new ArrayList<>();
    Set<String> lessonIds = new HashSet<>();
    for (ItemCommand item : command.items()) {
      BattleOrderRepository.ItemRow existing =
          item.existingItemId() == null ? null : currentById.get(item.existingItemId());
      if (existing != null && existing.immutable()) {
        validateImmutableMatch(item, existing);
        if (existing.mediaItemId() != null && !lessonIds.add(existing.mediaItemId())) {
          throw duplicateLesson();
        }
        prepared.add(PreparedItem.preserved(existing));
        continue;
      }
      PreparedItem next = prepareMutable(userId, plan.id(), item);
      if (next.mediaItemId() != null && !lessonIds.add(next.mediaItemId())) {
        throw duplicateLesson();
      }
      prepared.add(next);
    }

    List<PreparedItem> canonicalItems = canonicalize(prepared);
    // 相同项目再次保存属于幂等读取，不产生空修订，也不增加版本号。
    if (matchesCurrentSelection(canonicalItems, current)) {
      return view(plan, current);
    }
    orders.deleteMutableItems(plan.id());
    for (int index = 0; index < canonicalItems.size(); index++) {
      PreparedItem item = canonicalItems.get(index);
      if (item.preserved()) {
        orders.updateItemSortOrder(plan.id(), item.existing().id(), index, now);
      } else {
        orders.insertItem(item.withSortOrder(index).draft(), now);
      }
    }
    if (!orders.activateAndIncrement(plan.id(), command.expectedVersion(), now)) {
      throw versionConflict();
    }
    BattleOrderRepository.PlanRow updated = orders.findPlan(userId, date).orElseThrow();
    List<BattleOrderRepository.ItemRow> savedItems = orders.findItems(plan.id());
    PlanView result = view(updated, savedItems);
    orders.insertRevision(
        ids.nextId(), plan.id(), updated.version(), writeSnapshot(result.items()), now);
    return result;
  }

  private void validateCommandIdentity(
      List<ItemCommand> commands, Map<String, BattleOrderRepository.ItemRow> currentById) {
    Set<String> idsSeen = new HashSet<>();
    for (ItemCommand item : commands) {
      if (item == null) {
        throw invalid(HttpStatus.BAD_REQUEST, "BATTLE_ORDER_INVALID", "作战单项目不能为空");
      }
      String existingId = item.existingItemId();
      if (existingId == null) continue;
      if (!idsSeen.add(existingId) || !currentById.containsKey(existingId)) {
        throw invalid(HttpStatus.CONFLICT, "PLAN_ITEM_INVALID", "作战单项目已经失效，请重新加载");
      }
    }
  }

  private PreparedItem prepareMutable(String userId, String planId, ItemCommand command) {
    String requestedType = command.itemType() == null ? "" : command.itemType().trim();
    if (requestedType.equals("VIDEO") || requestedType.equals("REVIEW_SHORTCUT")) {
      if (command.mediaItemId() == null) {
        throw invalid(HttpStatus.BAD_REQUEST, "PLAN_MEDIA_INVALID", "请选择课时");
      }
      var lesson =
          catalog
              .findLesson(command.mediaItemId())
              .orElseThrow(() -> invalid(HttpStatus.CONFLICT, "PLAN_MEDIA_INVALID", "所选课时不可用"));
      boolean completed = orders.isLessonCompleted(userId, lesson.id());
      String logicalType = completed ? "REVIEW_SHORTCUT" : "VIDEO";
      boolean quizRequired =
          logicalType.equals("VIDEO")
              && videoRequirements.stream().anyMatch(port -> port.quizRequired(lesson.id()));
      return PreparedItem.created(
          new BattleOrderRepository.ItemDraft(
              command.existingItemId() == null ? ids.nextId() : command.existingItemId(),
              planId,
              logicalType,
              "VIDEO",
              lesson.title(),
              lesson.id(),
              null,
              null,
              Math.max(1, (lesson.durationMs() + 999) / 1000),
              quizRequired,
              command.sortOrder()));
    }
    if (requestedType.equals("MOCK_EXAM")) {
      if (command.mockExamPresetId() == null) {
        throw invalid(HttpStatus.BAD_REQUEST, "MOCK_EXAM_PRESET_NOT_FOUND", "请选择模拟考试预置");
      }
      var preset = presets.requireOwned(userId, command.mockExamPresetId());
      return PreparedItem.created(
          new BattleOrderRepository.ItemDraft(
              command.existingItemId() == null ? ids.nextId() : command.existingItemId(),
              planId,
              "MOCK_EXAM",
              "FOCUS",
              preset.name(),
              null,
              preset.id(),
              preset.name(),
              preset.durationSeconds(),
              false,
              command.sortOrder()));
    }
    throw invalid(HttpStatus.BAD_REQUEST, "PLAN_ITEM_TYPE_INVALID", "作战单项目类型无效");
  }

  private void validateImmutableMatch(ItemCommand command, BattleOrderRepository.ItemRow existing) {
    boolean same =
        existing.itemType().equals(command.itemType())
            && java.util.Objects.equals(existing.mediaItemId(), command.mediaItemId())
            && java.util.Objects.equals(existing.mockExamPresetId(), command.mockExamPresetId());
    if (!same) throw immutableItem(existing.title());
  }

  /** 视频和复习入口按目录固有顺序排列，模拟考试统一放在课时之后。 */
  private List<PreparedItem> canonicalize(List<PreparedItem> prepared) {
    Map<String, Integer> lessonOrder = catalog.canonicalLessonOrder();
    List<IndexedPreparedItem> indexed = new ArrayList<>();
    for (int index = 0; index < prepared.size(); index++) {
      indexed.add(new IndexedPreparedItem(prepared.get(index), index));
    }
    indexed.sort(
        Comparator.comparingInt(
                (IndexedPreparedItem value) -> value.item().mediaItemId() == null ? 1 : 0)
            .thenComparingInt(
                value ->
                    value.item().mediaItemId() == null
                        ? value.inputIndex()
                        : lessonOrder.getOrDefault(value.item().mediaItemId(), Integer.MAX_VALUE))
            .thenComparingInt(IndexedPreparedItem::inputIndex));
    return indexed.stream().map(IndexedPreparedItem::item).toList();
  }

  /** 比较权威排序后的业务选择；标题、时长等快照变化不能把用户的无操作保存伪装成修改。 */
  private boolean matchesCurrentSelection(
      List<PreparedItem> prepared, List<BattleOrderRepository.ItemRow> current) {
    if (prepared.size() != current.size()) return false;
    for (int index = 0; index < prepared.size(); index++) {
      if (!prepared.get(index).matches(current.get(index))) return false;
    }
    return true;
  }

  private PlanView view(
      BattleOrderRepository.PlanRow plan, List<BattleOrderRepository.ItemRow> items) {
    return new PlanView(
        plan.id(),
        plan.date(),
        plan.lifecycleStatus(),
        plan.version(),
        items.stream().map(ItemView::from).toList());
  }

  private String writeSnapshot(List<ItemView> items) {
    try {
      return objectMapper.writeValueAsString(items);
    } catch (Exception exception) {
      throw new IllegalStateException("无法保存作战单修订快照", exception);
    }
  }

  private BusinessException versionConflict() {
    return invalid(HttpStatus.CONFLICT, "PLAN_VERSION_CONFLICT", "作战单已在其他页面修改，请重新加载");
  }

  private BusinessException immutableItem(String title) {
    return invalid(HttpStatus.CONFLICT, "PLAN_ITEM_IMMUTABLE", "“" + title + "”已经开始，不能修改或删除");
  }

  private BusinessException duplicateLesson() {
    return invalid(HttpStatus.CONFLICT, "PLAN_LESSON_DUPLICATED", "同一课时当天只能加入一次");
  }

  private BusinessException invalid(HttpStatus status, String code, String message) {
    return new BusinessException(status, code, message);
  }

  public record SaveCommand(long expectedVersion, List<ItemCommand> items) {}

  public record ItemCommand(
      String existingItemId,
      String itemType,
      String mediaItemId,
      String mockExamPresetId,
      int sortOrder) {}

  public record PlanView(
      String id, LocalDate date, String status, long version, List<ItemView> items) {}

  public record ItemView(
      String id,
      String itemType,
      String title,
      String mediaItemId,
      String mockExamPresetId,
      String mockExamName,
      long plannedSeconds,
      long completedSeconds,
      String status,
      int sortOrder,
      boolean immutable) {
    static ItemView from(BattleOrderRepository.ItemRow item) {
      return new ItemView(
          item.id(),
          item.itemType(),
          item.title(),
          item.mediaItemId(),
          item.mockExamPresetId(),
          item.mockExamNameSnapshot(),
          item.plannedSeconds(),
          item.completedSeconds(),
          item.status(),
          item.sortOrder(),
          item.immutable());
    }
  }

  private record PreparedItem(
      BattleOrderRepository.ItemDraft draft,
      BattleOrderRepository.ItemRow existing,
      boolean preserved) {
    static PreparedItem created(BattleOrderRepository.ItemDraft draft) {
      return new PreparedItem(draft, null, false);
    }

    static PreparedItem preserved(BattleOrderRepository.ItemRow existing) {
      return new PreparedItem(null, existing, true);
    }

    String mediaItemId() {
      return preserved ? existing.mediaItemId() : draft.mediaItemId();
    }

    /** 只比较作战单的用户选择身份和固有顺序，不比较可刷新展示快照。 */
    boolean matches(BattleOrderRepository.ItemRow current) {
      if (preserved) return existing.id().equals(current.id());
      return draft.id().equals(current.id())
          && draft.logicalType().equals(current.itemType())
          && java.util.Objects.equals(draft.mediaItemId(), current.mediaItemId())
          && java.util.Objects.equals(draft.mockExamPresetId(), current.mockExamPresetId());
    }

    PreparedItem withSortOrder(int sortOrder) {
      if (preserved) return this;
      return created(
          new BattleOrderRepository.ItemDraft(
              draft.id(),
              draft.planId(),
              draft.logicalType(),
              draft.physicalType(),
              draft.title(),
              draft.mediaItemId(),
              draft.mockExamPresetId(),
              draft.mockExamNameSnapshot(),
              draft.plannedSeconds(),
              draft.quizRequired(),
              sortOrder));
    }
  }

  private record IndexedPreparedItem(PreparedItem item, int inputIndex) {}
}
