package com.shangan.diagnostics.domain;

import java.util.regex.Pattern;

/**
 * 诊断日志二次脱敏。
 *
 * <p>客户端写入前已经去掉凭据，这里再处理一遍，防止旧客户端或误记把 Token、口令和查询串带上来。
 */
public final class DiagnosticLogRedactor {

  private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-+=/]+");
  private static final Pattern AUTHORIZATION =
      Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)\\S+");
  private static final Pattern COOKIE = Pattern.compile("(?i)(Cookie\\s*[:=]\\s*)[^\\s]+");
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)((?:password|passwd|secret|api[_-]?key|sendkey|devicekey|jwt[_-]?secret)\\s*[:=]\\s*)\\S+");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_\\-]+=*\\.[A-Za-z0-9_\\-]+=*\\.[A-Za-z0-9_\\-]+=*");
  private static final Pattern QUERY = Pattern.compile("\\?[^\\s]+");

  private DiagnosticLogRedactor() {}

  /** 将凭据与查询串替换为固定占位，保留路径与业务字段。 */
  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = BEARER.matcher(text).replaceAll("$1[REDACTED]");
    out = AUTHORIZATION.matcher(out).replaceAll("$1[REDACTED]");
    out = COOKIE.matcher(out).replaceAll("$1[REDACTED]");
    out = SECRET.matcher(out).replaceAll("$1[REDACTED]");
    out = JWT.matcher(out).replaceAll("[REDACTED]");
    return QUERY.matcher(out).replaceAll("?[REDACTED]");
  }
}
