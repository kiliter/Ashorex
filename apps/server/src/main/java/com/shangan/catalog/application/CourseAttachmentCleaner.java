package com.shangan.catalog.application;

import java.util.List;

/** 数据库事务提交后清理课程关联的受控附件文件。 */
@FunctionalInterface
public interface CourseAttachmentCleaner {
  void delete(List<String> storagePaths);
}
