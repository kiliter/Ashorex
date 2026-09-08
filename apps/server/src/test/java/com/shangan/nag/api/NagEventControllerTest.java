package com.shangan.nag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shangan.common.auth.CurrentUserArgumentResolver;
import com.shangan.nag.application.*;
import com.shangan.nag.domain.NagTransportMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 无数据库的流协议测试：真实 emitter、用户隔离、保活和回退模式。 */
class NagEventControllerTest {
  @Test
  void 只向当前用户发送并允许切回轮询() throws Exception {
    NagTransportService transport = mock(NagTransportService.class);
    when(transport.current()).thenReturn(NagTransportMode.SSE);
    NagEventService events = new NagEventService(transport);
    var mvc =
        MockMvcBuilders.standaloneSetup(new NagEventController(events))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();
    try {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(
                  Jwt.withTokenValue("test-only")
                      .header("alg", "HS256")
                      .subject("learner-a")
                      .build()));
      var response =
          mvc.perform(get("/api/v1/nags/events"))
              .andExpect(status().isOk())
              .andExpect(request().asyncStarted())
              .andExpect(header().string("X-Accel-Buffering", "no"))
              .andReturn()
              .getResponse();
      assertThat(response.getContentAsString()).contains("event:ready", "data:ready");
      events.onAvailable(new NagAvailable("learner-b", "other-private-nag"));
      events.onAvailable(new NagAvailable("learner-a", "own-nag"));
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(response.getContentAsString()).contains("data:own-nag"));
      assertThat(response.getContentAsString()).doesNotContain("other-private-nag");
      when(transport.current()).thenReturn(NagTransportMode.HEARTBEAT);
      events.onAvailable(new NagAvailable("learner-a", "poll-only"));
      events.close();
      assertThat(response.getContentAsString()).doesNotContain("poll-only");
    } finally {
      events.close();
      SecurityContextHolder.clearContext();
    }
  }
}
