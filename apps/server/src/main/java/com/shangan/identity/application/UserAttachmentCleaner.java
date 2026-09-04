package com.shangan.identity.application;

import java.util.List;

/** 删除用户后清理其遗留在磁盘上的模拟考试试卷文件。 */
public interface UserAttachmentCleaner {

  /**
   * 删除给定存储路径对应的文件。
   *
   * @param storagePaths 数据库中记录的附件存储路径，实现必须拒绝受控目录之外的路径
   */
  void delete(List<String> storagePaths);
}
