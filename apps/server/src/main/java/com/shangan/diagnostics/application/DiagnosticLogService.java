package com.shangan.diagnostics.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.diagnostics.domain.DiagnosticLogRedactor;
import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import com.shangan.diagnostics.infrastructure.DiagnosticLogRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 接收用户手动上报的诊断日志，落盘、脱敏、限额，并供后台查看与删除。 */
@Service
public class DiagnosticLogService {

  /** 当前文件 2 MiB 加一份轮转备份，合并后上限 4 MiB。 */
  public static final long MAX_UPLOAD_BYTES = 4L * 1024 * 1024;

  /** 每用户保留的上传份数；超出删除最旧的文件与台账行。 */
  public static final int MAX_UPLOADS_PER_USER = 30;

  private static final int ADMIN_LIST_LIMIT = 200;

  private final DiagnosticLogRepository uploads;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final Path diagnosticsRoot;

  public DiagnosticLogService(
      DiagnosticLogRepository uploads,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${app.diagnostics-dir}") String diagnosticsDir) {
    this.uploads = uploads;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.diagnosticsRoot = Path.of(diagnosticsDir).toAbsolutePath().normalize();
  }

  /** 保存一份上传；空文件与超限拒绝，文件名由服务端生成。 */
  @Transactional
  public DiagnosticLogUpload upload(
      String userId, String appVersion, String platform, long declaredSize, InputStream content) {
    if (declaredSize <= 0) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "DIAGNOSTIC_LOG_EMPTY", "没有可上报的诊断日志");
    }
    if (declaredSize > MAX_UPLOAD_BYTES) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "DIAGNOSTIC_LOG_TOO_LARGE", "诊断日志不能超过 4MB");
    }
    byte[] raw;
    try {
      raw = content.readAllBytes();
    } catch (IOException exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DIAGNOSTIC_LOG_READ_FAILED", "诊断日志读取失败");
    }
    if (raw.length == 0) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "DIAGNOSTIC_LOG_EMPTY", "没有可上报的诊断日志");
    }
    if (raw.length > MAX_UPLOAD_BYTES) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "DIAGNOSTIC_LOG_TOO_LARGE", "诊断日志不能超过 4MB");
    }
    String redacted = DiagnosticLogRedactor.redact(new String(raw, StandardCharsets.UTF_8));
    byte[] stored = redacted.getBytes(StandardCharsets.UTF_8);
    String id = idGenerator.nextId();
    String relativePath = userId + "/" + id + ".log";
    Path target = diagnosticsRoot.resolve(relativePath).normalize();
    if (!target.startsWith(diagnosticsRoot)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "DIAGNOSTIC_LOG_PATH_INVALID", "日志路径不合法");
    }
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, stored);
    } catch (IOException exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "DIAGNOSTIC_LOG_WRITE_FAILED", "诊断日志写入失败");
    }
    DiagnosticLogUpload upload =
        new DiagnosticLogUpload(
            id,
            userId,
            relativePath,
            stored.length,
            safeLabel(appVersion),
            safeLabel(platform),
            clock.instant());
    try {
      uploads.insert(upload);
      prune(userId);
    } catch (RuntimeException exception) {
      deleteFileQuietly(target);
      throw exception;
    }
    return upload;
  }

  @Transactional(readOnly = true)
  public List<AdminRow> listForAdmin() {
    List<AdminRow> rows = new ArrayList<>();
    for (DiagnosticLogUpload upload : uploads.listRecent(ADMIN_LIST_LIMIT)) {
      rows.add(
          new AdminRow(
              upload.id(),
              upload.userId(),
              uploads.usernameOf(upload.userId()).orElse("已删除用户"),
              upload.sizeBytes(),
              upload.appVersion(),
              upload.platform(),
              upload.uploadedAt()));
    }
    return List.copyOf(rows);
  }

  @Transactional(readOnly = true)
  public Path locate(String id) {
    DiagnosticLogUpload upload =
        uploads
            .findById(id)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "DIAGNOSTIC_LOG_NOT_FOUND", "诊断日志不存在"));
    Path path = diagnosticsRoot.resolve(upload.storagePath()).normalize();
    if (!path.startsWith(diagnosticsRoot) || !Files.exists(path)) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "DIAGNOSTIC_LOG_NOT_FOUND", "诊断日志不存在");
    }
    return path;
  }

  /** 管理员删除一份上报：先删台账再删文件，避免留下无主路径。 */
  @Transactional
  public void delete(String id) {
    DiagnosticLogUpload upload =
        uploads
            .findById(id)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "DIAGNOSTIC_LOG_NOT_FOUND", "诊断日志不存在"));
    uploads.delete(upload.id());
    Path path = diagnosticsRoot.resolve(upload.storagePath()).normalize();
    deleteFileQuietly(path);
    Path parent = path.getParent();
    if (parent != null && parent.startsWith(diagnosticsRoot)) {
      try {
        Files.deleteIfExists(parent);
      } catch (IOException ignored) {
        // 目录里还有其他上报时保留。
      }
    }
  }

  /** 用户彻底删除时同步清掉该用户的诊断日志文件与台账。 */
  @Transactional
  public void deleteAllOf(String userId) {
    for (DiagnosticLogUpload upload : uploads.listByUserOldestFirst(userId)) {
      uploads.delete(upload.id());
      deleteFileQuietly(diagnosticsRoot.resolve(upload.storagePath()).normalize());
    }
    Path userDir = diagnosticsRoot.resolve(userId).normalize();
    if (userDir.startsWith(diagnosticsRoot)) {
      try {
        Files.deleteIfExists(userDir);
      } catch (IOException ignored) {
        // 目录非空时留给下一次清理；台账行已经删除。
      }
    }
  }

  private void prune(String userId) {
    List<DiagnosticLogUpload> existing = uploads.listByUserOldestFirst(userId);
    int overflow = existing.size() - MAX_UPLOADS_PER_USER;
    for (int i = 0; i < overflow; i++) {
      DiagnosticLogUpload oldest = existing.get(i);
      uploads.delete(oldest.id());
      deleteFileQuietly(diagnosticsRoot.resolve(oldest.storagePath()).normalize());
    }
  }

  private void deleteFileQuietly(Path path) {
    try {
      if (path.startsWith(diagnosticsRoot)) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // 台账已删时磁盘残留由下次彻底删除用户目录兜底。
    }
  }

  private String safeLabel(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
  }

  /** 后台列表行，不含存储路径。 */
  public record AdminRow(
      String id,
      String userId,
      String username,
      long sizeBytes,
      String appVersion,
      String platform,
      java.time.Instant uploadedAt) {}
}
