package com.shangan.ai.application;

import com.shangan.ai.domain.AiConversation;
import com.shangan.ai.domain.AiConversation.Citation;
import com.shangan.ai.infrastructure.JdbcAiConversationRepository;
import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsProvider;
import dev.langchain4j.invocation.InvocationParameters;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** AI 会话应用服务：先持久化用户消息，再启动模型流，并保证每用户只有一个活动流。 */
@Service
public class AiConversationService {
  private static final int MAXIMUM_MESSAGE_CHARACTERS = 8_000;
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]\\)\\}\"']+");

  private final JdbcAiConversationRepository conversations;
  private final CatalogQueryService catalog;
  private final VideoContextBuilder videoContexts;
  private final AiChatEngine engine;
  private final IdGenerator ids;
  private final Clock clock;
  private final ObjectMapper json;
  private final IntegrationSettingsProvider integrationSettings;
  private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();

  public AiConversationService(
      JdbcAiConversationRepository conversations,
      CatalogQueryService catalog,
      VideoContextBuilder videoContexts,
      AiChatEngine engine,
      IdGenerator ids,
      Clock clock,
      ObjectMapper json,
      IntegrationSettingsProvider integrationSettings) {
    this.conversations = conversations;
    this.catalog = catalog;
    this.videoContexts = videoContexts;
    this.engine = engine;
    this.ids = ids;
    this.clock = clock;
    this.json = json;
    this.integrationSettings = integrationSettings;
  }

  @Transactional
  public AiConversation create(
      String userId, String scope, String mediaItemId, String requestedTitle) {
    String normalizedScope =
        scope == null ? "GENERAL" : scope.trim().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("GENERAL", "VIDEO").contains(normalizedScope)) {
      throw invalid("AI_SCOPE_INVALID", "AI 会话范围无效");
    }
    String title =
        requestedTitle == null || requestedTitle.isBlank() ? "新对话" : requestedTitle.trim();
    if (title.length() > 100) throw invalid("AI_TITLE_TOO_LONG", "会话标题不能超过 100 个字符");
    if (normalizedScope.equals("VIDEO")) {
      catalog.findLesson(mediaItemId).orElseThrow(() -> invalid("AI_VIDEO_NOT_FOUND", "课时不存在或不可用"));
    } else {
      mediaItemId = null;
    }
    Instant now = clock.instant();
    AiConversation conversation =
        new AiConversation(
            ids.nextId(), userId, normalizedScope, mediaItemId, title, null, now, now);
    conversations.insert(conversation);
    return conversation;
  }

  @Transactional(readOnly = true)
  public List<AiConversation> list(String userId) {
    return conversations.findByUser(userId);
  }

  @Transactional(readOnly = true)
  public List<AiConversation.Message> messages(String userId, String conversationId) {
    requireOwned(userId, conversationId);
    return conversations.findMessages(userId, conversationId);
  }

  /** 建立流前完成全部同步校验，避免错误被包装成已经开始的 SSE 响应。 */
  public void stream(
      String userId,
      String timezone,
      String conversationId,
      String content,
      long currentPositionMs,
      EventSink sink) {
    String message = content == null ? "" : content.trim();
    if (message.isBlank()) throw invalid("AI_MESSAGE_EMPTY", "消息不能为空");
    if (message.length() > MAXIMUM_MESSAGE_CHARACTERS) {
      throw invalid("AI_MESSAGE_TOO_LONG", "消息不能超过 8000 个字符");
    }
    AiConversation conversation = requireOwned(userId, conversationId);
    if (!activeUsers.add(userId)) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "AI_STREAM_ALREADY_ACTIVE", "已有一个 AI 回答正在生成");
    }

    try {
      List<AiConversation.Message> history = conversations.findMessages(userId, conversationId);
      Instant now = clock.instant();
      conversations.insertMessage(
          new AiConversation.Message(
              ids.nextId(),
              conversationId,
              "USER",
              message,
              "COMPLETED",
              "[]",
              null,
              0,
              0,
              now,
              now));
      String assistantMessageId = ids.nextId();
      sink.send("message_start", Map.of("messageId", assistantMessageId));
      var context =
          conversation.scope().equals("VIDEO")
              ? videoContexts.build(
                  conversation.mediaItemId(), Math.max(0, currentPositionMs), message)
              : new VideoContextBuilder.VideoContext("", List.of(), false);
      String prompt = buildPrompt(history, context.promptContext(), message);
      InvocationParameters parameters =
          InvocationParameters.from(
              Map.of(
                  "userId",
                  userId,
                  "timezone",
                  timezone,
                  "mediaItemId",
                  conversation.mediaItemId() == null ? "" : conversation.mediaItemId()));
      engine.stream(
          conversationId,
          prompt,
          parameters,
          new StreamListener(
              userId,
              conversationId,
              assistantMessageId,
              context.citations(),
              sink,
              clock.instant()));
    } catch (RuntimeException error) {
      activeUsers.remove(userId);
      throw error;
    }
  }

  private AiConversation requireOwned(String userId, String conversationId) {
    return conversations
        .findOwned(userId, conversationId)
        .orElseThrow(
            () ->
                new BusinessException(HttpStatus.NOT_FOUND, "AI_CONVERSATION_NOT_FOUND", "会话不存在"));
  }

  private String buildPrompt(
      List<AiConversation.Message> history, String videoContext, String currentMessage) {
    int maximumContextCharacters =
        Math.max(8_000, integrationSettings.current().llm().maxContextTokens() * 3);
    StringBuilder bounded = new StringBuilder();
    for (int index = history.size() - 1; index >= 0; index--) {
      AiConversation.Message value = history.get(index);
      String line = value.role() + "：" + value.content() + "\n";
      if (bounded.length() + line.length() > maximumContextCharacters) break;
      bounded.insert(0, line);
    }
    return """
        以下聊天历史只用于连续问答：
        <untrusted_chat_history>
        %s</untrusted_chat_history>
        %s
        当前用户问题：%s
        """
        .formatted(bounded, videoContext, currentMessage);
  }

  private List<Citation> webCitations(String toolResult) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    var matcher = URL_PATTERN.matcher(toolResult == null ? "" : toolResult);
    while (matcher.find() && urls.size() < 20) urls.add(matcher.group());
    return urls.stream().map(url -> new Citation("WEB", url, url, null)).toList();
  }

  private String citationsJson(List<Citation> citations) {
    try {
      return json.writeValueAsString(citations);
    } catch (Exception error) {
      throw new IllegalStateException("AI 引用序列化失败", error);
    }
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }

  public interface EventSink {
    void send(String event, Object data);

    void complete();
  }

  private final class StreamListener implements AiChatEngine.Listener {
    private final String userId;
    private final String conversationId;
    private final String assistantMessageId;
    private final List<Citation> citations = new ArrayList<>();
    private final EventSink sink;
    private final Instant createdAt;
    private final StringBuilder partial = new StringBuilder();
    private boolean terminal;

    private StreamListener(
        String userId,
        String conversationId,
        String assistantMessageId,
        List<Citation> videoCitations,
        EventSink sink,
        Instant createdAt) {
      this.userId = userId;
      this.conversationId = conversationId;
      this.assistantMessageId = assistantMessageId;
      this.citations.addAll(videoCitations);
      this.sink = sink;
      this.createdAt = createdAt;
    }

    @Override
    public synchronized void onToolStarted(String name) {
      if (!terminal) sink.send("tool_status", Map.of("name", name, "status", "RUNNING"));
    }

    @Override
    public synchronized void onToolCompleted(String name, String result) {
      if (terminal) return;
      citations.addAll(webCitations(result));
      sink.send("tool_status", Map.of("name", name, "status", "COMPLETED"));
    }

    @Override
    public synchronized void onDelta(String text) {
      if (terminal || text == null || text.isEmpty()) return;
      partial.append(text);
      sink.send("delta", Map.of("text", text));
    }

    @Override
    public synchronized void onComplete(String modelName, int inputTokens, int outputTokens) {
      if (terminal) return;
      terminal = true;
      List<Citation> unique = citations.stream().distinct().toList();
      unique.forEach(value -> sink.send("citation", value));
      persist("COMPLETED", modelName, inputTokens, outputTokens, unique);
      sink.send(
          "message_end",
          Map.of(
              "messageId",
              assistantMessageId,
              "inputTokens",
              inputTokens,
              "outputTokens",
              outputTokens));
      finish();
    }

    @Override
    public synchronized void onError(Throwable error) {
      if (terminal) return;
      terminal = true;
      persist("FAILED", null, 0, 0, citations.stream().distinct().toList());
      sink.send(
          "error", Map.of("errorCode", "AI_PROVIDER_UNAVAILABLE", "message", "AI 回答中断，请稍后重试"));
      finish();
    }

    private void persist(
        String status,
        String modelName,
        int inputTokens,
        int outputTokens,
        List<Citation> persistedCitations) {
      Instant now = clock.instant();
      conversations.insertMessage(
          new AiConversation.Message(
              assistantMessageId,
              conversationId,
              "ASSISTANT",
              partial.toString(),
              status,
              citationsJson(persistedCitations),
              modelName,
              Math.max(0, inputTokens),
              Math.max(0, outputTokens),
              createdAt,
              now));
    }

    private void finish() {
      activeUsers.remove(userId);
      sink.complete();
    }
  }
}
