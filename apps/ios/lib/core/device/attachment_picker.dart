import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

/// 附件来源，对应原型 3-3「添加」展开的三个入口与 3-4 的「拍照 / 选文件」。
enum AttachmentSource {
  /// 相机现拍（原型 3-2「提前拍照」、3-4「拍照」）。
  camera,

  /// 相册选图。
  photoLibrary,

  /// 系统文件选择器，用于 PDF 与相册外的图片（原型 3-4「选文件」）。
  file,
}

/// 用户已选好、可直接交给服务端的附件字节。
final class PickedAttachment {
  const PickedAttachment({
    required this.filename,
    required this.contentType,
    required this.bytes,
  });

  final String filename;
  final String contentType;
  final Uint8List bytes;
}

/// 附件选择器抽象。
///
/// 抽成接口是因为相机与文件选择器都走平台通道：「完成凭证必填」是关键确认流程，
/// 必须能在 Widget 测试里注入假实现验证，不能依赖真机。
abstract interface class AttachmentPicker {
  /// 返回 null 表示用户取消，或当前环境不提供该来源。
  Future<PickedAttachment?> pick(AttachmentSource source);
}

/// 生产实现：图片走 image_picker，其他文件走 file_picker。
final class PlatformAttachmentPicker implements AttachmentPicker {
  const PlatformAttachmentPicker();

  /// 与服务端 `TodoPolicy` 白名单保持一致（`image/*` 与 `application/pdf`），
  /// 提前收窄选择范围，避免用户选完才被 `ATTACHMENT_TYPE_UNSUPPORTED` 拒回。
  static const _allowedExtensions = <String>[
    'jpg',
    'jpeg',
    'png',
    'webp',
    'gif',
    'heic',
    'heif',
    'pdf',
  ];

  @override
  Future<PickedAttachment?> pick(AttachmentSource source) {
    return switch (source) {
      AttachmentSource.camera => _pickImage(ImageSource.camera),
      AttachmentSource.photoLibrary => _pickImage(ImageSource.gallery),
      AttachmentSource.file => _pickFile(),
    };
  }

  /// 长边压到 2400 并按 85 质量重编码：10MB 上限仍由服务端裁决，
  /// 客户端只做一次常规压缩，避免手机原图直接超限。
  Future<PickedAttachment?> _pickImage(ImageSource source) async {
    final file = await ImagePicker().pickImage(
      source: source,
      maxWidth: 2400,
      maxHeight: 2400,
      imageQuality: 85,
    );
    if (file == null) return null;
    return PickedAttachment(
      filename: file.name,
      contentType: resolveAttachmentContentType(file.name, file.mimeType),
      bytes: await file.readAsBytes(),
    );
  }

  Future<PickedAttachment?> _pickFile() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: _allowedExtensions,
    );
    if (file == null) return null;
    return PickedAttachment(
      filename: file.name,
      contentType: resolveAttachmentContentType(file.name, null),
      bytes: await file.readAsBytes(),
    );
  }
}

/// 默认实现：不接触任何平台通道。
///
/// 生产环境在启动装配里覆盖为 [PlatformAttachmentPicker]；Widget 测试用假实现覆盖。
final class UnavailableAttachmentPicker implements AttachmentPicker {
  const UnavailableAttachmentPicker();

  @override
  Future<PickedAttachment?> pick(AttachmentSource source) async => null;
}

final attachmentPickerProvider = Provider<AttachmentPicker>(
  (ref) => const UnavailableAttachmentPicker(),
);

/// 推断上传用的 Content-Type。
///
/// Dio 默认按 `application/octet-stream` 发送，而服务端只接受图片与 PDF，
/// 因此必须显式给出类型：优先采用平台报告值，其次按扩展名推断，
/// 推不出来时保持 `application/octet-stream`，交给服务端裁决并回中文错误。
String resolveAttachmentContentType(String filename, String? reported) {
  final normalized = reported?.trim().toLowerCase() ?? '';
  if (normalized.startsWith('image/') || normalized == 'application/pdf') {
    return normalized;
  }
  final dot = filename.lastIndexOf('.');
  final extension = dot < 0 ? '' : filename.substring(dot + 1).toLowerCase();
  return switch (extension) {
    'jpg' || 'jpeg' => 'image/jpeg',
    'png' => 'image/png',
    'webp' => 'image/webp',
    'gif' => 'image/gif',
    'heic' => 'image/heic',
    'heif' => 'image/heif',
    'pdf' => 'application/pdf',
    _ => 'application/octet-stream',
  };
}
