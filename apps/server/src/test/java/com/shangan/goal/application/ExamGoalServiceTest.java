package com.shangan.goal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.goal.application.ExamGoalService.GoalView;
import com.shangan.goal.domain.ExamGoal;
import com.shangan.goal.domain.GoalUrgency;
import com.shangan.goal.infrastructure.ExamGoalRepository;
import com.shangan.identity.application.UserTimeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 考试目标：主目标唯一、剩余天数与紧急度由服务端计算。 */
@ExtendWith(MockitoExtension.class)
class ExamGoalServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
  private static final String USER_ID = "user-1";

  @Mock private ExamGoalRepository goals;
  @Mock private UserTimeService userTime;

  private ExamGoalService service;

  @BeforeEach
  void setUp() {
    service =
        new ExamGoalService(goals, userTime, () -> "goal-new", Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("首个目标自动成为主目标，并清空其他目标的主标记")
  void 首个目标自动主目标() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of());
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    GoalView view = service.create(USER_ID, "  考研初试  ", "2026-12-21", null, false);

    assertThat(view.id()).isEqualTo("goal-new");
    assertThat(view.name()).isEqualTo("考研初试");
    assertThat(view.primary()).isTrue();
    assertThat(view.note()).isEmpty();
    verify(goals).clearPrimary(USER_ID, "goal-new", NOW);
    verify(goals).insert(any(), org.mockito.ArgumentMatchers.eq(NOW));
  }

  @Test
  @DisplayName("新建非主目标时不动已有主目标")
  void 新建非主目标不影响主目标() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of(goal("goal-1", TODAY.plusDays(100), true)));
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    GoalView view = service.create(USER_ID, "六级", "2026-12-19", "顺带考", false);

    assertThat(view.primary()).isFalse();
    verify(goals, never()).clearPrimary(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("剩余天数按用户本地日期计算，60 天以上为 NORMAL")
  void 剩余天数与普通分档() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of(goal("goal-1", TODAY.plusDays(105), true)));
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    List<GoalView> views = service.list(USER_ID);

    assertThat(views)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.daysRemaining()).isEqualTo(105);
              assertThat(view.urgency()).isEqualTo(GoalUrgency.NORMAL);
            });
  }

  @Test
  @DisplayName("紧急度分档边界：30 天 URGENT、31 天 SOON、60 天 SOON、61 天 NORMAL")
  void 紧急度分档边界() {
    when(goals.findByUser(USER_ID))
        .thenReturn(
            List.of(
                goal("goal-30", TODAY.plusDays(30), true),
                goal("goal-31", TODAY.plusDays(31), false),
                goal("goal-60", TODAY.plusDays(60), false),
                goal("goal-61", TODAY.plusDays(61), false)));
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    assertThat(service.list(USER_ID))
        .extracting(GoalView::id, GoalView::urgency)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("goal-30", GoalUrgency.URGENT),
            org.assertj.core.groups.Tuple.tuple("goal-31", GoalUrgency.SOON),
            org.assertj.core.groups.Tuple.tuple("goal-60", GoalUrgency.SOON),
            org.assertj.core.groups.Tuple.tuple("goal-61", GoalUrgency.NORMAL));
  }

  @Test
  @DisplayName("已过期目标返回负数天数并标记 EXPIRED")
  void 过期目标返回负数天数() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of(goal("goal-1", TODAY.minusDays(3), true)));
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    assertThat(service.list(USER_ID))
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.daysRemaining()).isEqualTo(-3);
              assertThat(view.urgency()).isEqualTo(GoalUrgency.EXPIRED);
            });
  }

  @Test
  @DisplayName("改为主目标时清空其他目标的主标记")
  void 切换主目标互斥() {
    when(goals.findById("goal-2"))
        .thenReturn(Optional.of(goal("goal-2", TODAY.plusDays(50), false)));
    when(userTime.today(USER_ID)).thenReturn(TODAY);

    GoalView view = service.update(USER_ID, "goal-2", null, null, null, true);

    assertThat(view.primary()).isTrue();
    verify(goals).clearPrimary(USER_ID, "goal-2", NOW);
    verify(goals).update(any(), org.mockito.ArgumentMatchers.eq(NOW));
  }

  @Test
  @DisplayName("删除主目标后把剩余最近的目标提升为主目标")
  void 删除主目标后提升次目标() {
    when(goals.findById("goal-1"))
        .thenReturn(Optional.of(goal("goal-1", TODAY.plusDays(10), true)));
    when(goals.findByUser(USER_ID)).thenReturn(List.of(goal("goal-2", TODAY.plusDays(40), false)));

    service.delete(USER_ID, "goal-1");

    verify(goals).delete("goal-1");
    verify(goals)
        .update(new ExamGoal("goal-2", USER_ID, "目标 goal-2", TODAY.plusDays(40), "", true), NOW);
  }

  @Test
  @DisplayName("删除非主目标时不改动其他目标")
  void 删除非主目标不提升() {
    when(goals.findById("goal-2"))
        .thenReturn(Optional.of(goal("goal-2", TODAY.plusDays(40), false)));

    service.delete(USER_ID, "goal-2");

    verify(goals).delete("goal-2");
    verify(goals, never()).update(any(), any());
  }

  @Test
  @DisplayName("目标名称为空或超长时返回 GOAL_NAME_INVALID")
  void 名称校验() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.create(USER_ID, "   ", "2026-12-21", null, true))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("GOAL_NAME_INVALID");
  }

  @Test
  @DisplayName("考试日期格式错误时返回 GOAL_DATE_INVALID")
  void 日期校验() {
    when(goals.findByUser(USER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.create(USER_ID, "考研初试", "2026/12/21", null, true))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("GOAL_DATE_INVALID");
  }

  @Test
  @DisplayName("访问他人目标按不存在处理")
  void 越权访问视为不存在() {
    when(goals.findById("goal-1"))
        .thenReturn(
            Optional.of(new ExamGoal("goal-1", "other-user", "考研", TODAY.plusDays(10), "", true)));

    assertThatThrownBy(() -> service.delete(USER_ID, "goal-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("GOAL_NOT_FOUND");
  }

  @Test
  @DisplayName("目标数量达到 12 个上限时拒绝新建")
  void 数量上限() {
    List<ExamGoal> existing = new java.util.ArrayList<>();
    for (int index = 0; index < 12; index++) {
      existing.add(goal("goal-" + index, TODAY.plusDays(index + 1), index == 0));
    }
    when(goals.findByUser(USER_ID)).thenReturn(existing);

    assertThatThrownBy(() -> service.create(USER_ID, "再来一个", "2026-12-21", null, false))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("GOAL_LIMIT_REACHED");
  }

  private static ExamGoal goal(String id, LocalDate examDate, boolean primary) {
    return new ExamGoal(id, USER_ID, "目标 " + id, examDate, "", primary);
  }
}
