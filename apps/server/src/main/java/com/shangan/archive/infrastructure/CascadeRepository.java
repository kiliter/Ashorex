package com.shangan.archive.infrastructure;

import java.util.List;
import java.util.Map;

/** 级联删除与孤儿自检的持久化边界；所有方法都按固定顺序被调用。 */
public interface CascadeRepository {

  /** 统计课程删除会影响的行数，键为表名。 */
  Map<String, Integer> countCourseRows(String courseId);

  /** 统计用户删除会影响的行数，键为表名。 */
  Map<String, Integer> countUserRows(String userId);

  /** 课程下全部附件的存储相对路径，用于同步删除磁盘文件。 */
  List<String> courseAttachmentPaths(String courseId);

  List<String> userAttachmentPaths(String userId);

  /** 课程删除影响的用户数与累计观看毫秒，用于预检提示。 */
  ImpactSummary courseImpact(String courseId);

  /** 按 Spec 11.4 的顺序删除课程相关全部数据。 */
  Map<String, Integer> deleteCourseCascade(String courseId);

  /** 按 Spec 11.4 的顺序删除用户相关全部数据。 */
  Map<String, Integer> deleteUserCascade(String userId);

  /** 孤儿数据自检。 */
  OrphanReport scanOrphans();

  /** 已归档且超过保留期的对象数量，用于后台提醒。 */
  int countExpiredArchives(long archivedBefore);

  record ImpactSummary(int affectedUserCount, long affectedWatchedMs, long attachmentBytes) {}

  /** 四类孤儿检查结果。 */
  record OrphanReport(
      int orphanTodos,
      int orphanProgressEvents,
      int orphanAttachments,
      int orphanWatchStates,
      int orphanNags) {

    public boolean clean() {
      return orphanTodos == 0
          && orphanProgressEvents == 0
          && orphanAttachments == 0
          && orphanWatchStates == 0
          && orphanNags == 0;
    }
  }
}
