package com.shangan.catalog.domain;

import java.time.LocalDate;
import java.util.List;

/** 课程物理删除的安全影响快照；只包含数量、日期和受控文件标识，不包含课程正文或媒体路径。 */
public final class CourseDeletionGraph {

  private CourseDeletionGraph() {}

  /** 二次确认页面展示的关联记录数量。 */
  public record Impact(
      int courseCount,
      int lessonCount,
      int sourceMappingCount,
      int courseBindingCount,
      int planItemCount,
      int debtCount,
      int watchSessionCount,
      int videoProgressCount,
      int questionCount,
      int quizAttemptCount,
      int studyContentCount,
      int contentJobCount,
      int quizDraftCount,
      int reviewEventCount,
      int focusSessionCount,
      int attachmentCount,
      int derivedSnapshotCount) {}

  /** 删除前收集的用户自然日，用于提交后重新生成日报和日终结果。 */
  public record AffectedDay(String userId, LocalDate date, String timezone) {}

  /** 数据库删除完成后需要继续处理的受控文件和派生数据。 */
  public record DeletionResult(List<String> attachmentPaths, List<AffectedDay> affectedDays) {
    public DeletionResult {
      attachmentPaths = List.copyOf(attachmentPaths);
      affectedDays = List.copyOf(affectedDays);
    }
  }
}
