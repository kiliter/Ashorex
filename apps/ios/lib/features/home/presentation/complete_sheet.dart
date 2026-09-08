import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/attachment_editor.dart';

/// 完成回填面板：附件（上传 / 预览 / 删除）+ 备注 + 一键标签。
///
/// 任何类型完成后统一弹出；一键标签会写入 `noteTags` 供数据 Tab 聚合。
final class CompleteSheet extends ConsumerStatefulWidget {
  const CompleteSheet({required this.todo, this.backfill = false, super.key});

  final TodoItem todo;

  /// 历史日期补记完成时为真，此时备注必填。
  final bool backfill;

  static Future<bool> show(
    BuildContext context, {
    required TodoItem todo,
    bool backfill = false,
  }) async {
    // 原型 3-4：待办事项走居中对话框，其余类型走 3-3 的底部弹层。
    if (todo.todoType == TodoType.task) {
      final result = await showDialog<bool>(
        context: context,
        builder: (context) => Dialog(
          insetPadding: const EdgeInsets.symmetric(horizontal: 18),
          child: SingleChildScrollView(
            child: CompleteSheet(todo: todo, backfill: backfill),
          ),
        ),
      );
      return result ?? false;
    }
    return showShanganSheet(
      context,
      heightFactor: 0.9,
      builder: (context) => CompleteSheet(todo: todo, backfill: backfill),
    );
  }

  @override
  ConsumerState<CompleteSheet> createState() => _CompleteSheetState();
}

class _CompleteSheetState extends ConsumerState<CompleteSheet> {
  final _controller = TextEditingController();
  final _selected = <NoteTag>{};
  bool _submitting = false;
  String? _error;

  /// 附件真实数量。初值取日视图快照，附件清单拉到后由 [AttachmentEditor] 回报；
  /// 清单接口失败时保持快照值，避免把用户锁在「无法完成」状态。
  late int _attachmentCount;

  @override
  void initState() {
    super.initState();
    _controller.text = widget.todo.note;
    _selected.addAll(widget.todo.noteTags);
    _attachmentCount = widget.todo.attachmentCount;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _noteSatisfied =>
      !widget.backfill || _controller.text.trim().length >= 2;

  bool get _evidenceSatisfied =>
      !widget.todo.requireEvidence || _attachmentCount > 0;

  @override
  Widget build(BuildContext context) => AppActivityScope(
    activity: AppActivity('TODO', 'BROWSING', widget.todo.id),
    child: _buildSheet(context),
  );

  /// 详情/回填弹层覆盖底层页面时上报当前待办位置。
  Widget _buildSheet(BuildContext context) {
    final todo = widget.todo;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: _typeBackground,
                    borderRadius: BorderRadius.circular(11),
                    border: Border.all(color: _typeBorder),
                  ),
                  child: Icon(_typeIcon, size: 20, color: _typeForeground),
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.backfill
                            ? '补记完成 · ${todo.title}'
                            : todo.isDone
                            ? '已完成 · ${todo.title}'
                            : todo.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      Text(
                        _subtitle(todo),
                        style: const TextStyle(
                          fontSize: 12.5,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (todo.todoType == TodoType.task)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Divider(height: 1, color: ShanganColors.rule),
              ),
            ShanganGroupLabel(
              todo.requireEvidence
                  ? '完成凭证（必填）'
                  : '附件${_attachmentCount > 0 ? ' · $_attachmentCount 个' : ''}',
              padding: const EdgeInsets.only(top: 14, bottom: 8),
            ),
            // 原型 3-3（弹层）与 3-4（对话框）的附件区，走真实上传 / 预览 / 删除。
            AttachmentEditor(
              todoId: todo.id,
              layout: todo.todoType == TodoType.task
                  ? AttachmentEditorLayout.dialog
                  : AttachmentEditorLayout.sheet,
              highlightMissing: todo.requireEvidence && !_evidenceSatisfied,
              onCountChanged: (count) {
                if (!mounted || count == _attachmentCount) return;
                setState(() => _attachmentCount = count);
              },
            ),
            if (todo.requireEvidence && !_evidenceSatisfied) ...[
              const SizedBox(height: 8),
              const Row(
                children: [
                  Icon(Icons.error_outline, size: 14, color: ShanganColors.red),
                  SizedBox(width: 5),
                  Expanded(
                    child: Text(
                      '还需上传至少 1 个凭证才能标记完成',
                      style: TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w700,
                        color: ShanganColors.red,
                      ),
                    ),
                  ),
                ],
              ),
            ] else ...[
              const SizedBox(height: 6),
              const Text(
                '单个文件 ≤ 10MB，每条 Todo 最多 9 个附件。',
                style: TextStyle(fontSize: 11, color: ShanganColors.mutedInk),
              ),
            ],
            const ShanganGroupLabel('备注'),
            TextField(
              controller: _controller,
              minLines: 2,
              maxLines: 5,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                hintText: widget.backfill ? '补记必须说明情况' : '写下这次学习的收获或问题',
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: NoteTag.values
                  .map(
                    (tag) => ShanganFilterChip(
                      label: tag.label,
                      selected: _selected.contains(tag),
                      onTap: () => setState(() {
                        if (!_selected.remove(tag)) {
                          _selected.add(tag);
                          _appendTagToNote(tag);
                        }
                      }),
                    ),
                  )
                  .toList(growable: false),
            ),
            const SizedBox(height: 8),
            const Text(
              '一键填写：点选标签会追加到备注，并写入统计维度',
              style: TextStyle(fontSize: 11, color: ShanganColors.mutedInk),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                style: const TextStyle(fontSize: 12, color: ShanganColors.red),
              ),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _submitting
                        ? null
                        : () => Navigator.of(context).pop(false),
                    child: const Text('稍后再填'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  flex: 2,
                  child: FilledButton(
                    onPressed:
                        _submitting || !_noteSatisfied || !_evidenceSatisfied
                        ? null
                        : _submit,
                    child: Text(_submitting ? '保存中…' : '保存并完成'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 顶部图标按 Todo 类型着色，对应原型 `.type-ico c/f/t`。
  Color get _typeForeground => switch (widget.todo.todoType) {
    TodoType.course => ShanganColors.course,
    TodoType.focus => ShanganColors.ochre,
    TodoType.task => ShanganColors.green,
  };

  Color get _typeBackground => switch (widget.todo.todoType) {
    TodoType.course => ShanganColors.blueSoft,
    TodoType.focus => ShanganColors.ochreSoft,
    TodoType.task => ShanganColors.greenSoft,
  };

  Color get _typeBorder => switch (widget.todo.todoType) {
    TodoType.course => ShanganColors.blueLine,
    TodoType.focus => ShanganColors.ochreLine,
    TodoType.task => ShanganColors.greenLine,
  };

  IconData get _typeIcon => switch (widget.todo.todoType) {
    TodoType.course => Icons.check,
    TodoType.focus => Icons.timer_outlined,
    TodoType.task => Icons.sticky_note_2_outlined,
  };

  String _subtitle(TodoItem todo) {
    return switch (todo.todoType) {
      TodoType.course =>
        '观看 ${todo.progressPermille ~/ 10}% · 本次 ${formatDurationCompact(todo.watchedMs)}',
      TodoType.focus => '已专注 ${formatDurationCompact(todo.focusedMs)}',
      TodoType.task => '待办事项',
    };
  }

  void _appendTagToNote(NoteTag tag) {
    final current = _controller.text.trim();
    if (current.contains(tag.label)) return;
    _controller.text = current.isEmpty
        ? '${tag.label} · '
        : '$current ${tag.label}';
    _controller.selection = TextSelection.collapsed(
      offset: _controller.text.length,
    );
  }

  Future<void> _submit() async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await ref
          .read(shanganRepositoryProvider)
          .completeTodo(
            widget.todo.id,
            note: _controller.text.trim(),
            noteTags: _selected.toList(growable: false),
            backfill: widget.backfill,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() {
        _submitting = false;
        _error = '保存失败：$error';
      });
    }
  }
}
