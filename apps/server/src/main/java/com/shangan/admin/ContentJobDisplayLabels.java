package com.shangan.admin;

import com.shangan.ai.content.domain.ContentGenerationJob;

/** 内容任务管理页面专用显示文本，集中避免模板和 JavaScript 重复维护枚举映射。 */
public final class ContentJobDisplayLabels {

  private ContentJobDisplayLabels() {}

  /** 将持久化日志阶段转换为中文；未知阶段保留原值以便排查新版本日志。 */
  public static String stage(String stage) {
    if (stage == null || stage.isBlank()) return "未知阶段";
    try {
      return ContentGenerationJob.Status.valueOf(stage).label();
    } catch (IllegalArgumentException exception) {
      return stage;
    }
  }

  /** 将日志级别转换为中文，同时保留前端样式判断所需的原始级别。 */
  public static String level(String level) {
    return switch (level) {
      case "INFO" -> "信息";
      case "WARN" -> "警告";
      case "ERROR" -> "错误";
      default -> level == null || level.isBlank() ? "未知" : level;
    };
  }
}
