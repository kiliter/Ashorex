package com.shangan.learning.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.api.BusinessException;
import com.shangan.common.auth.CurrentUserArgumentResolver;
import com.shangan.learning.application.PlaybackSessionService;
import com.shangan.learning.application.WatchSessionService;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 独立 MVC 切片只检查快进协议、参数和稳定错误，不连接业务数据库。 */
class WatchFastForwardControllerTest {
  private final WatchSessionService service = mock(WatchSessionService.class);
  private MockMvc mvc;

  @BeforeEach
  void prepare() {
    var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("user").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WatchSessionController(mock(PlaybackSessionService.class), service))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void forwardsAuthenticatedFactsAndReturnsAuthorizedPosition() throws Exception {
    when(service.fastForward(eq("user"), eq("session"), any()))
        .thenReturn(
            new WatchSeekResponse(
                20_000, new WatchHeartbeatResponse(20_000, 10_000, true, false, false, "ACTIVE")));
    mvc.perform(
            post("/api/v1/watch-sessions/session/fast-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
          {"sequence":1,"positionMs":10000,"playing":true,"foreground":true,"playbackSpeed":1.0}
          """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positionMs").value(20_000))
        .andExpect(jsonPath("$.progress.verifiedWatchMs").value(10_000));
    verify(service)
        .fastForward("user", "session", new WatchHeartbeatRequest(1, 10_000, true, true, 1));
  }

  @Test
  void rejectsInvalidSequenceBeforeCallingService() throws Exception {
    mvc.perform(
            post("/api/v1/watch-sessions/session/fast-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
          {"sequence":0,"positionMs":0,"playing":true,"foreground":true,"playbackSpeed":1.0}
          """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    verifyNoInteractions(service);
  }

  @Test
  void pendingAliveCheckReturnsStableProblemDetails() throws Exception {
    when(service.fastForward(anyString(), anyString(), any()))
        .thenThrow(new BusinessException(HttpStatus.CONFLICT, "ALIVE_CHECK_REQUIRED", "请先完成验活"));
    mvc.perform(
            post("/api/v1/watch-sessions/session/fast-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
          {"sequence":1,"positionMs":0,"playing":true,"foreground":true,"playbackSpeed":1.0}
          """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("ALIVE_CHECK_REQUIRED"));
  }
}
