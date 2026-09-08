import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/device/attachment_picker.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 服务端 `TodoPolicy.MAX_ATTACHMENTS_PER_TODO`；达到上限后隐藏添加入口。
const int kMaxAttachmentsPerTodo = 9;

/// 附件区布局，两种都来自原型。
enum AttachmentEditorLayout {
  /// 原型 3-3 底部弹层：64x64 缩略图 + 单个虚线「添加」块。
  sheet,

  /// 原型 3-4 居中对话框：60x60 缩略图 + 「拍照」「选文件」两个虚线块。
  dialog,
}

/// Todo 附件区：真实清单 + 上传 + 预览 + 删除。
///
/// 这是「完成凭证必填」（`requireEvidence`）在客户端唯一的解除入口：
/// 没有任何附件时服务端完成接口会返回 `TODO_EVIDENCE_REQUIRED`。
/// 大小、类型与数量三道限制全部由服务端裁决，这里只把错误码翻成中文。
final class AttachmentEditor extends ConsumerStatefulWidget {
  const AttachmentEditor({
    required this.todoId,
    required this.layout,
    required this.onCountChanged,
    this.highlightMissing = false,
    super.key,
  });

  final String todoId;
  final AttachmentEditorLayout layout;

  /// 清单发生变化时回报真实数量，供外层门控「保存并完成 / 确认完成」按钮。
  final ValueChanged<int> onCountChanged;

  /// 凭证必填且尚未满足时为真：原型 3-4 把「拍照」块的虚线描边转红。
  final bool highlightMissing;

  @override
  ConsumerState<AttachmentEditor> createState() => _AttachmentEditorState();
}

class _AttachmentEditorState extends ConsumerState<AttachmentEditor> {
  /// null 表示清单还没成功拉到；此时不改写外层门控计数。
  List<TodoAttachment>? _attachments;
  final _thumbnails = <String, Uint8List>{};
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_reload());
  }

  @override
  Widget build(BuildContext context) {
    final attachments = _attachments ?? const <TodoAttachment>[];
    final tileSize = widget.layout == AttachmentEditorLayout.sheet
        ? 64.0
        : 60.0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 9,
          runSpacing: 9,
          children: [
            for (final attachment in attachments)
              _AttachmentThumb(
                attachment: attachment,
                size: tileSize,
                bytes: _thumbnails[attachment.id],
                onTap: _busy ? null : () => unawaited(_preview(attachment)),
              ),
            if (attachments.length < kMaxAttachmentsPerTodo)
              ..._addTiles(tileSize),
          ],
        ),
        if (_busy) ...[
          const SizedBox(height: 8),
          const Row(
            children: [
              SizedBox(
                width: 13,
                height: 13,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(width: 6),
              Text(
                '附件处理中…',
                style: TextStyle(fontSize: 11.5, color: ShanganColors.mutedInk),
              ),
            ],
          ),
        ],
        if (_error != null) ...[
          const SizedBox(height: 8),
          Row(
            children: [
              const Icon(
                Icons.error_outline,
                size: 14,
                color: ShanganColors.red,
              ),
              const SizedBox(width: 5),
              Expanded(
                child: Text(
                  _error!,
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: ShanganColors.red,
                  ),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  /// 原型 3-3 只有一个「添加」块（点开再选来源）；3-4 直接给「拍照」「选文件」。
  List<Widget> _addTiles(double tileSize) {
    return switch (widget.layout) {
      AttachmentEditorLayout.sheet => [
        _AttachmentTile(
          icon: Icons.add,
          label: '添加',
          size: tileSize,
          highlight: widget.highlightMissing,
          onTap: _busy ? null : () => unawaited(_chooseSource()),
        ),
      ],
      AttachmentEditorLayout.dialog => [
        _AttachmentTile(
          icon: Icons.photo_camera_outlined,
          label: '拍照',
          size: tileSize,
          highlight: widget.highlightMissing,
          onTap: _busy ? null : () => unawaited(_add(AttachmentSource.camera)),
        ),
        _AttachmentTile(
          icon: Icons.attach_file,
          label: '选文件',
          size: tileSize,
          onTap: _busy ? null : () => unawaited(_add(AttachmentSource.file)),
        ),
      ],
    };
  }

  Future<void> _reload() async {
    try {
      final attachments = await ref
          .read(shanganRepositoryProvider)
          .loadAttachments(widget.todoId);
      if (!mounted) return;
      setState(() {
        _attachments = attachments;
        _error = null;
      });
      widget.onCountChanged(attachments.length);
      for (final attachment in attachments) {
        unawaited(_loadThumbnail(attachment));
      }
    } catch (error) {
      // 清单拉取失败不回报计数：否则列表接口抖动会把用户锁在「无法完成」状态。
      if (!mounted) return;
      setState(() => _error = shanganErrorMessage(error, '附件清单加载失败'));
    }
  }

  /// 缩略图必须带 Bearer Token，因此经仓库取字节后交给 `Image.memory`。
  Future<void> _loadThumbnail(TodoAttachment attachment) async {
    if (attachment.isPdf || _thumbnails.containsKey(attachment.id)) return;
    try {
      final bytes = await ref
          .read(shanganRepositoryProvider)
          .loadAttachmentBytes(widget.todoId, attachment.id);
      if (!mounted) return;
      setState(() => _thumbnails[attachment.id] = bytes);
    } catch (_) {
      // 缩略图取不到不影响附件已存在的事实，保留文件图标即可。
    }
  }

  /// 「添加」展开的来源选择，只是系统级选择列表，不是原型之外的新界面元素。
  Future<void> _chooseSource() async {
    final source = await showModalBottomSheet<AttachmentSource>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: const Text('拍照'),
              onTap: () => Navigator.of(context).pop(AttachmentSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('从相册选择'),
              onTap: () =>
                  Navigator.of(context).pop(AttachmentSource.photoLibrary),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: const Text('选文件'),
              onTap: () => Navigator.of(context).pop(AttachmentSource.file),
            ),
          ],
        ),
      ),
    );
    if (source == null) return;
    await _add(source);
  }

  Future<void> _add(AttachmentSource source) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final picked = await ref.read(attachmentPickerProvider).pick(source);
      if (picked == null) {
        if (mounted) setState(() => _busy = false);
        return;
      }
      await ref
          .read(shanganRepositoryProvider)
          .uploadAttachment(
            widget.todoId,
            filename: picked.filename,
            contentType: picked.contentType,
            bytes: picked.bytes,
          );
      await _reload();
      if (mounted) setState(() => _busy = false);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = attachmentErrorMessage(error);
      });
    }
  }

  /// 点缩略图放大预览，并在预览里提供删除；不在缩略图上叠加额外按钮。
  Future<void> _preview(TodoAttachment attachment) async {
    final shouldDelete = await showDialog<bool>(
      context: context,
      builder: (context) => _AttachmentPreviewDialog(
        attachment: attachment,
        bytes: _thumbnails[attachment.id],
      ),
    );
    if (shouldDelete == true) await _delete(attachment);
  }

  Future<void> _delete(TodoAttachment attachment) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(shanganRepositoryProvider)
          .deleteAttachment(widget.todoId, attachment.id);
      _thumbnails.remove(attachment.id);
      await _reload();
      if (mounted) setState(() => _busy = false);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = attachmentErrorMessage(error);
      });
    }
  }
}

/// 把附件相关的服务端错误码翻成中文；未知错误回退到服务端说明。
///
/// 三个错误码与服务端 `TodoAttachmentService` 一一对应，客户端不重复判定阈值。
String attachmentErrorMessage(Object error) {
  if (error is ApiException) {
    switch (error.errorCode) {
      case 'ATTACHMENT_TOO_LARGE':
        return '单个附件不能超过 10MB';
      case 'ATTACHMENT_TYPE_UNSUPPORTED':
        return '只支持图片或 PDF 附件';
      case 'ATTACHMENT_LIMIT_REACHED':
        return '单条待办最多 9 个附件';
    }
  }
  return shanganErrorMessage(error, '附件操作失败，请稍后重试');
}

/// 已上传附件缩略图（原型 3-3 左侧两个实心块）。
final class _AttachmentThumb extends StatelessWidget {
  const _AttachmentThumb({
    required this.attachment,
    required this.size,
    required this.bytes,
    required this.onTap,
  });

  final TodoAttachment attachment;
  final double size;
  final Uint8List? bytes;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final image = bytes;
    return Semantics(
      button: true,
      label: '附件 ${attachment.filename}，点按可预览或删除',
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: size,
          height: size,
          clipBehavior: Clip.antiAlias,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: attachment.isPdf
                ? ShanganColors.blueSoft
                : ShanganColors.inkSoft,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: attachment.isPdf
                  ? ShanganColors.blueLine
                  : ShanganColors.rule,
            ),
          ),
          child: image == null
              ? Icon(
                  attachment.isPdf
                      ? Icons.picture_as_pdf_outlined
                      : Icons.insert_drive_file_outlined,
                  size: 20,
                  color: attachment.isPdf
                      ? ShanganColors.course
                      : ShanganColors.mutedInk,
                )
              : Image.memory(
                  image,
                  width: size,
                  height: size,
                  fit: BoxFit.cover,
                  // 字节损坏时退回文件图标，不让整块渲染失败。
                  errorBuilder: (context, error, stackTrace) => const Icon(
                    Icons.broken_image_outlined,
                    size: 20,
                    color: ShanganColors.mutedInk,
                  ),
                ),
        ),
      ),
    );
  }
}

/// 原型 3-3 / 3-4 的虚线添加块；凭证必填未满足时描边与文字转红。
final class _AttachmentTile extends StatelessWidget {
  const _AttachmentTile({
    required this.icon,
    required this.label,
    required this.size,
    required this.onTap,
    this.highlight = false,
  });

  final IconData icon;
  final String label;
  final double size;
  final VoidCallback? onTap;
  final bool highlight;

  @override
  Widget build(BuildContext context) {
    final color = highlight ? ShanganColors.red : ShanganColors.mutedInk;
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: onTap,
        child: CustomPaint(
          painter: _DashedBoxPainter(
            color: highlight ? ShanganColors.red : ShanganColors.rule,
          ),
          child: SizedBox(
            width: size,
            height: size,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 20, color: color),
                const SizedBox(height: 2),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 9.5,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 附件预览与删除。PDF 没有内置阅读器（V2 不引入 PDF 渲染依赖），只展示文件信息。
final class _AttachmentPreviewDialog extends StatelessWidget {
  const _AttachmentPreviewDialog({
    required this.attachment,
    required this.bytes,
  });

  final TodoAttachment attachment;
  final Uint8List? bytes;

  @override
  Widget build(BuildContext context) {
    final image = bytes;
    return AlertDialog(
      title: Text(
        attachment.filename,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800),
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (image != null)
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Image.memory(
                image,
                fit: BoxFit.contain,
                errorBuilder: (context, error, stackTrace) =>
                    const Text('这个附件无法预览，仍可在服务端保留。'),
              ),
            )
          else
            Text(
              attachment.isPdf ? 'PDF 附件，App 内暂不提供阅读。' : '预览暂不可用。',
              style: const TextStyle(
                fontSize: 12.5,
                color: ShanganColors.mutedInk,
              ),
            ),
          const SizedBox(height: 8),
          Text(
            '${attachment.contentType} · ${_formatSize(attachment.sizeBytes)}',
            style: const TextStyle(
              fontSize: 11.5,
              color: ShanganColors.mutedInk,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('关闭'),
        ),
        TextButton(
          style: TextButton.styleFrom(foregroundColor: ShanganColors.red),
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('删除附件'),
        ),
      ],
    );
  }

  static String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }
}

/// 虚线圆角方框（原型 `border:1.5px dashed`）。
final class _DashedBoxPainter extends CustomPainter {
  const _DashedBoxPainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final rect = RRect.fromRectAndRadius(
      Rect.fromLTWH(0.75, 0.75, size.width - 1.5, size.height - 1.5),
      const Radius.circular(12),
    );
    final path = Path()..addRRect(rect);
    for (final metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        final next = (distance + 4).clamp(0.0, metric.length);
        canvas.drawPath(metric.extractPath(distance, next), paint);
        distance = next + 3;
      }
    }
  }

  @override
  bool shouldRepaint(_DashedBoxPainter oldDelegate) =>
      oldDelegate.color != color;
}
