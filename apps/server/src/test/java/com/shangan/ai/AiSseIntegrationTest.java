package com.shangan.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.ai.application.AiChatEngine;
import com.shangan.ai.application.AiConversationService;
import dev.langchain4j.invocation.InvocationParameters;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证 SSE 顺序、消息事务时机和流中断后的 FAILED 部分内容。 */
@SpringBootTest
@Import(AiSseIntegrationTest.FakeEngineConfiguration.class)
class AiSseIntegrationTest {
  @TempDir static Path databaseDirectory;

  @Autowired AiConversationService conversations;
  @Autowired JdbcClient jdbc;
  @Autowired FakeAiChatEngine engine;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("ai-sse.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from ai_messages").update();
    jdbc.sql("delete from ai_conversations").update();
    jdbc.sql("delete from users").update();
    jdbc.sql(
            "insert into users(id,username,password_hash,display_name,role,timezone,created_at,updated_at) "
                + "values('user-1','tester','hash','测试用户','USER','Asia/Shanghai',1,1)")
        .update();
  }

  @Test
  void emitsFrozenOrderAndPersistsAssistantOnlyAfterCompletion() {
    engine.behavior =
        listener -> {
          assertThat(countMessages()).isEqualTo(1);
          listener.onToolStarted("web_search");
          listener.onToolCompleted("web_search", "来源 https://example.test/source");
          listener.onDelta("第一段");
          listener.onDelta("第二段");
          assertThat(countMessages()).isEqualTo(1);
          listener.onComplete("test-model", 12, 7);
        };
    var conversation = conversations.create("user-1", "GENERAL", null, "测试");
    RecordingSink sink = new RecordingSink();

    conversations.stream("user-1", "Asia/Shanghai", conversation.id(), "请联网解释增长率", 0, sink);

    assertThat(sink.events)
        .containsExactly(
            "message_start",
            "tool_status",
            "tool_status",
            "delta",
            "delta",
            "citation",
            "message_end");
    assertThat(conversations.messages("user-1", conversation.id()))
        .extracting(value -> value.role() + ":" + value.status() + ":" + value.content())
        .containsExactly("USER:COMPLETED:请联网解释增长率", "ASSISTANT:COMPLETED:第一段第二段");
  }

  @Test
  void persistsPartialAssistantAsFailedWhenStreamBreaks() {
    engine.behavior =
        listener -> {
          listener.onDelta("已收到部分内容");
          listener.onError(new IllegalStateException("第三方密钥不得外泄"));
        };
    var conversation = conversations.create("user-1", "GENERAL", null, "失败测试");
    RecordingSink sink = new RecordingSink();

    conversations.stream("user-1", "Asia/Shanghai", conversation.id(), "测试中断", 0, sink);

    assertThat(sink.events).containsExactly("message_start", "delta", "error");
    assertThat(conversations.messages("user-1", conversation.id()).getLast())
        .satisfies(
            value -> {
              assertThat(value.status()).isEqualTo("FAILED");
              assertThat(value.content()).isEqualTo("已收到部分内容");
            });
  }

  private int countMessages() {
    return jdbc.sql("select count(*) from ai_messages").query(Integer.class).single();
  }

  private static final class RecordingSink implements AiConversationService.EventSink {
    private final List<String> events = new ArrayList<>();

    @Override
    public void send(String event, Object data) {
      events.add(event);
    }

    @Override
    public void complete() {}
  }

  /** 可编排的同步 Fake，保证事件断言无需 sleep 或 JVM Agent。 */
  static final class FakeAiChatEngine implements AiChatEngine {
    private Consumer<Listener> behavior = listener -> {};

    @Override
    public void stream(
        String memoryId, String prompt, InvocationParameters parameters, Listener listener) {
      behavior.accept(listener);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeEngineConfiguration {
    @Bean
    @Primary
    FakeAiChatEngine fakeAiChatEngine() {
      return new FakeAiChatEngine();
    }
  }
}
