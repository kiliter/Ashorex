package com.shangan.admin;

import com.shangan.catalog.application.CourseImportService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 媒体库选课内部 API；沿用管理员 Session 与 CSRF，所有业务校验由应用服务承担。 */
@RestController
@RequestMapping("/admin/api/emby-import")
public class EmbyImportAdminController {
  private final CourseImportService imports;

  public EmbyImportAdminController(CourseImportService imports) {
    this.imports = imports;
  }

  /** 一次返回完整候选，前端本地搜索与分页不会遗漏远端后续页。 */
  @GetMapping("/candidates")
  List<CourseImportService.Candidate> candidates() {
    return imports.candidates();
  }

  /** 仅当前可见页按需读取准确课时数量。 */
  @GetMapping("/details")
  Details details(@RequestParam String sourceId) {
    return new Details(imports.resourceCount(sourceId));
  }

  /** 图片也必须经过管理员认证；不缓存到共享代理。 */
  @GetMapping("/cover")
  ResponseEntity<byte[]> cover(@RequestParam String sourceId) {
    var cover = imports.cover(sourceId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(cover.contentType()))
        .body(cover.bytes());
  }

  /** 每次只提交一个选择项，整批进度由客户端串行推进，避免长请求吞掉部分结果。 */
  @PostMapping
  CourseImportService.ImportResult importOne(@RequestBody ImportRequest request) {
    return imports.importOne(request.sourceId());
  }

  public record Details(int resourceCount) {}

  public record ImportRequest(String sourceId) {}
}
