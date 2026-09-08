package com.shangan.catalog.api;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.application.CatalogQueryService.CatalogFacets;
import com.shangan.catalog.application.CatalogQueryService.CourseDetail;
import com.shangan.catalog.application.CatalogQueryService.CourseFilter;
import com.shangan.catalog.application.CatalogQueryService.CourseSummary;
import com.shangan.common.auth.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学习端课程库只读 API；筛选维度来自 Emby 元数据。 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

  private final CatalogQueryService catalog;

  public CatalogController(CatalogQueryService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/facets")
  CatalogFacets facets() {
    return catalog.facets();
  }

  @GetMapping("/courses")
  List<CourseSummary> courses(
      CurrentUser currentUser,
      @RequestParam(required = false) String genre,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String person,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) String q) {
    return catalog.courses(currentUser.userId(), new CourseFilter(genre, tag, person, year, q));
  }

  @GetMapping("/courses/{courseId}")
  CourseDetail course(CurrentUser currentUser, @PathVariable String courseId) {
    return catalog.course(currentUser.userId(), courseId);
  }
}
