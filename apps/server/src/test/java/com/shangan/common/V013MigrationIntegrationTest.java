package com.shangan.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 验证 V013 能从真实 V012 结构迁移历史学习内容并删除废弃 AI 数据。 */
class V013MigrationIntegrationTest {

  @TempDir Path databaseDirectory;

  /** 先停在 V012 写入历史数据，再升级到最新版本并核对保留与删除结果。 */
  @Test
  void migratesLegacyStudyContentAndKeepsOnlyEmbySettings() {
    String jdbcUrl = "jdbc:sqlite:" + databaseDirectory.resolve("v013-upgrade.db");
    Flyway.configure().dataSource(jdbcUrl, null, null).target("12").load().migrate();
    JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl));
    insertLegacyRows(jdbc);

    Flyway.configure().dataSource(jdbcUrl, null, null).load().migrate();

    Map<String, Object> content =
        jdbc.sql(
                "select id, media_item_id, full_text, summary_markdown, imported_at, updated_at "
                    + "from lesson_study_contents")
            .query()
            .singleRow();
    assertThat(content.get("media_item_id")).isEqualTo("lesson-1");
    assertThat(content.get("full_text")).isEqualTo("第一段\n第二段");
    assertThat(content.get("summary_markdown")).isEqualTo("# 历史摘要");
    assertThat(((Number) content.get("imported_at")).longValue()).isEqualTo(1_000L);
    assertThat(((Number) content.get("updated_at")).longValue()).isEqualTo(3_000L);

    assertThat(tableExists(jdbc, "ai_messages")).isFalse();
    assertThat(tableExists(jdbc, "ai_conversations")).isFalse();
    assertThat(tableExists(jdbc, "transcription_jobs")).isFalse();
    assertThat(tableExists(jdbc, "transcript_segments")).isFalse();
    assertThat(tableExists(jdbc, "transcript_segments_fts")).isFalse();
    assertThat(tableExists(jdbc, "video_section_summaries")).isFalse();
    assertThat(tableExists(jdbc, "video_summaries")).isFalse();

    assertThat(tableColumns(jdbc, "runtime_integration_settings"))
        .containsExactly("id", "emby_base_url", "emby_api_key", "emby_user_id", "updated_at");
    Map<String, Object> settings =
        jdbc.sql("select * from runtime_integration_settings where id = 'default'")
            .query()
            .singleRow();
    assertThat(settings.get("emby_base_url")).isEqualTo("http://emby.local:8096");
    assertThat(settings.get("emby_api_key")).isEqualTo("legacy-emby-key");
    assertThat(settings.get("emby_user_id")).isEqualTo("emby-user");
    assertThat(((Number) settings.get("updated_at")).longValue()).isEqualTo(4_000L);
  }

  /** 构造 V012 中可复用的转写、摘要、聊天和四类运行时配置。 */
  private void insertLegacyRows(JdbcClient jdbc) {
    jdbc.sql(
            "insert into users (id, username, password_hash, display_name, role, timezone, "
                + "alive_check_level, day_end_local_time, enabled, created_at, updated_at) "
                + "values ('user-1', 'legacy', 'hash', '旧用户', 'USER', 'Asia/Shanghai', "
                + "'NORMAL', '23:59', 1, 1, 1)")
        .update();
    jdbc.sql(
            "insert into courses (id, name, description, emby_parent_item_id, enabled, sort_order, "
                + "created_at, updated_at) values ('course-1', '旧课程', '', 'parent-1', 1, 0, 1, 1)")
        .update();
    jdbc.sql(
            "insert into media_items (id, course_id, emby_item_id, title, duration_ms, enabled, "
                + "sort_order, available, created_at, updated_at) values "
                + "('lesson-1', 'course-1', 'emby-lesson-1', '旧课时', 60000, 1, 0, 1, 1, 1)")
        .update();
    jdbc.sql(
            "insert into transcription_jobs (id, media_item_id, status, attempt_count, "
                + "created_at, updated_at) values ('job-1', 'lesson-1', 'READY', 1, 1, 3000)")
        .update();
    jdbc.sql(
            "insert into transcript_segments (id, media_item_id, segment_index, start_ms, end_ms, "
                + "text, created_at) values "
                + "('segment-2', 'lesson-1', 1, 1000, 2000, '第二段', 2000), "
                + "('segment-1', 'lesson-1', 0, 0, 1000, '第一段', 1000)")
        .update();
    jdbc.sql(
            "insert into video_summaries (id, media_item_id, summary, outline_json, model_name, "
                + "generated_at) values ('summary-1', 'lesson-1', '# 历史摘要', '[]', 'legacy', 3000)")
        .update();
    jdbc.sql(
            "insert into ai_conversations (id, user_id, scope, media_item_id, title, created_at, "
                + "updated_at) values ('conversation-1', 'user-1', 'VIDEO', 'lesson-1', '旧会话', 1, 1)")
        .update();
    jdbc.sql(
            "insert into ai_messages (id, conversation_id, role, content, status, created_at, updated_at) "
                + "values ('message-1', 'conversation-1', 'USER', '旧消息', 'COMPLETED', 1, 1)")
        .update();
    jdbc.sql(
            "insert into runtime_integration_settings (id, emby_base_url, emby_api_key, emby_user_id, "
                + "llm_base_url, llm_api_key, llm_model, llm_max_context_tokens, llm_temperature, "
                + "llm_timeout_seconds, asr_base_url, asr_api_key, asr_model, asr_timeout_seconds, "
                + "mcp_url, mcp_bearer_token, mcp_allowed_tools, mcp_timeout_seconds, updated_at) "
                + "values ('default', 'http://emby.local:8096', 'legacy-emby-key', 'emby-user', "
                + "'https://llm.example', 'llm-key', 'model', 8192, 0.2, 60, "
                + "'https://asr.example', 'asr-key', 'asr-model', 600, "
                + "'https://mcp.example', 'mcp-token', 'search', 20, 4000)")
        .update();
  }

  /** 查询 SQLite 元数据，判断指定表或虚拟表是否仍然存在。 */
  private boolean tableExists(JdbcClient jdbc, String tableName) {
    return jdbc.sql("select count(*) from sqlite_master where type = 'table' and name = :name")
            .param("name", tableName)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** 按建表顺序返回字段名，用于证明旧 LLM、ASR 和 MCP 字段已被移除。 */
  private List<String> tableColumns(JdbcClient jdbc, String tableName) {
    return jdbc.sql("pragma table_info(" + tableName + ")")
        .query((row, number) -> row.getString("name"))
        .list();
  }
}
