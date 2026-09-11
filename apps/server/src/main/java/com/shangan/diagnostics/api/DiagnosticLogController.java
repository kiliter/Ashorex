package com.shangan.diagnostics.api;

import com.shangan.common.api.BusinessException;
import com.shangan.common.auth.CurrentUser;
import com.shangan.diagnostics.application.DiagnosticLogService;
import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import java.io.InputStream;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 学员手动上报本机诊断日志；不自动采集。 */
@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticLogController {

  private final DiagnosticLogService logs;

  public DiagnosticLogController(DiagnosticLogService logs) {
    this.logs = logs;
  }

  @PostMapping(path = "/logs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  UploadView upload(
      CurrentUser currentUser,
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "appVersion", required = false) String appVersion,
      @RequestParam(value = "platform", required = false) String platform) {
    try (InputStream content = file.getInputStream()) {
      DiagnosticLogUpload saved =
          logs.upload(currentUser.userId(), appVersion, platform, file.getSize(), content);
      return UploadView.of(saved);
    } catch (java.io.IOException exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DIAGNOSTIC_LOG_READ_FAILED", "诊断日志读取失败");
    }
  }

  /** 成功响应不含存储路径，避免暴露 DATA_DIR 布局。 */
  public record UploadView(
      String id, long sizeBytes, String appVersion, String platform, Instant uploadedAt) {

    static UploadView of(DiagnosticLogUpload upload) {
      return new UploadView(
          upload.id(),
          upload.sizeBytes(),
          upload.appVersion(),
          upload.platform(),
          upload.uploadedAt());
    }
  }
}
