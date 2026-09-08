package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shangan.common.api.BusinessException;
import com.shangan.presence.application.EffectiveActionRecorder;
import com.shangan.todo.TodoFixtures;
import com.shangan.todo.domain.Todo;
import com.shangan.todo.domain.TodoPolicy;
import com.shangan.todo.infrastructure.TodoRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 附件上传与下载：大小、类型、数量、归属与路径安全。 */
@ExtendWith(MockitoExtension.class)
class TodoAttachmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T17:00:00Z");

  @Mock private TodoRepository todos;
  @Mock private TodoService todoService;
  @Mock private EffectiveActionRecorder effectiveAction;

  @TempDir Path attachmentsRoot;

  private TodoAttachmentService service;

  @BeforeEach
  void setUp() {
    service =
        new TodoAttachmentService(
            todos,
            todoService,
            effectiveAction,
            () -> "attachment-1",
            Clock.fixed(NOW, ZoneOffset.UTC),
            attachmentsRoot.toString());
  }

  @Test
  @DisplayName("上传成功：文件名由服务端生成，落在用户目录下并记录 SHA-256")
  void 上传使用服务端文件名() throws Exception {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    when(todos.attachmentsOf(todo.id())).thenReturn(List.of());

    TodoRepository.Attachment attachment =
        service.upload(
            TodoFixtures.USER_ID,
            todo.id(),
            "../../etc/passwd.png",
            "image/png",
            5L,
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

    assertThat(attachment.id()).isEqualTo("attachment-1");
    assertThat(attachment.storagePath()).isEqualTo("user-1/attachment-1.png");
    assertThat(attachment.originalFilename()).isEqualTo("passwd.png");
    assertThat(attachment.sizeBytes()).isEqualTo(5L);
    assertThat(attachment.sortOrder()).isZero();
    assertThat(attachment.createdAt()).isEqualTo(NOW);
    assertThat(attachment.sha256())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertThat(Files.readString(pathOf(attachment))).isEqualTo("hello");
    verify(todos).insertAttachment(attachment);
    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("超过 10MB 的附件被拒绝，且不写入任何文件")
  void 超大附件被拒绝() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    assertThatThrownBy(
            () ->
                service.upload(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    "big.png",
                    "image/png",
                    TodoPolicy.MAX_ATTACHMENT_BYTES + 1,
                    new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_TOO_LARGE");
    verify(todos, never()).insertAttachment(any());
  }

  @Test
  @DisplayName("声明大小为 0 的附件被拒绝")
  void 空附件被拒绝() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    assertThatThrownBy(
            () ->
                service.upload(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    "empty.png",
                    "image/png",
                    0L,
                    new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_TOO_LARGE");
  }

  @Test
  @DisplayName("只允许图片与 PDF：其他类型返回 ATTACHMENT_TYPE_UNSUPPORTED")
  void 非法类型被拒绝() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);

    assertThatThrownBy(
            () ->
                service.upload(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    "run.sh",
                    "application/x-sh",
                    10L,
                    new ByteArrayInputStream(new byte[10])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_TYPE_UNSUPPORTED");
    assertThat(TodoPolicy.allowedContentType("application/pdf")).isTrue();
    assertThat(TodoPolicy.allowedContentType("image/heic")).isTrue();
    assertThat(TodoPolicy.allowedContentType(null)).isFalse();
  }

  @Test
  @DisplayName("单条待办已有 9 个附件时拒绝继续上传")
  void 超过数量上限被拒绝() {
    Todo todo = TodoFixtures.task().build();
    when(todoService.requireOwned(TodoFixtures.USER_ID, todo.id())).thenReturn(todo);
    List<TodoRepository.Attachment> existing = new ArrayList<>();
    for (int index = 0; index < TodoPolicy.MAX_ATTACHMENTS_PER_TODO; index++) {
      existing.add(attachment("attachment-" + index, TodoFixtures.USER_ID));
    }
    when(todos.attachmentsOf(todo.id())).thenReturn(existing);

    assertThatThrownBy(
            () ->
                service.upload(
                    TodoFixtures.USER_ID,
                    todo.id(),
                    "extra.png",
                    "image/png",
                    5L,
                    new ByteArrayInputStream(new byte[5])))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_LIMIT_REACHED");
  }

  @Test
  @DisplayName("读取他人附件按不存在处理")
  void 越权读取被拒绝() {
    when(todos.findAttachment("attachment-1"))
        .thenReturn(Optional.of(attachment("attachment-1", "other-user")));

    assertThatThrownBy(() -> service.locate(TodoFixtures.USER_ID, "attachment-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_NOT_FOUND");
  }

  @Test
  @DisplayName("附件行存在但磁盘文件缺失时返回 ATTACHMENT_NOT_FOUND")
  void 文件缺失时按不存在处理() {
    when(todos.findAttachment("attachment-1"))
        .thenReturn(Optional.of(attachment("attachment-1", TodoFixtures.USER_ID)));

    assertThatThrownBy(() -> service.locate(TodoFixtures.USER_ID, "attachment-1"))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo("ATTACHMENT_NOT_FOUND");
  }

  @Test
  @DisplayName("删除附件同时删除数据库行与磁盘文件")
  void 删除附件清理文件() throws Exception {
    TodoRepository.Attachment attachment = attachment("attachment-1", TodoFixtures.USER_ID);
    Path path = pathOf(attachment);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "content");
    when(todos.findAttachment("attachment-1")).thenReturn(Optional.of(attachment));

    service.delete(TodoFixtures.USER_ID, "attachment-1");

    verify(todos).deleteAttachment("attachment-1");
    assertThat(Files.exists(path)).isFalse();
    verify(effectiveAction).record(TodoFixtures.USER_ID);
  }

  @Test
  @DisplayName("删除整条待办的附件时逐个清理文件并删除全部行")
  void 批量清理附件() throws Exception {
    TodoRepository.Attachment attachment = attachment("attachment-1", TodoFixtures.USER_ID);
    Path path = pathOf(attachment);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "content");
    when(todos.attachmentsOf("todo-1")).thenReturn(List.of(attachment));

    service.deleteAllOf("todo-1");

    assertThat(Files.exists(path)).isFalse();
    verify(todos).deleteAttachmentsOf("todo-1");
  }

  private Path pathOf(TodoRepository.Attachment attachment) {
    return attachmentsRoot.resolve(attachment.storagePath()).normalize();
  }

  private static TodoRepository.Attachment attachment(String id, String userId) {
    return new TodoRepository.Attachment(
        id,
        "todo-1",
        userId,
        userId + "/" + id + ".png",
        "photo.png",
        "image/png",
        5L,
        "sha",
        0,
        NOW);
  }
}
