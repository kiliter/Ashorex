package com.shangan.ai.transcript;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** iOS 视频页读取转写就绪状态；未 READY 时仍可进入普通只读问答。 */
@RestController
@RequestMapping("/api/v1/lessons/{lessonId}/ai-status")
public class TranscriptionStatusController {
  private final CatalogQueryService catalog;
  private final TranscriptionJobService jobs;

  public TranscriptionStatusController(CatalogQueryService catalog, TranscriptionJobService jobs) {
    this.catalog = catalog;
    this.jobs = jobs;
  }

  @GetMapping
  StatusView status(@PathVariable String lessonId) {
    catalog
        .findLesson(lessonId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "MEDIA_ITEM_NOT_FOUND", "课时不存在"));
    return jobs.findByMediaItem(lessonId)
        .map(job -> new StatusView(job.status(), job.status().equals("READY")))
        .orElseGet(() -> new StatusView("NOT_REQUESTED", false));
  }

  public record StatusView(String status, boolean videoContextAvailable) {}
}
