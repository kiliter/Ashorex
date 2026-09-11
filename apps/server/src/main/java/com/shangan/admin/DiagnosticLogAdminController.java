package com.shangan.admin;

import com.shangan.diagnostics.application.DiagnosticLogService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理后台查看并删除用户上报的诊断日志。 */
@RestController
@RequestMapping("/admin/api/diagnostic-logs")
public class DiagnosticLogAdminController {

  private final DiagnosticLogService logs;

  public DiagnosticLogAdminController(DiagnosticLogService logs) {
    this.logs = logs;
  }

  @GetMapping
  List<Row> list() {
    return logs.listForAdmin().stream().map(Row::from).toList();
  }

  @GetMapping("/{id}/content")
  ResponseEntity<Resource> content(@PathVariable String id) {
    Path path = logs.locate(id);
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(new FileSystemResource(path));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable String id) {
    logs.delete(id);
    return ResponseEntity.noContent().build();
  }

  public record Row(
      String id,
      String userId,
      String username,
      long sizeBytes,
      String appVersion,
      String platform,
      Instant uploadedAt) {

    static Row from(DiagnosticLogService.AdminRow row) {
      return new Row(
          row.id(),
          row.userId(),
          row.username(),
          row.sizeBytes(),
          row.appVersion(),
          row.platform(),
          row.uploadedAt());
    }
  }
}
