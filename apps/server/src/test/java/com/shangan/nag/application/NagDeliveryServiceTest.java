package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.IdGenerator;
import com.shangan.nag.application.channel.NagChannel;
import com.shangan.nag.domain.EffectiveNagPolicy;
import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import com.shangan.nag.domain.NagTrigger;
import com.shangan.nag.infrastructure.NagRepository;
import com.shangan.presence.domain.PresenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 催办渠道选择与降级：在线走全屏、离线走 Server 酱、全屏超时追加投递。 */
@ExtendWith(MockitoExtension.class)
class NagDeliveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T14:00:00Z");
  private static final String USER_ID = "user-1";

  @Mock private NagRepository nags;
  @Mock private com.shangan.identity.application.UserTimeService userTime;

  @Test
  void 个人Bark启用替代Server酱且失败不双发() {
    RecordingChannel bark = new RecordingChannel(NagChannelType.BARK, true, false);
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    service(List.of(bark, serverchan)).deliver(nag(), "小明", null, policy(false, true), null);
    verify(nags)
        .insertDelivery(
            anyString(),
            org.mockito.ArgumentMatchers.eq(nag().id()),
            org.mockito.ArgumentMatchers.eq(NagChannelType.BARK),
            org.mockito.ArgumentMatchers.eq("FAILED"),
            org.mockito.ArgumentMatchers.eq("推送失败"),
            org.mockito.ArgumentMatchers.eq(NOW));
    assertThat(serverchan.deliveries).isEmpty();
    verify(nags, never()).markDelivered(anyString(), any());
  }

  @Test
  @DisplayName("全屏等待期间跨入用户免打扰时段不追加推送")
  void 免打扰期间不降级() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(serverchan));
    when(userTime.localTimeNow(any())).thenReturn(LocalTime.MIDNIGHT);
    when(nags.findFullscreenAwaitingBefore(NOW)).thenReturn(List.of(nag()));
    assertThat(service.escalateTimedOut(id -> policy(true, true), id -> "小明")).isZero();
    assertThat(serverchan.deliveries).isEmpty();
  }

  @Test
  @DisplayName("两个渠道均关闭时不得悄悄投递全屏")
  void 全部关闭不投递() {
    RecordingChannel fullscreen = new RecordingChannel(NagChannelType.FULLSCREEN, true, true);
    service(List.of(fullscreen)).deliver(nag(), "小明", online(), policy(false, false), null);
    assertThat(fullscreen.deliveries).isEmpty();
    verify(nags, never()).markDelivered(anyString(), any());
  }

  @Test
  @DisplayName("用户覆盖的超时时间未到时不得按全局超时提前降级")
  void 用户覆盖超时生效() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    when(nags.findFullscreenAwaitingBefore(NOW)).thenReturn(List.of(nag()));
    EffectiveNagPolicy custom =
        new EffectiveNagPolicy(
            5,
            150,
            60,
            90,
            60,
            3,
            30,
            LocalTime.of(23, 30),
            LocalTime.of(7, 0),
            1,
            5,
            true,
            true,
            "{{user}}",
            true,
            true,
            true);
    assertThat(service(List.of(serverchan)).escalateTimedOut(id -> custom, id -> "小明")).isZero();
    assertThat(serverchan.deliveries).isEmpty();
  }

  @Test
  @DisplayName("在线且全屏渠道开启时选择全屏，并在成功后标记已投递")
  void 在线选择全屏() {
    RecordingChannel fullscreen = new RecordingChannel(NagChannelType.FULLSCREEN, true, true);
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(fullscreen, serverchan));

    service.deliver(nag(), "小明", online(), policy(true, true), null);

    assertThat(fullscreen.deliveries).containsExactly("小明");
    assertThat(serverchan.deliveries).isEmpty();
    verify(nags)
        .insertDelivery("delivery-1", "nag-1", NagChannelType.FULLSCREEN, "SENT", "已投递", NOW);
    verify(nags).markDelivered("nag-1", NOW);
  }

  @Test
  @DisplayName("离线时降级到 Server 酱")
  void 离线降级到推送() {
    RecordingChannel fullscreen = new RecordingChannel(NagChannelType.FULLSCREEN, true, true);
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(fullscreen, serverchan));

    service.deliver(nag(), "小明", offline(), policy(true, true), null);

    assertThat(serverchan.deliveries).containsExactly("小明");
    assertThat(fullscreen.deliveries).isEmpty();
    verify(nags)
        .insertDelivery("delivery-1", "nag-1", NagChannelType.SERVERCHAN, "SENT", "已投递", NOW);
  }

  @Test
  @DisplayName("在线但全屏渠道被关闭时同样走 Server 酱")
  void 全屏关闭时走推送() {
    RecordingChannel fullscreen = new RecordingChannel(NagChannelType.FULLSCREEN, true, true);
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(fullscreen, serverchan));

    service.deliver(nag(), "小明", online(), policy(false, true), null);

    assertThat(serverchan.deliveries).containsExactly("小明");
  }

  @Test
  @DisplayName("显式指定渠道时不做在线判定")
  void 指定渠道优先() {
    RecordingChannel fullscreen = new RecordingChannel(NagChannelType.FULLSCREEN, true, true);
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(fullscreen, serverchan));

    service.deliver(nag(), "小明", offline(), policy(true, true), NagChannelType.FULLSCREEN);

    assertThat(fullscreen.deliveries).containsExactly("小明");
    assertThat(serverchan.deliveries).isEmpty();
  }

  @Test
  @DisplayName("渠道不可用时写入 FAILED 流水且不标记已投递")
  void 渠道不可用记录失败() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, false, true);
    NagDeliveryService service = service(List.of(serverchan));

    service.deliver(nag(), "小明", offline(), policy(true, true), null);

    assertThat(serverchan.deliveries).isEmpty();
    verify(nags)
        .insertDelivery("delivery-1", "nag-1", NagChannelType.SERVERCHAN, "FAILED", "渠道不可用", NOW);
    verify(nags, never()).markDelivered(anyString(), any());
  }

  @Test
  @DisplayName("外部渠道调用失败时记录 FAILED，不抛异常也不标记已投递")
  void 外部失败记录失败流水() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, false);
    NagDeliveryService service = service(List.of(serverchan));

    service.deliver(nag(), "小明", offline(), policy(true, true), null);

    verify(nags)
        .insertDelivery("delivery-1", "nag-1", NagChannelType.SERVERCHAN, "FAILED", "推送失败", NOW);
    verify(nags, never()).markDelivered(anyString(), any());
  }

  @Test
  @DisplayName("请求的渠道没有对应实现时记录 FAILED，原因区别于「渠道不可用」")
  void 缺少渠道实现记录失败() {
    NagDeliveryService service = service(List.of());

    service.deliver(nag(), "小明", online(), policy(true, true), null);

    verify(nags)
        .insertDelivery("delivery-1", "nag-1", NagChannelType.FULLSCREEN, "FAILED", "渠道未注册", NOW);
  }

  @Test
  @DisplayName("渠道自述的不可用原因原样写入投递流水，供后台区分处置动作")
  void 渠道自述原因写入流水() {
    RecordingChannel serverchan =
        new RecordingChannel(NagChannelType.SERVERCHAN, false, true, "Server 酱未配置 SendKey");
    NagDeliveryService service = service(List.of(serverchan));

    service.deliver(nag(), "小明", offline(), policy(true, true), null);

    verify(nags)
        .insertDelivery(
            "delivery-1", "nag-1", NagChannelType.SERVERCHAN, "FAILED", "Server 酱未配置 SendKey", NOW);
  }

  @Test
  @DisplayName("全屏超时未回应时在同一条催办上追加一次 Server 酱投递")
  void 全屏超时追加推送() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(serverchan));
    when(nags.findFullscreenAwaitingBefore(NOW)).thenReturn(List.of(nag()));
    when(nags.deliveredVia("nag-1", NagChannelType.SERVERCHAN)).thenReturn(false);

    int escalated = service.escalateTimedOut(userId -> policy(true, true), userId -> "小明");

    assertThat(escalated).isEqualTo(1);
    assertThat(serverchan.deliveries).containsExactly("小明");
  }

  @Test
  @DisplayName("已经通过 Server 酱投递过的催办不再重复降级")
  void 已推送过不重复降级() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(serverchan));
    when(nags.findFullscreenAwaitingBefore(NOW)).thenReturn(List.of(nag()));
    when(nags.deliveredVia("nag-1", NagChannelType.SERVERCHAN)).thenReturn(true);

    assertThat(service.escalateTimedOut(userId -> policy(true, true), userId -> "小明")).isZero();
    assertThat(serverchan.deliveries).isEmpty();
  }

  @Test
  @DisplayName("Server 酱渠道被关闭时不做降级")
  void 推送关闭时不降级() {
    RecordingChannel serverchan = new RecordingChannel(NagChannelType.SERVERCHAN, true, true);
    NagDeliveryService service = service(List.of(serverchan));
    when(nags.findFullscreenAwaitingBefore(NOW)).thenReturn(List.of(nag()));
    when(nags.deliveredVia("nag-1", NagChannelType.SERVERCHAN)).thenReturn(false);

    assertThat(service.escalateTimedOut(userId -> policy(true, false), userId -> "小明")).isZero();
    assertThat(serverchan.deliveries).isEmpty();
  }

  private NagDeliveryService service(List<NagChannel> channels) {
    org.mockito.Mockito.lenient().when(userTime.localTimeNow(any())).thenReturn(LocalTime.NOON);
    return new NagDeliveryService(
        nags, channels, sequentialIds(), Clock.fixed(NOW, ZoneOffset.UTC), userTime);
  }

  private static PresenceSnapshot online() {
    return new PresenceSnapshot(
        USER_ID, NOW.minusSeconds(30), NOW.minusSeconds(6_000), "FOREGROUND", "2.0.0");
  }

  private static PresenceSnapshot offline() {
    return new PresenceSnapshot(
        USER_ID, NOW.minusSeconds(3_600), NOW.minusSeconds(6_000), "BACKGROUND", "2.0.0");
  }

  private static Nag nag() {
    return new Nag(
        "nag-1",
        USER_ID,
        LocalDate.of(2026, 9, 7),
        1,
        NagTrigger.AUTO,
        null,
        100,
        3,
        "还有 3 项没做",
        true,
        NagStatus.DELIVERED,
        NOW.minusSeconds(1_200),
        null,
        null,
        null,
        null,
        NOW.minusSeconds(1_200));
  }

  private static EffectiveNagPolicy policy(boolean fullscreen, boolean serverchan) {
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
        5,
        fullscreen,
        serverchan,
        "{{user}}",
        true,
        true,
        true);
  }

  private static IdGenerator sequentialIds() {
    return new IdGenerator() {
      private int counter;

      @Override
      public String nextId() {
        return "delivery-" + (++counter);
      }
    };
  }

  /** 记录被调用情况的测试渠道；不是被测对象，因此用真实实现而非 mock。 */
  private static final class RecordingChannel implements NagChannel {

    private final NagChannelType type;
    private final boolean available;
    private final boolean succeeds;
    private final String unavailableReason;
    private final List<String> deliveries = new ArrayList<>();

    private RecordingChannel(NagChannelType type, boolean available, boolean succeeds) {
      this(type, available, succeeds, null);
    }

    private RecordingChannel(
        NagChannelType type, boolean available, boolean succeeds, String unavailableReason) {
      this.type = type;
      this.available = available;
      this.succeeds = succeeds;
      this.unavailableReason = unavailableReason;
    }

    @Override
    public NagChannelType type() {
      return type;
    }

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return unavailableReason == null ? NagChannel.super.unavailableReason() : unavailableReason;
    }

    @Override
    public DeliveryOutcome deliver(Nag nag, String recipientDisplayName) {
      if (!succeeds) {
        return DeliveryOutcome.failed("推送失败");
      }
      deliveries.add(recipientDisplayName);
      return DeliveryOutcome.sent("已投递");
    }
  }
}
