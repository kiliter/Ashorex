package com.shangan.identity.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 SQLite 删除用户关联图；先清理外键无法级联的表，再由 users 的 CASCADE 覆盖其余表。 */
@Repository
public class JdbcUserDeletionRepository implements UserDeletionRepository {

  private final JdbcClient jdbc;

  public JdbcUserDeletionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> findMockExamAttachmentPaths(String userId) {
    return jdbc.sql("select storage_path from mock_exam_attachments where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .list();
  }

  @Override
  public void deleteUserGraph(String userId) {
    // debt_repayments 以 RESTRICT 引用 daily_plan_items 与 learning_debts，且自身没有 user_id，
    // 不显式删除会让后面的 delete users 因外键约束失败。
    jdbc.sql(
            """
            delete from debt_repayments
            where debt_id in (select id from learning_debts where user_id = :userId)
               or plan_item_id in (
                    select id from daily_plan_items
                    where plan_id in (select id from daily_plans where user_id = :userId)
                  )
            """)
        .param("userId", userId)
        .update();
    // 其余 15 张用户数据表都以 ON DELETE CASCADE 引用 users，由数据库一次清除。
    jdbc.sql("delete from users where id = :userId").param("userId", userId).update();
  }
}
