import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/complete_sheet.dart';
import 'package:shangan_ios/features/home/presentation/delete_reason_dialog.dart';

/// 首页 Todo 行。
///
/// 一行内展示类型徽标、目标进度、实际进度、时长、附件数与备注摘要，
/// 尽可能提高信息密度。对应原型 1-1 的 `.todo` 结构：
/// tick + todo-main（title / meta / bar / note）+ act。
final class TodoRow extends ConsumerWidget {
  const TodoRow({
    required this.todo,
    required this.onChanged,
    this.selectable = false,
    this.selected = false,
    this.onSelect,
    this.minReasonLength = 5,
    this.supervisorName,
    this.compact = false,
    this.overdue = false,
    this.overdueColor,
    this.extraBadges = const [],
    this.readOnly = false,
    this.showCompletedTime = false,
    super.key,
  });

  final TodoItem todo;
  final Future<void> Function() onChanged;
  final bool selectable;
  final bool selected;
  final ValueChanged<bool>? onSelect;
  final int minReasonLength;
  final String? supervisorName;

  /// 紧凑态：标题 14px 且不画进度条与备注块，对应原型 1-6 月视图当日列表。
  final bool compact;

  /// 逾期态：tick 改红 / 赭虚线，对应原型 1-7、1-8。
  final bool overdue;

  /// 逾期虚线的颜色；1-8 里「逾期 3 天以上」用红，「逾期 1 – 2 天」用赭。
  final Color? overdueColor;

  /// 追加到 meta 行尾的徽标，例如 1-8 的「逾期 N 天」。
  final List<Widget> extraBadges;

  /// 只读态：不渲染行尾操作按钮，对应原型 1-7、1-9 的历史已完成行。
  final bool readOnly;

  /// 在标题前加完成时刻，对应原型 1-9「当日时间轴」。
  final bool showCompletedTime;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final body = _TodoBody(
      todo: todo,
      compact: compact,
      showCompletedTime: showCompletedTime,
      extraBadges: [
        if (todo.review)
          const ShanganBadge(label: '复习', tone: ShanganBadgeTone.ochre),
        ...extraBadges,
      ],
    );
    return Padding(
      padding: EdgeInsets.symmetric(vertical: compact ? 11 : 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (selectable)
            Padding(
              // 原型 1-2 / 1-8 编辑态用 `.pick .box`（22px 方框），不是 Material 圆角勾选框。
              padding: const EdgeInsets.only(top: 3, right: 6),
              child: ShanganCheckBox(
                checked: selected,
                semanticLabel: '选择 ${todo.title}',
                onToggle: (value) => onSelect?.call(value),
              ),
            )
          else
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: ShanganTick(
                color: overdue ? _overdueColor : _typeColor,
                done: todo.isDone,
                half: todo.status == TodoStatus.inProgress,
                dashed: overdue,
                semanticLabel: todo.isDone ? '已完成' : '开始 ${todo.title}',
                onTap: todo.isDone
                    ? null
                    : () => _handlePrimaryAction(context, ref),
              ),
            ),
          const SizedBox(width: 11),
          Expanded(
            child: selectable
                // 原型 `.pick` 整行可点，不要求精准点中 22px 方框。
                ? GestureDetector(
                    onTap: () => onSelect?.call(!selected),
                    behavior: HitTestBehavior.opaque,
                    child: body,
                  )
                : body,
          ),
          const SizedBox(width: 8),
          // 已完成课程保留详情箭头，独立回放按钮不影响完成与附件入口。
          if (!selectable &&
              !readOnly &&
              todo.isDone &&
              todo.todoType == TodoType.course)
            IconButton(
              tooltip: '回放',
              icon: const Icon(Icons.replay_rounded),
              onPressed: () async {
                if (!todo.resourceAvailable) {
                  _showUnavailable(context, ref);
                  return;
                }
                await context.push<bool>(
                  '/player/${todo.id}?date=${todo.localDate.toIso8601String().substring(0, 10)}',
                );
                await onChanged();
              },
            ),
          if (!selectable && !readOnly)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: _ActionButton(
                todo: todo,
                onTap: () => _handlePrimaryAction(context, ref),
              ),
            ),
        ],
      ),
    );
  }

  Color get _typeColor => switch (todo.todoType) {
    TodoType.course => ShanganColors.course,
    TodoType.focus => ShanganColors.ochre,
    TodoType.task => ShanganColors.green,
  };

  /// 逾期 3 天以上用红，1 – 2 天用赭，与 1-8 的分组底色一致。
  Color get _overdueColor => overdueColor ?? ShanganColors.red;

  /// 主动作：课程去播放，专注去计时页，待办直接走完成回填。
  Future<void> _handlePrimaryAction(BuildContext context, WidgetRef ref) async {
    if (todo.isDone) {
      final changed = await CompleteSheet.show(context, todo: todo);
      if (changed) await onChanged();
      return;
    }
    switch (todo.todoType) {
      case TodoType.course:
        if (!todo.resourceAvailable) {
          _showUnavailable(context, ref);
          return;
        }
        await context.push<bool>(
          '/player/${todo.id}?date=${todo.localDate.toIso8601String().substring(0, 10)}',
        );
        // 系统返回不带布尔结果，仍须重新读取已上报的进度。
        await onChanged();
      case TodoType.focus:
        await context.push<bool>(
          '/focus/${todo.id}?date=${todo.localDate.toIso8601String().substring(0, 10)}',
        );
        await onChanged();
      case TodoType.task:
        final done = await CompleteSheet.show(context, todo: todo);
        if (done) await onChanged();
    }
  }

  /// 课时已下架时允许正常标记完成或删除。
  Future<void> _showUnavailable(BuildContext context, WidgetRef ref) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(18),
              child: Text(
                '该课时已在 Emby 下架，无法播放。可以标记完成或删除这条待办。',
                style: TextStyle(fontSize: 14),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.check),
              title: const Text('标记完成'),
              onTap: () => Navigator.of(context).pop('complete'),
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text('删除待办'),
              onTap: () => Navigator.of(context).pop('delete'),
            ),
          ],
        ),
      ),
    );
    if (!context.mounted) return;
    if (action == 'complete') {
      final done = await CompleteSheet.show(context, todo: todo);
      if (done) await onChanged();
    } else if (action == 'delete') {
      final deleted = await DeleteReasonDialog.show(
        context,
        todos: [todo],
        minReasonLength: minReasonLength,
        supervisorName: supervisorName,
      );
      if (deleted) await onChanged();
    }
  }
}

final class _TodoBody extends StatelessWidget {
  const _TodoBody({
    required this.todo,
    required this.compact,
    required this.showCompletedTime,
    required this.extraBadges,
  });

  final TodoItem todo;
  final bool compact;
  final bool showCompletedTime;
  final List<Widget> extraBadges;

  @override
  Widget build(BuildContext context) {
    final target = todo.targetProgressPermille;
    final completedAt = todo.completedAt;
    final prefix = showCompletedTime && completedAt != null
        ? '${completedAt.hour.toString().padLeft(2, '0')}:'
              '${completedAt.minute.toString().padLeft(2, '0')} '
        : '';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '$prefix${todo.title}',
          style: TextStyle(
            fontSize: compact ? 14 : 15,
            height: 1.35,
            fontWeight: FontWeight.w700,
            color: todo.isDone ? ShanganColors.mutedInk : ShanganColors.ink,
            decoration: todo.isDone ? TextDecoration.lineThrough : null,
          ),
        ),
        const SizedBox(height: 5),
        Wrap(
          spacing: 6,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            TodoTypeBadge(type: todo.todoType),
            ...extraBadges,
            ..._metaChips(),
          ],
        ),
        if (!compact && todo.todoType == TodoType.course && target != null) ...[
          const SizedBox(height: 8),
          TargetProgressBar(
            value: todo.progressPermille / 1000,
            targetValue: target / 1000,
          ),
          const SizedBox(height: 4),
          Text(
            _courseHint(target),
            style: const TextStyle(fontSize: 11, color: ShanganColors.mutedInk),
          ),
        ],
        if (!compact && todo.note.isNotEmpty) ...[
          const SizedBox(height: 7),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
            decoration: BoxDecoration(
              color: ShanganColors.inkSoft,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.sticky_note_2_outlined,
                  size: 14,
                  color: ShanganColors.mutedInk,
                ),
                const SizedBox(width: 5),
                Expanded(
                  child: Text(
                    todo.note,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 11.5,
                      height: 1.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  /// 原型 1-1「已看 21 分 · 还需 2:25 达标」：剩余量按时间给，不按百分比。
  String _courseHint(int target) {
    if (todo.isDone) {
      return '已完成 · 累计 ${formatDurationCompact(todo.watchedMs)}';
    }
    final duration = todo.resourceDurationMs;
    // 短时观看保留秒数，避免已有播放记录仍显示为零分钟。
    final watched = '已看 ${formatPosition(todo.watchedMs)}';
    if (duration == null || duration <= 0) {
      return '$watched · 还需 ${todo.remainingPermille ~/ 10}% 达标';
    }
    // 用原始毫秒计算差额，不能先取整千分比再反推，避免与播放位置相差数秒。
    final targetMs = (duration * target / 1000).ceil();
    final remainingMs = (targetMs - todo.progressPositionMs).clamp(0, targetMs);
    return '$watched · 还需 ${formatPosition(remainingMs)} 达标';
  }

  List<Widget> _metaChips() {
    final chips = <Widget>[];
    switch (todo.todoType) {
      case TodoType.course:
        if (todo.isDone) {
          chips.add(
            ShanganMeta(
              '看完 ${todo.progressPermille ~/ 10}% · '
              '${formatDurationCompact(todo.watchedMs)}',
            ),
          );
        } else {
          final duration = todo.resourceDurationMs;
          chips.add(
            ShanganMeta(
              '${formatPosition(todo.progressPositionMs)}'
              '${duration == null ? '' : ' / ${formatPosition(duration)}'}',
              icon: Icons.play_arrow_rounded,
            ),
          );
        }
        final target = todo.targetProgressPermille;
        if (target != null && !todo.isDone) {
          chips.add(
            ShanganBadge(
              label: '目标 ${target ~/ 10}%',
              tone: ShanganBadgeTone.blue,
            ),
          );
        }
        if (!todo.resourceAvailable) {
          chips.add(
            const ShanganBadge(label: '课时已下架', tone: ShanganBadgeTone.red),
          );
        }
      case TodoType.focus:
        final plannedMs = (todo.plannedSeconds ?? 0) * 1000;
        if (todo.isDone) {
          chips.add(
            ShanganMeta('${formatDurationCompact(todo.focusedMs)} · 已完成'),
          );
        } else {
          // 原型 1-1「剩 12:30 / 25:00」：先给剩余，再给总时长。
          final remaining = (plannedMs - todo.focusAttemptMs).clamp(
            0,
            plannedMs,
          );
          chips.add(
            ShanganMeta(
              '剩 ${formatPosition(remaining)} / ${formatPosition(plannedMs)}',
              icon: Icons.timer_outlined,
            ),
          );
        }
        if (todo.focusState == FocusState.running) {
          chips.add(
            const ShanganBadge(label: '运行中', tone: ShanganBadgeTone.blue),
          );
        } else if (todo.focusState == FocusState.paused) {
          chips.add(
            const ShanganBadge(label: '已暂停', tone: ShanganBadgeTone.ochre),
          );
        } else if (todo.focusState == FocusState.stopped) {
          chips.add(
            const ShanganBadge(label: '已停止', tone: ShanganBadgeTone.ochre),
          );
        } else if (todo.focusState == FocusState.abandoned) {
          chips.add(
            const ShanganBadge(label: '已跳过', tone: ShanganBadgeTone.ochre),
          );
        }
      case TodoType.task:
        final completedAt = todo.completedAt;
        if (todo.isDone && completedAt != null) {
          chips.add(
            ShanganMeta(
              '${completedAt.hour.toString().padLeft(2, '0')}:'
              '${completedAt.minute.toString().padLeft(2, '0')} 完成',
            ),
          );
        } else if (todo.requireEvidence) {
          chips.add(
            const ShanganMeta('需完成凭证', icon: Icons.photo_camera_outlined),
          );
        }
    }
    if (todo.attachmentCount > 0) {
      chips.add(
        ShanganMeta('${todo.attachmentCount}', icon: Icons.attach_file),
      );
    }
    if (todo.backfilled) {
      chips.add(const ShanganBadge(label: '补记', tone: ShanganBadgeTone.ochre));
    }
    return chips;
  }
}

/// 行尾操作按钮，对应原型 `.act`：课程播放、专注暂停/开始、待办勾选。
final class _ActionButton extends StatelessWidget {
  const _ActionButton({required this.todo, required this.onTap});

  final TodoItem todo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    if (todo.isDone) {
      // 已完成行只提供进入回填详情的箭头，用 flat 档位。
      return ShanganActButton(
        icon: Icons.chevron_right,
        tone: ShanganActTone.flat,
        onTap: onTap,
        semanticLabel: '查看 ${todo.title}',
      );
    }
    final (IconData icon, ShanganActTone tone) = switch (todo.todoType) {
      TodoType.course => (Icons.play_arrow_rounded, ShanganActTone.course),
      TodoType.focus => (
        todo.focusState == FocusState.running
            ? Icons.pause
            : Icons.play_arrow_rounded,
        ShanganActTone.focus,
      ),
      TodoType.task => (Icons.check, ShanganActTone.task),
    };
    return ShanganActButton(
      icon: icon,
      tone: tone,
      onTap: onTap,
      semanticLabel: '执行 ${todo.title}',
    );
  }
}
