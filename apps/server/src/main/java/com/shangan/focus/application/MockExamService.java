package com.shangan.focus.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.focus.infrastructure.MockExamRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 模拟考试由服务端截止时间驱动，上传第一张合法试卷照片后完成对应作战单项目。 */
@Service
public class MockExamService {
  private static final int MAX_ATTACHMENTS = 9;
  private static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

  private final MockExamRepository exams;
  private final IdGenerator ids;
  private final Clock clock;
  private final Path attachmentRoot;

  public MockExamService(
      MockExamRepository exams,
      IdGenerator ids,
      Clock clock,
      @Value("${app.mock-exam-attachments-dir:./data/mock-exams}") String attachmentDirectory) {
    this.exams = exams;
    this.ids = ids;
    this.clock = clock;
    this.attachmentRoot = Path.of(attachmentDirectory).toAbsolutePath().normalize();
  }

  @Transactional
  public SessionView start(String userId, String planItemId) {
    var existing = exams.findByPlanItem(userId, planItemId);
    if (existing.isPresent()) return view(refresh(userId, existing.get()));
    var item =
        exams
            .findOwnedPlanItem(userId, planItemId)
            .orElseThrow(
                () ->
                    invalid(HttpStatus.NOT_FOUND, "MOCK_EXAM_PLAN_ITEM_NOT_FOUND", "模拟考试作战单项目不存在"));
    if (!item.status().equals("PENDING")) throw illegal("模拟考试已经结束");
    Instant startedAt = clock.instant();
    var session =
        new MockExamRepository.SessionRow(
            ids.nextId(),
            userId,
            item.id(),
            item.name(),
            item.durationSeconds(),
            "RUNNING",
            startedAt,
            startedAt.plusSeconds(item.durationSeconds()),
            null,
            null);
    exams.insertSession(session, startedAt);
    return view(session);
  }

  @Transactional
  public SessionView get(String userId, String sessionId) {
    return view(refresh(userId, requireSession(userId, sessionId)));
  }

  @Transactional
  public SessionView submitEarly(String userId, String sessionId) {
    var session = refresh(userId, requireSession(userId, sessionId));
    if (!session.status().equals("RUNNING")) {
      if (session.status().equals("AWAITING_UPLOAD")) return view(session);
      throw illegal("当前模拟考试不能提前交卷");
    }
    exams.markAwaitingUpload(userId, sessionId, clock.instant());
    return view(requireSession(userId, sessionId));
  }

  @Transactional
  public SessionWithAttachments addAttachment(
      String userId, String sessionId, AttachmentUpload upload) {
    var session = refresh(userId, requireSession(userId, sessionId));
    // 第一张图片完成考试，但仍允许继续补齐同一份试卷的其余页面。
    if (!List.of("AWAITING_UPLOAD", "COMPLETED").contains(session.status())) {
      throw illegal("模拟考试尚未进入试卷上传阶段");
    }
    int count = exams.countAttachments(userId, sessionId);
    if (count >= MAX_ATTACHMENTS) {
      throw invalid(HttpStatus.CONFLICT, "MOCK_EXAM_ATTACHMENT_LIMIT", "试卷照片最多上传 9 张");
    }
    ValidatedAttachment valid = validate(upload);
    Instant now = clock.instant();
    String attachmentId = ids.nextId();
    Path target = safeTarget(sessionId, attachmentId + valid.extension());
    writeAttachment(target, valid.bytes());
    deleteOnRollback(target);
    var row =
        new MockExamRepository.AttachmentRow(
            attachmentId,
            sessionId,
            userId,
            attachmentRoot.relativize(target).toString(),
            valid.originalFilename(),
            valid.contentType(),
            valid.bytes().length,
            sha256(valid.bytes()),
            count,
            now);
    exams.insertAttachment(row);
    exams.complete(userId, sessionId, session.planItemId(), now);
    return details(userId, sessionId);
  }

  /** 按会话和附件双重所有权读取图片，存储路径始终限制在固定根目录内。 */
  @Transactional(readOnly = true)
  public AttachmentDownload downloadAttachment(
      String userId, String sessionId, String attachmentId) {
    requireSession(userId, sessionId);
    var attachment =
        exams.findAttachments(userId, sessionId).stream()
            .filter(item -> item.id().equals(attachmentId))
            .findFirst()
            .orElseThrow(
                () -> invalid(HttpStatus.NOT_FOUND, "MOCK_EXAM_ATTACHMENT_NOT_FOUND", "试卷照片不存在"));
    Path target = attachmentRoot.resolve(attachment.storagePath()).normalize();
    if (!target.startsWith(attachmentRoot) || !Files.isRegularFile(target)) {
      throw invalid(HttpStatus.NOT_FOUND, "MOCK_EXAM_ATTACHMENT_NOT_FOUND", "试卷照片不存在");
    }
    try {
      return new AttachmentDownload(
          attachment.originalFilename(), attachment.contentType(), Files.readAllBytes(target));
    } catch (IOException exception) {
      throw new IllegalStateException("试卷照片读取失败", exception);
    }
  }

  @Transactional(readOnly = true)
  public SessionWithAttachments details(String userId, String sessionId) {
    var session = requireSession(userId, sessionId);
    List<AttachmentView> attachments =
        exams.findAttachments(userId, sessionId).stream().map(AttachmentView::from).toList();
    return new SessionWithAttachments(view(session), attachments);
  }

  private MockExamRepository.SessionRow refresh(
      String userId, MockExamRepository.SessionRow session) {
    if (session.status().equals("RUNNING") && !clock.instant().isBefore(session.deadlineAt())) {
      exams.markAwaitingUpload(userId, session.id(), session.deadlineAt());
      return requireSession(userId, session.id());
    }
    return session;
  }

  private MockExamRepository.SessionRow requireSession(String userId, String sessionId) {
    return exams
        .findOwnedSession(userId, sessionId)
        .orElseThrow(
            () -> invalid(HttpStatus.NOT_FOUND, "MOCK_EXAM_SESSION_NOT_FOUND", "模拟考试会话不存在"));
  }

  private ValidatedAttachment validate(AttachmentUpload upload) {
    if (upload == null
        || upload.bytes() == null
        || upload.bytes().length == 0
        || upload.bytes().length > MAX_ATTACHMENT_BYTES) {
      throw invalid(HttpStatus.BAD_REQUEST, "MOCK_EXAM_ATTACHMENT_INVALID", "试卷照片不能为空且单张不能超过 10MB");
    }
    String original =
        upload.originalFilename() == null
            ? "试卷照片"
            : Path.of(upload.originalFilename()).getFileName().toString();
    if (original.length() > 255) {
      throw invalid(HttpStatus.BAD_REQUEST, "MOCK_EXAM_ATTACHMENT_INVALID", "试卷照片文件名过长");
    }
    FileType fileType = detectFileType(upload.bytes());
    return new ValidatedAttachment(
        original, upload.bytes(), fileType.contentType(), fileType.extension());
  }

  private FileType detectFileType(byte[] bytes) {
    if (bytes.length >= 8
        && (bytes[0] & 0xff) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4e
        && bytes[3] == 0x47
        && bytes[4] == 0x0d
        && bytes[5] == 0x0a
        && bytes[6] == 0x1a
        && bytes[7] == 0x0a) {
      return new FileType("image/png", ".png");
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xff) == 0xff
        && (bytes[1] & 0xff) == 0xd8
        && (bytes[2] & 0xff) == 0xff) {
      return new FileType("image/jpeg", ".jpg");
    }
    if (bytes.length >= 12
        && bytes[4] == 'f'
        && bytes[5] == 't'
        && bytes[6] == 'y'
        && bytes[7] == 'p') {
      String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
      if (List.of("heic", "heix", "hevc", "hevx", "mif1").contains(brand)) {
        return new FileType("image/heic", ".heic");
      }
    }
    throw invalid(
        HttpStatus.BAD_REQUEST, "MOCK_EXAM_ATTACHMENT_INVALID", "仅支持 JPEG、PNG 或 HEIC 试卷照片");
  }

  private Path safeTarget(String sessionId, String filename) {
    Path directory = attachmentRoot.resolve(sessionId).normalize();
    Path target = directory.resolve(filename).normalize();
    if (!target.startsWith(attachmentRoot)) {
      throw invalid(HttpStatus.BAD_REQUEST, "MOCK_EXAM_ATTACHMENT_INVALID", "试卷照片路径无效");
    }
    return target;
  }

  private void writeAttachment(Path target, byte[] bytes) {
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      throw new IllegalStateException("试卷照片保存失败", exception);
    }
  }

  private void deleteOnRollback(Path target) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return;
            try {
              Files.deleteIfExists(target);
            } catch (IOException ignored) {
              // 回滚清理失败由附件孤儿文件巡检处理，不能覆盖原始事务异常。
            }
          }
        });
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("运行环境缺少 SHA-256", exception);
    }
  }

  private SessionView view(MockExamRepository.SessionRow session) {
    return new SessionView(
        session.id(),
        session.planItemId(),
        session.name(),
        session.durationSeconds(),
        session.status(),
        session.startedAt(),
        session.deadlineAt(),
        session.submittedAt(),
        session.completedAt(),
        clock.instant());
  }

  private BusinessException illegal(String message) {
    return invalid(HttpStatus.CONFLICT, "MOCK_EXAM_ILLEGAL_TRANSITION", message);
  }

  private BusinessException invalid(HttpStatus status, String code, String message) {
    return new BusinessException(status, code, message);
  }

  public record AttachmentUpload(String originalFilename, byte[] bytes) {}

  public record SessionView(
      String id,
      String planItemId,
      String name,
      long durationSeconds,
      String status,
      Instant startedAt,
      Instant deadlineAt,
      Instant submittedAt,
      Instant completedAt,
      Instant serverNow) {}

  public record AttachmentView(
      String id,
      String originalFilename,
      String contentType,
      long sizeBytes,
      int sortOrder,
      Instant createdAt) {
    static AttachmentView from(MockExamRepository.AttachmentRow row) {
      return new AttachmentView(
          row.id(),
          row.originalFilename(),
          row.contentType(),
          row.sizeBytes(),
          row.sortOrder(),
          row.createdAt());
    }
  }

  public record SessionWithAttachments(SessionView session, List<AttachmentView> attachments) {}

  public record AttachmentDownload(String originalFilename, String contentType, byte[] bytes) {}

  private record FileType(String contentType, String extension) {}

  private record ValidatedAttachment(
      String originalFilename, byte[] bytes, String contentType, String extension) {}
}
