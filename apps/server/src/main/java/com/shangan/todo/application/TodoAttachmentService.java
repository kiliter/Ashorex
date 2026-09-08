package com.shangan.todo.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoPolicy;
import com.shangan.todo.infrastructure.TodoRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Todo 附件的存储与清理；文件名由服务端生成，禁止使用客户端原始路径。 */
@Service
public class TodoAttachmentService {

  private final TodoRepository todos;
  private final TodoService todoService;
  private final EffectiveActionRecorder effectiveAction;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final Path attachmentsRoot;

  public TodoAttachmentService(
      TodoRepository todos,
      TodoService todoService,
      EffectiveActionRecorder effectiveAction,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${app.attachments-dir}") String attachmentsDir) {
    this.todos = todos;
    this.todoService = todoService;
    this.effectiveAction = effectiveAction;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.attachmentsRoot = Path.of(attachmentsDir).toAbsolutePath().normalize();
  }

  /** 保存一个附件；大小、类型与数量在服务端统一校验。 */
  @Transactional
  public TodoRepository.Attachment upload(
      String userId,
      String todoId,
      String originalFilename,
      String contentType,
      long sizeBytes,
      InputStream content) {
    Todo todo = todoService.requireOwned(userId, todoId);
    if (sizeBytes <= 0 || sizeBytes > TodoPolicy.MAX_ATTACHMENT_BYTES) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "ATTACHMENT_TOO_LARGE", "单个附件不能超过 10MB");
    }
    if (!TodoPolicy.allowedContentType(contentType)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "ATTACHMENT_TYPE_UNSUPPORTED", "只支持图片或 PDF 附件");
    }
    List<TodoRepository.Attachment> existing = todos.attachmentsOf(todo.id());
    if (existing.size() >= TodoPolicy.MAX_ATTACHMENTS_PER_TODO) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "ATTACHMENT_LIMIT_REACHED",
          "单条待办最多 " + TodoPolicy.MAX_ATTACHMENTS_PER_TODO + " 个附件");
    }

    String attachmentId = idGenerator.nextId();
    String extension = extensionOf(contentType);
    String relativePath = userId + "/" + attachmentId + extension;
    Path target = attachmentsRoot.resolve(relativePath).normalize();
    if (!target.startsWith(attachmentsRoot)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "ATTACHMENT_PATH_INVALID", "附件路径不合法");
    }
    String sha256;
    try {
      Files.createDirectories(target.getParent());
      byte[] bytes = content.readAllBytes();
      if (bytes.length > TodoPolicy.MAX_ATTACHMENT_BYTES) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST, "ATTACHMENT_TOO_LARGE", "单个附件不能超过 10MB");
      }
      sha256 = digest(bytes);
      Files.write(target, bytes);
    } catch (IOException exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_WRITE_FAILED", "附件写入失败");
    }

    TodoRepository.Attachment attachment =
        new TodoRepository.Attachment(
            attachmentId,
            todo.id(),
            userId,
            relativePath,
            safeFilename(originalFilename),
            contentType,
            sizeBytes,
            sha256,
            existing.size(),
            clock.instant());
    todos.insertAttachment(attachment);
    effectiveAction.record(userId);
    return attachment;
  }

  @Transactional(readOnly = true)
  public List<TodoRepository.Attachment> list(String userId, String todoId) {
    todoService.requireOwned(userId, todoId);
    return todos.attachmentsOf(todoId);
  }

  /** 读取附件内容；归属校验后返回本地绝对路径。 */
  @Transactional(readOnly = true)
  public Path locate(String userId, String attachmentId) {
    TodoRepository.Attachment attachment = requireOwnedAttachment(userId, attachmentId);
    Path path = attachmentsRoot.resolve(attachment.storagePath()).normalize();
    if (!path.startsWith(attachmentsRoot) || !Files.exists(path)) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在");
    }
    return path;
  }

  @Transactional
  public void delete(String userId, String attachmentId) {
    TodoRepository.Attachment attachment = requireOwnedAttachment(userId, attachmentId);
    todos.deleteAttachment(attachment.id());
    deleteFileQuietly(attachmentsRoot.resolve(attachment.storagePath()).normalize());
    effectiveAction.record(userId);
  }

  /** 删除某条 Todo 的全部附件文件与行，供删除台账流程调用。 */
  @Transactional
  public void deleteAllOf(String todoId) {
    for (TodoRepository.Attachment attachment : todos.attachmentsOf(todoId)) {
      deleteFileQuietly(attachmentsRoot.resolve(attachment.storagePath()).normalize());
    }
    todos.deleteAttachmentsOf(todoId);
  }

  private TodoRepository.Attachment requireOwnedAttachment(String userId, String attachmentId) {
    TodoRepository.Attachment attachment =
        todos
            .findAttachment(attachmentId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在"));
    if (!attachment.userId().equals(userId)) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在");
    }
    return attachment;
  }

  private void deleteFileQuietly(Path path) {
    try {
      if (path.startsWith(attachmentsRoot)) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_DELETE_FAILED", "附件删除失败");
    }
  }

  private String extensionOf(String contentType) {
    String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "image/png" -> ".png";
      case "image/jpeg", "image/jpg" -> ".jpg";
      case "image/heic" -> ".heic";
      case "image/webp" -> ".webp";
      case "application/pdf" -> ".pdf";
      default -> ".bin";
    };
  }

  /** 原始文件名只用于展示，去掉路径分隔符避免出现目录信息。 */
  private String safeFilename(String originalFilename) {
    if (originalFilename == null || originalFilename.isBlank()) {
      return "attachment";
    }
    String name = originalFilename.replace('\\', '/');
    int index = name.lastIndexOf('/');
    String base = index >= 0 ? name.substring(index + 1) : name;
    return base.length() > 120 ? base.substring(base.length() - 120) : base;
  }

  private String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前运行时不支持 SHA-256", exception);
    }
  }
}
