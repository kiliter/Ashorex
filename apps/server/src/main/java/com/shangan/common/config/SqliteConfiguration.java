package com.shangan.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** 创建单实例 SQLite 数据源，并确保每个连接都启用冻结规范要求的 PRAGMA。 */
@Configuration(proxyBeanMethods = false)
public class SqliteConfiguration {

  /**
   * 使用 SQLite 原生配置初始化底层连接，再交由 Hikari 管理小型连接池。
   *
   * @param jdbcUrl SQLite 文件 JDBC URL
   * @param maximumPoolSize 最大连接数，V1 固定不超过 4
   * @param connectionTimeoutMs 获取连接的最长等待时间
   * @return 配置完毕的数据源
   */
  @Bean(destroyMethod = "close")
  DataSource dataSource(
      @Value("${spring.datasource.url}") String jdbcUrl,
      @Value("${spring.datasource.hikari.maximum-pool-size:4}") int maximumPoolSize,
      @Value("${spring.datasource.hikari.connection-timeout:5000}") long connectionTimeoutMs) {
    SQLiteConfig sqliteConfig = new SQLiteConfig();
    sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
    sqliteConfig.enforceForeignKeys(true);
    sqliteConfig.setBusyTimeout(5000);
    sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    // 写事务在 BEGIN 时取锁并等待 busy_timeout，避免 DEFERRED 升级时立刻 SQLITE_BUSY_SNAPSHOT。
    sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    // 禁用 SQLiteConfig#setExplicitReadOnly：与 IMMEDIATE 组合时，readOnly 事务会给物理连接
    // 打上 PRAGMA query_only=true 且无法复位，连接归还 Hikari 后所有写入都报 SQLITE_READONLY
    // （2026-09-10 事故）。不启用时 Spring 的 readOnly 标记由驱动静默降级，不影响正确性。

    SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqliteConfig);
    sqliteDataSource.setUrl(jdbcUrl);

    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setDataSource(sqliteDataSource);
    hikariConfig.setMaximumPoolSize(Math.min(maximumPoolSize, 4));
    hikariConfig.setConnectionTimeout(connectionTimeoutMs);
    hikariConfig.setPoolName("shangan-sqlite");
    // Hikari 取出连接后再设一次，避免只写在 SQLiteConfig 时 native busy handler 未生效。
    hikariConfig.setConnectionInitSql("PRAGMA busy_timeout = 5000");
    return new HikariDataSource(hikariConfig);
  }
}
