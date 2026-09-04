package com.shangan.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.planning.application.BattleOrderService;
import com.shangan.planning.infrastructure.BattleOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 覆盖作战日历摘要、项目课程字段，以及非当天作战单不可保存。 */
class BattleOrderServiceTest {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T04:00:00Z"), SHANGHAI);

  @Test
  void calendarMarksCompletedAndDebtDaysWithoutInventingEmptyDates() {
    FakeRepository orders = new FakeRepository();
    orders.addPlan("plan-1", LocalDate.of(2026, 9, 1), "COMPLETED", 2, 2);
    orders.addPlan("plan-2", LocalDate.of(2026, 9, 2), "CLOSED_WITH_DEBT", 3, 1);
    orders.addPlan("plan-3", LocalDate.of(2026, 9, 3), "ABANDONED", 1, 0);
    orders.addPlan("plan-4", LocalDate.of(2026, 9, 4), "DRAFT", 2, 0);
    BattleOrderService service = service(orders);

    BattleOrderService.CalendarView view =
        service.calendar("user-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4));

    assertThat(view.days()).hasSize(4);
    assertThat(view.days().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(view.days().get(0).completed()).isTrue();
    assertThat(view.days().get(0).hasDebt()).isFalse();
    assertThat(view.days().get(1).date()).isEqualTo(LocalDate.of(2026, 9, 2));
    assertThat(view.days().get(1).completed()).isFalse();
    assertThat(view.days().get(1).hasDebt()).isTrue();
    assertThat(view.days().get(1).itemCount()).isEqualTo(3);
    assertThat(view.days().get(1).completedItemCount()).isEqualTo(1);
    assertThat(view.days().get(2).hasDebt()).isTrue();
    assertThat(view.days().get(3).hasDebt()).isFalse();
  }

  @Test
  void calendarRejectsInvertedOrOverlongRange() {
    BattleOrderService service = service(new FakeRepository());
    assertThatThrownBy(
            () -> service.calendar("user-1", LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 1)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("日历查询范围无效");
    assertThatThrownBy(
            () -> service.calendar("user-1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 3)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("日历查询范围无效");
  }

  @Test
  void getIncludesCourseGroupingFields() {
    FakeRepository orders = new FakeRepository();
    orders.addPlan("plan-1", LocalDate.of(2026, 9, 3), "ACTIVE", 1, 0);
    orders.items.put(
        "plan-1",
        List.of(
            new BattleOrderRepository.ItemRow(
                "item-1",
                "plan-1",
                "VIDEO",
                "判断推理 12",
                "lesson-1",
                null,
                null,
                2280,
                600,
                "PENDING",
                0,
                false,
                "course-1",
                "判断推理",
                true,
                null,
                null)));
    BattleOrderService.PlanView view = service(orders).get("user-1", LocalDate.of(2026, 9, 3));

    assertThat(view.items()).hasSize(1);
    assertThat(view.items().getFirst().courseId()).isEqualTo("course-1");
    assertThat(view.items().getFirst().courseName()).isEqualTo("判断推理");
    assertThat(view.items().getFirst().quizRequired()).isTrue();
    assertThat(view.items().getFirst().mockExamSessionStatus()).isNull();
  }

  @Test
  void saveRejectsDatesOtherThanUserToday() {
    BattleOrderService service = service(new FakeRepository());
    BattleOrderService.SaveCommand command = new BattleOrderService.SaveCommand(0, List.of());
    assertThatThrownBy(
            () -> service.save("user-1", "Asia/Shanghai", LocalDate.of(2026, 9, 2), command))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能修改已经过去的作战单");
  }

  private BattleOrderService service(FakeRepository orders) {
    return new BattleOrderService(orders, null, null, List.of(), () -> "id-1", null, CLOCK);
  }

  /** 纯内存仓库只覆盖日历与读取边界，不连接 SQLite。 */
  private static final class FakeRepository implements BattleOrderRepository {
    private final List<PlanRow> plans = new ArrayList<>();
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private final Map<String, Integer> completedCounts = new HashMap<>();
    private final Map<String, List<ItemRow>> items = new HashMap<>();

    void addPlan(String id, LocalDate date, String status, int itemCount, int completedItemCount) {
      plans.add(new PlanRow(id, "user-1", date, status, 1));
      itemCounts.put(id, itemCount);
      completedCounts.put(id, completedItemCount);
    }

    @Override
    public Optional<PlanRow> findPlan(String userId, LocalDate date) {
      return plans.stream()
          .filter(plan -> plan.userId().equals(userId) && plan.date().equals(date))
          .findFirst();
    }

    @Override
    public List<PlanDayRow> listPlanDays(String userId, LocalDate from, LocalDate to) {
      return plans.stream()
          .filter(
              plan ->
                  plan.userId().equals(userId)
                      && !plan.date().isBefore(from)
                      && !plan.date().isAfter(to))
          .map(
              plan ->
                  new PlanDayRow(
                      plan.date(),
                      plan.lifecycleStatus(),
                      itemCounts.getOrDefault(plan.id(), 0),
                      completedCounts.getOrDefault(plan.id(), 0),
                      0L))
          .toList();
    }

    @Override
    public List<ItemRow> findItems(String planId) {
      return items.getOrDefault(planId, List.of());
    }

    @Override
    public void insertPlan(String id, String userId, LocalDate date, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean activateAndIncrement(String planId, long expectedVersion, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteMutableItems(String planId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertItem(ItemDraft item, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateItemSortOrder(String planId, String itemId, int sortOrder, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertRevision(
        String id, String planId, long version, String snapshotJson, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLessonCompleted(String userId, String mediaItemId) {
      return false;
    }
  }
}
