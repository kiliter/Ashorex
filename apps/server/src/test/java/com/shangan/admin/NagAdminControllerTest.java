package com.shangan.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.domain.UserStatus;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.todo.application.TodoDeletionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 催办记录后台接口的 Controller 切片测试。
 *
 * <p>覆盖投递可观测性：`GET /admin/api/nags` 必须把 `nag_deliveries` 的每次渠道尝试与脱敏失败原因一起返回，
 * 让管理员能区分「还没轮到投递」与「两个渠道都试过且失败」；同时断言响应正文不含 SendKey、目标地址与堆栈。
 *
 * <p>不启动数据库与 Flyway，全部依赖以 Mockito 打桩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("催办记录后台接口")
class NagAdminControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-07T16:00:00Z");
  private static final String SEND_KEY = "SCT123456SECRET";

  @Mock private NagPolicyResolver policies;
  @Mock private NagRepository nags;
  @Mock private TodoDeletionService deletions;
  @Mock private AuthService users;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new NagAdminController(
                    policies, nags, deletions, users, Clock.fixed(NOW, ZoneOffset.UTC)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("两个渠道都失败时返回逐次投递尝试与脱敏原因")
  void returnsFailedDeliveryAttempts() throws Exception {
    stubCommonLookups();
    when(nags.findRecent(anyInt())).thenReturn(List.of(pendingNag()));
    when(nags.deliveriesOfAll(List.of("nag-1")))
        .thenReturn(
            List.of(
                new NagRepository.Delivery(
                    "d-1",
                    "nag-1",
                    NagChannelType.FULLSCREEN,
                    "FAILED",
                    "渠道不可用",
                    NOW.minusSeconds(120)),
                new NagRepository.Delivery(
                    "d-2",
                    "nag-1",
                    NagChannelType.SERVERCHAN,
                    "FAILED",
                    "Server 酱未配置或未启用",
                    NOW.minusSeconds(60))));

    mockMvc
        .perform(get("/admin/api/nags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nags[0].id").value("nag-1"))
        .andExpect(jsonPath("$.nags[0].status").value("PENDING"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1']", hasSize(2)))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][0].channel").value("FULLSCREEN"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][0].status").value("FAILED"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][0].detail").value("渠道不可用"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][1].channel").value("SERVERCHAN"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][1].status").value("FAILED"))
        .andExpect(jsonPath("$.deliveryAttempts['nag-1'][1].detail").value("Server 酱未配置或未启用"))
        .andExpect(content().string(not(containsString(SEND_KEY))))
        .andExpect(content().string(not(containsString("sctapi.ftqq.com"))))
        .andExpect(content().string(not(containsString("Exception:"))));
  }

  @Test
  @DisplayName("尚未产生任何投递记录的催办不出现在投递映射里")
  void omitsNagsWithoutAttempts() throws Exception {
    stubCommonLookups();
    when(nags.findRecent(anyInt())).thenReturn(List.of(pendingNag()));
    when(nags.deliveriesOfAll(List.of("nag-1"))).thenReturn(List.of());

    mockMvc
        .perform(get("/admin/api/nags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveryAttempts['nag-1']").doesNotExist());
  }

  @Test
  @DisplayName("没有任何催办时不查询投递流水，直接返回空映射")
  void skipsDeliveryQueryWhenNoNags() throws Exception {
    stubCommonLookups();
    when(nags.findRecent(anyInt())).thenReturn(List.of());

    mockMvc
        .perform(get("/admin/api/nags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveryAttempts").isEmpty());

    Assertions.assertThat(Mockito.mockingDetails(nags).getInvocations())
        .noneMatch(invocation -> invocation.getMethod().getName().equals("deliveriesOfAll"));
  }

  private void stubCommonLookups() {
    when(users.listUsers()).thenReturn(List.of(learner()));
    when(deletions.recent(anyInt())).thenReturn(List.of());
    when(nags.countByStatus())
        .thenReturn(List.of(new NagRepository.StatusCount(NagStatus.PENDING, 1)));
  }

  private static User learner() {
    return new User(
        "user-1",
        "lisi",
        "hash",
        "李四",
        "Asia/Shanghai",
        UserStatus.ACTIVE,
        null,
        Set.of(UserRole.LEARNER));
  }

  private static Nag pendingNag() {
    return new Nag(
        "nag-1",
        "user-1",
        LocalDate.of(2026, 9, 7),
        1,
        NagTrigger.AUTO,
        null,
        95,
        3,
        "还有 3 项没做",
        true,
        NagStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        NOW.minusSeconds(180));
  }
}
