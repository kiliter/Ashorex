package com.shangan.identity.infrastructure;

import java.util.List;

/** 用户关联图的物理删除边界；实现负责处理外键无法自动覆盖的部分。 */
public interface UserDeletionRepository {

  /** 读取该用户模拟考试附件的磁盘存储路径；必须在删除数据库行之前调用。 */
  List<String> findMockExamAttachmentPaths(String userId);

  /**
   * 删除用户及其全部关联记录。
   *
   * <p>{@code debt_repayments} 没有 {@code user_id} 列，却以 RESTRICT 引用 {@code daily_plan_items} 和
   * {@code learning_debts}，因此必须先显式删除，否则删除 users 行会被外键阻断。其余表通过 {@code users} 的 CASCADE 外键清除。
   */
  void deleteUserGraph(String userId);
}
