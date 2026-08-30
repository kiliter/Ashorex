/// App 当前连接的服务端 Origin；业务 API 路径始终由 Repository 追加。
final class ServerConfiguration {
  const ServerConfiguration._(this.uri);

  final Uri uri;

  /// 解析并规范化用户输入，只接受不带认证信息和子路径的 HTTP/HTTPS Origin。
  factory ServerConfiguration.parse(String value) {
    final input = value.trim();
    final parsed = Uri.tryParse(input);
    if (input.isEmpty || parsed == null) {
      throw const FormatException('请输入完整的服务端地址');
    }
    final scheme = parsed.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      throw const FormatException('服务端地址必须以 http:// 或 https:// 开头');
    }
    if (!parsed.hasAuthority || parsed.host.isEmpty) {
      throw const FormatException('服务端地址缺少有效主机名或 IP');
    }
    if (parsed.userInfo.isNotEmpty) {
      throw const FormatException('服务端地址不能包含用户名或密码');
    }
    if (parsed.hasQuery || parsed.hasFragment) {
      throw const FormatException('服务端地址不能包含 Query 或 Fragment');
    }
    if (parsed.path.isNotEmpty && parsed.path != '/') {
      throw const FormatException('服务端地址不能包含 API 子路径');
    }

    final normalized = Uri(
      scheme: scheme,
      host: parsed.host,
      port: parsed.hasPort ? parsed.port : null,
    );
    return ServerConfiguration._(normalized);
  }

  String get baseUrl => uri.toString();

  /// 登录页只展示主机和端口，避免把冗长路径误认为连接目标。
  String get displayLabel => uri.authority;

  @override
  bool operator ==(Object other) =>
      other is ServerConfiguration && other.baseUrl == baseUrl;

  @override
  int get hashCode => baseUrl.hashCode;
}
