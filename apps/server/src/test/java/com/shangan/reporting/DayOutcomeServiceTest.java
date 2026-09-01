package com.shangan.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.common.IdGenerator;
import com.shangan.planning.application.DayEndPlanCloser;
import com.shangan.reporting.application.DayOutcomeService;
import com.shangan.reporting.infrastructure.DayOutcomeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 不连接数据库，直接验证无作战单日期的日终业务分类。 */
class DayOutcomeServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-01T16:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  @Test
  void noPlanAndNoEffectiveActivityBecomesSlacked() {
    FakeDayOutcomeRepository repository =
        new FakeDayOutcomeRepository(new DayOutcomeRepository.DayActivitySummary(0, 0, 0, 0));

    assertThat(service(repository).settle("user-1", DATE, ZONE)).isEqualTo("SLACKED");
    assertThat(repository.savedOutcome).isEqualTo("SLACKED");
  }

  @Test
  void trustedFreeStudyWithoutPlanBecomesFreeStudy() {
    FakeDayOutcomeRepository repository =
        new FakeDayOutcomeRepository(new DayOutcomeRepository.DayActivitySummary(1, 0, 0, 0));

    assertThat(service(repository).settle("user-1", DATE, ZONE)).isEqualTo("FREE_STUDY");
    assertThat(repository.savedOutcome).isEqualTo("FREE_STUDY");
  }

  @Test
  void reviewAuditDoesNotPreventSlackedOutcome() {
    FakeDayOutcomeRepository repository =
        new FakeDayOutcomeRepository(new DayOutcomeRepository.DayActivitySummary(0, 0, 0, 3));

    assertThat(service(repository).settle("user-1", DATE, ZONE)).isEqualTo("SLACKED");
  }

  /** 使用固定时钟和内存边界创建待测服务，不启动 Spring。 */
  private DayOutcomeService service(FakeDayOutcomeRepository repository) {
    IdGenerator ids = () -> "outcome-1";
    DayEndPlanCloser plans = (userId, planId) -> {};
    return new DayOutcomeService(repository, plans, ids, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** 只保存测试关心的业务输入和输出，不模拟任何 SQL 行为。 */
  private static final class FakeDayOutcomeRepository implements DayOutcomeRepository {
    private final DayActivitySummary activitySummary;
    private String savedOutcome;

    private FakeDayOutcomeRepository(DayActivitySummary activitySummary) {
      this.activitySummary = activitySummary;
    }

    @Override
    public List<UserDaySettings> users() {
      return List.of();
    }

    @Override
    public Optional<LocalDate> latestOutcomeDate(String userId) {
      return Optional.empty();
    }

    @Override
    public Optional<PlanDay> findPlan(String userId, LocalDate date) {
      return Optional.empty();
    }

    @Override
    public DayActivitySummary activitySummary(String userId, Instant start, Instant end) {
      return activitySummary;
    }

    @Override
    public void upsert(
        String id, String userId, LocalDate date, String outcome, Instant generatedAt) {
      savedOutcome = outcome;
    }
  }
}
