import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 考试目标管理页。
///
/// 目标只有名称、考试日期、备注与是否主目标；不绑定任何课程（见 Spec 5.2）。
final class GoalsPage extends ConsumerWidget {
  const GoalsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final goals = ref.watch(goalsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('考试目标')),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _openEditor(context, ref, null),
        child: const Icon(Icons.add),
      ),
      body: goals.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('目标加载失败：$error')),
        data: (list) => ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 90),
          children: [
            const Text(
              '目标只做倒计时看板，不与课程或待办产生关联。',
              style: TextStyle(fontSize: 12.5, color: ShanganColors.mutedInk),
            ),
            const SizedBox(height: 14),
            if (list.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(
                  child: Text(
                    '还没有目标，点右下角新建',
                    style: TextStyle(color: ShanganColors.mutedInk),
                  ),
                ),
              ),
            for (final goal in list)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: GestureDetector(
                  onTap: () => _openEditor(context, ref, goal),
                  child: ShanganCard(
                    padding: const EdgeInsets.all(14),
                    borderColor: goal.primary ? ShanganColors.blue : null,
                    child: Row(
                      children: [
                        Column(
                          children: [
                            Text(
                              '${goal.daysRemaining}',
                              style: const TextStyle(
                                fontSize: 26,
                                height: 1,
                                fontWeight: FontWeight.w800,
                                letterSpacing: -1.2,
                                fontFeatures: [FontFeature.tabularFigures()],
                              ),
                            ),
                            const Text(
                              '天',
                              style: TextStyle(
                                fontSize: 10,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      goal.name,
                                      style: const TextStyle(
                                        fontSize: 16,
                                        fontWeight: FontWeight.w800,
                                      ),
                                    ),
                                  ),
                                  if (goal.primary)
                                    const ShanganBadge(
                                      label: '主目标',
                                      tone: ShanganBadgeTone.blue,
                                    ),
                                ],
                              ),
                              const SizedBox(height: 4),
                              Text(
                                '${formatIsoDate(goal.examDate)} 周${weekdayLabel(goal.examDate)}'
                                '${goal.note.isEmpty ? '' : ' · ${goal.note}'}',
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: ShanganColors.mutedInk,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _openEditor(
    BuildContext context,
    WidgetRef ref,
    ExamGoal? goal,
  ) async {
    // 原型 6-2 是一整页编辑器（大号倒计时 + 字段 + 删除），不是弹层。
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (context) => _GoalEditorPage(goal: goal)),
    );
    if (changed ?? false) {
      ref.invalidate(goalsProvider);
    }
  }
}

/// 考试目标编辑页（原型 6-2）。
final class _GoalEditorPage extends ConsumerStatefulWidget {
  const _GoalEditorPage({this.goal});

  final ExamGoal? goal;

  @override
  ConsumerState<_GoalEditorPage> createState() => _GoalEditorPageState();
}

class _GoalEditorPageState extends ConsumerState<_GoalEditorPage> {
  late final TextEditingController _name;
  late final TextEditingController _note;
  late DateTime _examDate;
  late bool _primary;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.goal?.name ?? '');
    _note = TextEditingController(text: widget.goal?.note ?? '');
    _examDate =
        widget.goal?.examDate ?? DateTime.now().add(const Duration(days: 90));
    _primary = widget.goal?.primary ?? false;
  }

  @override
  void dispose() {
    _name.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final editing = widget.goal != null;
    final now = DateTime.now();
    final days = DateTime(
      _examDate.year,
      _examDate.month,
      _examDate.day,
    ).difference(DateTime(now.year, now.month, now.day)).inDays;
    final canSave = _name.text.trim().isNotEmpty && !_busy;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
          children: [
            Row(
              children: [
                ShanganIconButton(
                  icon: Icons.chevron_right,
                  quarterTurns: 2,
                  semanticLabel: '返回',
                  onTap: () => Navigator.of(context).pop(false),
                ),
                Expanded(
                  child: Text(
                    editing ? '编辑目标' : '新建目标',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                ShanganFilterChip(
                  label: _busy ? '保存中' : '保存',
                  selected: canSave,
                  tone: ShanganBadgeTone.blue,
                  onTap: canSave ? _save : () {},
                ),
              ],
            ),
            const SizedBox(height: 16),
            // 大号倒计时卡：58px 数字 + 考试日与星期。
            ShanganCard(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
              borderColor: ShanganColors.blue,
              child: Column(
                children: [
                  Text(
                    '距 ${_name.text.trim().isEmpty ? '考试' : _name.text.trim()}',
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.4,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '$days',
                    style: const TextStyle(
                      fontSize: 58,
                      height: 1.05,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -2.5,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                  Text(
                    '天 · ${formatIsoDate(_examDate)} 周${weekdayLabel(_examDate)}',
                    style: const TextStyle(
                      fontSize: 12.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            ShanganField(
              label: '目标名称',
              controller: _name,
              hint: '例如 注册会计师',
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 12),
            GestureDetector(
              onTap: _pickDate,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: ShanganColors.surface,
                  borderRadius: BorderRadius.circular(ShanganRadius.field),
                  border: Border.all(
                    color: ShanganColors.rule,
                    width: ShanganRadius.borderWidth,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '考试日期',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.4,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      formatIsoDate(_examDate),
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            ShanganField(
              label: '备注（可选）',
              controller: _note,
              hint: '例如 会计 / 税法 / 经济法 三科',
              minLines: 1,
              maxLines: 3,
            ),
            const SizedBox(height: 12),
            ShanganSwitchCard(
              tiles: [
                ShanganSwitchTile(
                  title: '设为主目标',
                  subtitle: '首页看板优先展示',
                  value: _primary,
                  onChanged: (value) => setState(() => _primary = value),
                ),
              ],
            ),
            if (editing) ...[
              const SizedBox(height: 16),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: ShanganColors.red,
                  side: const BorderSide(color: ShanganColors.red, width: 1.5),
                ),
                onPressed: _busy ? null : _delete,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('删除目标'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _examDate,
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 5)),
    );
    if (picked != null) setState(() => _examDate = picked);
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    final repository = ref.read(shanganRepositoryProvider);
    try {
      if (widget.goal == null) {
        await repository.createGoal(
          name: _name.text.trim(),
          examDate: _examDate,
          note: _note.text.trim(),
          primary: _primary,
        );
      } else {
        await repository.updateGoal(
          goalId: widget.goal!.id,
          name: _name.text.trim(),
          examDate: _examDate,
          note: _note.text.trim(),
          primary: _primary,
        );
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() => _busy = false);
      if (mounted) {
        ShanganFeedback.show(context, '保存失败：$error', error: true);
      }
    }
  }

  Future<void> _delete() async {
    setState(() => _busy = true);
    try {
      await ref.read(shanganRepositoryProvider).deleteGoal(widget.goal!.id);
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() => _busy = false);
      if (mounted) {
        ShanganFeedback.show(context, '删除失败：$error', error: true);
      }
    }
  }
}
