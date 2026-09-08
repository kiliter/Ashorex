package com.shangan.nag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shangan.nag.domain.NagTransportMode;
import com.shangan.nag.infrastructure.NagPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 通知模式默认值和持久化边界，不测试具体 SQL 或数据库。 */
class NagTransportServiceTest {
  @Test
  void 默认SSE并可保存心跳模式() {
    var repository = mock(NagPolicyRepository.class);
    var now = Instant.parse("2026-09-08T00:00:00Z");
    var service = new NagTransportService(repository, Clock.fixed(now, ZoneOffset.UTC));
    assertThat(service.current()).isEqualTo(NagTransportMode.SSE);
    service.save(NagTransportMode.HEARTBEAT);
    verify(repository).saveTransportMode(NagTransportMode.HEARTBEAT, now);
    when(repository.findTransportMode()).thenReturn(Optional.of(NagTransportMode.HEARTBEAT));
    assertThat(service.current()).isEqualTo(NagTransportMode.HEARTBEAT);
  }
}
