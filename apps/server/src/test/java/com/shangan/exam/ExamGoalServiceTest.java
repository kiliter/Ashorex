package com.shangan.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.exam.application.EmptyExamLearningProgress;
import com.shangan.exam.application.ExamGoalService;
import com.shangan.exam.application.ExamProgressCalculator;
import com.shangan.exam.domain.ExamGoal;
import com.shangan.exam.infrastructure.ExamGoalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 覆盖考试目标删除的归属校验、幂等错误码和对其它考试目标的隔离。 */
class ExamGoalServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");

  @Test
  void deleteRemovesOnlyRequestedGoalOfCurrentUser() {
    FakeExamGoalRepository goals = new FakeExamGoalRepository();
    goals.put(goal("goal-1", "user-1", "2026 国考"));
    goals.put(goal("goal-2", "user-1", "省考"));
    goals.put(goal("goal-3", "user-2", "事业单位"));
    ExamGoalService service = service(goals);

    service.deleteGoal("user-1", "goal-1");

    assertThat(goals.listByUserId("user-1")).extracting(ExamGoal::id).containsExactly("goal-2");
    assertThat(goals.listByUserId("user-2")).extracting(ExamGoal::id).containsExactly("goal-3");
    assertThat(goals.deletedCourseBindings).containsExactly("goal-1");
  }

  @Test
  void deleteAllowsRemovingTheLastGoal() {
    FakeExamGoalRepository goals = new FakeExamGoalRepository();
    goals.put(goal("goal-1", "user-1", "2026 国考"));
    ExamGoalService service = service(goals);

    service.deleteGoal("user-1", "goal-1");

    assertThat(goals.listByUserId("user-1")).isEmpty();
    assertThat(service.listGoals("user-1")).isEmpty();
  }

  @Test
  void deleteRejectsGoalOwnedByAnotherUser() {
    FakeExamGoalRepository goals = new FakeExamGoalRepository();
    goals.put(goal("goal-1", "user-2", "别人的考试"));
    ExamGoalService service = service(goals);

    assertThatThrownBy(() -> service.deleteGoal("user-1", "goal-1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error -> {
              BusinessException business = (BusinessException) error;
              assertThat(business.errorCode()).isEqualTo("EXAM_GOAL_NOT_FOUND");
              assertThat(business.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    assertThat(goals.listByUserId("user-2")).extracting(ExamGoal::id).containsExactly("goal-1");
  }

  @Test
  void deleteRejectsUnknownGoal() {
    FakeExamGoalRepository goals = new FakeExamGoalRepository();
    ExamGoalService service = service(goals);

    assertThatThrownBy(() -> service.deleteGoal("user-1", "missing"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).errorCode())
        .isEqualTo("EXAM_GOAL_NOT_FOUND");
  }

  private ExamGoalService service(ExamGoalRepository goals) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new ExamGoalService(
        goals,
        // 删除路径不允许读取课程目录；一旦访问，UnusedCourseRepository 会直接让测试失败。
        new CatalogQueryService(new UnusedCourseRepository()),
        new EmptyExamLearningProgress(),
        new ExamProgressCalculator(clock),
        new FixedIdGenerator(),
        clock);
  }

  private ExamGoal goal(String goalId, String userId, String name) {
    return new ExamGoal(
        goalId,
        userId,
        name,
        LocalDate.parse("2026-11-29"),
        LocalDate.parse("2026-11-15"),
        14,
        "Asia/Shanghai",
        List.of("course-1"));
  }

  /** 保留插入顺序的考试目标仓储替身，并记录课程绑定的清理调用。 */
  private static final class FakeExamGoalRepository implements ExamGoalRepository {
    private final Map<String, ExamGoal> stored = new LinkedHashMap<>();
    private final List<String> deletedCourseBindings = new ArrayList<>();

    void put(ExamGoal goal) {
      stored.put(goal.id(), goal);
    }

    @Override
    public Optional<ExamGoal> findByUserId(String userId) {
      List<ExamGoal> goals = listByUserId(userId);
      return goals.isEmpty() ? Optional.empty() : Optional.of(goals.getFirst());
    }

    @Override
    public List<ExamGoal> listByUserId(String userId) {
      return stored.values().stream().filter(goal -> goal.userId().equals(userId)).toList();
    }

    @Override
    public Optional<ExamGoal> findById(String userId, String goalId) {
      return Optional.ofNullable(stored.get(goalId)).filter(goal -> goal.userId().equals(userId));
    }

    @Override
    public void save(ExamGoal goal, Instant now) {
      stored.put(goal.id(), goal);
    }

    @Override
    public boolean delete(String userId, String goalId) {
      Optional<ExamGoal> owned = findById(userId, goalId);
      if (owned.isEmpty()) {
        return false;
      }
      deletedCourseBindings.add(goalId);
      stored.remove(goalId);
      return true;
    }
  }

  private static final class FixedIdGenerator implements IdGenerator {
    @Override
    public String nextId() {
      return "generated-id";
    }
  }

  /** 课程仓储替身：任何访问都表示删除路径越界读取了课程目录。 */
  private static final class UnusedCourseRepository implements CourseRepository {
    private UnsupportedOperationException unexpected() {
      return new UnsupportedOperationException("删除考试目标不应访问课程目录");
    }

    @Override
    public Optional<Course> findCourse(String courseId) {
      throw unexpected();
    }

    @Override
    public List<Course> findAllCourses(boolean enabledOnly) {
      throw unexpected();
    }

    @Override
    public List<MediaItem> findMediaItems(String courseId, boolean enabledOnly) {
      throw unexpected();
    }

    @Override
    public Optional<MediaItem> findMediaItem(String mediaItemId) {
      throw unexpected();
    }

    @Override
    public void insertCourse(Course course, Instant now) {
      throw unexpected();
    }

    @Override
    public void insertMediaItem(MediaItem item, Instant now) {
      throw unexpected();
    }

    @Override
    public void updateMediaItemFromRemote(MediaItem item, Instant now) {
      throw unexpected();
    }

    @Override
    public void insertMediaItemSourceMapping(
        String id,
        String mediaItemId,
        String oldEmbyItemId,
        String newEmbyItemId,
        String matchType,
        Instant now) {
      throw unexpected();
    }

    @Override
    public void markUnavailableExceptMediaIds(
        String courseId, List<String> availableMediaItemIds, Instant now) {
      throw unexpected();
    }

    @Override
    public void updateCourseSource(String courseId, String embyParentItemId, Instant now) {
      throw unexpected();
    }

    @Override
    public void updateCourseEnabled(String courseId, boolean enabled, Instant now) {
      throw unexpected();
    }

    @Override
    public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {
      throw unexpected();
    }

    @Override
    public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {
      throw unexpected();
    }
  }
}
