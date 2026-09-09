import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/features/home/presentation/delete_reason_dialog.dart';
import 'package:shangan_ios/features/home/presentation/todo_row.dart';

/// 未完成汇总：跨日期聚合历史未完成项，按逾期天数分组（原型 1-8）。
///
/// 取代 V1 的「学习欠债」页，但不计算任何债务量（见 ADR-0025）。
final class PendingSummaryPage extends ConsumerStatefulWidget {
  const PendingSummaryPage({super.key});

  @override
  ConsumerState<PendingSummaryPage> createState() => _PendingSummaryPageState();
}

class _PendingSummaryPageState extends ConsumerState<PendingSummaryPage> {
  /// 原型 1-8 的类型筛选：全部 / 课程 / 专注 / 待办。
  TodoType? _typeFilter;

  /// 原型 1-8 右上角「编辑」：进入逐项勾选，底部批量删除动作只作用于所选项。
  bool _editing = false;
  final _selected = <String>{};

  @override
  Widget build(BuildContext context) {
    final summary = ref.watch(pendingSummaryProvider);
    final settings = ref.watch(meSettingsProvider);
    final minReasonLength = settings.maybeWhen(
      data: (value) => value.minReasonLength,
      orElse: () => 5,
    );
    return Scaffold(
      body: SafeArea(
        child: summary.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(child: Text('加载失败：$error')),
          data: (data) {
            final items = _typeFilter == null
                ? data.items
                : data.items
                      .where((item) => item.todo.todoType == _typeFilter)
                      .toList(growable: false);
            final severe = items
                .where((item) => item.severe)
                .toList(growable: false);
            final recent = items
                .where((item) => !item.severe)
                .toList(growable: false);
            // 编辑态下批量动作只作用于勾选项；非编辑态沿用「全部」语义。
            final targets = _editing
                ? items
                      .where((item) => _selected.contains(item.todo.id))
                      .toList(growable: false)
                : items;
            return RefreshIndicator(
              onRefresh: () async => ref.invalidate(pendingSummaryProvider),
              child: ListView(
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
                children: [
                  Row(
                    children: [
                      ShanganIconButton(
                        icon: Icons.chevron_right,
                        quarterTurns: 2,
                        semanticLabel: '返回',
                        onTap: () => Navigator.of(context).pop(),
                      ),
                      Expanded(
                        child: Text(
                          _editing ? '已选 ${targets.length} 项' : '未完成汇总',
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      ShanganFilterChip(
                        label: _editing ? '完成' : '编辑',
                        selected: _editing,
                        tone: ShanganBadgeTone.blue,
                        onTap: () => setState(() {
                          _editing = !_editing;
                          _selected.clear();
                        }),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  StatStrip(
                    items: [
                      StatStripItem(
                        value: '${data.total}',
                        label: '未完成',
                        highlighted: true,
                      ),
                      StatStripItem(
                        value: '${data.countByType[TodoType.course] ?? 0}',
                        label: '课程',
                      ),
                      StatStripItem(
                        value: '${data.countByType[TodoType.focus] ?? 0}',
                        label: '专注',
                      ),
                      StatStripItem(
                        value: '${data.countByType[TodoType.task] ?? 0}',
                        label: '待办',
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 7,
                    runSpacing: 7,
                    children: [
                      ShanganFilterChip(
                        label: '全部',
                        selected: _typeFilter == null,
                        onTap: () => setState(() => _typeFilter = null),
                      ),
                      for (final type in TodoType.values)
                        ShanganFilterChip(
                          label: type.label,
                          selected: _typeFilter == type,
                          onTap: () => setState(() => _typeFilter = type),
                        ),
                    ],
                  ),
                  if (data.items.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 40),
                      child: Text(
                        '历史待办都已处理完，没有欠着的事情',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: ShanganColors.mutedInk),
                      ),
                    )
                  else if (items.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 40),
                      child: Text(
                        '这个类型下没有未完成项',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: ShanganColors.mutedInk),
                      ),
                    ),
                  if (severe.isNotEmpty) ...[
                    const ShanganGroupLabel('逾期 3 天以上'),
                    _PendingGroup(
                      items: severe,
                      severe: true,
                      minReasonLength: minReasonLength,
                      onChanged: _refresh,
                      editing: _editing,
                      selected: _selected,
                      onSelect: _toggleSelection,
                    ),
                  ],
                  if (recent.isNotEmpty) ...[
                    const ShanganGroupLabel('逾期 1 – 2 天'),
                    _PendingGroup(
                      items: recent,
                      severe: false,
                      minReasonLength: minReasonLength,
                      onChanged: _refresh,
                      editing: _editing,
                      selected: _selected,
                      onSelect: _toggleSelection,
                    ),
                  ],
                  if (items.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            style: _smallStyle.copyWith(
                              foregroundColor: const WidgetStatePropertyAll(
                                ShanganColors.red,
                              ),
                              side: const WidgetStatePropertyAll(
                                BorderSide(
                                  color: ShanganColors.red,
                                  width: 1.5,
                                ),
                              ),
                            ),
                            onPressed: targets.isEmpty
                                ? null
                                : () => _purge(targets, minReasonLength),
                            icon: const Icon(Icons.delete_outline, size: 18),
                            label: Text(
                              _editing ? '删除 ${targets.length} 项' : '批量清理',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  static final _smallStyle = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(40),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  void _toggleSelection(String todoId, bool value) {
    setState(() {
      if (value) {
        _selected.add(todoId);
      } else {
        _selected.remove(todoId);
      }
    });
  }

  Future<void> _refresh() async {
    if (mounted) setState(() => _selected.clear());
    ref.invalidate(pendingSummaryProvider);
    ref.invalidate(dayViewProvider);
    ref.invalidate(statsProvider);
  }

  Future<void> _purge(List<PendingItem> items, int minReasonLength) async {
    final deleted = await DeleteReasonDialog.show(
      context,
      todos: items.map((item) => item.todo).toList(growable: false),
      minReasonLength: minReasonLength,
    );
    if (deleted) await _refresh();
  }
}

/// 一个逾期分组：卡片描边随严重度变红或用默认描边。
final class _PendingGroup extends ConsumerWidget {
  const _PendingGroup({
    required this.items,
    required this.severe,
    required this.minReasonLength,
    required this.onChanged,
    required this.editing,
    required this.selected,
    required this.onSelect,
  });

  final List<PendingItem> items;
  final bool severe;
  final int minReasonLength;
  final Future<void> Function() onChanged;

  /// 原型 1-8 编辑态：行首换成复选框，行尾操作按钮收起。
  final bool editing;
  final Set<String> selected;
  final void Function(String todoId, bool value) onSelect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ShanganCard(
      borderColor: severe ? ShanganColors.redLine : null,
      child: Column(
        children: [
          for (var index = 0; index < items.length; index++) ...[
            if (index > 0) const Divider(height: 1, color: ShanganColors.hair),
            TodoRow(
              todo: items[index].todo,
              compact: true,
              overdue: true,
              overdueColor: severe ? ShanganColors.red : ShanganColors.ochre,
              selectable: editing,
              selected: selected.contains(items[index].todo.id),
              onSelect: (value) => onSelect(items[index].todo.id, value),
              extraBadges: [
                ShanganBadge(
                  label: '逾期 ${items[index].overdueDays} 天',
                  tone: severe ? ShanganBadgeTone.red : ShanganBadgeTone.ochre,
                ),
              ],
              minReasonLength: minReasonLength,
              onChanged: onChanged,
            ),
          ],
        ],
      ),
    );
  }
}
