import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 全屏催办页。
///
/// 不可点击外部关闭、不可返回；必须选择原因并填够字数才能提交（见 ADR-0026）。
final class FullscreenNagPage extends ConsumerStatefulWidget {
  const FullscreenNagPage({
    required this.nag,
    required this.minReasonLength,
    super.key,
  });

  final PendingNag nag;
  final int minReasonLength;

  /// 首页入口、心跳和 SSE 共用同一个展示锁，避免叠加不可关闭的全屏页。
  static final _presentations = Expando<Future<void>>('催办展示锁');

  static Future<void> show(
    BuildContext context, {
    required PendingNag nag,
    required int minReasonLength,
  }) {
    final navigator = Navigator.of(context, rootNavigator: true);
    final active = _presentations[navigator];
    if (active != null) return active;
    final future = navigator.push<void>(
      PageRouteBuilder<void>(
        opaque: true,
        barrierDismissible: false,
        pageBuilder: (context, _, _) =>
            FullscreenNagPage(nag: nag, minReasonLength: minReasonLength),
      ),
    );
    final presentation = future.whenComplete(
      () => _presentations[navigator] = null,
    );
    _presentations[navigator] = presentation;
    return presentation;
  }

  @override
  ConsumerState<FullscreenNagPage> createState() => _FullscreenNagPageState();
}

class _FullscreenNagPageState extends ConsumerState<FullscreenNagPage> {
  final _controller = TextEditingController();
  NagReasonTag? _tag;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _canSubmit =>
      (!widget.nag.requireReason ||
          (_tag != null &&
              _controller.text.trim().length >= widget.minReasonLength)) &&
      !_busy;

  @override
  Widget build(BuildContext context) => AppActivityScope(
    activity: const AppActivity('NAG', 'RESPONDING'),
    child: _buildPage(context),
  );

  /// 催办覆盖播放器时，当前位置应显示回应催办。
  Widget _buildPage(BuildContext context) {
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: ShanganColors.redSoft,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              // 横屏或键盘压缩高度时保留表单可用空间，并允许整页滚动到提交入口。
              final minimumHeight =
                  560 * MediaQuery.textScalerOf(context).scale(14) / 14;
              final contentHeight = constraints.maxHeight < minimumHeight
                  ? minimumHeight
                  : constraints.maxHeight;
              return SingleChildScrollView(
                child: SizedBox(
                  height: contentHeight,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 20, 20, 20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Center(
                          child: Container(
                            width: 70,
                            height: 70,
                            decoration: BoxDecoration(
                              color: ShanganColors.red,
                              borderRadius: BorderRadius.circular(24),
                            ),
                            child: const Icon(
                              Icons.warning_amber_rounded,
                              color: Colors.white,
                              size: 34,
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          '你已经 ${widget.nag.idleMinutes} 分钟没动了',
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            fontSize: 25,
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.6,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          widget.nag.message.isEmpty
                              ? '今天还有 ${widget.nag.pendingCount} 项没完成，必须说明原因才能继续使用。'
                              : widget.nag.message,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            fontSize: 14,
                            height: 1.65,
                            color: Color(0xFF8A3B31),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Expanded(
                          child: SingleChildScrollView(
                            child: Container(
                              padding: const EdgeInsets.all(13),
                              decoration: BoxDecoration(
                                color: ShanganColors.surface,
                                borderRadius: BorderRadius.circular(16),
                                border: Border.all(
                                  color: ShanganColors.red,
                                  width: 1.5,
                                ),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  const Text(
                                    '请选择或填写原因',
                                    style: TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w800,
                                      letterSpacing: 0.3,
                                      color: ShanganColors.red,
                                    ),
                                  ),
                                  const SizedBox(height: 10),
                                  Wrap(
                                    spacing: 7,
                                    runSpacing: 7,
                                    children: NagReasonTag.values
                                        .map(
                                          (tag) => ShanganFilterChip(
                                            label: tag.label,
                                            selected: _tag == tag,
                                            onTap: () =>
                                                setState(() => _tag = tag),
                                          ),
                                        )
                                        .toList(growable: false),
                                  ),
                                  const SizedBox(height: 10),
                                  TextField(
                                    controller: _controller,
                                    minLines: 3,
                                    maxLines: 5,
                                    onChanged: (_) => setState(() {}),
                                    decoration: const InputDecoration(
                                      hintText: '说明一下现在的情况与接下来的安排',
                                    ),
                                  ),
                                  const SizedBox(height: 9),
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
                                          '原因至少 ${widget.minReasonLength} 个字，会记入当日数据并同步给督学人',
                                          style: const TextStyle(
                                            fontSize: 11.5,
                                            fontWeight: FontWeight.w700,
                                            color: ShanganColors.red,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                  if (_error != null) ...[
                                    const SizedBox(height: 8),
                                    Text(
                                      _error!,
                                      style: const TextStyle(
                                        fontSize: 12,
                                        color: ShanganColors.red,
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        // 原型 7-1 底部的今日未完成摘要；服务端只返回条数，不返回标题。
                        ShanganCard(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 13,
                            vertical: 11,
                          ),
                          child: Row(
                            children: [
                              const Text(
                                '今日未完成',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: ShanganColors.mutedInk,
                                ),
                              ),
                              const Spacer(),
                              Text(
                                '${widget.nag.pendingCount} 项',
                                style: const TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 12),
                        FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: _canSubmit
                                ? ShanganColors.red
                                : ShanganColors.rule,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(15),
                              side: BorderSide(
                                color: _canSubmit
                                    ? ShanganColors.red
                                    : ShanganColors.rule,
                              ),
                            ),
                          ),
                          onPressed: _canSubmit ? _submit : null,
                          child: Text(_busy ? '提交中…' : '提交原因并继续'),
                        ),
                        const SizedBox(height: 9),
                        const Text(
                          '未响应会改用 Server 酱推送给督学人',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 11,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(shanganRepositoryProvider)
          .respondNag(
            widget.nag.id,
            reasonTag: _tag?.wire ?? 'TEMP_BUSY',
            reasonText: _controller.text.trim(),
          );
      ref.invalidate(dayViewProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (error) {
      setState(() {
        _busy = false;
        _error = '提交失败：$error';
      });
    }
  }
}
