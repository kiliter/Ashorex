package com.shangan.admin;

import com.shangan.catalog.application.CourseSyncService;
import com.shangan.catalog.application.ResourceMappingPlanner;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程与 Emby 同步的 JSON API。
 *
 * <p>课程分类、标签、人物全部来自 Emby 只读投影，后台不提供编辑入口。
 */
@RestController
@RequestMapping("/admin/api")
public class CatalogAdminController {

  private final CourseRepository courses;
  private final CourseSyncService sync;
  private final EmbyGateway emby;
  private final RuntimeIntegrationSettingsService settings;
  private final Clock clock;

  public CatalogAdminController(
      CourseRepository courses,
      CourseSyncService sync,
      EmbyGateway emby,
      RuntimeIntegrationSettingsService settings,
      Clock clock) {
    this.courses = courses;
    this.sync = sync;
    this.emby = emby;
    this.settings = settings;
    this.clock = clock;
  }

  @GetMapping("/courses")
  List<CourseRow> courses() {
    List<CourseRow> rows = new ArrayList<>();
    for (Course course : courses.findAll()) {
      List<LearningResource> resources = courses.findResourcesByCourse(course.id());
      long totalMs =
          resources.stream()
              .mapToLong(resource -> resource.durationMs() == null ? 0 : resource.durationMs())
              .sum();
      int unavailable = (int) resources.stream().filter(resource -> !resource.available()).count();
      rows.add(
          new CourseRow(
              course.id(),
              course.title(),
              course.status().name(),
              course.sourceMissing(),
              course.sortOrder(),
              courses.genresOf(course.id()),
              courses.tagsOf(course.id()),
              courses.peopleOf(course.id()).stream().map(person -> person.name()).toList(),
              resources.size(),
              totalMs,
              unavailable));
    }
    return rows;
  }

  /**
   * 课程详情：课时列表与只读元数据。
   *
   * <p>边界：课程不存在时返回 404 与稳定错误码 {@code COURSE_NOT_FOUND}，而不是 500；错误文案不回显调用方传入的 ID，避免把无意义的输入原样反射回页面。
   */
  @GetMapping("/courses/detail")
  CourseDetail courseDetail(@RequestParam String courseId) {
    Course course =
        courses
            .findById(courseId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在或已被彻底删除"));
    return new CourseDetail(
        course.id(),
        course.title(),
        course.status().name(),
        course.sourceMissing(),
        course.sortOrder(),
        courses.genresOf(course.id()),
        courses.tagsOf(course.id()),
        courses.peopleOf(course.id()),
        courses.findResourcesByCourse(course.id()).stream().map(ResourceRow::of).toList());
  }

  @PostMapping("/courses")
  CreateCourseResult create(@RequestBody CreateCourseRequest request) {
    Course created = sync.createCourse(request.externalRef().trim(), request.sortOrder());
    return new CreateCourseResult(created.id(), created.title());
  }

  @PostMapping("/courses/sync")
  SyncResultView sync(@RequestBody CourseIdRequest request) {
    CourseSyncService.SyncResult result = sync.sync(request.courseId());
    return SyncResultView.of(result);
  }

  @PostMapping("/courses/sort")
  ResponseEntity<Void> updateSort(@RequestBody SortRequest request) {
    courses.updateSortOrder(request.courseId(), request.sortOrder(), clock.instant());
    return ResponseEntity.noContent().build();
  }

  // ---------- Emby 同步 ----------

  @GetMapping("/emby-sync")
  EmbySyncResponse embySync() {
    RuntimeIntegrationSettings current = settings.current();
    return new EmbySyncResponse(
        courses.findAll().stream()
            .map(
                course ->
                    new CourseOption(
                        course.id(),
                        course.title(),
                        course.status().name(),
                        course.sourceMissing()))
            .toList(),
        current.embyLibraries(),
        current.emby().configured());
  }

  /** 拉取 Emby 侧可选媒体库，供绑定时选择，不落库。 */
  @GetMapping("/emby-sync/remote-libraries")
  List<EmbyDtos.MediaLibrary> remoteLibraries() {
    return emby.listMediaLibraries();
  }

  /** 按关键字联想 Emby 来源（媒体库 / Series / Folder），用于填写 externalRef。 */
  @GetMapping("/emby-sync/search-sources")
  List<EmbyDtos.MediaSource> searchSources(@RequestParam String query) {
    return emby.searchSources(query);
  }

  /** 预览重绑结果，不写库；歧义项必须人工确认。 */
  @PostMapping("/emby-sync/preview")
  MappingPlanView preview(@RequestBody RebindRequest request) {
    ResourceMappingPlanner.MappingPlan plan =
        sync.previewRebind(request.courseId(), request.newParentRef().trim());
    return new MappingPlanView(
        plan.inPlace().size(),
        plan.created().size(),
        plan.markedUnavailable().size(),
        plan.ambiguous().size());
  }

  @PostMapping("/emby-sync/rebind")
  SyncResultView rebind(@RequestBody RebindRequest request) {
    return SyncResultView.of(
        sync.rebind(request.courseId(), request.newParentRef().trim(), "admin"));
  }

  @PostMapping("/emby-sync/confirm-mapping")
  ResponseEntity<Void> confirmMapping(@RequestBody ConfirmMappingRequest request) {
    sync.confirmMapping(request.resourceId(), request.newExternalRef().trim(), "admin");
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/emby-sync/libraries")
  ResponseEntity<Void> saveLibraries(@RequestBody SaveLibrariesRequest request) {
    List<RuntimeIntegrationSettings.EmbyLibrary> libraries = new ArrayList<>();
    for (LibraryInput input : request.libraries()) {
      if (input.id() == null || input.id().isBlank()) {
        continue;
      }
      libraries.add(
          new RuntimeIntegrationSettings.EmbyLibrary(
              input.id().trim(),
              input.name() == null || input.name().isBlank() ? input.id() : input.name(),
              RuntimeIntegrationSettings.EmbyLibraryType.valueOf(
                  input.type() == null ? "MIXED" : input.type())));
    }
    settings.saveEmbyLibraries(libraries);
    return ResponseEntity.noContent().build();
  }

  // ---------- DTO ----------

  /** 课程列表行；元数据列全部只读。 */
  public record CourseRow(
      String id,
      String title,
      String status,
      boolean sourceMissing,
      int sortOrder,
      List<String> genres,
      List<String> tags,
      List<String> people,
      int resourceCount,
      long totalDurationMs,
      int unavailableCount) {}

  /** 课程详情。 */
  public record CourseDetail(
      String id,
      String title,
      String status,
      boolean sourceMissing,
      int sortOrder,
      List<String> genres,
      List<String> tags,
      List<?> people,
      List<ResourceRow> resources) {}

  /** 课时行。 */
  public record ResourceRow(
      String id,
      String title,
      String resourceType,
      int sortIndex,
      Long durationMs,
      Integer pageCount,
      boolean available) {

    static ResourceRow of(LearningResource resource) {
      return new ResourceRow(
          resource.id(),
          resource.title(),
          resource.resourceType().name(),
          resource.sortIndex(),
          resource.durationMs(),
          resource.pageCount(),
          resource.available());
    }
  }

  /** 下拉课程选项。 */
  public record CourseOption(String id, String title, String status, boolean sourceMissing) {}

  /** Emby 同步页响应。 */
  public record EmbySyncResponse(
      List<CourseOption> courses,
      List<RuntimeIntegrationSettings.EmbyLibrary> libraries,
      boolean embyConfigured) {}

  /** 同步结果视图。 */
  public record SyncResultView(
      boolean succeeded,
      int inPlaceCount,
      int createdCount,
      int unavailableCount,
      int ambiguousCount,
      String error) {

    static SyncResultView of(CourseSyncService.SyncResult result) {
      return new SyncResultView(
          result.succeeded(),
          result.inPlaceCount(),
          result.createdCount(),
          result.unavailableCount(),
          result.ambiguousCount(),
          result.error());
    }
  }

  /** 重绑预览结果。 */
  public record MappingPlanView(int inPlace, int created, int markedUnavailable, int ambiguous) {}

  /** 创建课程请求。 */
  public record CreateCourseRequest(String externalRef, int sortOrder) {}

  /** 创建课程结果。 */
  public record CreateCourseResult(String id, String title) {}

  /** 课程 ID 请求。 */
  public record CourseIdRequest(String courseId) {}

  /** 排序请求。 */
  public record SortRequest(String courseId, int sortOrder) {}

  /** 重绑请求。 */
  public record RebindRequest(String courseId, String newParentRef) {}

  /** 确认课时映射请求。 */
  public record ConfirmMappingRequest(String resourceId, String newExternalRef, String courseId) {}

  /** 保存媒体库绑定请求。 */
  public record SaveLibrariesRequest(List<LibraryInput> libraries) {}

  /** 单个媒体库输入。 */
  public record LibraryInput(String id, String name, String type) {}
}
