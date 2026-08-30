package com.shangan.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.ai.application.AiChatEngine;
import com.shangan.ai.application.AiConversationService;
import com.shangan.ai.application.ReadOnlyStudyTools;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** 场景 C 验收：AI 可流式读取学习上下文，但七个工具和整条对话都不能修改业务事实。 */
@SpringBootTest
@Import(AiReadOnlyAcceptanceTest.FakeEngineConfiguration.class)
class AiReadOnlyAcceptanceTest {
  @TempDir static Path databaseDirectory;

  @Autowired AiConversationService conversations;
  @Autowired com.shangan.planning.application.DailyPlanService plans;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("ai-read-only.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql(
            "insert into users (id,username,password_hash,display_name,role,timezone,alive_check_level,day_end_local_time,enabled,created_at,updated_at) "
                + "values ('user-1','learner','x','学习者','USER','Asia/Shanghai','OFF','23:59',1,1,1)")
        .update();
    plans.addItem(
        "user-1",
        LocalDate.of(2026, 8, 30),
        new com.shangan.planning.application.DailyPlanService.ItemDraft(
            "FOCUS", "申论练习", null, null, 300, 0));
  }

  @Test
  void streamsWithoutChangingAnyBusinessTable() {
    assertThat(
            Arrays.stream(ReadOnlyStudyTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name()))
        .containsExactlyInAnyOrderElementsOf(ReadOnlyStudyTools.ALLOWED_TOOL_NAMES)
        .allMatch(name -> name.startsWith("get_") || name.startsWith("search_"));

    Map<String, Long> before = businessCounts();
    var conversation = conversations.create("user-1", "GENERAL", null, "只读验收");
    RecordingSink sink = new RecordingSink();
    conversations.stream("user-1", "Asia/Shanghai", conversation.id(), "告诉我今日计划", 0, sink);

    assertThat(sink.events).containsExactly("message_start", "tool_status", "delta", "message_end");
    assertThat(businessCounts()).isEqualTo(before);
    assertThat(jdbc.sql("select count(*) from ai_messages").query(Long.class).single())
        .isEqualTo(2);
  }

  private Map<String, Long> businessCounts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table :
        java.util.List.of(
            "users",
            "exam_goals",
            "daily_plans",
            "daily_plan_items",
            "learning_debts",
            "video_progress",
            "quiz_attempts",
            "focus_sessions",
            "daily_reports")) {
      counts.put(table, jdbc.sql("select count(*) from " + table).query(Long.class).single());
    }
    return counts;
  }

  private static final class RecordingSink implements AiConversationService.EventSink {
    private final java.util.List<String> events = new java.util.ArrayList<>();

    @Override
    public void send(String event, Object data) {
      events.add(event);
    }

    @Override
    public void complete() {}
  }

  /** Fake 只驱动冻结的 SSE 事件，不接触数据库或外部网络。 */
  static final class FakeEngine implements AiChatEngine {
    @Override
    public void stream(
        String memoryId, String prompt, InvocationParameters parameters, Listener listener) {
      listener.onToolStarted("get_today_plan_summary");
      listener.onDelta("今日有一项专注计划。");
      listener.onComplete("fake-read-only", 8, 6);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeEngineConfiguration {
    @Bean
    @Primary
    FakeEngine fakeEngine() {
      return new FakeEngine();
    }
  }
}
