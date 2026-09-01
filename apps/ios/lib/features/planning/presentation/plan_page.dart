import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 作战单编排区使用本地草稿集中编辑，只有点击“保存作战单”才会整单提交。
final class PlanPage extends ConsumerStatefulWidget {
  const PlanPage({super.key});

  @override
  ConsumerState<PlanPage> createState() => _PlanPageState();
}

final class _PlanPageState extends ConsumerState<PlanPage> {
  DailyPlanData? _plan;
  List<BattleOrderDraft> _drafts = const [];
  Map<String, PlanItemData> _savedItems = const {};
  Object? _loadError;
  bool _loading = true;
  bool _saving = false;
  bool _dirty = false;

  bool get _editable =>
      _plan == null ||
      const {'NONE', 'DRAFT', 'ACTIVE'}.contains(_plan!.status);

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _loadError = null;
      });
    }
    try {
      final plan = await ref.read(planRepositoryProvider).loadToday();
      if (!mounted) return;
      setState(() {
        _plan = plan;
        _drafts = plan.items.map(BattleOrderDraft.fromSaved).toList();
        _savedItems = {for (final item in plan.items) item.id: item};
        _dirty = false;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loadError = error;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_dirty,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _confirmDiscard();
      },
      child: Scaffold(
        appBar: AppBar(title: const Text('作战单编排区')),
        body: _buildBody(context),
        bottomNavigationBar: _editable && !_loading && _loadError == null
            ? SafeArea(
                minimum: const EdgeInsets.fromLTRB(20, 10, 20, 12),
                child: FilledButton.icon(
                  key: const Key('saveBattleOrder'),
                  onPressed: _saving || !_dirty ? null : _save,
                  icon: _saving
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.task_alt),
                  label: Text(_saving ? '正在保存' : '保存作战单'),
                ),
              )
            : null,
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading) return const ShanganLoading('正在读取今日作战单');
    if (_loadError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const ShanganNotice(
                title: '作战单读取失败',
                message: '服务暂时不可用，已有作战单不会被覆盖。',
                tone: ShanganTagTone.warning,
              ),
              const SizedBox(height: 18),
              OutlinedButton.icon(
                onPressed: _reload,
                icon: const Icon(Icons.refresh),
                label: const Text('重新加载'),
              ),
            ],
          ),
        ),
      );
    }

    final plan = _plan!;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ShanganEyebrow(
                    '${_planDate(plan.date)} · 第 ${plan.version} 版',
                  ),
                  const SizedBox(height: 7),
                  Text(
                    '把今天要完成的行动排好顺序',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                ],
              ),
            ),
            ShanganStatusTag(
              _planLabel(plan.status),
              tone: _planTone(plan.status),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          '课时和模拟考试在这里集中编排。课时严格按照课程目录顺序排列，已开始的项目不可删除。',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 18),
        const Divider(color: ShanganColors.ink, thickness: 2),
        if (!_editable) ...[
          const SizedBox(height: 14),
          const ShanganNotice(title: '今日作战单已结算', message: '结算后的项目只可查看，不能继续修改。'),
        ],
        if (_drafts.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 22),
            child: ShanganNotice(
              title: '作战单还是空的',
              message: '加入今天准备学习的课时，或选择一个模拟考试预置。',
            ),
          ),
        ..._drafts.indexed.map((entry) => _buildDraft(entry.$1, entry.$2)),
        if (_editable) ...[
          const SizedBox(height: 20),
          OutlinedButton.icon(
            key: const Key('openBattleOrderPicker'),
            onPressed: _openPicker,
            icon: const Icon(Icons.add),
            label: const Text('加入作战单'),
          ),
          const SizedBox(height: 12),
          const ShanganNotice(
            title: '复习只是快捷入口',
            message: '已学习课时会自动标记为复习，不计入进度、完成率或学习欠债。',
            tone: ShanganTagTone.success,
          ),
        ],
      ],
    );
  }

  Widget _buildDraft(int index, BattleOrderDraft draft) {
    final saved = draft.existingItemId == null
        ? null
        : _savedItems[draft.existingItemId];
    final immutable = draft.immutable;
    final progressPercent = saved == null || saved.plannedSeconds <= 0
        ? 0
        : (saved.completedSeconds * 100 ~/ saved.plannedSeconds).clamp(0, 100);
    final watchCompleted = saved != null && _planWatchCompleted(saved);

    return InkWell(
      onTap: saved == null ? null : () => _openItem(saved),
      child: Container(
        constraints: const BoxConstraints(minHeight: 92),
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: ShanganColors.rule)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 30,
              height: 30,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: ShanganColors.blueSoft,
                border: Border.all(color: ShanganColors.blue, width: 1.5),
                borderRadius: BorderRadius.circular(9),
              ),
              child: Text(
                '${index + 1}'.padLeft(2, '0'),
                style: shanganNumberStyle(
                  context,
                  fontSize: 10,
                ).copyWith(color: ShanganColors.blue),
              ),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          draft.title,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      if (immutable)
                        const Icon(
                          Icons.lock_outline,
                          size: 18,
                          color: ShanganColors.mutedInk,
                        ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  if (draft.mediaItemId != null)
                    ShanganWatchProgress(
                      progressPercent: progressPercent,
                      completed:
                          draft.itemType == 'REVIEW_SHORTCUT' || watchCompleted,
                      durationSeconds: draft.plannedSeconds,
                      meta: _itemTypeLabel(draft.itemType),
                    )
                  else
                    Text(
                      '${_itemTypeLabel(draft.itemType)} · ${shanganDuration(draft.plannedSeconds)}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                ],
              ),
            ),
            if (_editable) ...[
              const SizedBox(width: 4),
              if (!immutable)
                IconButton(
                  tooltip: '从作战单删除',
                  onPressed: () => _remove(index),
                  icon: const Icon(Icons.delete_outline),
                  color: ShanganColors.red,
                ),
            ],
          ],
        ),
      ),
    );
  }

  void _remove(int index) {
    setState(() {
      final next = [..._drafts]..removeAt(index);
      _drafts = next;
      _dirty = true;
    });
  }

  Future<void> _openPicker() async {
    final mediaIds = _drafts
        .map((item) => item.mediaItemId)
        .whereType<String>()
        .toSet();
    final presetIds = _drafts
        .map((item) => item.mockExamPresetId)
        .whereType<String>()
        .toSet();
    final immutableMediaIds = _drafts
        .where((item) => item.immutable)
        .map((item) => item.mediaItemId)
        .whereType<String>()
        .toSet();
    final immutablePresetIds = _drafts
        .where((item) => item.immutable)
        .map((item) => item.mockExamPresetId)
        .whereType<String>()
        .toSet();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (sheetContext) => _BattleOrderPickerSheet(
        catalog: ref.read(catalogRepositoryProvider),
        exams: ref.read(mockExamRepositoryProvider),
        selectedMediaIds: mediaIds,
        selectedPresetIds: presetIds,
        immutableMediaIds: immutableMediaIds,
        immutablePresetIds: immutablePresetIds,
        onOpenExamSettings: () {
          Navigator.pop(sheetContext);
          if (mounted) context.push('/mock-exam-presets');
        },
        onToggle: (draft, selected) {
          setState(() {
            final next = [..._drafts];
            if (selected) {
              next.add(draft);
            } else if (draft.mediaItemId != null) {
              next.removeWhere(
                (item) =>
                    !item.immutable && item.mediaItemId == draft.mediaItemId,
              );
            } else {
              next.removeWhere(
                (item) =>
                    !item.immutable &&
                    item.mockExamPresetId == draft.mockExamPresetId,
              );
            }
            next.sort(_compareDrafts);
            _drafts = next;
            _dirty = true;
          });
        },
      ),
    );
  }

  Future<void> _save() async {
    final plan = _plan;
    if (plan == null || _saving) return;
    setState(() => _saving = true);
    try {
      final saved = await ref
          .read(planRepositoryProvider)
          .saveToday(expectedVersion: plan.version, items: _drafts);
      if (!mounted) return;
      setState(() {
        _plan = saved;
        _drafts = saved.items.map(BattleOrderDraft.fromSaved).toList();
        _savedItems = {for (final item in saved.items) item.id: item};
        _dirty = false;
      });
      _showSaveMessage('作战单已保存为第 ${saved.version} 版');
    } catch (error) {
      if (!mounted) return;
      _showSaveMessage('保存失败，请重新加载后再试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// 保存提示始终只有一个；连续操作时新提示替换旧提示，不进入 SnackBar 等待队列。
  void _showSaveMessage(String message) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.clearSnackBars();
    messenger.showSnackBar(
      SnackBar(duration: const Duration(seconds: 2), content: Text(message)),
    );
  }

  Future<void> _confirmDiscard() async {
    final discard = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('放弃未保存修改？'),
        content: const Text('返回后，本次对作战单的调整不会保留。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('继续编辑'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('放弃修改'),
          ),
        ],
      ),
    );
    if (discard == true && mounted) context.pop();
  }

  /// 已保存项目才可进入执行页面，新加入项目必须先保存以取得服务端项目 ID。
  void _openItem(PlanItemData item) {
    if (item.itemType == 'MOCK_EXAM') {
      context.push(
        Uri(
          path: '/mock-exam',
          queryParameters: {'planItemId': item.id, 'title': item.title},
        ).toString(),
      );
      return;
    }
    final mediaItemId = item.mediaItemId;
    if (mediaItemId == null) return;
    context.push(
      Uri(
        path: '/player/$mediaItemId',
        queryParameters: {'planItemId': item.id, 'title': item.title},
      ).toString(),
    );
  }
}

/// 选择器集中展示课程课时和模拟考试预置，避免在课程列表散落写操作。
final class _BattleOrderPickerSheet extends StatefulWidget {
  const _BattleOrderPickerSheet({
    required this.catalog,
    required this.exams,
    required this.selectedMediaIds,
    required this.selectedPresetIds,
    required this.immutableMediaIds,
    required this.immutablePresetIds,
    required this.onOpenExamSettings,
    required this.onToggle,
  });

  final CatalogRepository catalog;
  final MockExamRepository exams;
  final Set<String> selectedMediaIds;
  final Set<String> selectedPresetIds;
  final Set<String> immutableMediaIds;
  final Set<String> immutablePresetIds;
  final VoidCallback onOpenExamSettings;
  final void Function(BattleOrderDraft draft, bool selected) onToggle;

  @override
  State<_BattleOrderPickerSheet> createState() =>
      _BattleOrderPickerSheetState();
}

final class _BattleOrderPickerSheetState
    extends State<_BattleOrderPickerSheet> {
  late final Future<List<CourseDetail>> _courseDetails = _loadCourseDetails();
  late final Future<List<MockExamPresetData>> _presets = widget.exams
      .listPresets();
  late final Set<String> _selectedMediaIds = {...widget.selectedMediaIds};
  late final Set<String> _selectedPresetIds = {...widget.selectedPresetIds};
  late final Map<String, int> _lessonOrders = <String, int>{};
  final TextEditingController _searchController = TextEditingController();
  String _query = '';

  /// 搜索需要同时匹配课程与课时，因此打开选择器时一次加载完整只读目录。
  Future<List<CourseDetail>> _loadCourseDetails() async {
    final courses = await widget.catalog.listCourses();
    final details = await Future.wait(
      courses.map((course) => widget.catalog.loadCourse(course.id)),
    );
    var order = 0;
    for (final course in details) {
      for (final lesson in course.lessons) {
        _lessonOrders[lesson.id] = order++;
      }
    }
    return details;
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.82,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const ShanganEyebrow('集中编排'),
                        const SizedBox(height: 4),
                        Text(
                          '加入作战单',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: '完成',
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
            ),
            const TabBar(
              tabs: [
                Tab(text: '课程课时'),
                Tab(text: '模拟考试'),
              ],
            ),
            Expanded(
              child: TabBarView(children: [_buildCourses(), _buildPresets()]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCourses() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: TextField(
            key: const Key('battleOrderLessonSearch'),
            controller: _searchController,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: '搜索课程或课时',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '清空搜索',
                      onPressed: () {
                        _searchController.clear();
                        setState(() => _query = '');
                      },
                      icon: const Icon(Icons.close),
                    ),
            ),
            onChanged: (value) => setState(() => _query = value),
          ),
        ),
        Expanded(
          child: FutureBuilder<List<CourseDetail>>(
            future: _courseDetails,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const ShanganLoading('正在读取课程和课时');
              }
              if (snapshot.hasError) {
                return const Center(child: Text('课程课时加载失败，请重新打开选择器'));
              }
              final query = _normalizeSearch(_query);
              final matches =
                  <({CourseDetail course, List<LessonSummary> lessons})>[];
              for (final course in snapshot.data ?? const <CourseDetail>[]) {
                final courseMatches =
                    query.isEmpty ||
                    _normalizeSearch(course.name).contains(query) ||
                    _normalizeSearch(course.description).contains(query);
                final lessons = courseMatches
                    ? course.lessons
                    : course.lessons
                          .where(
                            (lesson) =>
                                _normalizeSearch(lesson.title).contains(query),
                          )
                          .toList();
                if (lessons.isNotEmpty) {
                  matches.add((course: course, lessons: lessons));
                }
              }
              if (matches.isEmpty) {
                return Center(
                  child: Text(query.isEmpty ? '暂无可用课时' : '没有找到匹配的课程或课时'),
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                itemCount: matches.length,
                itemBuilder: (context, index) {
                  final match = matches[index];
                  return _CourseLessonsTile(
                    key: ValueKey('${match.course.id}:$query'),
                    course: match.course,
                    lessons: match.lessons,
                    // 默认保持课程折叠；只有用户主动搜索时展开命中的课程。
                    initiallyExpanded: query.isNotEmpty,
                    selectedMediaIds: _selectedMediaIds,
                    immutableMediaIds: widget.immutableMediaIds,
                    onToggle: (lesson, selected) {
                      setState(() {
                        if (selected) {
                          _selectedMediaIds.add(lesson.id);
                        } else {
                          _selectedMediaIds.remove(lesson.id);
                        }
                      });
                      widget.onToggle(
                        BattleOrderDraft(
                          existingItemId: null,
                          itemType: lesson.learningStatus == 'COMPLETED'
                              ? 'REVIEW_SHORTCUT'
                              : 'VIDEO',
                          title: lesson.title,
                          mediaItemId: lesson.id,
                          mockExamPresetId: null,
                          plannedSeconds: (lesson.durationMs / 1000).ceil(),
                          immutable: false,
                          catalogOrder: _lessonOrders[lesson.id],
                        ),
                        selected,
                      );
                    },
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildPresets() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 12, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '选择考试名称和预置时长',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton.icon(
                onPressed: widget.onOpenExamSettings,
                icon: const Icon(Icons.settings_outlined),
                label: const Text('考试预置设置'),
              ),
            ],
          ),
        ),
        Expanded(
          child: FutureBuilder<List<MockExamPresetData>>(
            future: _presets,
            builder: (context, snapshot) {
              if (!snapshot.hasData) {
                return const ShanganLoading('正在读取考试预置');
              }
              final presets = snapshot.data!;
              if (presets.isEmpty) {
                return Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text('还没有模拟考试预置'),
                        const SizedBox(height: 12),
                        OutlinedButton.icon(
                          onPressed: widget.onOpenExamSettings,
                          icon: const Icon(Icons.add),
                          label: const Text('去设置考试名称和时长'),
                        ),
                      ],
                    ),
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                itemCount: presets.length,
                separatorBuilder: (_, _) =>
                    const Divider(color: ShanganColors.rule),
                itemBuilder: (context, index) {
                  final preset = presets[index];
                  final selected = _selectedPresetIds.contains(preset.id);
                  final immutable = widget.immutablePresetIds.contains(
                    preset.id,
                  );
                  return ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(preset.name),
                    subtitle: Text(shanganDuration(preset.durationSeconds)),
                    trailing: selected
                        ? ShanganStatusTag(
                            immutable ? '已开始' : '已加入',
                            tone: ShanganTagTone.success,
                          )
                        : const Icon(Icons.add_circle_outline),
                    onTap: immutable
                        ? null
                        : () {
                            final nextSelected = !selected;
                            setState(() {
                              if (nextSelected) {
                                _selectedPresetIds.add(preset.id);
                              } else {
                                _selectedPresetIds.remove(preset.id);
                              }
                            });
                            widget.onToggle(
                              BattleOrderDraft(
                                existingItemId: null,
                                itemType: 'MOCK_EXAM',
                                title: preset.name,
                                mediaItemId: null,
                                mockExamPresetId: preset.id,
                                plannedSeconds: preset.durationSeconds,
                                immutable: false,
                                catalogOrder: null,
                              ),
                              nextSelected,
                            );
                          },
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

/// 课程默认折叠；展开后以统一进度组件明确区分未观看、已观看和已看完课时。
final class _CourseLessonsTile extends StatelessWidget {
  const _CourseLessonsTile({
    required this.course,
    required this.lessons,
    required this.initiallyExpanded,
    required this.selectedMediaIds,
    required this.immutableMediaIds,
    required this.onToggle,
    super.key,
  });

  final CourseDetail course;
  final List<LessonSummary> lessons;
  final bool initiallyExpanded;
  final Set<String> selectedMediaIds;
  final Set<String> immutableMediaIds;
  final void Function(LessonSummary lesson, bool selected) onToggle;

  @override
  Widget build(BuildContext context) {
    final watchedCount = lessons
        .where(
          (lesson) =>
              lesson.learningStatus != 'NOT_STARTED' ||
              lesson.progressPercent > 0,
        )
        .length;
    return ExpansionTile(
      initiallyExpanded: initiallyExpanded,
      title: Text(course.name),
      subtitle: Text('${lessons.length} 个课时 · 已观看 $watchedCount 个'),
      children: lessons.map((lesson) {
        final selected = selectedMediaIds.contains(lesson.id);
        final immutable = immutableMediaIds.contains(lesson.id);
        return ListTile(
          contentPadding: const EdgeInsets.only(left: 24, right: 8),
          title: Text(lesson.title),
          subtitle: Padding(
            padding: const EdgeInsets.only(top: 5),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ShanganWatchProgress(
                  progressPercent: lesson.progressPercent,
                  completed: lesson.learningStatus == 'COMPLETED',
                  durationSeconds: (lesson.durationMs / 1000).ceil(),
                ),
                if (lesson.learningStatus == 'COMPLETED') ...[
                  const SizedBox(height: 5),
                  Text(
                    '加入后作为复习快捷入口',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ],
            ),
          ),
          trailing: selected
              ? Icon(
                  immutable ? Icons.lock_outline : Icons.check,
                  color: ShanganColors.green,
                )
              : const Icon(Icons.add_circle_outline),
          onTap: immutable ? null : () => onToggle(lesson, !selected),
        );
      }).toList(),
    );
  }
}

/// 作战单的观看完成判断复用服务端阈值，避免 98% 已完成却仍显示“学习中”。
bool _planWatchCompleted(PlanItemData item) {
  if (item.plannedSeconds <= 0) return false;
  final allowance = (item.plannedSeconds * 0.02).round().clamp(0, 30);
  return item.completedSeconds >= item.plannedSeconds - allowance;
}

/// 忽略大小写和空白做本地模糊匹配，中文、英文和编号课时都可直接搜索。
String _normalizeSearch(String value) =>
    value.toLowerCase().replaceAll(RegExp(r'\s+'), '');

/// 客户端即时展示也遵循目录固有顺序；服务端保存时会再次权威排序。
int _compareDrafts(BattleOrderDraft left, BattleOrderDraft right) {
  final leftExam = left.mediaItemId == null;
  final rightExam = right.mediaItemId == null;
  if (leftExam != rightExam) return leftExam ? 1 : -1;
  if (leftExam) return 0;
  return (left.catalogOrder ?? 1 << 30).compareTo(
    right.catalogOrder ?? 1 << 30,
  );
}

String _planLabel(String status) => switch (status) {
  'NONE' => '未制定',
  'DRAFT' => '草稿',
  'ACTIVE' => '执行中',
  'COMPLETED' => '已完成',
  'ABANDONED' => '已结算',
  'CLOSED_WITH_DEBT' => '已结算欠债',
  _ => status,
};

ShanganTagTone _planTone(String status) => switch (status) {
  'ACTIVE' => ShanganTagTone.info,
  'COMPLETED' => ShanganTagTone.success,
  'ABANDONED' || 'CLOSED_WITH_DEBT' => ShanganTagTone.risk,
  _ => ShanganTagTone.neutral,
};

String _itemTypeLabel(String type) => switch (type) {
  'VIDEO' => '视频学习',
  'REVIEW_SHORTCUT' => '复习快捷入口',
  'MOCK_EXAM' => '模拟考试',
  _ => type,
};

String _planDate(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}/'
    '${value.month.toString().padLeft(2, '0')}/'
    '${value.day.toString().padLeft(2, '0')}';
