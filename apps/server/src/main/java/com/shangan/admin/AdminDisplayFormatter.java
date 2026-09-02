package com.shangan.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 管理后台统一日期与耗时格式，避免各模板重复拼接 UTC 时间和毫秒数。 */
public final class AdminDisplayFormatter {

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

  private AdminDisplayFormatter() {}

  /** 将数据库中的 UTC 时间转换为管理后台使用的中国标准时间。 */
  public static String dateTime(Instant value) {
    return value == null ? "—" : DATE_TIME.format(value);
  }

  /** 根据耗时大小自动使用 ms、s、m 或 h，最多保留两个可读单位。 */
  public static String duration(Long milliseconds) {
    if (milliseconds == null) return "—";
    long value = Math.max(0L, milliseconds);
    if (value < 1_000L) return value + " ms";
    if (value < 60_000L) {
      if (value % 1_000L == 0L) return value / 1_000L + " s";
      return String.format(Locale.ROOT, "%.1f s", value / 1_000.0);
    }
    long totalSeconds = value / 1_000L;
    if (value < 3_600_000L) {
      long minutes = totalSeconds / 60L;
      long seconds = totalSeconds % 60L;
      return seconds == 0L ? minutes + " m" : minutes + " m " + seconds + " s";
    }
    long hours = totalSeconds / 3_600L;
    long minutes = totalSeconds % 3_600L / 60L;
    return minutes == 0L ? hours + " h" : hours + " h " + minutes + " m";
  }

  /** 将课程媒体总时长展示为中文小时和分钟，便于管理员直接判断课程体量。 */
  public static String lessonDuration(long milliseconds) {
    long totalMinutes = Math.max(0L, milliseconds) / 60_000L;
    long hours = totalMinutes / 60L;
    long minutes = totalMinutes % 60L;
    return minutes == 0L ? hours + " 小时" : hours + " 小时 " + minutes + " 分钟";
  }
}
