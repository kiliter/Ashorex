package com.shangan.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证 SQLite 文件数据库、必需 PRAGMA 和 Flyway 迁移能够随应用启动。 */
@SpringBootTest
class DatabaseBootstrapTest {

  @TempDir static Path databaseDirectory;

  @Autowired JdbcClient jdbc;

  /** 为测试使用独立文件数据库，避免 SQLite 内存库的多连接行为差异。 */
  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("bootstrap.db"));
  }

  @Test
  void enablesRequiredSqlitePragmasAndFlyway() {
    assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single())
        .isEqualToIgnoringCase("wal");
    assertThat(jdbc.sql("PRAGMA busy_timeout").query(Integer.class).single()).isEqualTo(5000);
    assertThat(jdbc.sql("PRAGMA synchronous").query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("select count(*) from flyway_schema_history").query(Integer.class).single())
        .isPositive();
  }
}
