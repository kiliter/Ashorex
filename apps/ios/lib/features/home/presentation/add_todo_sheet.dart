import 'dart:convert';
import 'dart:math';
import 'dart:async';
import 'package:shangan_ios/features/catalog/presentation/course_filter_panel.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:flutter/material.dart';
import 'course_addition_confirmation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 添加 Todo 的类型选择与三条创建流程。
///
/// 对应原型 1-3、2-1 ~ 2-4：三类入口固定，全部在 Bottom Sheet 栈内完成，
/// 不存在模拟考试、答题或还债入口（见 ADR-0025）。
final class AddTodoSheet extends ConsumerWidget {
  const AddTodoSheet({required this.date, super.key});

  final DateTime date;

  /// 返回 true 表示有新建成功，调用方需要刷新列表。
  static Future<bool> show(BuildContext context, DateTime date) {
    return showShanganSheet(
      context,
      heightFactor: 0.7,
      builder: (context) => AddTodoSheet(date: date),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final now = DateTime.now();
    final isToday = date.isAtSameMomentAs(
      DateTime(now.year, now.month, now.day),
    );
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ShanganSheetHeader(
              title: isToday ? '添加到今日' : '添加到 ${formatDate(date)}',
              subtitle: '选择 Todo 类型。所有类型都支持附件与备注。',
            ),
            const SizedBox(height: 14),
            _TypeCard(
              icon: Icons.play_arrow_rounded,
              title: '课程',
              description: '从课程库挑选课时，点开即可看视频。统计观看进度与观看时长。',
              tone: ShanganBadgeTone.blue,
              onTap: () async {
                final created = await _CoursePickerSheet.show(context, date);
                if (created && context.mounted) Navigator.of(context).pop(true);
              },
            ),
            const SizedBox(height: 10),
            _TypeCard(
              icon: Icons.timer_outlined,
              title: '专注计时',
              description: '自定义名称与倒计时时长。统计专注时长与完成 / 未完成，支持拍照。',
              tone: ShanganBadgeTone.ochre,
              onTap: () async {
                final created = await _NewFocusSheet.show(context, date);
                if (created && context.mounted) Navigator.of(context).pop(true);
              },
            ),
            const SizedBox(height: 10),
            _TypeCard(
              icon: Icons.sticky_note_2_outlined,
              title: '待办事项',
              description: '纯文本任务，自行打勾完成，可上传完成凭证。',
              tone: ShanganBadgeTone.green,
              onTap: () async {
                final created = await _NewTaskSheet.show(context, date);
                if (created && context.mounted) Navigator.of(context).pop(true);
              },
            ),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('取消'),
            ),
          ],
        ),
      ),
    );
  }
}

/// 从课程详情「批量加入」直接进入 2-2 选课时步骤。
Future<bool> showCourseResourcePicker(
  BuildContext context, {
  required CourseSummary course,
  required DateTime date,
}) {
  return _ResourcePickerSheet.show(context, course: course, date: date);
}

/// 原型 `.type-card`：44px 圆角图标块 + 标题 + 说明 + 右侧箭头。
final class _TypeCard extends StatelessWidget {
  const _TypeCard({
    required this.icon,
    required this.title,
    required this.description,
    required this.tone,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String description;
  final ShanganBadgeTone tone;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background, Color border) = switch (tone) {
      ShanganBadgeTone.blue => (
        ShanganColors.course,
        ShanganColors.blueSoft,
        ShanganColors.blueLine,
      ),
      ShanganBadgeTone.ochre => (
        ShanganColors.ochre,
        ShanganColors.ochreSoft,
        ShanganColors.ochreLine,
      ),
      _ => (
        ShanganColors.green,
        ShanganColors.greenSoft,
        ShanganColors.greenLine,
      ),
    };
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        decoration: BoxDecoration(
          color: ShanganColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: ShanganColors.rule, width: 1.5),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: background,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: border),
              ),
              child: Icon(icon, color: foreground, size: 26),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    description,
                    style: const TextStyle(
                      fontSize: 11.5,
                      height: 1.55,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
            const Padding(
              padding: EdgeInsets.only(top: 12),
              child: Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
            ),
          ],
        ),
      ),
    );
  }
}

/// 课程 Todo 第一步：选课程（原型 2-1）。
///
/// 搜索与流派 / 标签筛选都在已加载的课程快照上做，不额外打接口。
final class _CoursePickerSheet extends ConsumerStatefulWidget {
  const _CoursePickerSheet({required this.date});

  final DateTime date;

  static Future<bool> show(BuildContext context, DateTime date) {
    return showShanganSheet(
      context,
      heightFactor: 0.93,
      builder: (context) => _CoursePickerSheet(date: date),
    );
  }

  @override
  ConsumerState<_CoursePickerSheet> createState() => _CoursePickerSheetState();
}

class _CoursePickerSheetState extends ConsumerState<_CoursePickerSheet> {
  final _keyword = TextEditingController();
  CatalogFilter _filter = const CatalogFilter();
  double _filterDrag = 0;
  bool _openingFilters = false;
  Timer? _debounce;
  String _appliedKeyword = '';

  /// 仅替换当前选课面板的已应用条件，不污染学习页。
  void _apply(CatalogFilter value) => setState(() => _filter = value);
  void _search(String value) {
    _debounce?.cancel();
    if (value.isEmpty) {
      setState(() => _appliedKeyword = '');
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (mounted) setState(() => _appliedKeyword = value.trim());
    });
  }

  /// 弹层打开期间忽略重复手势，避免多层面板重叠。
  Future<void> _filters(List<CourseSummary> list) async {
    if (_openingFilters) return;
    _openingFilters = true;
    try {
      final result = await showCourseFilterPanel(
        context,
        selected: _filter,
        genres: (<String>{for (final c in list) ...c.genres}.toList()..sort()),
        people: (<String>{for (final c in list) ...c.people}.toList()..sort()),
        tags: (<String>{for (final c in list) ...c.tags}.toList()..sort()),
      );
      if (mounted && result != null) _apply(result);
    } finally {
      _openingFilters = false;
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _keyword.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final courses = ref.watch(todoPickerCoursesProvider);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: courses.when(
          loading: () => const Padding(
            padding: EdgeInsets.symmetric(vertical: 60),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (error, _) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Text('课程库加载失败：$error'),
          ),
          data: (list) {
            final keyword = _appliedKeyword;
            final filtered = list
                .where((course) {
                  if (!_filter.matches(course)) return false;
                  if (keyword.isEmpty) return true;
                  return course.title.contains(keyword) ||
                      course.people.any((person) => person.contains(keyword));
                })
                .toList(growable: false);
            return LayoutBuilder(
              builder: (context, constraints) {
                // 横屏键盘展开后合并标题、搜索和面板入口，固定区不挤掉课程列表。
                final compact = constraints.maxHeight < 250;
                // 横屏键盘与主题拖动柄共同压缩高度时，已选条件并入搜索行。
                final singleRow = constraints.maxHeight < 120;
                final selectedFilters = SelectedCourseFilters(
                  filter: _filter,
                  onChanged: _apply,
                  onClear: () {
                    _keyword.clear();
                    _search('');
                    _apply(const CatalogFilter());
                  },
                );

                return GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onHorizontalDragStart: (_) => _filterDrag = 0,
                  onHorizontalDragUpdate: (details) =>
                      _filterDrag += details.delta.dx,
                  onHorizontalDragEnd: (details) {
                    if (_filterDrag > 60 ||
                        (details.primaryVelocity ?? 0) > 150) {
                      _filters(list);
                    }
                  },
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (compact)
                        Row(
                          children: [
                            const Text('选择课程'),
                            const SizedBox(width: 12),
                            Expanded(
                              child: ShanganSearchField(
                                controller: _keyword,
                                hint: '搜索课程或讲师',
                                onChanged: _search,
                              ),
                            ),
                            IconButton(
                              tooltip: '筛选课程',
                              onPressed: () => _filters(list),
                              icon: const Icon(Icons.filter_list),
                            ),
                            if (singleRow) Expanded(child: selectedFilters),
                          ],
                        )
                      else ...[
                        const ShanganSheetHeader(
                          title: '选择课程',
                          step: '第 1 / 2 步',
                        ),
                        const SizedBox(height: 10),
                        ShanganSearchField(
                          controller: _keyword,
                          hint: '搜索课程或讲师',
                          onChanged: _search,
                        ),
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton.icon(
                            onPressed: () => _filters(list),
                            icon: const Icon(Icons.filter_list),
                            label: const Text('筛选课程'),
                          ),
                        ),
                      ],
                      if (!singleRow) selectedFilters,
                      SizedBox(height: compact ? 4 : 12),
                      if (filtered.isEmpty)
                        const Flexible(
                          child: SingleChildScrollView(
                            child: Padding(
                              padding: EdgeInsets.symmetric(vertical: 40),
                              child: Text(
                                '没有符合条件的课程；课程库为空时请让管理员先在后台同步 Emby。',
                                textAlign: TextAlign.center,
                                style: TextStyle(color: ShanganColors.mutedInk),
                              ),
                            ),
                          ),
                        )
                      else
                        Flexible(
                          child: SingleChildScrollView(
                            child: ShanganCard(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                              ),
                              child: Column(
                                children: [
                                  for (
                                    var index = 0;
                                    index < filtered.length;
                                    index++
                                  ) ...[
                                    if (index > 0)
                                      const Divider(
                                        height: 1,
                                        color: ShanganColors.hair,
                                      ),
                                    _CoursePickRow(
                                      course: filtered[index],
                                      index: index,
                                      onTap: () =>
                                          _openResources(filtered[index]),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }

  Future<void> _openResources(CourseSummary course) async {
    final created = await _ResourcePickerSheet.show(
      context,
      course: course,
      date: widget.date,
    );
    if (created && mounted) Navigator.of(context).pop(true);
  }
}

/// 原型 `.filter-row`：单选 pill 横向滚动条。
final class _FilterRow extends StatelessWidget {
  const _FilterRow({
    required this.labels,
    required this.selectedIndex,
    required this.onSelected,
  });

  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (var index = 0; index < labels.length; index++) ...[
            if (index > 0) const SizedBox(width: 7),
            ShanganFilterChip(
              label: labels[index],
              selected: index == selectedIndex,
              onTap: () => onSelected(index),
            ),
          ],
        ],
      ),
    );
  }
}

/// 原型 2-1 的课程选择行：封面 + 名称 + 讲师课时进度 + 学习进度条。
final class _CoursePickRow extends StatelessWidget {
  const _CoursePickRow({
    required this.course,
    required this.index,
    required this.onTap,
  });

  final CourseSummary course;
  final int index;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final people = course.people.isEmpty ? '未标注人物' : course.people.join('、');
    return ShanganPickRow(
      title: course.title,
      subtitle:
          '$people · ${course.resourceCount} 课时 · '
          '${course.completedPercent == 0 ? '未开始' : '已学 ${course.completedPercent}%'}',
      leading: ShanganCover(
        letter: course.title.isEmpty ? '课' : course.title.substring(0, 1),
        caption: course.tags.isEmpty ? '课程' : course.tags.first,
        index: index,
        width: 44,
        height: 58,
      ),
      extra: course.completedPercent == 0
          ? null
          : TargetProgressBar(value: course.completedPercent / 100),
      trailing: const Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
      onTap: onTap,
    );
  }
}

/// 课程 Todo 第二步：多选课时并设定目标进度（原型 2-2）。
final class _ResourcePickerSheet extends ConsumerStatefulWidget {
  const _ResourcePickerSheet({required this.course, required this.date});

  final CourseSummary course;
  final DateTime date;

  static Future<bool> show(
    BuildContext context, {
    required CourseSummary course,
    required DateTime date,
  }) {
    return showShanganSheet(
      context,
      heightFactor: 0.93,
      showDragHandle: false,
      builder: (context) => _ResourcePickerSheet(course: course, date: date),
    );
  }

  @override
  ConsumerState<_ResourcePickerSheet> createState() =>
      _ResourcePickerSheetState();
}

class _ResourcePickerSheetState extends ConsumerState<_ResourcePickerSheet> {
  // 控制器同时绑定课时列表和常显滚动条，拖动滑块可精确定位长列表。
  final _lessonScroll = ScrollController();
  final _selected = <String>{};
  final _existingResourceIds = <String>{};

  @override
  void initState() {
    super.initState();
    _loadExisting();
  }

  /// 行内标记只是提示；加载失败仍允许操作，最终以提交前服务端预览为准。
  Future<void> _loadExisting() async {
    try {
      final day = await ref
          .read(shanganRepositoryProvider)
          .loadDay(date: widget.date);
      if (!mounted) return;
      setState(() {
        _existingResourceIds.addAll(
          day.todos.map((todo) => todo.resourceId).whereType<String>(),
        );
        _selected.removeAll(_existingResourceIds);
      });
    } catch (_) {
      // 网络恢复后预览与提交会再次校验，不以提示加载失败放宽服务端防重。
    }
  }

  final _customPercent = TextEditingController();
  int _targetPermille = 1000;
  bool _customTarget = false;

  /// 课时筛选：全部 / 未看 / 看过一半 / 已看完。
  int _progressFilter = 0;
  bool _submitting = false;

  @override
  void dispose() {
    _lessonScroll.dispose();
    _customPercent.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(courseDetailProvider(widget.course.id));
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: detail.when(
          loading: () => const Padding(
            padding: EdgeInsets.symmetric(vertical: 60),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (error, _) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Text('课程详情加载失败：$error'),
          ),
          data: (data) {
            final resources = data.resources
                .where(_matchesFilter)
                .toList(growable: false);
            final picked = data.resources
                .where((resource) => _selected.contains(resource.id))
                .toList(growable: false);
            // 课时只占剩余高度；目标标准与提交区不进入任何纵向滚动容器。
            return LayoutBuilder(
              builder: (context, constraints) {
                final compact =
                    constraints.maxHeight < 620 ||
                    MediaQuery.textScalerOf(context).scale(13) > 20;
                final landscape = constraints.maxWidth > 600;
                final controls = _completionControls(
                  picked,
                  compact: compact,
                  inlineCustom: landscape,
                  tight: constraints.maxHeight < 200,
                );
                final lessons = Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            widget.course.title,
                            maxLines: compact ? 1 : 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: '关闭',
                          icon: const Icon(Icons.close),
                          onPressed: () => Navigator.of(context).pop(false),
                        ),
                      ],
                    ),
                    if (!compact)
                      Text(
                        '${widget.course.people.isEmpty ? '未标注人物' : widget.course.people.join('、')} · ${widget.course.resourceCount} 课时',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    const SizedBox(height: 4),
                    _FilterRow(
                      labels: const ['全部', '未看', '看过一半', '已看完'],
                      selectedIndex: _progressFilter,
                      onSelected: (index) =>
                          setState(() => _progressFilter = index),
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: RawScrollbar(
                        key: const ValueKey('resource-picker-scrollbar'),
                        controller: _lessonScroll,
                        thumbVisibility: true,
                        trackVisibility: true,
                        interactive: true,
                        thickness: 4,
                        radius: const Radius.circular(6),
                        trackRadius: const Radius.circular(6),
                        thumbColor: ShanganColors.course.withValues(
                          alpha: 0.55,
                        ),
                        trackColor: ShanganColors.hair.withValues(alpha: 0.55),
                        trackBorderColor: Colors.transparent,
                        mainAxisMargin: 4,
                        child: ListView.separated(
                          controller: _lessonScroll,
                          key: const ValueKey('resource-picker-list'),
                          padding: const EdgeInsets.only(right: 12),
                          itemCount: resources.isEmpty ? 1 : resources.length,
                          separatorBuilder: (_, _) => const Divider(
                            height: 1,
                            color: ShanganColors.hair,
                          ),
                          itemBuilder: (context, index) {
                            if (resources.isEmpty) {
                              return const Padding(
                                padding: EdgeInsets.symmetric(vertical: 28),
                                child: Text(
                                  '这个筛选下没有课时',
                                  textAlign: TextAlign.center,
                                ),
                              );
                            }
                            final resource = resources[index];
                            return _ResourcePickRow(
                              resource: resource,
                              alreadyAdded: _existingResourceIds.contains(
                                resource.id,
                              ),
                              selected: _selected.contains(resource.id),
                              onToggle: (value) => setState(() {
                                if (value) {
                                  _selected.add(resource.id);
                                } else {
                                  _selected.remove(resource.id);
                                }
                              }),
                            );
                          },
                        ),
                      ),
                    ),
                  ],
                );
                if (landscape) {
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Expanded(flex: 6, child: lessons),
                      const VerticalDivider(
                        width: 24,
                        color: ShanganColors.hair,
                      ),
                      Expanded(
                        flex: 5,
                        child: Align(
                          alignment: Alignment.bottomCenter,
                          child: controls,
                        ),
                      ),
                    ],
                  );
                }
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Expanded(child: lessons),
                    const Divider(height: 16, color: ShanganColors.hair),
                    controls,
                  ],
                );
              },
            );
          },
        ),
      ),
    );
  }

  /// 固定完成标准；小空间合并输入行，避免键盘将课时与提交挤出屏幕。
  Widget _completionControls(
    List<CourseResource> picked, {
    required bool compact,
    required bool inlineCustom,
    required bool tight,
  }) {
    final customField = TextField(
      key: const ValueKey('resource-target-input'),
      controller: _customPercent,
      keyboardType: TextInputType.number,
      decoration: const InputDecoration(
        labelText: '目标 1–100%',
        hintText: '例如 35',
        isDense: true,
      ),
      onChanged: (value) {
        final parsed = int.tryParse(value);
        if (parsed != null && parsed >= 1 && parsed <= 100) {
          setState(() => _targetPermille = parsed * 10);
        }
      },
    );
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (compact)
          Row(
            children: [
              const Text(
                '完成标准',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: DropdownButton<int>(
                  isExpanded: true,
                  value: _customTarget ? -1 : _targetPermille,
                  items: [
                    for (final value in [300, 500, 800, 1000, -1])
                      DropdownMenuItem(
                        value: value,
                        child: Text(
                          value == -1
                              ? '自定义'
                              : value == 1000
                              ? '全部看完'
                              : '看到 ${value ~/ 10}%',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                  ],
                  onChanged: (value) {
                    if (value == null) return;
                    setState(() {
                      _customTarget = value == -1;
                      if (value != -1) _targetPermille = value;
                    });
                  },
                ),
              ),
              if (_customTarget && inlineCustom) ...[
                const SizedBox(width: 8),
                Expanded(child: customField),
              ],
            ],
          )
        else ...[
          const Text(
            '完成标准',
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          _TargetRow(
            targetPermille: _targetPermille,
            custom: _customTarget,
            onSelect: (permille) => setState(() {
              _customTarget = false;
              _targetPermille = permille;
            }),
            onCustom: () => setState(() => _customTarget = true),
          ),
        ],
        if (_customTarget && (!compact || !inlineCustom)) ...[
          const SizedBox(height: 8),
          customField,
        ],
        if (!compact && picked.isNotEmpty) ...[
          const SizedBox(height: 8),
          _TargetPreviewCard(
            resource: picked.first,
            targetPermille: _targetPermille,
          ),
        ],
        if (!tight) ...[
          const SizedBox(height: 8),
          Text(
            '已选 ${picked.length} 课时 · 目标合计 ${formatPosition(_targetTotalMs(picked))}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
          ),
        ],
        const SizedBox(height: 8),
        FilledButton.icon(
          onPressed: _selected.isEmpty || _submitting ? null : _submit,
          icon: const Icon(Icons.add, size: 18),
          label: Text(_submitting ? '添加中…' : '加入待办'),
        ),
      ],
    );
  }

  bool _matchesFilter(CourseResource resource) {
    if (!resource.measurable) return _progressFilter == 0;
    return switch (_progressFilter) {
      1 => resource.progressPermille == 0,
      2 => resource.progressPermille > 0 && resource.progressPermille < 1000,
      3 => resource.completedBefore || resource.progressPermille >= 1000,
      _ => true,
    };
  }

  int _targetTotalMs(List<CourseResource> picked) {
    var total = 0;
    for (final resource in picked) {
      total += ((resource.durationMs ?? 0) * _targetPermille / 1000).round();
    }
    return total;
  }

  String? _additionRequestId;
  String? _additionPayload;
  String? _additionItems;
  List<String> _additionReviews = [];
  List<String> _additionReuse = [];

  /// 对已选课时先预览再提交；失败重试保留批次 ID，取消不写入。
  Future<void> _submit() async {
    if (_submitting || _selected.isEmpty) return;
    setState(() => _submitting = true);
    final repository = ref.read(shanganRepositoryProvider);
    final items = _selected
        .map(
          (resourceId) => repository.courseTodoPayload(
            resourceId: resourceId,
            targetProgressPermille: _targetPermille,
            localDate: widget.date,
          ),
        )
        .toList(growable: false);
    try {
      // 响应丢失后的重试必须提交原批次，不能因重新预览看到新建记录而变成第二轮。
      if (_additionRequestId != null && _additionItems == jsonEncode(items)) {
        final result = await repository.addCourses(
          items,
          _additionReuse,
          reviewResourceIds: _additionReviews,
          requestId: _additionRequestId,
        );
        if (!mounted) return;
        ShanganFeedback.show(context, result.message);
        Navigator.of(context).pop(true);
        return;
      }
      final preview = await repository.previewCourseAdditions(items);
      if (!mounted) return;
      List<String> confirmed = [];
      if (preview.any((item) => item.status != 'NEW' || item.reviewAvailable)) {
        final choice = await confirmCourseAdditions(
          context,
          preview,
          targetProgressPermille: items.first['targetProgressPermille'] as int,
        );
        if (!mounted || choice == null) return;
        confirmed = choice;
      }
      final reviewIds =
          confirmed
              .where((id) => id.startsWith('review:'))
              .map((id) => id.substring(7))
              .toList()
            ..sort();
      final reuseIds =
          confirmed.where((id) => !id.startsWith('review:')).toList()..sort();
      final payload = jsonEncode([items, reuseIds, reviewIds]);
      // 同一内容的失败重试复用标识；成功关闭弹窗，下一次主动添加生成新标识。
      if (_additionPayload != payload) {
        _additionPayload = payload;
        _additionRequestId = List.generate(
          16,
          (_) => Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'),
        ).join();
      }
      _additionItems = jsonEncode(items);
      _additionReuse = reuseIds;
      _additionReviews = reviewIds;
      final result = await repository.addCourses(
        items,
        reuseIds,
        reviewResourceIds: reviewIds,
        requestId: _additionRequestId,
      );
      if (!mounted) return;
      ShanganFeedback.show(context, result.message);
      Navigator.of(context).pop(true);
    } catch (error) {
      if (mounted) ShanganFeedback.show(context, '添加失败：$error', error: true);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}

/// 原型 2-2 的课时选择行：复选框 + 时长与已看进度 + 续学 / 已完成徽标。
final class _ResourcePickRow extends StatelessWidget {
  const _ResourcePickRow({
    required this.resource,
    this.alreadyAdded = false,
    required this.selected,
    required this.onToggle,
  });

  final CourseResource resource;
  final bool alreadyAdded;
  final bool selected;
  final ValueChanged<bool> onToggle;

  @override
  Widget build(BuildContext context) {
    final done = resource.completedBefore || resource.progressPermille >= 1000;
    return Opacity(
      opacity: resource.measurable ? 1 : 0.55,
      child: ShanganPickRow(
        title: resource.title,
        subtitle: _subtitle(),
        checked: resource.measurable ? selected : null,
        onToggle: resource.measurable ? onToggle : null,
        trailing: alreadyAdded
            ? const ShanganBadge(label: '当日已添加', tone: ShanganBadgeTone.blue)
            : done
            ? const ShanganBadge(label: '已完成', tone: ShanganBadgeTone.green)
            : resource.progressPermille > 0
            ? const ShanganBadge(label: '续学', tone: ShanganBadgeTone.blue)
            : null,
      ),
    );
  }

  String _subtitle() {
    if (alreadyAdded) return '当日已有安排，再次选择可确认提高目标';
    if (!resource.measurable) {
      return resource.resourceType == ResourceType.document
          ? '页数待客户端首次打开后回报'
          : '缺少时长信息，暂时无法加入待办';
    }
    final total = resource.resourceType == ResourceType.video
        ? formatPosition(resource.durationMs ?? 0)
        : '${resource.pageCount} 页';
    if (resource.completedBefore || resource.progressPermille >= 1000) {
      return '$total · 已看完';
    }
    if (resource.progressPermille == 0) return '$total · 未看';
    return '$total · 已看 ${resource.progressPermille ~/ 10}%';
  }
}

/// 原型 2-2 的完成标准 pill 组，含「自定义」。
final class _TargetRow extends StatelessWidget {
  const _TargetRow({
    required this.targetPermille,
    required this.custom,
    required this.onSelect,
    required this.onCustom,
  });

  final int targetPermille;
  final bool custom;
  final ValueChanged<int> onSelect;
  final VoidCallback onCustom;

  @override
  Widget build(BuildContext context) {
    const presets = [300, 500, 800, 1000];
    return Wrap(
      spacing: 7,
      runSpacing: 7,
      children: [
        for (final permille in presets)
          ShanganFilterChip(
            label: permille == 1000 ? '全部看完' : '看到 ${permille ~/ 10}%',
            selected: !custom && targetPermille == permille,
            onTap: () => onSelect(permille),
          ),
        ShanganFilterChip(label: '自定义', selected: custom, onTap: onCustom),
      ],
    );
  }
}

/// 原型 2-2 的蓝底目标预览卡：目标时刻、已看位置与还需时长。
final class _TargetPreviewCard extends StatelessWidget {
  const _TargetPreviewCard({
    required this.resource,
    required this.targetPermille,
  });

  final CourseResource resource;
  final int targetPermille;

  @override
  Widget build(BuildContext context) {
    final duration = resource.durationMs ?? 0;
    final targetMs = (duration * targetPermille / 1000).round();
    final remaining = (targetMs - resource.maxPositionMs).clamp(0, targetMs);
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
      borderColor: ShanganColors.blueLine,
      backgroundColor: ShanganColors.blueSoft,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '${resource.title} · ${formatPosition(duration)}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: ShanganColors.course,
                  ),
                ),
              ),
              Text(
                '目标 ${formatPosition(targetMs)}（${targetPermille ~/ 10}%）',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: ShanganColors.course,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          TargetProgressBar(
            value: duration == 0 ? 0 : resource.maxPositionMs / duration,
            targetValue: targetPermille / 1000,
            color: ShanganColors.course,
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Text(
                '已看 ${formatPosition(resource.maxPositionMs)}',
                style: const TextStyle(
                  fontSize: 11,
                  color: ShanganColors.mutedInk,
                ),
              ),
              const Spacer(),
              Text(
                remaining == 0 ? '已达标' : '还需 ${formatPosition(remaining)} 达标',
                style: const TextStyle(
                  fontSize: 11,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// 新建专注计时（原型 2-3）。
final class _NewFocusSheet extends ConsumerStatefulWidget {
  const _NewFocusSheet({required this.date});

  final DateTime date;

  static Future<bool> show(BuildContext context, DateTime date) {
    return showShanganSheet(
      context,
      heightFactor: 0.87,
      builder: (context) => _NewFocusSheet(date: date),
    );
  }

  @override
  ConsumerState<_NewFocusSheet> createState() => _NewFocusSheetState();
}

class _NewFocusSheetState extends ConsumerState<_NewFocusSheet> {
  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const ShanganSheetHeader(
              title: '新建专注计时',
              subtitle: '倒计时结束即判定完成；提前放弃记为未完成，两种结果都会进入统计。',
            ),
            const SizedBox(height: 14),
            _FocusTodoForm(date: widget.date),
          ],
        ),
      ),
    );
  }
}

/// 专注表单：手机抽屉和 Pad 弹框共用同一套字段与提交口径。
final class _FocusTodoForm extends ConsumerStatefulWidget {
  const _FocusTodoForm({required this.date});

  final DateTime date;

  @override
  ConsumerState<_FocusTodoForm> createState() => _FocusTodoFormState();
}

class _FocusTodoFormState extends ConsumerState<_FocusTodoForm> {
  final _title = TextEditingController();
  final _custom = TextEditingController();
  final _note = TextEditingController();
  int _minutes = 25;
  bool _customMinutes = false;
  bool _requireEvidence = false;
  bool _submitting = false;

  @override
  void dispose() {
    _title.dispose();
    _custom.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const presets = [15, 25, 45, 60];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ShanganField(
          label: '名称',
          controller: _title,
          hint: '例如 法条背诵 · 第三章',
          onChanged: (_) => setState(() {}),
        ),
        const ShanganGroupLabel(
          '倒计时时长',
          padding: EdgeInsets.only(top: 12, bottom: 7),
        ),
        Wrap(
          spacing: 7,
          runSpacing: 7,
          children: [
            for (final minutes in presets)
              ShanganFilterChip(
                label: '$minutes 分',
                selected: !_customMinutes && _minutes == minutes,
                onTap: () => setState(() {
                  _customMinutes = false;
                  _minutes = minutes;
                }),
              ),
            ShanganFilterChip(
              label: '自定义',
              selected: _customMinutes,
              onTap: () => setState(() => _customMinutes = true),
            ),
          ],
        ),
        if (_customMinutes) ...[
          const SizedBox(height: 12),
          ShanganField(
            label: '自定义时长（分钟）',
            controller: _custom,
            hint: '1 – 480',
            keyboardType: TextInputType.number,
            onChanged: (value) {
              final parsed = int.tryParse(value);
              setState(() => _minutes = parsed ?? 0);
            },
          ),
        ],
        const SizedBox(height: 12),
        ShanganSwitchCard(
          tiles: [
            ShanganSwitchTile(
              title: '完成时要求拍照',
              subtitle: '结束后必须上传至少 1 张凭证',
              value: _requireEvidence,
              onChanged: (value) => setState(() => _requireEvidence = value),
            ),
          ],
        ),
        const SizedBox(height: 12),
        ShanganField(
          label: '备注（可选）',
          controller: _note,
          hint: '写点计划或提醒',
          minLines: 2,
          maxLines: 4,
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('取消'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 2,
              child: FilledButton(
                onPressed:
                    _title.text.trim().isEmpty ||
                        _minutes < 1 ||
                        _minutes > 480 ||
                        _submitting
                    ? null
                    : _submit,
                child: Text(_submitting ? '保存中…' : '保存并加入今日'),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Future<void> _submit() async {
    setState(() => _submitting = true);
    final repository = ref.read(shanganRepositoryProvider);
    try {
      await repository.createTodos([
        repository.focusTodoPayload(
          title: _title.text.trim(),
          plannedSeconds: _minutes * 60,
          localDate: widget.date,
          requireEvidence: _requireEvidence,
          note: _note.text.trim(),
        ),
      ]);
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() => _submitting = false);
      if (mounted) {
        ShanganFeedback.show(context, '保存失败：$error', error: true);
      }
    }
  }
}

/// 新建待办事项（原型 2-4）。
final class _NewTaskSheet extends ConsumerStatefulWidget {
  const _NewTaskSheet({required this.date});

  final DateTime date;

  static Future<bool> show(BuildContext context, DateTime date) {
    return showShanganSheet(
      context,
      heightFactor: 0.82,
      builder: (context) => _NewTaskSheet(date: date),
    );
  }

  @override
  ConsumerState<_NewTaskSheet> createState() => _NewTaskSheetState();
}

class _NewTaskSheetState extends ConsumerState<_NewTaskSheet> {
  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const ShanganSheetHeader(
              title: '新建待办事项',
              subtitle: '自行打勾完成，不做任何自动核验。',
            ),
            const SizedBox(height: 14),
            _TaskTodoForm(date: widget.date),
          ],
        ),
      ),
    );
  }
}

/// 待办表单：手机抽屉和 Pad 弹框共用同一套字段与提交口径。
final class _TaskTodoForm extends ConsumerStatefulWidget {
  const _TaskTodoForm({required this.date});

  final DateTime date;

  @override
  ConsumerState<_TaskTodoForm> createState() => _TaskTodoFormState();
}

class _TaskTodoFormState extends ConsumerState<_TaskTodoForm> {
  final _title = TextEditingController();
  final _note = TextEditingController();
  bool _requireEvidence = false;
  bool _submitting = false;

  @override
  void dispose() {
    _title.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ShanganField(
          label: '标题',
          controller: _title,
          hint: '例如 整理错题本第 3 章',
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 12),
        ShanganField(
          label: '备注（可选）',
          controller: _note,
          hint: '补充说明、页码、章节…',
          minLines: 2,
          maxLines: 4,
        ),
        const SizedBox(height: 12),
        ShanganSwitchCard(
          tiles: [
            ShanganSwitchTile(
              title: '完成时要求凭证',
              subtitle: '勾选完成前必须上传照片或文件',
              value: _requireEvidence,
              onChanged: (value) => setState(() => _requireEvidence = value),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('取消'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 2,
              child: FilledButton(
                onPressed: _title.text.trim().isEmpty || _submitting
                    ? null
                    : _submit,
                child: Text(_submitting ? '保存中…' : '保存'),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Future<void> _submit() async {
    setState(() => _submitting = true);
    final repository = ref.read(shanganRepositoryProvider);
    try {
      await repository.createTodos([
        repository.taskTodoPayload(
          title: _title.text.trim(),
          note: _note.text.trim(),
          localDate: widget.date,
          requireEvidence: _requireEvidence,
        ),
      ]);
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() => _submitting = false);
      if (mounted) {
        ShanganFeedback.show(context, '保存失败：$error', error: true);
      }
    }
  }
}

/// Pad 添加待办：居中高保真弹框，内容对齐 ashorex-pad-v2 的 `showAddTodo`。
final class PadAddTodoDialog extends ConsumerStatefulWidget {
  const PadAddTodoDialog({required this.date, super.key});

  final DateTime date;

  static Future<bool> show(BuildContext context, DateTime date) async {
    final result = await showDialog<bool>(
      context: context,
      barrierColor: const Color(0x55263B60),
      builder: (context) => PadAddTodoDialog(date: date),
    );
    return result ?? false;
  }

  @override
  ConsumerState<PadAddTodoDialog> createState() => _PadAddTodoDialogState();
}

enum _PadAddKind { course, focus, task }

class _PadAddTodoDialogState extends ConsumerState<PadAddTodoDialog> {
  _PadAddKind _kind = _PadAddKind.course;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: ShanganColors.surface,
      insetPadding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(
          color: ShanganColors.blue,
          width: ShanganRadius.borderWidth,
        ),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460, maxHeight: 640),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(23, 20, 23, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '添加今日待办',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.5,
                      ),
                    ),
                  ),
                  ShanganIconButton(
                    icon: Icons.close,
                    semanticLabel: '关闭弹窗',
                    onTap: () => Navigator.of(context).pop(false),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              ShanganSegmented(
                labels: const ['课程', '专注', '待办'],
                selectedIndex: _kind.index,
                onChanged: (index) =>
                    setState(() => _kind = _PadAddKind.values[index]),
              ),
              const SizedBox(height: 12),
              Flexible(
                child: SingleChildScrollView(
                  child: switch (_kind) {
                    _PadAddKind.course => const _PadCoursePrompt(),
                    _PadAddKind.focus => _FocusTodoForm(date: widget.date),
                    _PadAddKind.task => _TaskTodoForm(date: widget.date),
                  },
                ),
              ),
              if (_kind == _PadAddKind.course) ...[
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () => Navigator.of(context).pop(false),
                        child: const Text('取消'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton(
                        onPressed: _pickCourse,
                        child: const Text('选择课程'),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickCourse() async {
    final created = await _CoursePickerSheet.show(context, widget.date);
    if (created && mounted) Navigator.of(context).pop(true);
  }
}

/// 原型课程类型的空态说明，真正选课仍走现有课程库挑选流程。
final class _PadCoursePrompt extends StatelessWidget {
  const _PadCoursePrompt();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.fromLTRB(15, 28, 15, 12),
      child: Column(
        children: [
          _PadCourseIcon(),
          SizedBox(height: 15),
          Text(
            '从课程库选择要学的课时',
            style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
          ),
          SizedBox(height: 8),
          Text(
            '先选课程，再将课时加入今日计划。\n保留现有课程与待办的关联方式。',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              height: 1.65,
              color: ShanganColors.mutedInk,
            ),
          ),
        ],
      ),
    );
  }
}

final class _PadCourseIcon extends StatelessWidget {
  const _PadCourseIcon();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 52,
      height: 52,
      decoration: BoxDecoration(
        color: ShanganColors.blueSoft,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: ShanganColors.blueLine),
      ),
      child: const Icon(
        Icons.menu_book_outlined,
        size: 26,
        color: ShanganColors.course,
      ),
    );
  }
}
