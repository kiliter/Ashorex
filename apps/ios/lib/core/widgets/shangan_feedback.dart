import 'dart:async';
import 'package:flutter/material.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// 右上角轻提示：避开安全区，可点击关闭，替换上一条，页面离开后自动释放。
final class ShanganFeedback {
  static OverlayEntry? _entry;

  /// 普通反馈不阻断当前操作；需要确认的行为仍使用独立确认面板。
  static void show(BuildContext context, String message, {bool error = false}) {
    dismiss();
    final overlay = Overlay.of(context, rootOverlay: true);
    late final OverlayEntry entry;
    entry = OverlayEntry(
      builder: (context) => _FeedbackLifetime(
        duration: Duration(seconds: error ? 7 : 4),
        onClose: dismiss,
        onDisposed: () {
          if (identical(_entry, entry)) _entry = null;
        },
        child: Positioned(
          top: MediaQuery.paddingOf(context).top + 12,
          right: 16,
          left: 16,
          child: Align(
            alignment: Alignment.topRight,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 360),
              child: Semantics(
                liveRegion: true,
                child: Material(
                  elevation: 5,
                  shadowColor: Colors.black.withValues(alpha: 0.12),
                  color: error
                      ? const Color(0xFFFFF4EF)
                      : const Color(0xFFF0F7F3),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(
                      color: error
                          ? const Color(0xFFEACDBD)
                          : const Color(0xFFC8DECF),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.only(left: 14, top: 6, bottom: 6),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          error
                              ? Icons.info_outline_rounded
                              : Icons.check_circle_outline_rounded,
                          size: 21,
                          color: error
                              ? ShanganColors.ochre
                              : ShanganColors.green,
                        ),
                        const SizedBox(width: 10),
                        Flexible(
                          child: Text(
                            message,
                            style: const TextStyle(
                              fontSize: 14,
                              height: 1.4,
                              color: ShanganColors.ink,
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: '关闭提示',
                          onPressed: dismiss,
                          icon: const Icon(Icons.close_rounded, size: 18),
                          constraints: const BoxConstraints(
                            minWidth: 44,
                            minHeight: 44,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    _entry = entry;
    overlay.insert(entry);
  }

  /// 手动关闭与超时共用入口，避免重复移除 Overlay。
  static void dismiss() {
    _entry?.remove();
    _entry?.dispose();
    _entry = null;
  }
}

/// 提示计时器绑定 Overlay 子树，导航容器销毁时也能立即取消。
final class _FeedbackLifetime extends StatefulWidget {
  const _FeedbackLifetime({
    required this.duration,
    required this.onClose,
    required this.onDisposed,
    required this.child,
  });
  final Duration duration;
  final VoidCallback onClose;
  final VoidCallback onDisposed;
  final Widget child;
  @override
  State<_FeedbackLifetime> createState() => _FeedbackLifetimeState();
}

final class _FeedbackLifetimeState extends State<_FeedbackLifetime> {
  Timer? _timer;
  @override
  void initState() {
    super.initState();
    _timer = Timer(widget.duration, widget.onClose);
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.onDisposed();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
