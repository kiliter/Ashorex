package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.application.EffectiveActionRecorder;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.UncategorizedSQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 催办回应：原因必填、长度下限来自策略，回应刷新有效操作时间。 */
@ExtendWith(MockitoExtension.class)
class NagResponseServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T15:00:00Z");
  private static final String USER_ID = "user-1";

  @Mock private com.shangan.identity.application.UserTimeService userTime;
  @Mock private NagRepository nags;
  @Mock private NagPolicyResolver policies;
  @Mock private EffectiveActionRecorder effectiveAction;

  private NagResponseService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient()
        .when(userTime.today(USER_ID))
        .thenReturn(LocalDate.of(2026, 9, 7));
    service =
        new NagResponseService(
            nags, policies, effectiveAction, Clock.fixed(NOW, ZoneOffset.UTC), userTime);
  }

  @Test
  @DisplayName("读取待回应催办前先按学员日期结束旧催办")
  void 跨日催办过期() {
    service.pending(USER_ID);
    var order = org.mockito.Mockito.inOrder(nags);
    order.verify(nags).expireBefore(USER_ID, LocalDate.of(2026, 9, 7));
    order.verify(nags).supersedeOlderAppNags(USER_ID);
    order.verify(nags).findAwaitingByUser(USER_ID);
  }

  @Test
  void 被替代的催办不再要求回应也不刷新操作时间() {
    Nag old = nag(true);
    Nag replaced =
        new Nag(
            old.id(),
            old.userId(),
            old.localDate(),
            old.thresholdLevel(),
            old.trigger(),
            old.triggeredByUserId(),
            old.idleMinutes(),
            old.pendingCount(),
            old.message(),
            old.requireReason(),
            old.status(),
            old.deliveredAt(),
            old.respondedAt(),
            old.reasonTag(),
            old.reasonText(),
            old.supervisorUserIdSnapshot(),
            old.createdAt(),
            old.title(),
            true);
    assertThat(replaced.awaitingResponse()).isFalse();
    when(nags.findById("nag-1")).thenReturn(Optional.of(replaced));
    assertThat(service.respond(USER_ID, "nag-1", "TEMP_BUSY", "临时有事马上回来")).isEqualTo(replaced);
    verify(nags, never()).markResponded(anyString(), anyString(), anyString(), any());
    verifyNoInteractions(effectiveAction);
  }

  @Test
  @DisplayName("重复回应不覆盖原原因和回应时间")
  void 重复回应不覆盖() {
    Nag old = nag(true);
    Nag responded =
        new Nag(
            old.id(),
            old.userId(),
            old.localDate(),
            old.thresholdLevel(),
            old.trigger(),
            old.triggeredByUserId(),
            old.idleMinutes(),
            old.pendingCount(),
            old.message(),
            old.requireReason(),
            NagStatus.RESPONDED,
            NOW,
            NOW,
            "TEMP_BUSY",
            "先前的说明内容",
            old.supervisorUserIdSnapshot(),
            old.createdAt());
    when(nags.findById("nag-1")).thenReturn(Optional.of(responded));
    assertThat(service.respond(USER_ID, "nag-1", "OTHER", "新内容不覆盖")).isEqualTo(responded);
    verify(nags, never()).markResponded(anyString(), anyString(), anyString(), any());
    verifyNoInteractions(effectiveAction);
  }

  @Test
  void 已取消催办不能回应或刷新有效操作() {
    Nag old = nag(true);
    Nag cancelled =
        new Nag(
            old.id(),
            old.userId(),
            old.localDate(),
            old.thresholdLevel(),
            old.trigger(),
            old.triggeredByUserId(),
            old.idleMinutes(),
            old.pendingCount(),
            old.message(),
            old.requireReason(),
            NagStatus.CANCELLED,
            null,
            null,
            null,
            null,
            old.supervisorUserIdSnapshot(),
            old.createdAt());
    when(nags.findById("nag-1")).thenReturn(Optional.of(cancelled));
    assertThatThrownBy(() -> service.respond(USER_ID, "nag-1", "TEMP_BUSY", "今天回来填写"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("NAG_CANCELLED");
    verify(nags, never()).markResponded(anyString(), anyString(), anyString(), any());
    verifyNoInteractions(effectiveAction);
  }

  @Test
  @DisplayName("跨日催办不得从旧页面继续回应")
  void 过期回应拒绝() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(true)));
    when(userTime.today(USER_ID)).thenReturn(LocalDate.of(2026, 9, 8));
    assertThatThrownBy(() -> service.respond(USER_ID, "nag-1", "TEMP_BUSY", "今天回来填写"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo("NAG_EXPIRED");
    verify(nags, never()).markResponded(anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("回应催办：写入原因并刷新有效操作时间")
  void 回应成功刷新有效操作() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(true)));
    when(policies.resolve(USER_ID)).thenReturn(policy(5));

    service.respond(USER_ID, "nag-1", "TEMP_BUSY", "  临时有事马上回来  ");

    verify(nags).markResponded("nag-1", "TEMP_BUSY", "临时有事马上回来", NOW);
    verify(effectiveAction).record(USER_ID);
  }

  @Test
  @DisplayName("缺少原因标签时返回 NAG_REASON_REQUIRED")
  void 缺少原因标签被拒绝() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(true)));
    when(policies.resolve(USER_ID)).thenReturn(policy(5));

    assertThatThrownBy(() -> service.respond(USER_ID, "nag-1", null, "临时有事马上回来"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("NAG_REASON_REQUIRED");
    verify(nags, never()).markResponded(anyString(), anyString(), anyString(), any());
    verifyNoInteractions(effectiveAction);
  }

  @Test
  @DisplayName("原因说明短于策略下限时被拒绝")
  void 原因过短被拒绝() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(true)));
    when(policies.resolve(USER_ID)).thenReturn(policy(5));

    assertThatThrownBy(() -> service.respond(USER_ID, "nag-1", "TEMP_BUSY", "忙"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("NAG_REASON_REQUIRED");
  }

  @Test
  @DisplayName("不要求原因的催办可以直接回应")
  void 不要求原因可直接回应() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(false)));
    when(policies.resolve(USER_ID)).thenReturn(policy(5));

    service.respond(USER_ID, "nag-1", null, null);

    verify(nags).markResponded("nag-1", null, "", NOW);
  }

  @Test
  @DisplayName("回应他人的催办按不存在处理，避免暴露他人数据")
  void 越权回应视为不存在() {
    when(nags.findById("nag-1")).thenReturn(Optional.of(nag(true)));

    assertThatThrownBy(() -> service.respond("other-user", "nag-1", "TEMP_BUSY", "临时有事马上回来"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("NAG_NOT_FOUND");
  }

  @Test
  @DisplayName("待回应催办查询直接透传仓储结果")
  void 待回应查询透传() {
    when(nags.findAwaitingByUser(USER_ID)).thenReturn(Optional.of(nag(true)));

    assertThat(service.pending(USER_ID)).map(Nag::id).contains("nag-1");
  }

  @Test
  @DisplayName("昨日催办即使尚未写成 EXPIRED 也不再作为待回应返回")
  void 昨日催办不弹出() {
    when(nags.findAwaitingByUser(USER_ID)).thenReturn(Optional.of(nagOn(LocalDate.of(2026, 9, 6))));

    assertThat(service.pending(USER_ID)).isEmpty();
    assertThat(service.peekPending(USER_ID)).isEmpty();
  }

  @Test
  @DisplayName("清理跨日催办遇到 SQLITE_BUSY 时仍返回今日待回应催办")
  void 锁冲突时仍可读今日催办() {
    doThrow(
            new UncategorizedSQLException(
                "update",
                "UPDATE nags",
                new SQLException("[SQLITE_BUSY] The database file is locked", null, 5)))
        .when(nags)
        .expireBefore(USER_ID, LocalDate.of(2026, 9, 7));
    when(nags.findAwaitingByUser(USER_ID)).thenReturn(Optional.of(nag(true)));

    assertThat(service.pending(USER_ID)).map(Nag::id).contains("nag-1");
    verify(nags, never()).supersedeOlderAppNags(USER_ID);
  }

  private static Nag nag(boolean requireReason) {
    return nagOn(LocalDate.of(2026, 9, 7), requireReason);
  }

  private static Nag nagOn(LocalDate localDate) {
    return nagOn(localDate, true);
  }

  private static Nag nagOn(LocalDate localDate, boolean requireReason) {
    return new Nag(
        "nag-1",
        USER_ID,
        localDate,
        1,
        NagTrigger.AUTO,
        null,
        95,
        3,
        "还有 3 项没做",
        requireReason,
        NagStatus.DELIVERED,
        NOW.minusSeconds(120),
        null,
        null,
        null,
        null,
        NOW.minusSeconds(180));
  }

  private static EffectiveNagPolicy policy(int minReasonLength) {
    return new EffectiveNagPolicy(
        5,
        150,
        60,
        90,
        60,
        3,
        10,
        LocalTime.of(23, 30),
        LocalTime.of(7, 0),
        1,
        minReasonLength,
        true,
        true,
        "{{user}}",
        true,
        true,
        true);
  }
}
