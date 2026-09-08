package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 扩展 SQLite 专注状态约束，原子保留 Todo 及全部子表数据。 外键开关必须在事务外生效，因此自行管理本次迁移事务，失败回滚并恢复连接配置。 */
public class V003__focus_stop_and_operation_history extends BaseJavaMigration {
  private byte[] script() {
    try (var input = getClass().getResourceAsStream("/db/support/focus_stop_v3.sql")) {
      if (input == null) throw new IllegalStateException("专注迁移资源缺失");
      return input.readAllBytes();
    } catch (java.io.IOException error) {
      throw new IllegalStateException("无法读取专注迁移资源", error);
    }
  }

  /** 资源变动参与 Flyway 校验，发布后禁止修改本迁移。 */
  @Override
  public Integer getChecksum() {
    return Arrays.hashCode(script());
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    boolean autoCommit = connection.getAutoCommit();
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = OFF");
      connection.setAutoCommit(false);
      try {
        // 受控资源只有普通 DDL/DML，没有触发器或含分号字符串；逐句执行避开块语法解析。
        String sql =
            new String(script(), StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--[^\\n]*", "");
        for (String part : sql.split(";")) {
          if (!part.isBlank()) statement.execute(part);
        }
        try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
          if (violations.next()) throw new IllegalStateException("专注迁移后存在外键异常，已回滚");
        }
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
        statement.execute("PRAGMA foreign_keys = ON");
        connection.setAutoCommit(autoCommit);
      }
    }
  }
}
