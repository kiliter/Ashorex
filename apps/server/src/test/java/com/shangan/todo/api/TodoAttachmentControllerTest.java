package com.shangan.todo.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.api.BusinessException;
import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.NagPolicyResolver;
import com.shangan.todo.application.FocusService;
import com.shangan.todo.application.TodoAttachmentService;
import com.shangan.todo.application.TodoCompletionService;
import com.shangan.todo.application.TodoDeletionService;
import com.shangan.todo.application.TodoProgressService;
import com.shangan.todo.application.TodoService;
import com.shangan.todo.application.TodoViewService;
import com.shangan.todo.infrastructure.TodoRepository;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 附件端点的 Controller 切片测试，覆盖上传、读取、下载与删除。
 *
 * <p>核心约束：响应投影不含服务器文件系统路径与摘要、下载 URL 指向受同一归属校验保护的内容端点、 越权访问按「不存在」返回 404 而不是泄露归属信息、三道上传限制各有稳定
 * errorCode。
 *
 * <p>上传端点是「完成凭证必填」（requireEvidence）唯一的解除入口，因此越权与限制分支必须逐条覆盖。
 *
 * <p>不启动数据库与 Flyway，依赖全部以 Mockito 打桩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Todo 附件读取端点")
class TodoAttachmentControllerTest {

  private static final String USER_ID = "user-1";
  private static final String TODO_ID = "todo-1";
  private static final String STORAGE_PATH = USER_ID + "/attachment-1.png";
  private static final String SHA256 = "6b1c2f0e9d4a5b8c7e3f1a2b3c4d5e6f";

  @Mock private TodoService todoService;
  @Mock private TodoViewService views;
  @Mock private TodoProgressService progress;
  @Mock private TodoCompletionService completion;
  @Mock private FocusService focus;
  @Mock private TodoDeletionService deletions;
  @Mock private TodoAttachmentService attachments;
  @Mock private NagPolicyResolver nagPolicies;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new TodoController(
                    todoService,
                    views,
                    progress,
                    completion,
                    focus,
                    deletions,
                    attachments,
                    nagPolicies))
            .setCustomArgumentResolvers(new FixedCurrentUserResolver())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("附件列表返回展示字段与下载 URL，且不泄露存储路径与摘要")
  void 附件列表不泄露存储路径() throws Exception {
    when(attachments.list(USER_ID, TODO_ID)).thenReturn(List.of(attachment()));

    String body =
        mockMvc
            .perform(get("/api/v1/todos/{todoId}/attachments", TODO_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("attachment-1"))
            .andExpect(jsonPath("$[0].filename").value("proof.png"))
            .andExpect(jsonPath("$[0].contentType").value("image/png"))
            .andExpect(jsonPath("$[0].sizeBytes").value(2048))
            .andExpect(jsonPath("$[0].sortOrder").value(0))
            .andExpect(jsonPath("$[0].createdAt").value("2026-09-07T01:00:00Z"))
            .andExpect(
                jsonPath("$[0].downloadUrl")
                    .value("/api/v1/todos/todo-1/attachments/attachment-1/content"))
            .andExpect(jsonPath("$[0].storagePath").doesNotExist())
            .andExpect(jsonPath("$[0].sha256").doesNotExist())
            .andExpect(jsonPath("$[0].userId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain(STORAGE_PATH).doesNotContain(SHA256);
  }

  @Test
  @DisplayName("没有任何附件时返回空数组，而不是 404")
  void 空附件返回空数组() throws Exception {
    when(attachments.list(USER_ID, TODO_ID)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/todos/{todoId}/attachments", TODO_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("访问他人 Todo 的附件列表返回 404 TODO_NOT_FOUND，不暴露该 Todo 是否存在")
  void 越权读取附件列表返回404() throws Exception {
    when(attachments.list(USER_ID, "todo-of-others"))
        .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));

    mockMvc
        .perform(get("/api/v1/todos/{todoId}/attachments", "todo-of-others"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
  }

  @Test
  @DisplayName("越权下载附件内容同样返回 404，且不触碰文件定位逻辑")
  void 越权下载附件返回404() throws Exception {
    when(attachments.list(USER_ID, "todo-of-others"))
        .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));

    mockMvc
        .perform(
            get(
                "/api/v1/todos/{todoId}/attachments/{attachmentId}/content",
                "todo-of-others",
                "attachment-1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));

    org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).locate(any(), any());
  }

  @Test
  @DisplayName("附件属于自己但不挂在该 Todo 上时返回 404 ATTACHMENT_NOT_FOUND")
  void 附件不属于该Todo返回404() throws Exception {
    when(attachments.list(USER_ID, TODO_ID)).thenReturn(List.of(attachment()));

    mockMvc
        .perform(
            get(
                "/api/v1/todos/{todoId}/attachments/{attachmentId}/content",
                TODO_ID,
                "attachment-from-another-todo"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_NOT_FOUND"));

    org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).locate(any(), any());
  }

  // ---------------- 上传：requireEvidence 的唯一解除入口 ----------------

  @Test
  @DisplayName("上传成功返回附件视图，且响应不含存储路径与摘要")
  void 上传附件成功() throws Exception {
    when(attachments.upload(
            eq(USER_ID), eq(TODO_ID), eq("proof.png"), eq("image/png"), eq(4L), any()))
        .thenReturn(attachment());

    String body =
        mockMvc
            .perform(multipart("/api/v1/todos/{todoId}/attachments", TODO_ID).file(pngPart()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("attachment-1"))
            .andExpect(jsonPath("$.filename").value("proof.png"))
            .andExpect(jsonPath("$.contentType").value("image/png"))
            .andExpect(jsonPath("$.sizeBytes").value(2048))
            .andExpect(
                jsonPath("$.downloadUrl")
                    .value("/api/v1/todos/todo-1/attachments/attachment-1/content"))
            .andExpect(jsonPath("$.storagePath").doesNotExist())
            .andExpect(jsonPath("$.sha256").doesNotExist())
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertThat(body).doesNotContain(STORAGE_PATH).doesNotContain(SHA256);
  }

  @Test
  @DisplayName("向他人的 Todo 上传附件返回 404 TODO_NOT_FOUND")
  void 越权上传附件返回404() throws Exception {
    when(attachments.upload(eq(USER_ID), eq("todo-of-others"), any(), any(), anyLong(), any()))
        .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));

    mockMvc
        .perform(multipart("/api/v1/todos/{todoId}/attachments", "todo-of-others").file(pngPart()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
  }

  @Test
  @DisplayName("附件超过 10MB 返回 400 ATTACHMENT_TOO_LARGE")
  void 上传超大附件被拒() throws Exception {
    when(attachments.upload(eq(USER_ID), eq(TODO_ID), any(), any(), anyLong(), any()))
        .thenThrow(
            new BusinessException(HttpStatus.BAD_REQUEST, "ATTACHMENT_TOO_LARGE", "单个附件不能超过 10MB"));

    mockMvc
        .perform(multipart("/api/v1/todos/{todoId}/attachments", TODO_ID).file(pngPart()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_TOO_LARGE"));
  }

  @Test
  @DisplayName("multipart 解析器拦下的超限请求同样收敛为 ATTACHMENT_TOO_LARGE")
  void 解析器超限也返回相同errorCode() throws Exception {
    when(attachments.upload(eq(USER_ID), eq(TODO_ID), any(), any(), anyLong(), any()))
        .thenThrow(new MaxUploadSizeExceededException(10L * 1024 * 1024));

    mockMvc
        .perform(multipart("/api/v1/todos/{todoId}/attachments", TODO_ID).file(pngPart()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_TOO_LARGE"));
  }

  @Test
  @DisplayName("不在白名单的类型返回 400 ATTACHMENT_TYPE_UNSUPPORTED")
  void 上传不支持的类型被拒() throws Exception {
    when(attachments.upload(
            eq(USER_ID), eq(TODO_ID), eq("note.txt"), eq("text/plain"), anyLong(), any()))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST, "ATTACHMENT_TYPE_UNSUPPORTED", "只支持图片或 PDF 附件"));

    mockMvc
        .perform(
            multipart("/api/v1/todos/{todoId}/attachments", TODO_ID)
                .file(new MockMultipartFile("file", "note.txt", "text/plain", "hi".getBytes())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_TYPE_UNSUPPORTED"));
  }

  @Test
  @DisplayName("第 10 个附件返回 400 ATTACHMENT_LIMIT_REACHED")
  void 上传数量超上限被拒() throws Exception {
    when(attachments.upload(eq(USER_ID), eq(TODO_ID), any(), any(), anyLong(), any()))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST, "ATTACHMENT_LIMIT_REACHED", "单条待办最多 9 个附件"));

    mockMvc
        .perform(multipart("/api/v1/todos/{todoId}/attachments", TODO_ID).file(pngPart()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_LIMIT_REACHED"));
  }

  // ---------------- 删除 ----------------

  @Test
  @DisplayName("删除自己的附件返回 204 且响应体为空")
  void 删除附件成功() throws Exception {
    when(attachments.list(USER_ID, TODO_ID)).thenReturn(List.of(attachment()));

    mockMvc
        .perform(
            delete("/api/v1/todos/{todoId}/attachments/{attachmentId}", TODO_ID, "attachment-1"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    org.mockito.Mockito.verify(attachments).delete(USER_ID, "attachment-1");
  }

  @Test
  @DisplayName("删除他人 Todo 上的附件返回 404，且不进入删除逻辑")
  void 越权删除附件返回404() throws Exception {
    when(attachments.list(USER_ID, "todo-of-others"))
        .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));

    mockMvc
        .perform(
            delete(
                "/api/v1/todos/{todoId}/attachments/{attachmentId}",
                "todo-of-others",
                "attachment-1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));

    org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).delete(any(), any());
  }

  @Test
  @DisplayName("删除不挂在该 Todo 上的附件返回 404 ATTACHMENT_NOT_FOUND")
  void 删除不属于该Todo的附件返回404() throws Exception {
    when(attachments.list(USER_ID, TODO_ID)).thenReturn(List.of(attachment()));

    mockMvc
        .perform(
            delete(
                "/api/v1/todos/{todoId}/attachments/{attachmentId}",
                TODO_ID,
                "attachment-from-another-todo"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_NOT_FOUND"));

    org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).delete(any(), any());
  }

  private static MockMultipartFile pngPart() {
    return new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1, 2, 3, 4});
  }

  private TodoRepository.Attachment attachment() {
    return new TodoRepository.Attachment(
        "attachment-1",
        TODO_ID,
        USER_ID,
        STORAGE_PATH,
        "proof.png",
        "image/png",
        2048L,
        SHA256,
        0,
        Instant.parse("2026-09-07T01:00:00Z"));
  }

  /** 切片测试里不跑 JWT 过滤器，直接注入固定的当前用户。 */
  private static final class FixedCurrentUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory binderFactory) {
      return new CurrentUser(USER_ID, "zhangsan", "LEARNER", "Asia/Shanghai");
    }
  }
}
