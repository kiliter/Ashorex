package com.shangan.exam.domain;

import java.time.LocalDate;
import java.util.List;

/** 用户唯一活动考试目标；日期均为用户时区下的自然日。 */
public record ExamGoal(
    String id,
    String userId,
    String name,
    LocalDate examDate,
    LocalDate targetCompletionDate,
    int reviewBufferDays,
    String timezone,
    List<String> courseIds) {

  public ExamGoal {
    courseIds = List.copyOf(courseIds);
  }
}
