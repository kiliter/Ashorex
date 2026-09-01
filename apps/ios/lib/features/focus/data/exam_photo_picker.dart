import 'package:flutter/services.dart';

/// 原生图片选择结果；二进制只在用户主动上传时短暂驻留内存。
final class ExamPhotoData {
  const ExamPhotoData({required this.filename, required this.bytes});

  final String filename;
  final Uint8List bytes;
}

/// iOS 原生相机/相册桥接，业务页面不直接接触 MethodChannel。
final class ExamPhotoPicker {
  const ExamPhotoPicker();

  static const _channel = MethodChannel('com.shangan/exam-photo-picker');

  Future<ExamPhotoData?> pick({required bool camera}) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'pickImage',
      {'source': camera ? 'camera' : 'library'},
    );
    if (result == null) return null;
    final bytes = result['bytes'];
    if (bytes is! Uint8List) {
      throw const FormatException('原生图片选择结果缺少二进制内容');
    }
    return ExamPhotoData(
      filename: result['filename'] as String? ?? '试卷照片.jpg',
      bytes: bytes,
    );
  }
}
