package com.shangan.diagnostics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import com.shangan.diagnostics.infrastructure.DiagnosticLogRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/** 诊断日志上传：空文件、超限、脱敏、路径安全与每用户 30 份保留。 */
@ExtendWith(MockitoExtension.class)
class DiagnosticLogServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");
  private static final String USER_ID = "user-1";

  @Mock private DiagnosticLogRepository uploads;

  @TempDir Path diagnosticsRoot;

  private DiagnosticLogService service;
  private final List<DiagnosticLogUpload> stored = new ArrayList<>();

  @BeforeEach
  void setUp() {
    service =
        new DiagnosticLogService(
            uploads, () -> "log-1", Clock.fixed(NOW, ZoneOffset.UTC), diagnosticsRoot.toString());
  }

  @Test
  @DisplayName("上传成功：文件名由服务端生成，正文脱敏后落盘")
  void 上传脱敏并使用服务端文件名() throws Exception {
    when(uploads.listByUserOldestFirst(USER_ID)).thenReturn(List.of());
    String raw =
        "Authorization: Bearer abc.def.ghi\nGET /api/v1/me?token=secret\npassword=hunter2\n";

    DiagnosticLogUpload upload =
        service.upload(
            USER_ID,
            "2.6.0",
            "ios",
            raw.length(),
            new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

    assertThat(upload.id()).isEqualTo("log-1");
    assertThat(upload.storagePath()).isEqualTo("user-1/log-1.log");
    assertThat(upload.appVersion()).isEqualTo("2.6.0");
    assertThat(upload.platform()).isEqualTo("ios");
    String saved = Files.readString(diagnosticsRoot.resolve(upload.storagePath()));
    assertThat(saved).doesNotContain("abc.def.ghi").doesNotContain("token=secret");
    assertThat(saved).doesNotContain("hunter2");
    assertThat(saved).contains("[REDACTED]");
    verify(uploads).insert(upload);
  }

  @Test
  @DisplayName("空文件被拒绝且不落盘")
  void 空文件拒绝() {
    assertThatThrownBy(
            () -> service.upload(USER_ID, "2.6.0", "ios", 0, new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("DIAGNOSTIC_LOG_EMPTY");
    verify(uploads, never()).insert(any());
  }

  @Test
  @DisplayName("超过 4 MiB 被拒绝")
  void 超限拒绝() {
    assertThatThrownBy(
            () ->
                service.upload(
                    USER_ID,
                    "2.6.0",
                    "ios",
                    DiagnosticLogService.MAX_UPLOAD_BYTES + 1,
                    new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("DIAGNOSTIC_LOG_TOO_LARGE");
    verify(uploads, never()).insert(any());
  }

  @Test
  @DisplayName("超过 30 份时删除最旧的文件与台账")
  void 超出保留份数删除最旧() throws Exception {
    List<DiagnosticLogUpload> existing = new ArrayList<>();
    for (int i = 0; i < DiagnosticLogService.MAX_UPLOADS_PER_USER; i++) {
      String id = "old-" + i;
      Path file = diagnosticsRoot.resolve(USER_ID).resolve(id + ".log");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "old");
      existing.add(
          new DiagnosticLogUpload(
              id,
              USER_ID,
              USER_ID + "/" + id + ".log",
              3,
              "2.6.0",
              "ios",
              NOW.minusSeconds(i + 1)));
    }
    when(uploads.listByUserOldestFirst(USER_ID))
        .thenAnswer(
            (Answer<List<DiagnosticLogUpload>>)
                invocation -> {
                  List<DiagnosticLogUpload> copy = new ArrayList<>(existing);
                  stored.stream().findFirst().ifPresent(copy::add);
                  return copy;
                });
    doAnswer(
            invocation -> {
              stored.add(invocation.getArgument(0));
              return null;
            })
        .when(uploads)
        .insert(any());

    service.upload(
        USER_ID,
        "2.6.0",
        "ios",
        4,
        new ByteArrayInputStream("next".getBytes(StandardCharsets.UTF_8)));

    ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
    verify(uploads).delete(deleted.capture());
    assertThat(deleted.getValue()).isEqualTo("old-0");
    assertThat(Files.exists(diagnosticsRoot.resolve(USER_ID).resolve("old-0.log"))).isFalse();
  }

  @Test
  @DisplayName("后台列表不含存储路径")
  void 后台列表不含路径() {
    when(uploads.listRecent(200))
        .thenReturn(
            List.of(
                new DiagnosticLogUpload(
                    "log-1", USER_ID, "user-1/log-1.log", 12, "2.6.0", "ios", NOW)));
    when(uploads.usernameOf(USER_ID)).thenReturn(Optional.of("zhangsan"));

    DiagnosticLogService.AdminRow row = service.listForAdmin().getFirst();
    assertThat(row.username()).isEqualTo("zhangsan");
    assertThat(row.id()).isEqualTo("log-1");
  }
}
