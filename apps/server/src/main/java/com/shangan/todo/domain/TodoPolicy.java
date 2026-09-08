package com.shangan.todo.domain;

/**
 * Todo 附件与进度上报的纯规则集合。
 *
 * <p>集中这些常量与判定，避免阈值散落在各处。
 */
public final class TodoPolicy {

  /** 单个附件最大 10MB，与数据库 CHECK 一致。 */
  public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

  /** 单条 Todo 最多 9 个附件。 */
  public static final int MAX_ATTACHMENTS_PER_TODO = 9;

  /** 专注计划时长范围（秒）。 */
  public static final int MIN_PLANNED_SECONDS = 60;

  public static final int MAX_PLANNED_SECONDS = 43_200;

  private TodoPolicy() {}

  /** 允许的附件内容类型：图片与 PDF。 */
  public static boolean allowedContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("image/") || normalized.equals("application/pdf");
  }

  /**
   * 计算上报后的最远位置：单调不回退。
   *
   * <p>V2 允许客户端自由拖动，因此这里不做跳跃校验，只保证不倒退。
   */
  public static long advancePosition(long current, Long reported) {
    if (reported == null || reported < 0) {
      return current;
    }
    return Math.max(current, reported);
  }

  public static int advancePage(int current, Integer reported) {
    if (reported == null || reported < 0) {
      return current;
    }
    return Math.max(current, reported);
  }

  /** 只有前台上报的时长增量才计入累计，后台与暂停不计。 */
  public static long accumulate(long current, long delta, boolean foreground) {
    if (!foreground || delta <= 0) {
      return current;
    }
    return current + delta;
  }
}
