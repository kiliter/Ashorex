package com.shangan.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 验证 V014 可从 V013 安全升级，并建立内容生成任务所需的约束。 */
class V014MigrationIntegrationTest {

  @TempDir Path databaseDirectory;

  /** 已导入的全文和摘要必须保留，同时允许后续分别更新两类内容。 */
  @Test
  void migratesStudyContentAndCreatesGenerationTables() {
    String jdbcUrl = "jdbc:sqlite:" + databaseDirectory.resolve("v014-upgrade.db");
    Flyway.configure().dataSource(jdbcUrl, null, null).target("13").load().migrate();
    JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl));
    insertV013Rows(jdbc);

    Flyway.configure().dataSource(jdbcUrl, null, null).load().migrate();

    Map<String, Object> content =
        jdbc.sql("select * from lesson_study_contents where media_item_id='lesson-1'")
            .query()
            .singleRow();
    assertThat(content.get("full_text")).isEqualTo("完整全文");
    assertThat(content.get("summary_markdown")).isEqualTo("# 已有摘要");
    assertThat(((Number) content.get("transcript_updated_at")).longValue()).isEqualTo(3_000L);
    assertThat(((Number) content.get("summary_updated_at")).longValue()).isEqualTo(3_000L);

    assertThat(tableColumns(jdbc, "content_generation_jobs"))
        .contains(
            "job_type", "status", "llm_context_length", "llm_max_completion_tokens", "error_code");
    assertThat(tableExists(jdbc, "content_generation_job_logs")).isTrue();
    assertThat(tableExists(jdbc, "llm_model_catalog")).isTrue();
    assertThat(tableExists(jdbc, "quiz_generation_drafts")).isTrue();
    assertThat(tableExists(jdbc, "quiz_generation_draft_items")).isTrue();
    assertThat(tableExists(jdbc, "quiz_generation_draft_options")).isTrue();
  }

  /** V013 的 Emby 配置升级后应补齐安全默认值，且定时补全默认关闭。 */
  @Test
  void extendsRuntimeSettingsWithDisabledAutoFillDefaults() {
    String jdbcUrl = "jdbc:sqlite:" + databaseDirectory.resolve("v014-settings.db");
    Flyway.configure().dataSource(jdbcUrl, null, null).target("13").load().migrate();
    JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl));
    jdbc.sql(
            "insert into runtime_integration_settings "
                + "(id,emby_base_url,emby_api_key,emby_user_id,updated_at) "
                + "values ('default','https://emby.example','emby-key','emby-user',4000)")
        .update();

    Flyway.configure().dataSource(jdbcUrl, null, null).load().migrate();

    Map<String, Object> settings =
        jdbc.sql("select * from runtime_integration_settings where id='default'")
            .query()
            .singleRow();
    assertThat(settings.get("emby_api_key")).isEqualTo("emby-key");
    assertThat(settings.get("asr_model")).isEqualTo("mlx-community/Qwen3-ASR-1.7B-8bit");
    assertThat(((Number) settings.get("content_auto_fill_enabled")).intValue()).isZero();
    assertThat(((Number) settings.get("content_auto_fill_interval_minutes")).intValue())
        .isEqualTo(15);
  }

  /** 同课时同类型只能存在一个未完成任务，但失败后可以重新排队。 */
  @Test
  void enforcesOneActiveJobPerLessonAndType() {
    String jdbcUrl = "jdbc:sqlite:" + databaseDirectory.resolve("v014-constraints.db");
    Flyway.configure().dataSource(jdbcUrl, null, null).load().migrate();
    JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl));
    insertCatalogRows(jdbc);

    insertJob(jdbc, "job-1", "QUEUED");
    assertThatThrownBy(() -> insertJob(jdbc, "job-2", "TRANSCRIBING"))
        .hasMessageContaining("UNIQUE constraint failed");

    jdbc.sql("update content_generation_jobs set status='FAILED' where id='job-1'").update();
    insertJob(jdbc, "job-2", "QUEUED");
    assertThat(
            jdbc.sql("select count(*) from content_generation_jobs").query(Integer.class).single())
        .isEqualTo(2);
  }

  /** 构造升级测试需要的 V013 课程、课时、内容和运行时配置。 */
  private void insertV013Rows(JdbcClient jdbc) {
    insertCatalogRows(jdbc);
    jdbc.sql(
            "insert into lesson_study_contents "
                + "(id,media_item_id,full_text,summary_markdown,imported_at,updated_at) "
                + "values ('content-1','lesson-1','完整全文','# 已有摘要',2000,3000)")
        .update();
    jdbc.sql(
            "insert into runtime_integration_settings "
                + "(id,emby_base_url,emby_api_key,emby_user_id,updated_at) "
                + "values ('default','https://emby.example','emby-key','emby-user',4000)")
        .update();
  }

  /** 构造满足外键的最小课程和课时数据。 */
  private void insertCatalogRows(JdbcClient jdbc) {
    jdbc.sql(
            "insert into courses "
                + "(id,name,description,emby_parent_item_id,enabled,sort_order,created_at,updated_at) "
                + "values ('course-1','课程','说明','parent-1',1,0,1,1)")
        .update();
    jdbc.sql(
            "insert into media_items "
                + "(id,course_id,emby_item_id,title,duration_ms,enabled,sort_order,available,created_at,updated_at) "
                + "values ('lesson-1','course-1','emby-1','课时',60000,1,0,1,1,1)")
        .update();
  }

  /** 插入最小转写任务，用于验证部分唯一索引。 */
  private void insertJob(JdbcClient jdbc, String id, String status) {
    jdbc.sql(
            "insert into content_generation_jobs "
                + "(id,course_id,media_item_id,job_type,status,queued_at,attempt,created_by) "
                + "values (:id,'course-1','lesson-1','TRANSCRIBE',:status,1000,1,'admin')")
        .param("id", id)
        .param("status", status)
        .update();
  }

  private boolean tableExists(JdbcClient jdbc, String tableName) {
    return jdbc.sql("select count(*) from sqlite_master where type='table' and name=:name")
            .param("name", tableName)
            .query(Integer.class)
            .single()
        > 0;
  }

  private List<String> tableColumns(JdbcClient jdbc, String tableName) {
    return jdbc.sql("pragma table_info(" + tableName + ")")
        .query((row, number) -> row.getString("name"))
        .list();
  }
}
