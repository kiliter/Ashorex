package com.shangan.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.ai.application.VideoContextBuilder;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 使用真实 SQLite FTS 验证视频上下文内容预算、时间范围和媒体隔离。 */
@SpringBootTest
class VideoContextBuilderTest {
  @TempDir static Path databaseDirectory;

  @Autowired JdbcClient jdbc;
  @Autowired VideoContextBuilder builder;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("video-context.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @BeforeEach
  void setUp() {
    for (String table :
        java.util.List.of(
            "video_summaries",
            "video_section_summaries",
            "transcript_segments",
            "media_items",
            "courses")) {
      jdbc.sql("delete from " + table).update();
    }
    jdbc.sql(
            "insert into courses(id,name,emby_parent_item_id,created_at,updated_at) "
                + "values('course-1','行测','parent',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items(id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) values "
                + "('media-1','course-1','emby-1','资料分析',1200000,1,1),"
                + "('media-2','course-1','emby-2','判断推理',1200000,1,1)")
        .update();
    insertSegment("s1", "media-1", 0, 100_000, 120_000, "增长率计算公式");
    insertSegment("s2", "media-1", 1, 500_000, 520_000, "基期量相关例题");
    insertSegment("other", "media-2", 0, 100_000, 120_000, "另一个视频的增长率秘密");
    jdbc.sql(
            "insert into video_section_summaries(id,media_item_id,section_index,start_ms,end_ms,summary,created_at) "
                + "values('section-1','media-1',0,0,600000,'本节讲增长率',1)")
        .update();
    jdbc.sql(
            "insert into video_summaries(id,media_item_id,summary,outline_json,model_name,generated_at) "
                + "values('summary-1','media-1','全局摘要内容','[]','test-model',1)")
        .update();
  }

  @Test
  void includesSummaryNearbyTopMatchesAndSectionsWithoutCrossVideoContent() {
    var context = builder.build("media-1", 110_000, "基期量 增长率");

    assertThat(context.promptContext())
        .contains("<untrusted_transcript")
        .contains("全局摘要内容")
        .contains("本节讲增长率")
        .contains("增长率计算公式")
        .contains("基期量相关例题")
        .doesNotContain("另一个视频");
    assertThat(context.citations()).allMatch(value -> value.positionMs() != null);
  }

  private void insertSegment(
      String id, String mediaId, int index, long startMs, long endMs, String text) {
    jdbc.sql(
            "insert into transcript_segments(id,media_item_id,segment_index,start_ms,end_ms,text,created_at) "
                + "values(:id,:media,:index,:start,:end,:text,1)")
        .param("id", id)
        .param("media", mediaId)
        .param("index", index)
        .param("start", startMs)
        .param("end", endMs)
        .param("text", text)
        .update();
  }
}
