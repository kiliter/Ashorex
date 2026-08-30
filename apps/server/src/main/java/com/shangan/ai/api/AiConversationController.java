package com.shangan.ai.api;

import com.shangan.ai.application.AiConversationService;
import com.shangan.ai.domain.AiConversation;
import com.shangan.common.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** App AI API：身份和会话所有权在服务端校验，SSE 只输出冻结事件类型。 */
@RestController
@RequestMapping("/api/v1/ai/conversations")
public class AiConversationController {
  private final AiConversationService conversations;

  public AiConversationController(AiConversationService conversations) {
    this.conversations = conversations;
  }

  @PostMapping
  AiConversation create(CurrentUser user, @Valid @RequestBody CreateConversationRequest request) {
    return conversations.create(
        user.userId(), request.scope(), request.mediaItemId(), request.title());
  }

  @GetMapping
  List<AiConversation> list(CurrentUser user) {
    return conversations.list(user.userId());
  }

  @GetMapping("/{id}/messages")
  List<AiConversation.Message> messages(CurrentUser user, @PathVariable String id) {
    return conversations.messages(user.userId(), id);
  }

  @PostMapping(path = "/{id}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(
      CurrentUser user, @PathVariable String id, @Valid @RequestBody StreamMessageRequest request) {
    SseEmitter emitter = new SseEmitter(180_000L);
    conversations.stream(
        user.userId(),
        user.timezone(),
        id,
        request.content(),
        request.currentPositionMs(),
        new AiConversationService.EventSink() {
          @Override
          public void send(String event, Object data) {
            try {
              emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException error) {
              emitter.completeWithError(error);
            }
          }

          @Override
          public void complete() {
            emitter.complete();
          }
        });
    return emitter;
  }

  public record CreateConversationRequest(String scope, String mediaItemId, String title) {}

  public record StreamMessageRequest(
      @NotBlank(message = "消息不能为空") @Size(max = 8000, message = "消息不能超过 8000 个字符") String content,
      @PositiveOrZero(message = "播放位置不能为负数") @Max(value = Long.MAX_VALUE) long currentPositionMs) {}
}
