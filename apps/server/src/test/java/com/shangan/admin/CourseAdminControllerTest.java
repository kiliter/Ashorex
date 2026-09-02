package com.shangan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.shangan.catalog.application.CourseBatchService;
import com.shangan.catalog.application.CourseBatchWriter;
import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.domain.Course;
import com.shangan.media.emby.EmbyDtos;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证课程后台联想、批量添加、归档确认和恢复的 HTTP 边界。 */
class CourseAdminControllerTest {

  private final StubCourses courses = new StubCourses();
  private final StubBatches batches = new StubBatches();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CourseAdminController(courses, batches, null, null))
            .build();
  }

  @Test
  void searchesSafeEmbySourcesAsJson() throws Exception {
    batches.searchResults =
        List.of(new EmbyDtos.MediaSource("series-1", "判断推理", "Series", "", "library-1"));

    mockMvc
        .perform(get("/admin/emby/sources").param("query", "判断"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value("series-1"))
        .andExpect(jsonPath("$[0].name").value("判断推理"))
        .andExpect(jsonPath("$[0].itemType").value("Series"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Path"))));
    assertThat(batches.lastQuery).isEqualTo("判断");
  }

  @Test
  void batchAddReturnsPerSourceResultOnTheCoursePage() throws Exception {
    var item =
        new CourseBatchService.BatchItem(
            "series-1",
            "判断推理",
            "course-1",
            CourseBatchWriter.Action.CREATED,
            CourseBatchService.SyncStatus.SUCCESS,
            "课程已创建并同步");
    batches.batchResult = new CourseBatchService.BatchResult(List.of(item));

    mockMvc
        .perform(post("/admin/courses/batch").param("sourceIds", "series-1"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/courses"))
        .andExpect(model().attributeExists("batchResult"));
    assertThat(batches.lastSourceIds).containsExactly("series-1");
  }

  @Test
  void confirmsArchiveThenArchivesAndRestoresByPost() throws Exception {
    Course course = new Course("course-1", "行测", "", "source-1", true, 0, null, null);
    courses.course = course;
    courses.lessonCount = 12;

    mockMvc
        .perform(get("/admin/courses/course-1/archive"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/course-archive"))
        .andExpect(model().attribute("course", course))
        .andExpect(model().attribute("lessonCount", 12));

    mockMvc
        .perform(post("/admin/courses/course-1/archive"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/courses?archived=1"));
    mockMvc
        .perform(post("/admin/courses/course-1/restore"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/courses/archived?restored=1"));

    assertThat(courses.archivedCourseId).isEqualTo("course-1");
    assertThat(courses.restoredCourseId).isEqualTo("course-1");
  }

  /** 控制器测试专用 Stub，只覆盖当前路由会调用的课程服务方法。 */
  private static final class StubCourses extends CourseSyncService {
    private Course course;
    private int lessonCount;
    private String archivedCourseId;
    private String restoredCourseId;

    private StubCourses() {
      super(null, null, null, null, null, null);
    }

    @Override
    public List<Course> listAdminCourses() {
      return List.of();
    }

    @Override
    public Course getAdminCourse(String courseId) {
      return course;
    }

    @Override
    public int countAdminLessons(String courseId) {
      return lessonCount;
    }

    @Override
    public void archiveCourse(String courseId) {
      archivedCourseId = courseId;
    }

    @Override
    public void restoreCourse(String courseId) {
      restoredCourseId = courseId;
    }
  }

  /** 控制器测试专用 Stub，记录查询和批量提交参数。 */
  private static final class StubBatches extends CourseBatchService {
    private List<EmbyDtos.MediaSource> searchResults = List.of();
    private CourseBatchService.BatchResult batchResult =
        new CourseBatchService.BatchResult(List.of());
    private String lastQuery;
    private List<String> lastSourceIds;

    private StubBatches() {
      super(null, null, null);
    }

    @Override
    public List<EmbyDtos.MediaSource> searchSources(String query) {
      lastQuery = query;
      return searchResults;
    }

    @Override
    public CourseBatchService.BatchResult addAndSynchronize(List<String> sourceIds) {
      lastSourceIds = List.copyOf(sourceIds);
      return batchResult;
    }
  }
}
