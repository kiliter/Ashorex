package com.shangan.focus.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.focus.application.MockExamService;
import io.swagger.v3.oas.annotations.Parameter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 模拟考试执行 API；倒计时和完成状态均以服务端会话为准。 */
@RestController
@RequestMapping("/api/v1/mock-exams")
public class MockExamController {
  private final MockExamService mockExams;

  public MockExamController(MockExamService mockExams) {
    this.mockExams = mockExams;
  }

  @PostMapping("/{planItemId}/start")
  MockExamService.SessionView start(CurrentUser user, @PathVariable String planItemId) {
    return mockExams.start(user.userId(), planItemId);
  }

  @GetMapping("/{sessionId}")
  MockExamService.SessionWithAttachments get(CurrentUser user, @PathVariable String sessionId) {
    mockExams.get(user.userId(), sessionId);
    return mockExams.details(user.userId(), sessionId);
  }

  @PostMapping("/{sessionId}/submit-early")
  MockExamService.SessionView submitEarly(CurrentUser user, @PathVariable String sessionId) {
    return mockExams.submitEarly(user.userId(), sessionId);
  }

  @PostMapping(path = "/{sessionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  MockExamService.SessionWithAttachments addAttachment(
      @Parameter(hidden = true) CurrentUser user,
      @PathVariable String sessionId,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    return mockExams.addAttachment(
        user.userId(),
        sessionId,
        new MockExamService.AttachmentUpload(file.getOriginalFilename(), file.getBytes()));
  }

  /** 返回当前用户所属考试会话中的单张试卷照片。 */
  @GetMapping("/{sessionId}/attachments/{attachmentId}")
  ResponseEntity<byte[]> downloadAttachment(
      CurrentUser user, @PathVariable String sessionId, @PathVariable String attachmentId) {
    var attachment = mockExams.downloadAttachment(user.userId(), sessionId, attachmentId);
    var disposition =
        ContentDisposition.attachment()
            .filename(attachment.originalFilename(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(attachment.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(attachment.bytes());
  }
}
