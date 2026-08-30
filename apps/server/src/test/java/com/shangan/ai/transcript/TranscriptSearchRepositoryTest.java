package com.shangan.ai.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证 FTS5 索引、同步触发器和媒体范围过滤。 */
@SpringBootTest
class TranscriptSearchRepositoryTest {
  @TempDir static Path databaseDirectory;

  @Autowired JdbcClient jdbc;
  @Autowired TranscriptSearchRepository transcripts;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("transcript-search.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from transcript_segments").update();
    jdbc.sql("delete from media_items").update();
    jdbc.sql("delete from courses").update();
    jdbc.sql(
            "insert into courses (id,name,emby_parent_item_id,created_at,updated_at) "
                + "values ('course-1','行测','parent-1',1,1)")
        .update();
    jdbc.sql(
            "insert into media_items (id,course_id,emby_item_id,title,duration_ms,created_at,updated_at) values "
                + "('media-1','course-1','emby-1','资料分析',1200000,1,1),"
                + "('media-2','course-1','emby-2','判断推理',1200000,1,1)")
        .update();
  }

  @Test
  void searchesTimestampedSegmentAndKeepsFtsTriggersSynchronized() {
    insert("segment-1", "media-1", 0, 0, 20_000, "增长率基期量计算方法");
    insert("segment-2", "media-1", 1, 20_000, 40_000, "判断推理图形规律");
    insert("segment-3", "media-2", 0, 0, 20_000, "增长率是另一门课");

    var matches = transcripts.search("media-1", "增长率", 8);

    assertThat(matches)
        .singleElement()
        .satisfies(
            match -> {
              assertThat(match.segmentId()).isEqualTo("segment-1");
              assertThat(match.startMs()).isZero();
              assertThat(match.endMs()).isEqualTo(20_000);
              assertThat(match.text()).contains("增长率");
            });

    jdbc.sql("update transcript_segments set text='现期量计算' where id='segment-1'").update();
    assertThat(transcripts.search("media-1", "增长率", 8)).isEmpty();
    assertThat(transcripts.search("media-1", "现期量", 8)).hasSize(1);

    jdbc.sql("delete from transcript_segments where id='segment-1'").update();
    assertThat(transcripts.search("media-1", "现期量", 8)).isEmpty();
  }

  private void insert(
      String id, String mediaItemId, int index, long startMs, long endMs, String text) {
    jdbc.sql(
            "insert into transcript_segments "
                + "(id,media_item_id,segment_index,start_ms,end_ms,text,created_at) "
                + "values (:id,:media,:index,:start,:end,:text,1)")
        .param("id", id)
        .param("media", mediaItemId)
        .param("index", index)
        .param("start", startMs)
        .param("end", endMs)
        .param("text", text)
        .update();
  }
}
