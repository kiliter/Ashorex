package com.shangan.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 验证 V022 补齐默认预置后，V024 以追加迁移统一历史复合 ID。 */
class V022MigrationIntegrationTest {

  @TempDir Path databaseDirectory;

  /** 先执行不可修改的 V022，再升级到 V024 并核对名称、时长和 UUID。 */
  @Test
  void backfillsDefaultExamPresetsWithUuidIdentifiers() {
    String jdbcUrl = "jdbc:sqlite:" + databaseDirectory.resolve("v022-upgrade.db");
    Flyway.configure().dataSource(jdbcUrl, null, null).target("21").load().migrate();
    JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl));
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-1','legacy','hash','历史用户','USER','Asia/Shanghai','NORMAL','23:59',1,1,1)
            """)
        .update();

    Flyway.configure().dataSource(jdbcUrl, null, null).target("22").load().migrate();
    assertThat(
            jdbc.sql("select id from mock_exam_presets where user_id='user-1' order by sort_order")
                .query(String.class)
                .list())
        .containsExactly(
            "user-1:default-exam:xingce",
            "user-1:default-exam:shenlun",
            "user-1:default-exam:essay");

    Flyway.configure().dataSource(jdbcUrl, null, null).target("24").load().migrate();

    var presets =
        jdbc.sql(
                "select id,name,duration_seconds from mock_exam_presets "
                    + "where user_id='user-1' order by sort_order")
            .query()
            .listOfRows();
    assertThat(presets)
        .extracting(row -> row.get("name"), row -> row.get("duration_seconds"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("行测", 7_200),
            org.assertj.core.groups.Tuple.tuple("申论", 10_800),
            org.assertj.core.groups.Tuple.tuple("大作文", 10_800));
    assertThat(presets)
        .allSatisfy(
            row ->
                assertThat((String) row.get("id"))
                    .matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
  }
}
