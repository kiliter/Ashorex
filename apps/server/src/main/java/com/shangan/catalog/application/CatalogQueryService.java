package com.shangan.catalog.application;

import com.shangan.catalog.application.ResourceProgressPort.ResourceProgress;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.catalog.infrastructure.CatalogFacetRepository;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学习端课程库查询：筛选维度全部来自 Emby 元数据投影，进度来自跨 Todo 累计状态。 */
@Service
public class CatalogQueryService {

  private final CourseRepository courses;
  private final CatalogFacetRepository facets;
  private final ResourceProgressPort progress;
  private final IntegrationSettingsProvider settings;

  public CatalogQueryService(
      CourseRepository courses,
      CatalogFacetRepository facets,
      ResourceProgressPort progress,
      IntegrationSettingsProvider settings) {
    this.courses = courses;
    this.facets = facets;
    this.progress = progress;
    this.settings = settings;
  }

  /** 筛选维度与计数，用于学习 Tab 顶部 chips。 */
  @Transactional(readOnly = true)
  public CatalogFacets facets() {
    return new CatalogFacets(facets.genres(), facets.tags(), facets.people(), facets.years());
  }

  /** 课程列表；支持流派、标签、人物、年份与关键字叠加筛选。 */
  @Transactional(readOnly = true)
  public List<CourseSummary> courses(String userId, CourseFilter filter) {
    List<String> matchedIds =
        facets.courseIdsMatching(
            filter.genre(), filter.tag(), filter.person(), filter.year(), filter.query());
    if (matchedIds.isEmpty()) {
      return List.of();
    }
    Set<String> allowed = new LinkedHashSet<>(matchedIds);
    List<CourseSummary> result = new ArrayList<>();
    for (Course course : courses.findVisible()) {
      if (!allowed.contains(course.id())) {
        continue;
      }
      result.add(summarize(userId, course));
    }
    return List.copyOf(result);
  }

  /** 课程详情：资源列表 + 本人在每个资源上的累计进度。 */
  @Transactional(readOnly = true)
  public CourseDetail course(String userId, String courseId) {
    Course course =
        courses
            .findById(courseId)
            .filter(Course::visibleToLearners)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    List<LearningResource> resources = visibleResources(courseId);
    Map<String, ResourceProgress> states = progress.progressOfCourse(userId, courseId);
    List<ResourceView> views = new ArrayList<>();
    for (LearningResource resource : resources) {
      views.add(
          ResourceView.of(resource, states.getOrDefault(resource.id(), ResourceProgress.empty())));
    }
    CourseSummary summary = summarize(userId, course);
    return new CourseDetail(
        summary,
        courses.genresOf(courseId),
        courses.tagsOf(courseId),
        courses.peopleOf(courseId),
        List.copyOf(views));
  }

  /** 单个资源的读取，供 Todo 创建与播放代理校验归属与可用性。 */
  @Transactional(readOnly = true)
  public LearningResource requireVisibleResource(String resourceId) {
    LearningResource resource =
        courses
            .findResourceById(resourceId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "学习资源不存在"));
    if (!resource.visibleToLearners()) {
      throw new BusinessException(HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "该学习资源已下架或已归档");
    }
    if (resource.resourceType() == ResourceType.DOCUMENT && !documentEnabled()) {
      throw new BusinessException(HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "材料资源当前未启用");
    }
    Course course =
        courses
            .findById(resource.courseId())
            .filter(Course::visibleToLearners)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "所属课程已归档或失联"));
    if (course.id() == null) {
      throw new BusinessException(HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE", "所属课程不可用");
    }
    return resource;
  }

  private List<LearningResource> visibleResources(String courseId) {
    boolean documentEnabled = documentEnabled();
    return courses.findVisibleResourcesByCourse(courseId).stream()
        .filter(resource -> documentEnabled || resource.resourceType() == ResourceType.VIDEO)
        .toList();
  }

  private boolean documentEnabled() {
    return settings.current().features().documentResources();
  }

  private CourseSummary summarize(String userId, Course course) {
    List<LearningResource> resources = visibleResources(course.id());
    Map<String, ResourceProgress> states = progress.progressOfCourse(userId, course.id());
    long totalDurationMs = 0;
    long watchedMs = 0;
    int completed = 0;
    for (LearningResource resource : resources) {
      totalDurationMs += resource.durationMs() == null ? 0 : resource.durationMs();
      ResourceProgress state = states.getOrDefault(resource.id(), ResourceProgress.empty());
      watchedMs += state.totalWatchedMs();
      if (state.completedAtLeastOnce()) {
        completed++;
      }
    }
    int percent = resources.isEmpty() ? 0 : completed * 100 / resources.size();
    return new CourseSummary(
        course.id(),
        course.title(),
        course.overview(),
        course.productionYear(),
        courses.genresOf(course.id()),
        courses.tagsOf(course.id()),
        courses.peopleOf(course.id()),
        resources.size(),
        totalDurationMs,
        completed,
        watchedMs,
        percent);
  }

  /** 筛选条件；空值表示不限制。 */
  public record CourseFilter(String genre, String tag, String person, Integer year, String query) {

    public static CourseFilter empty() {
      return new CourseFilter(null, null, null, null, null);
    }
  }

  /** 学习 Tab 顶部筛选维度。 */
  public record CatalogFacets(
      List<CatalogFacetRepository.FacetValue> genres,
      List<CatalogFacetRepository.FacetValue> tags,
      List<CatalogFacetRepository.FacetValue> people,
      List<CatalogFacetRepository.FacetValue> years) {}

  /** 课程卡片视图。 */
  public record CourseSummary(
      String id,
      String title,
      String overview,
      Integer productionYear,
      List<String> genres,
      List<String> tags,
      List<ResourceMetadata.Person> people,
      int resourceCount,
      long totalDurationMs,
      int completedCount,
      long watchedMs,
      int completedPercent) {}

  /** 课程详情视图。 */
  public record CourseDetail(
      CourseSummary summary,
      List<String> genres,
      List<String> tags,
      List<ResourceMetadata.Person> people,
      List<ResourceView> resources) {}

  /** 资源行视图，含本人累计进度与是否已完成过。 */
  public record ResourceView(
      String id,
      ResourceType resourceType,
      String title,
      int sortIndex,
      Long durationMs,
      Integer pageCount,
      long maxPositionMs,
      int maxPositionPage,
      long watchedMs,
      int progressPermille,
      boolean completedBefore,
      boolean measurable) {

    static ResourceView of(LearningResource resource, ResourceProgress state) {
      return new ResourceView(
          resource.id(),
          resource.resourceType(),
          resource.title(),
          resource.sortIndex(),
          resource.durationMs(),
          resource.pageCount(),
          state.maxPositionMs(),
          state.maxPositionPage(),
          state.totalWatchedMs(),
          resource.progressPermille(state.maxPositionMs(), state.maxPositionPage()),
          state.completedAtLeastOnce(),
          resource.measurable());
    }
  }
}
