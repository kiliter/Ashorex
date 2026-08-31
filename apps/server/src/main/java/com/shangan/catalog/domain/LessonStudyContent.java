package com.shangan.catalog.domain;

import java.time.Instant;

/** 一集课程可分别持有全文和 Markdown 摘要，并保留各自更新时间。 */
public record LessonStudyContent(
    String id,
    String mediaItemId,
    String fullText,
    String summaryMarkdown,
    Instant transcriptUpdatedAt,
    Instant summaryUpdatedAt,
    Instant importedAt,
    Instant updatedAt) {

  /** ZIP 导入会同时提供全文和摘要，沿用原构造方式并同步两项更新时间。 */
  public LessonStudyContent(
      String id,
      String mediaItemId,
      String fullText,
      String summaryMarkdown,
      Instant importedAt,
      Instant updatedAt) {
    this(id, mediaItemId, fullText, summaryMarkdown, updatedAt, updatedAt, importedAt, updatedAt);
  }

  public boolean transcriptReady() {
    return fullText != null && !fullText.isBlank();
  }

  public boolean summaryReady() {
    return summaryMarkdown != null && !summaryMarkdown.isBlank();
  }
}
