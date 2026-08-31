package com.shangan.catalog.domain;

import java.time.Instant;

/** 一集课程由管理员维护的完整全文和 Markdown 摘要。 */
public record LessonStudyContent(
    String id,
    String mediaItemId,
    String fullText,
    String summaryMarkdown,
    Instant importedAt,
    Instant updatedAt) {}
