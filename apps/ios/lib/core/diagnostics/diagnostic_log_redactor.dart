/// 诊断日志脱敏：去掉凭据、口令与查询串，保留路径和业务字段。
final class DiagnosticLogRedactor {
  const DiagnosticLogRedactor();

  static final _bearer = RegExp(
    r'(Bearer\s+)[A-Za-z0-9._\-+=/]+',
    caseSensitive: false,
  );
  static final _authorization = RegExp(
    r'(Authorization\s*[:=]\s*)\S+',
    caseSensitive: false,
  );
  static final _cookie = RegExp(
    r'(Cookie\s*[:=]\s*)[^\s]+',
    caseSensitive: false,
  );
  static final _secret = RegExp(
    r'((?:password|passwd|secret|api[_-]?key|sendkey|devicekey|jwt[_-]?secret)\s*[:=]\s*)\S+',
    caseSensitive: false,
  );
  static final _jwt = RegExp(
    r'eyJ[A-Za-z0-9_\-]+=*\.[A-Za-z0-9_\-]+=*\.[A-Za-z0-9_\-]+=*',
  );
  static final _query = RegExp(r'\?[^\s]+');

  /// 将一行或一段日志中的敏感片段替换为 `[REDACTED]`。
  String redact(String text) {
    if (text.isEmpty) return text;
    var out = text;
    out = out.replaceAllMapped(_bearer, (m) => '${m[1]}[REDACTED]');
    out = out.replaceAllMapped(_authorization, (m) => '${m[1]}[REDACTED]');
    out = out.replaceAllMapped(_cookie, (m) => '${m[1]}[REDACTED]');
    out = out.replaceAllMapped(_secret, (m) => '${m[1]}[REDACTED]');
    out = out.replaceAll(_jwt, '[REDACTED]');
    return out.replaceAll(_query, '?[REDACTED]');
  }

  /// 只保留 URI 路径，丢弃查询串与片段。
  String pathOnly(String uri) {
    final parsed = Uri.tryParse(uri);
    if (parsed == null || parsed.path.isEmpty) {
      return redact(uri);
    }
    return parsed.path;
  }
}
