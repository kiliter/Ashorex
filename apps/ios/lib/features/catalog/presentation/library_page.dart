import 'dart:async';
import 'course_filter_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:shangan_ios/features/home/presentation/add_todo_sheet.dart';

/// 学习 Tab：课程库总览（原型 4-1 / 4-2）。
///
/// 筛选维度全部来自 Emby 元数据（流派 / 标签 / 人物），本地不维护分类主数据。
final class LibraryPage extends ConsumerStatefulWidget {
  const LibraryPage({super.key});

  @override
  ConsumerState<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends ConsumerState<LibraryPage> {
  final _keyword = TextEditingController();

  /// 原型 4-1 右上角两个 iconbtn：搜索框与筛选区都可收起。
  bool _searchOpen = false;
  Timer? _debounce;
  String _appliedKeyword = '';
  bool _openingFilters = false;

  /// 搜索只在停止输入 300ms 后生效；清空立即更新。
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

  /// 取消面板不会修改共享查询，应用仅提交一次。
  Future<void> _openFilters() async {
    if (_openingFilters) return;
    _openingFilters = true;
    try {
      final facets = await ref.read(catalogFacetsProvider.future);
      if (!mounted) return;
      final result = await showCourseFilterPanel(
        context,
        selected: ref.read(catalogFilterProvider),
        genres: facets.genres.map((f) => f.value).toList(),
        people: facets.people.map((f) => f.value).toList(),
        tags: facets.tags.map((f) => f.value).toList(),
      );
      if (mounted && result != null) {
        ref.read(catalogFilterProvider.notifier).apply(result);
      }
    } catch (_) {
      // 元数据暂时不可用不影响已展示课程；允许用户主动重试。
      if (!mounted) return;
      ref.invalidate(catalogFacetsProvider);
      ShanganFeedback.show(context, '筛选条件加载失败，请重试', error: true);
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
    final courses = ref.watch(coursesProvider);
    final filter = ref.watch(catalogFilterProvider);
    final keyword = _appliedKeyword;
    final grouping = ShanganSegmented(
      labels: const ['按流派', '按人物'],
      selectedIndex: filter.groupByPerson ? 1 : 0,
      onChanged: (index) =>
          ref.read(catalogFilterProvider.notifier).setGroupByPerson(index == 1),
    );
    final selectedFilters = SelectedCourseFilters(
      filter: filter,
      onChanged: ref.read(catalogFilterProvider.notifier).apply,
      onClear: () {
        _keyword.clear();
        _search('');
        if (filter.genre != null ||
            filter.tag != null ||
            filter.person != null ||
            filter.year != null ||
            (filter.query?.isNotEmpty ?? false)) {
          ref
              .read(catalogFilterProvider.notifier)
              .apply(CatalogFilter(groupByPerson: filter.groupByPerson));
        }
      },
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        // 横屏键盘展开后固定区压为两行，仍保留标题与所有筛选入口。
        final compact =
            constraints.maxHeight < 300 && constraints.maxWidth > 600;
        return GestureDetector(
          onHorizontalDragEnd: (details) {
            if ((details.primaryVelocity ?? 0) < -150) _openFilters();
          },
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
            child: Column(
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'LIBRARY',
                            style: TextStyle(
                              fontSize: 11.5,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 1.6,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                          Text(
                            '课程库',
                            style: TextStyle(
                              fontSize: 26,
                              fontWeight: FontWeight.w800,
                              letterSpacing: -0.8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (compact && _searchOpen)
                      Expanded(
                        child: ShanganSearchField(
                          controller: _keyword,
                          hint: '搜索课程或人物',
                          onChanged: _search,
                        ),
                      ),
                    ShanganIconButton(
                      icon: Icons.search,
                      highlighted: _searchOpen,
                      semanticLabel: '搜索课程',
                      onTap: () => setState(() => _searchOpen = !_searchOpen),
                    ),
                    const SizedBox(width: 9),
                    ShanganIconButton(
                      icon: Icons.filter_list,
                      highlighted:
                          filter.genre != null ||
                          filter.person != null ||
                          filter.tag != null,
                      semanticLabel: '展开筛选',
                      onTap: _openFilters,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (_searchOpen && !compact) ...[
                  ShanganSearchField(
                    controller: _keyword,
                    hint: '搜索课程或人物',
                    onChanged: _search,
                  ),
                  const SizedBox(height: 12),
                ],
                if (compact)
                  Row(
                    children: [
                      Expanded(child: grouping),
                      const SizedBox(width: 12),
                      Expanded(child: selectedFilters),
                    ],
                  )
                else ...[
                  grouping,
                  selectedFilters,
                ],
                Expanded(
                  child: RefreshIndicator(
                    onRefresh: () async {
                      ref.invalidate(catalogFacetsProvider);
                      ref.invalidate(coursesProvider);
                    },
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.only(bottom: 110),
                      children: [
                        courses.when(
                          loading: () => const Padding(
                            padding: EdgeInsets.symmetric(vertical: 40),
                            child: Center(child: CircularProgressIndicator()),
                          ),
                          error: (error, _) => Text('课程列表加载失败：$error'),
                          data: (list) {
                            final visible = keyword.isEmpty
                                ? list
                                : list
                                      .where(
                                        (course) =>
                                            course.title.contains(keyword) ||
                                            course.people.any(
                                              (person) =>
                                                  person.contains(keyword),
                                            ),
                                      )
                                      .toList(growable: false);
                            if (visible.isEmpty) {
                              return const Padding(
                                padding: EdgeInsets.symmetric(vertical: 40),
                                child: Center(
                                  child: Text(
                                    '没有符合条件的课程',
                                    style: TextStyle(
                                      color: ShanganColors.mutedInk,
                                    ),
                                  ),
                                ),
                              );
                            }
                            return filter.groupByPerson
                                ? _PeopleList(courses: visible)
                                : _GenreGroups(courses: visible);
                          },
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// 原型 4-1：按流派分组，组标题用 grouplabel。
final class _GenreGroups extends StatelessWidget {
  const _GenreGroups({required this.courses});

  final List<CourseSummary> courses;

  @override
  Widget build(BuildContext context) {
    final groups = <String, List<CourseSummary>>{};
    for (final course in courses) {
      groups.putIfAbsent(course.primaryGenre, () => []).add(course);
    }
    var index = 0;
    final children = <Widget>[];
    for (final entry in groups.entries) {
      children.add(ShanganGroupLabel('${entry.key} · ${entry.value.length} 门'));
      for (final course in entry.value) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: _CourseCard(course: course, index: index++),
          ),
        );
      }
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: children,
    );
  }
}

/// 原型 4-2：按人物聚合，本地不存人物主表，只按人物名聚合课程与我的时长。
final class _PeopleList extends ConsumerWidget {
  const _PeopleList({required this.courses});

  final List<CourseSummary> courses;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final grouped = <String, List<CourseSummary>>{};
    for (final course in courses) {
      final people = course.people.isEmpty ? ['未标注人物'] : course.people;
      for (final person in people) {
        grouped.putIfAbsent(person, () => []).add(course);
      }
    }
    final entries = grouped.entries.toList(growable: false);
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14),
      child: Column(
        children: [
          for (var index = 0; index < entries.length; index++) ...[
            if (index > 0) const Divider(height: 1, color: ShanganColors.hair),
            _PersonRow(
              name: entries[index].key,
              courses: entries[index].value,
              index: index,
              onTap: () {
                ref
                    .read(catalogFilterProvider.notifier)
                    .togglePerson(entries[index].key);
                ref
                    .read(catalogFilterProvider.notifier)
                    .setGroupByPerson(false);
              },
            ),
          ],
        ],
      ),
    );
  }
}

final class _PersonRow extends StatelessWidget {
  const _PersonRow({
    required this.name,
    required this.courses,
    required this.index,
    required this.onTap,
  });

  final String name;
  final List<CourseSummary> courses;
  final int index;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final lessons = courses.fold<int>(
      0,
      (previous, course) => previous + course.resourceCount,
    );
    final watchedMs = courses.fold<int>(
      0,
      (previous, course) => previous + course.watchedMs,
    );
    final genres = <String>{for (final course in courses) course.primaryGenre};
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 11),
        child: Row(
          children: [
            ShanganAvatar(letter: name.substring(0, 1), index: index),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${genres.join('、')} · ${courses.length} 门课程 · $lessons 课时',
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    watchedMs == 0
                        ? '尚未开始'
                        : '我已学 ${formatDurationCompact(watchedMs)}',
                    style: const TextStyle(
                      fontSize: 11,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
          ],
        ),
      ),
    );
  }
}

/// 原型 `.course-card`：封面 + 名称 + 两行元数据 + 进度条 + 已看统计。
final class _CourseCard extends StatelessWidget {
  const _CourseCard({required this.course, required this.index});

  final CourseSummary course;
  final int index;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => context.push('/courses/${course.id}'),
      child: ShanganCard(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ShanganCover(
              letter: course.primaryGenre.isEmpty
                  ? '课'
                  : course.primaryGenre.substring(0, 1),
              caption: course.tags.isEmpty ? '课程' : course.tags.first,
              index: index,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    course.title,
                    style: const TextStyle(
                      fontSize: 15,
                      height: 1.3,
                      fontWeight: FontWeight.w800,
                      color: ShanganColors.course,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${course.people.isEmpty ? '未标注人物' : course.people.join('、')}'
                    ' · ${course.resourceCount} 课时'
                    ' · ${formatDurationCompact(course.totalDurationMs)}',
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 5,
                    runSpacing: 4,
                    children: [
                      if (course.productionYear != null)
                        ShanganBadge(
                          label: '${course.productionYear}',
                          tone: ShanganBadgeTone.blue,
                        ),
                      for (final tag in course.tags.take(2))
                        ShanganBadge(label: tag),
                    ],
                  ),
                  const SizedBox(height: 8),
                  TargetProgressBar(value: course.completedPercent / 100),
                  const SizedBox(height: 6),
                  Text(
                    '已看 ${course.completedCount} / ${course.resourceCount}'
                    ' · ${formatDurationCompact(course.watchedMs)}',
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 课程详情：封面头 + 课时列表（原型 4-3）。
final class CourseDetailPage extends ConsumerStatefulWidget {
  const CourseDetailPage({required this.courseId, super.key});

  final String courseId;

  @override
  ConsumerState<CourseDetailPage> createState() => _CourseDetailPageState();
}

class _CourseDetailPageState extends ConsumerState<CourseDetailPage> {
  final _keyword = TextEditingController();
  bool _searchOpen = false;

  @override
  void dispose() {
    _keyword.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(courseDetailProvider(widget.courseId));
    final keyword = _keyword.text.trim();
    return Scaffold(
      body: SafeArea(
        child: detail.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(child: Text('课程详情加载失败：$error')),
          data: (data) {
            final resources = keyword.isEmpty
                ? data.resources
                : data.resources
                      .where((item) => item.title.contains(keyword))
                      .toList(growable: false);
            final next = data.resources
                .where(
                  (item) => item.measurable && item.progressPermille < 1000,
                )
                .firstOrNull;
            return ListView(
              padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
              children: [
                Row(
                  children: [
                    ShanganIconButton(
                      icon: Icons.chevron_right,
                      quarterTurns: 2,
                      semanticLabel: '返回',
                      onTap: () => Navigator.of(context).pop(),
                    ),
                    const Spacer(),
                    ShanganIconButton(
                      icon: Icons.search,
                      highlighted: _searchOpen,
                      semanticLabel: '搜索课时',
                      onTap: () => setState(() => _searchOpen = !_searchOpen),
                    ),
                  ],
                ),
                if (_searchOpen) ...[
                  const SizedBox(height: 12),
                  ShanganSearchField(
                    controller: _keyword,
                    hint: '搜索课时',
                    onChanged: (_) => setState(() {}),
                  ),
                ],
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ShanganCover(
                      letter: data.genres.isEmpty
                          ? '课'
                          : data.genres.first.substring(0, 1),
                      caption: data.tags.isEmpty ? '课程' : data.tags.first,
                      index: 0,
                      width: 88,
                      height: 118,
                    ),
                    const SizedBox(width: 13),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            data.summary.title,
                            style: const TextStyle(
                              fontSize: 18,
                              height: 1.28,
                              fontWeight: FontWeight.w800,
                              color: ShanganColors.course,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Wrap(
                            spacing: 5,
                            runSpacing: 4,
                            children: [
                              for (final genre in data.genres)
                                ShanganBadge(label: genre),
                              for (final tag in data.tags)
                                ShanganBadge(label: tag),
                              if (data.summary.productionYear != null)
                                ShanganBadge(
                                  label: '${data.summary.productionYear}',
                                ),
                            ],
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '人物：${data.people.isEmpty ? '未标注' : data.people.join('、')}'
                            ' · 来自 Emby 元数据',
                            style: const TextStyle(
                              fontSize: 12,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '${data.resources.length} 课时 · '
                            '${formatDurationCompact(data.summary.totalDurationMs)}',
                            style: const TextStyle(
                              fontSize: 12,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                          const SizedBox(height: 8),
                          TargetProgressBar(
                            value: data.summary.completedPercent / 100,
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '已看 ${data.summary.completedCount} / ${data.resources.length}'
                            ' · 累计 ${formatDurationCompact(data.summary.watchedMs)}',
                            style: const TextStyle(
                              fontSize: 11.5,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      flex: 13,
                      child: FilledButton.icon(
                        style: _smallFilledStyle,
                        onPressed: next == null
                            ? null
                            : () => _addToToday(data.summary),
                        icon: const Icon(Icons.play_arrow_rounded, size: 18),
                        label: Text(
                          next == null ? '已全部看完' : '继续${next.title}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                    const SizedBox(width: 9),
                    Expanded(
                      flex: 10,
                      child: OutlinedButton.icon(
                        style: _smallOutlinedStyle,
                        onPressed: () => _addToToday(data.summary),
                        icon: const Icon(Icons.add, size: 18),
                        label: const Text('批量加入'),
                      ),
                    ),
                  ],
                ),
                const ShanganGroupLabel('课时'),
                ShanganCard(
                  padding: const EdgeInsets.symmetric(horizontal: 13),
                  child: Column(
                    children: [
                      for (
                        var index = 0;
                        index < resources.length;
                        index++
                      ) ...[
                        if (index > 0)
                          const Divider(height: 1, color: ShanganColors.hair),
                        _ResourceRow(
                          resource: resources[index],
                          onAdd: () => _addToToday(data.summary),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  static final _smallFilledStyle = FilledButton.styleFrom(
    minimumSize: const Size.fromHeight(40),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  static final _smallOutlinedStyle = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(40),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  /// 打开 2-2 的选课时步骤，把课时加入今日待办。
  Future<void> _addToToday(CourseSummary course) async {
    try {
      // 添加动作重新读取账号“今天”，避免页面跨午夜停留后仍提交昨天。
      final today = (await ref.read(shanganRepositoryProvider).loadDay()).date;
      if (!mounted) return;
      final created = await showCourseResourcePicker(
        context,
        course: course,
        date: today,
      );
      if (created && mounted) {
        ref.invalidate(dayViewProvider);
        ref.invalidate(courseDetailProvider(widget.courseId));
      }
    } catch (_) {
      if (mounted) ShanganFeedback.show(context, '读取今日日期失败，请稍后重试', error: true);
    }
  }
}

/// 原型 4-3 的课时行：tick + 标题 + 时长/进度/附件 + 行尾操作。
final class _ResourceRow extends StatelessWidget {
  const _ResourceRow({required this.resource, required this.onAdd});

  final CourseResource resource;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final isDocument = resource.resourceType == ResourceType.document;
    final done = resource.completedBefore || resource.progressPermille >= 1000;
    final started = resource.progressPermille > 0 && !done;
    return Opacity(
      opacity: isDocument ? 0.55 : 1,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 11),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: ShanganTick(
                color: isDocument ? ShanganColors.green : ShanganColors.course,
                done: done,
                half: started,
              ),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    resource.title,
                    style: const TextStyle(
                      fontSize: 14,
                      height: 1.35,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 5),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (isDocument) ...[
                        const ShanganBadge(
                          label: '材料',
                          tone: ShanganBadgeTone.green,
                        ),
                        ShanganMeta('${resource.pageCount ?? 0} 页'),
                        const ShanganMeta('后续版本启用'),
                      ] else ...[
                        ShanganMeta(formatPosition(resource.durationMs ?? 0)),
                        ShanganMeta(
                          done
                              ? '100%'
                              : resource.progressPermille == 0
                              ? '未看'
                              : '${resource.progressPermille ~/ 10}%',
                        ),
                        if (!resource.measurable)
                          const ShanganBadge(
                            label: '不可加入',
                            tone: ShanganBadgeTone.ink,
                          ),
                      ],
                    ],
                  ),
                  if (started) ...[
                    const SizedBox(height: 6),
                    TargetProgressBar(value: resource.progressPermille / 1000),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 8),
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: ShanganActButton(
                icon: isDocument ? Icons.picture_as_pdf_outlined : Icons.add,
                tone: isDocument || done
                    ? ShanganActTone.flat
                    : ShanganActTone.course,
                semanticLabel: '把 ${resource.title} 加入今日待办',
                onTap: isDocument || !resource.measurable ? null : onAdd,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
