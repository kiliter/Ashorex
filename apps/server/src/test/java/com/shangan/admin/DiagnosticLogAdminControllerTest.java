package com.shangan.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.diagnostics.application.DiagnosticLogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 后台只读列表与正文，响应不含磁盘绝对路径。 */
@ExtendWith(MockitoExtension.class)
class DiagnosticLogAdminControllerTest {

  @Mock private DiagnosticLogService logs;

  @TempDir Path temp;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DiagnosticLogAdminController(logs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("列表返回用户名与版本，不含存储路径")
  void 列表不含存储路径() throws Exception {
    when(logs.listForAdmin())
        .thenReturn(
            List.of(
                new DiagnosticLogService.AdminRow(
                    "log-1",
                    "user-1",
                    "zhangsan",
                    12,
                    "2.6.0",
                    "ios",
                    Instant.parse("2026-09-10T08:00:00Z"))));

    mockMvc
        .perform(get("/admin/api/diagnostic-logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].username").value("zhangsan"))
        .andExpect(jsonPath("$[0].storagePath").doesNotExist());
  }

  @Test
  @DisplayName("正文以纯文本返回且不包含绝对路径字段")
  void 正文为纯文本() throws Exception {
    Path file = temp.resolve("sample.log");
    Files.writeString(file, "INFO [player] ended");
    when(logs.locate("log-1")).thenReturn(file);

    mockMvc
        .perform(get("/admin/api/diagnostic-logs/log-1/content"))
        .andExpect(status().isOk())
        .andExpect(content().string("INFO [player] ended"));
  }
}
