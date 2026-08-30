package com.shangan.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.learning.application.PlaybackTicketService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证短期 HMAC 播放票据的签名、过期和用户绑定。 */
class PlaybackTicketServiceTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
  private final PlaybackTicketService tickets =
      new PlaybackTicketService(
          clock, new ObjectMapper(), "playback-test-secret-with-at-least-32-bytes");

  @Test
  void acceptsValidTicketWithoutLeakingMediaCredential() {
    String ticket = tickets.issue("user-1", "media-1", "session-1", "device-1");
    var claims = tickets.verify(ticket, "user-1");

    assertThat(claims.mediaItemId()).isEqualTo("media-1");
    assertThat(ticket).doesNotContain("emby-key");
  }

  @Test
  void rejectsAnotherUserTamperingAndExpiry() {
    String ticket = tickets.issue("user-1", "media-1", "session-1", "device-1");
    assertThatThrownBy(() -> tickets.verify(ticket, "user-2"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> tickets.verify(ticket + "x", "user-1"))
        .isInstanceOf(BusinessException.class);

    PlaybackTicketService expired =
        new PlaybackTicketService(
            Clock.offset(clock, java.time.Duration.ofHours(3)),
            new ObjectMapper(),
            "playback-test-secret-with-at-least-32-bytes");
    assertThatThrownBy(() -> expired.verify(ticket, "user-1"))
        .isInstanceOf(BusinessException.class);
  }
}
