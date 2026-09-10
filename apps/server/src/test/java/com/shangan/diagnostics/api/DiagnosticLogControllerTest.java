package com.shangan.diagnostics.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.auth.CurrentUser;
import com.shangan.diagnostics.application.DiagnosticLogService;
import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** App 上报端点：成功响应不含存储路径。 */
@ExtendWith(MockitoExtension.class)
class DiagnosticLogControllerTest {

  @Mock private DiagnosticLogService logs;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DiagnosticLogController(logs))
            .setCustomArgumentResolvers(new FixedCurrentUserResolver())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("上传成功返回 id 与大小，不泄露存储路径")
  void 上传成功不泄露路径() throws Exception {
    when(logs.upload(eq("user-1"), eq("2.6.0"), eq("ios"), anyLong(), any()))
        .thenReturn(
            new DiagnosticLogUpload(
                "log-1",
                "user-1",
                "user-1/log-1.log",
                12,
                "2.6.0",
                "ios",
                Instant.parse("2026-09-10T08:00:00Z")));

    mockMvc
        .perform(
            multipart("/api/v1/diagnostics/logs")
                .file(
                    new MockMultipartFile(
                        "file",
                        "diagnostic.log",
                        MediaType.TEXT_PLAIN_VALUE,
                        "hello log".getBytes()))
                .param("appVersion", "2.6.0")
                .param("platform", "ios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("log-1"))
        .andExpect(jsonPath("$.sizeBytes").value(12))
        .andExpect(jsonPath("$.storagePath").doesNotExist());
  }

  private static final class FixedCurrentUserResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory binderFactory) {
      return new CurrentUser("user-1", "zhangsan", "LEARNER", "Asia/Shanghai");
    }
  }
}
