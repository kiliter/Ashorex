import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 删除原因弹窗。
///
/// 未选标签或说明不足最少字数时，确认按钮为禁用态并给出明确文案，
/// 不依赖颜色单独表达（见 Spec 7.4）。
final class DeleteReasonDialog extends ConsumerStatefulWidget {
  const DeleteReasonDialog({
    required this.todos,
    required this.minReasonLength,
    this.supervisorName,
    super.key,
  });

  final List<TodoItem> todos;
  final int minReasonLength;
  final String? supervisorName;

  /// 返回 true 表示删除已提交。
  static Future<bool> show(
    BuildContext context, {
    required List<TodoItem> todos,
    required int minReasonLength,
    String? supervisorName,
  }) async {
    final result = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => DeleteReasonDialog(
        todos: todos,
        minReasonLength: minReasonLength,
        supervisorName: supervisorName,
      ),
    );
    return result ?? false;
  }

  @override
  ConsumerState<DeleteReasonDialog> createState() => _DeleteReasonDialogState();
}

class _DeleteReasonDialogState extends ConsumerState<DeleteReasonDialog> {
  DeletionReasonTag? _tag;
  final _controller = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _canSubmit =>
      _tag != null &&
      _controller.text.trim().length >= widget.minReasonLength &&
      !_submitting;

  @override
  Widget build(BuildContext context) {
    final count = widget.todos.length;
    return AlertDialog(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: const BorderSide(color: ShanganColors.red, width: 2),
      ),
      title: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: ShanganColors.redSoft,
              borderRadius: BorderRadius.circular(11),
              border: Border.all(color: ShanganColors.redLine),
            ),
            child: const Icon(
              Icons.delete_outline,
              size: 20,
              color: ShanganColors.red,
            ),
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  count == 1 ? '删除待办' : '删除 $count 项待办',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const Text(
                  '删除后不可恢复，学习记录仍保留',
                  style: TextStyle(
                    fontSize: 11.5,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 原型 1-10 的 `.hr.strong`：rule 色分隔线，比卡内细线更重。
            const Padding(
              padding: EdgeInsets.only(bottom: 12),
              child: Divider(height: 1, color: ShanganColors.rule),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: ShanganColors.inkSoft,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final todo in widget.todos)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            todo.title,
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          Text(
                            _describe(todo),
                            style: const TextStyle(
                              fontSize: 11,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            const ShanganGroupLabel(
              '删除原因（必填）',
              padding: EdgeInsets.only(top: 14, bottom: 8),
            ),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: DeletionReasonTag.values
                  .map(
                    (tag) => ShanganFilterChip(
                      label: tag.label,
                      selected: _tag == tag,
                      onTap: () => setState(() => _tag = tag),
                    ),
                  )
                  .toList(growable: false),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _controller,
              minLines: 2,
              maxLines: 4,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(hintText: '说明一下原因'),
            ),
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
                    widget.supervisorName == null
                        ? '至少 ${widget.minReasonLength} 个字，会记入删除统计'
                        : '至少 ${widget.minReasonLength} 个字，会同步给督学人 ${widget.supervisorName}',
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
                style: const TextStyle(fontSize: 12, color: ShanganColors.red),
              ),
            ],
            const SizedBox(height: 10),
            const Text(
              '更推荐「顺延到明天」，不会计入删除统计',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: ShanganColors.mutedInk),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting
              ? null
              : () => Navigator.of(context).pop(false),
          child: const Text('取消'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: _canSubmit
                ? ShanganColors.red
                : ShanganColors.rule,
            minimumSize: const Size(120, 44),
          ),
          onPressed: _canSubmit ? _submit : null,
          child: Text(_submitting ? '删除中…' : '确认删除'),
        ),
      ],
    );
  }

  String _describe(TodoItem todo) {
    return switch (todo.todoType) {
      TodoType.course =>
        '课程 · ${todo.progressPermille ~/ 10}% · 已看 ${formatDurationCompact(todo.watchedMs)}',
      TodoType.focus =>
        '专注 · ${todo.focusState.name} · 已专注 ${formatDurationCompact(todo.focusedMs)}',
      TodoType.task => '待办 · ${todo.isDone ? '已完成' : '未完成'}',
    };
  }

  Future<void> _submit() async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    final repository = ref.read(shanganRepositoryProvider);
    try {
      if (widget.todos.length == 1) {
        await repository.deleteTodo(
          widget.todos.single.id,
          reasonTag: _tag!,
          reasonText: _controller.text.trim(),
        );
      } else {
        await repository.deleteTodos(
          widget.todos.map((todo) => todo.id).toList(growable: false),
          reasonTag: _tag!,
          reasonText: _controller.text.trim(),
        );
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() {
        _submitting = false;
        _error = '删除失败：$error';
      });
    }
  }
}
