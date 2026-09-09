package com.shangan.catalog.api;

import com.shangan.catalog.application.CourseCoverService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 学习端认证封面代理；客户端只接收图片，不接触 Emby 地址和凭据。 */
@RestController
public class CourseCoverController {
  private final CourseCoverService covers;

  public CourseCoverController(CourseCoverService covers) {
    this.covers = covers;
  }

  /** 小尺寸封面允许私有短期缓存，归档与失联课程不向新请求提供图片。 */
  @GetMapping("/api/v1/catalog/courses/{courseId}/cover")
  ResponseEntity<byte[]> cover(@PathVariable String courseId) {
    var cover = covers.cover(courseId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
        .contentType(MediaType.parseMediaType(cover.contentType()))
        .body(cover.bytes());
  }
}
