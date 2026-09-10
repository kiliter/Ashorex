/// 发布版本只比较三段版本号；构建号不参与提示、不展示给用户。
final class AppRelease {
  AppRelease.fromJson(Map<String, dynamic> json)
    : version = json['version'] as String,
      notes = json['notes'] as String? ?? '',
      downloadable = json['downloadable'] == true,
      size = (json['size'] as num).toInt(),
      sha256 = json['sha256'] as String? ?? '',
      downloadPath = json['downloadPath'] as String,
      pagePath = json['pagePath'] as String;
  final String version, notes, sha256, downloadPath, pagePath;
  final bool downloadable;
  final int size;

  /// 数字分段比较，避免 2.10.0 被错误判定低于 2.9.0。
  static int compare(String left, String right) {
    List<int> parts(String version) {
      if (!RegExp(
        r'^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$',
      ).hasMatch(version)) {
        throw const FormatException('版本号格式不正确');
      }
      return version.split('.').map(int.parse).toList();
    }

    final a = parts(left), b = parts(right);
    for (var i = 0; i < 3; i++) {
      final result = a[i].compareTo(b[i]);
      if (result != 0) return result;
    }
    return 0;
  }
}
