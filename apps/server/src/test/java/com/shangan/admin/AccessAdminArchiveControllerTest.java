package com.shangan.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.archive.application.ArchiveService;
import com.shangan.archive.domain.ArchivableEntityType;
import com.shangan.archive.domain.CascadePlan;
import com.shangan.archive.infrastructure.CascadeRepository;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.identity.application.AuthService;
import com.shangan.supervision.application.SupervisionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 归档区后台接口的 Controller 切片测试。
 *
 * <p>覆盖 ADR-0033 要求的三条后台安全约束：手动孤儿自检只读、彻底删除前必须能取到级联清单、 未知归档类型返回 400 而不是 500，且响应中不出现文件系统绝对路径。
 *
 * <p>不启动数据库与 Flyway，全部依赖以 Mockito 打桩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("归档区后台接口")
class AccessAdminArchiveControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-07T16:00:00Z");

  @Mock private AuthService users;
  @Mock private SupervisionService supervisions;
  @Mock private ArchiveService archive;
  @Mock private CourseRepository courses;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AccessAdminController(
                    users, supervisions, archive, courses, Clock.fixed(NOW, ZoneOffset.UTC)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("立即扫描返回五类残留计数、总数与服务端时间戳")
  void scanOrphansAggregatesCounts() throws Exception {
    when(archive.scanOrphans()).thenReturn(new CascadeRepository.OrphanReport(1, 2, 3, 4, 5));

    mockMvc
        .perform(post("/admin/api/archive/scan-orphans"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(15))
        .andExpect(jsonPath("$.clean").value(false))
        .andExpect(jsonPath("$.orphans.orphanTodos").value(1))
        .andExpect(jsonPath("$.orphans.orphanNags").value(5))
        .andExpect(jsonPath("$.scannedAt").value("2026-09-07T16:00:00Z"));
  }

  @Test
  @DisplayName("无残留时 clean=true 且 total=0")
  void scanOrphansReportsClean() throws Exception {
    when(archive.scanOrphans()).thenReturn(new CascadeRepository.OrphanReport(0, 0, 0, 0, 0));

    mockMvc
        .perform(post("/admin/api/archive/scan-orphans"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.clean").value(true));
  }

  @Test
  @DisplayName("手动扫描是只读动作，不触发彻底删除")
  void scanOrphansNeverPurges() throws Exception {
    when(archive.scanOrphans()).thenReturn(new CascadeRepository.OrphanReport(0, 0, 0, 0, 0));

    mockMvc.perform(post("/admin/api/archive/scan-orphans")).andExpect(status().isOk());

    verify(archive, never())
        .purge(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("预检返回级联清单与确认串所需的实体名称，且不含绝对路径")
  void preflightReturnsCascadePlan() throws Exception {
    when(archive.preflight(ArchivableEntityType.COURSE, "c-1"))
        .thenReturn(
            new CascadePlan(
                ArchivableEntityType.COURSE,
                "c-1",
                "行政法专题",
                List.of(
                    new CascadePlan.Step(1, "todo_attachments", 7, "该课程 Todo 的附件"),
                    new CascadePlan.Step(2, "todos", 3, "引用该课程的 Todo")),
                2048L,
                2,
                600000L));

    String body =
        mockMvc
            .perform(
                get("/admin/api/archive/preflight")
                    .param("type", "COURSE")
                    .param("entityId", "c-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entityLabel").value("行政法专题"))
            .andExpect(jsonPath("$.steps.length()").value(2))
            .andExpect(jsonPath("$.steps[0].table").value("todo_attachments"))
            .andExpect(jsonPath("$.steps[0].rowCount").value(7))
            .andExpect(jsonPath("$.affectedUserCount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain("/Users/").doesNotContain("/data/");
  }

  @Test
  @DisplayName("未知归档类型返回 400 与 ARCHIVE_TYPE_UNSUPPORTED，不落到 500")
  void unknownTypeReturnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/admin/api/archive/preflight").param("type", "PLANET").param("entityId", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ARCHIVE_TYPE_UNSUPPORTED"));
  }

  @Test
  @DisplayName("彻底删除把确认串原样交给 ArchiveService 校验，不在 Controller 兜底放行")
  void purgeForwardsConfirmLabel() throws Exception {
    mockMvc
        .perform(
            post("/admin/api/archive/purge")
                .contentType("application/json")
                .content("{\"type\":\"COURSE\",\"entityId\":\"c-1\",\"confirmLabel\":\"行政法专题\"}"))
        .andExpect(status().isNoContent());

    verify(archive).purge(eq(ArchivableEntityType.COURSE), eq("c-1"), eq("admin"), eq("行政法专题"));
  }
}
