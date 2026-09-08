package com.shangan.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
 * 课程详情与 Emby 来源联想接口的 Controller 切片测试。
 *
 * <p>覆盖后台两条行为约束：课程不存在返回 404 与稳定错误码而不是 500；课时明细与来源联想的响应 都不得包含 Emby 原始物理路径。
 *
 * <p>不启动数据库与 Flyway，全部依赖以 Mockito 打桩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("课程与 Emby 同步后台接口")
class CatalogAdminControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-07T16:00:00Z");

  @Mock private CourseRepository courses;
  @Mock private CourseSyncService sync;
  @Mock private EmbyGateway emby;
  @Mock private RuntimeIntegrationSettingsService settings;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CatalogAdminController(
                    courses, sync, emby, settings, Clock.fixed(NOW, ZoneOffset.UTC)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("课程详情返回课时列表与只读元数据，且不含 Emby 物理路径")
  void courseDetailReturnsResources() throws Exception {
    when(courses.findById("c-1")).thenReturn(Optional.of(course()));
    when(courses.genresOf("c-1")).thenReturn(List.of("法律"));
    when(courses.tagsOf("c-1")).thenReturn(List.of("重点"));
    when(courses.peopleOf("c-1")).thenReturn(List.of());
    when(courses.findResourcesByCourse("c-1"))
        .thenReturn(
            List.of(
                resource("r-1", "第 01 讲 总论", 1, 1_800_000L, true),
                resource("r-2", "第 02 讲 分论", 2, 2_400_000L, false)));

    String body =
        mockMvc
            .perform(get("/admin/api/courses/detail").param("courseId", "c-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("c-1"))
            .andExpect(jsonPath("$.title").value("行政法专题"))
            .andExpect(jsonPath("$.resources.length()").value(2))
            .andExpect(jsonPath("$.resources[0].title").value("第 01 讲 总论"))
            .andExpect(jsonPath("$.resources[0].durationMs").value(1800000))
            .andExpect(jsonPath("$.resources[1].available").value(false))
            .andExpect(jsonPath("$.genres[0]").value("法律"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 课时明细绝不下发 externalRef 之外的来源细节，尤其不含 Emby 原始路径与指纹。
    Assertions.assertThat(body)
        .doesNotContain(".mp4")
        .doesNotContain("sourceFingerprint")
        .doesNotContain("externalRef");
  }

  @Test
  @DisplayName("课程不存在返回 404 与 COURSE_NOT_FOUND，且不回显传入的 ID")
  void courseDetailMissingReturns404() throws Exception {
    when(courses.findById("../../etc/passwd")).thenReturn(Optional.empty());

    String body =
        mockMvc
            .perform(get("/admin/api/courses/detail").param("courseId", "../../etc/passwd"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("COURSE_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain("etc/passwd").doesNotContain("at com.shangan");
  }

  @Test
  @DisplayName("来源联想把关键字透传给 Emby 网关，只回可选来源标识与名称")
  void searchSourcesForwardsQuery() throws Exception {
    when(emby.searchSources("行政"))
        .thenReturn(
            List.of(
                new EmbyDtos.MediaSource("emby-1", "行政法专题", "Series", null, "lib-1"),
                new EmbyDtos.MediaSource("emby-2", "行政诉讼法", "Folder", null, "lib-1")));

    String body =
        mockMvc
            .perform(get("/admin/api/emby-sync/search-sources").param("query", "行政"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("emby-1"))
            .andExpect(jsonPath("$[0].name").value("行政法专题"))
            .andExpect(jsonPath("$[1].itemType").value("Folder"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain("/media/").doesNotContain(".mp4");
  }

  private Course course() {
    return new Course(
        "c-1",
        "emby",
        "emby-1",
        "行政法专题",
        "概述",
        2026,
        1,
        CatalogStatus.ACTIVE,
        false,
        NOW,
        null,
        null);
  }

  private LearningResource resource(
      String id, String title, int sortIndex, long durationMs, boolean available) {
    return new LearningResource(
        id,
        "c-1",
        ResourceType.VIDEO,
        title,
        sortIndex,
        durationMs,
        null,
        "emby-" + id,
        "fp-" + id,
        available,
        CatalogStatus.ACTIVE,
        null);
  }
}
