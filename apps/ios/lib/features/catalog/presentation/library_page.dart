import 'dart:async';
import 'dart:typed_data';
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
  bool _hideFullyWatched = false;

  /// 横向分类只用于当前浏览，不覆盖筛选面板的多选条件。
  String? _browseCategory;
  Timer? _debounce;
  String _appliedKeyword = '';
  bool _openingFilters = false;
  double _filterDrag = 0;

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
      // 预取失败时在本次点击重试，不能复用失败缓存。
      if (ref.read(catalogFacetsProvider).hasError) {
        ref.invalidate(catalogFacetsProvider);
      }
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
    // 预取只读候选，点击筛选时无需首次等待网络。
    ref.watch(catalogFacetsProvider);
    final courses = ref.watch(coursesProvider);
    final filter = ref.watch(catalogFilterProvider);
    final keyword = _appliedKeyword;
    final grouping = ShanganSegmented(
      labels: const ['按流派', '按人物'],
      selectedIndex: filter.groupByPerson ? 1 : 0,
      onChanged: (index) {
        setState(() => _browseCategory = null);
        ref.read(catalogFilterProvider.notifier).setGroupByPerson(index == 1);
      },
    );
    final selectedFilters = SelectedCourseFilters(
      filter: filter,
      onChanged: ref.read(catalogFilterProvider.notifier).apply,
      onClear: () {
        _keyword.clear();
        _search('');
        setState(() {
          _hideFullyWatched = false;
          _browseCategory = null;
        });
        if (filter.hasTags ||
            filter.year != null ||
            (filter.query?.isNotEmpty ?? false)) {
          ref
              .read(catalogFilterProvider.notifier)
              .apply(CatalogFilter(groupByPerson: filter.groupByPerson));
        }
      },
    );
    // 标签取自当前筛选结果的完整元数据；点击分类不反过来缩减标签候选。
    final categories = <String>{
      for (final course in courses.asData?.value ?? <CourseSummary>[])
        ..._courseCategories(course, filter.groupByPerson),
    }.toList();
    final selectedCategory = categories.contains(_browseCategory)
        ? _browseCategory
        : null;
    return LayoutBuilder(
      builder: (context, constraints) {
        // 横屏键盘展开后固定区压为两行，仍保留标题与所有筛选入口。
        final compact =
            constraints.maxHeight < 300 && constraints.maxWidth > 600;
        return GestureDetector(
          behavior: HitTestBehavior.translucent,
          onHorizontalDragStart: (_) => _filterDrag = 0,
          onHorizontalDragUpdate: (details) => _filterDrag += details.delta.dx,
          onHorizontalDragEnd: (details) {
            if (_filterDrag > 60 || (details.primaryVelocity ?? 0) > 150) {
              _openFilters();
            }
          },
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (!compact)
                            const Text(
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
                              fontSize: compact ? 20 : 26,
                              fontWeight: FontWeight.w800,
                              letterSpacing: -0.8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (compact)
                      ShanganIconButton(
                        icon: Icons.visibility_off_outlined,
                        highlighted: _hideFullyWatched,
                        semanticLabel: '隐藏已看完',
                        onTap: () => setState(
                          () => _hideFullyWatched = !_hideFullyWatched,
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
                      highlighted: filter.hasTags,
                      semanticLabel: '展开筛选',
                      onTap: _openFilters,
                    ),
                  ],
                ),
                SizedBox(height: compact ? 4 : 12),
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
                  Row(
                    children: [
                      Expanded(child: grouping),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('隐藏已看完'),
                        selected: _hideFullyWatched,
                        onSelected: (value) =>
                            setState(() => _hideFullyWatched = value),
                      ),
                    ],
                  ),
                  selectedFilters,
                ],
                _CategoryRail(
                  key: ValueKey(filter.groupByPerson),
                  categories: categories,
                  selected: selectedCategory,
                  onSelected: (value) =>
                      setState(() => _browseCategory = value),
                ),
                Expanded(
                  child: RefreshIndicator(
                    onRefresh: () async {
                      ref.invalidate(catalogFacetsProvider);
                      ref.invalidate(libraryCoursesSnapshotProvider);
                    },
                    child: courses.when(
                      loading: () =>
                          const Center(child: CircularProgressIndicator()),
                      error: (error, _) => CustomScrollView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        slivers: const [
                          SliverFillRemaining(
                            hasScrollBody: false,
                            child: Center(child: Text('课程列表加载失败，请下拉重试')),
                          ),
                        ],
                      ),
                      data: (list) {
                        // 只消费服务端看完结论；空课程与旧服务端响应不会被误隐藏。
                        final visible = list
                            .where(
                              (course) =>
                                  (selectedCategory == null ||
                                      _courseCategories(
                                        course,
                                        filter.groupByPerson,
                                      ).contains(selectedCategory)) &&
                                  (!_hideFullyWatched ||
                                      !course.fullyWatched) &&
                                  (keyword.isEmpty ||
                                      course.title.contains(keyword) ||
                                      course.people.any(
                                        (person) => person.contains(keyword),
                                      )),
                            )
                            .toList(growable: false);
                        return _FolderCourses(courses: visible);
                      },
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

/// 分类来自 Emby，空元数据仅使用可读的本地兜底分组。
List<String> _courseCategories(CourseSummary course, bool byPerson) => byPerson
    ? (course.people.isEmpty ? ['未标注人物'] : course.people)
    : (course.genres.isEmpty ? ['未分类'] : course.genres);

/// 分类栏独立横向滚动，优先接管左右拖动，避免误打开外层筛选抽屉。
class _CategoryRail extends StatelessWidget {
  const _CategoryRail({
    super.key,
    required this.categories,
    required this.selected,
    required this.onSelected,
  });
  final List<String> categories;
  final String? selected;
  final ValueChanged<String?> onSelected;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: MediaQuery.textScalerOf(context).scale(12) + 32,
    child: ListView.separated(
      key: const ValueKey('category-rail'),
      scrollDirection: Axis.horizontal,
      itemCount: categories.length + 1,
      separatorBuilder: (_, _) => const SizedBox(width: 8),
      itemBuilder: (context, index) {
        final name = index == 0 ? null : categories[index - 1];
        return Center(
          child: ChoiceChip(
            key: ValueKey(index == 0 ? 'category-all' : 'category-$name'),
            label: Text(name ?? '全部', style: const TextStyle(fontSize: 12)),
            selected: selected == name,
            showCheckmark: false,
            onSelected: (_) => onSelected(name),
          ),
        );
      },
    ),
  );
}

/// 双列封面目录按需构建；分类由固定横向栏完成，不再占用课程区重复展示分组标题。
final class _FolderCourses extends ConsumerWidget {
  const _FolderCourses({required this.courses});
  final List<CourseSummary> courses;

  @override
  Widget build(BuildContext context, WidgetRef ref) => LayoutBuilder(
    builder: (context, constraints) {
      final scaler = MediaQuery.textScalerOf(context);
      final compact = constraints.maxHeight < 110;
      final columns = constraints.maxWidth < 600
          ? 2
          : (constraints.maxWidth / 180).floor().clamp(3, 8);
      final coverHeight =
          ((constraints.maxWidth - (columns - 1) * 10) / columns - 16) / 1.6;
      return CustomScrollView(
        key: const PageStorageKey('library-folders'),
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          if (courses.isEmpty)
            const SliverFillRemaining(
              hasScrollBody: false,
              child: Center(child: Text('没有符合条件的课程')),
            )
          else
            SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: columns,
                crossAxisSpacing: 10,
                mainAxisSpacing: 10,
                mainAxisExtent:
                    (compact ? 20 : coverHeight + 28) +
                    scaler.scale(12) * 2.8 +
                    scaler.scale(11) * 1.4,
              ),
              delegate: SliverChildBuilderDelegate((context, index) {
                final course = courses[index];
                return _CoverTile(
                  compact: compact,
                  title: course.title,
                  courseId: course.id,
                  caption: course.fullyWatched
                      ? '已看完'
                      : '已完成 ${course.completedCount}/${course.resourceCount}',
                  onTap: () async {
                    // 进入和返回时刷新进度，避免看完筛选继续使用旧快照。
                    ref.invalidate(courseDetailProvider(course.id));
                    await context.push('/courses/${course.id}');
                    if (context.mounted) {
                      ref.invalidate(libraryCoursesSnapshotProvider);
                    }
                  },
                );
              }, childCount: courses.length),
            ),
          const SliverToBoxAdapter(child: SizedBox(height: 24)),
        ],
      );
    },
  );
}

/// 封面只在卡片进入构建范围后请求，离开后释放原始字节，图片缓存由 Flutter 管理。
final _courseCoverProvider = FutureProvider.autoDispose
    .family<Uint8List, String>(
      (ref, id) => ref.watch(shanganRepositoryProvider).loadCourseCover(id),
      retry: (count, error) => null,
    );

/// 参考片单卡片：横向封面在上，小字号两行标题在下，保留完整无障碍名称。
final class _CoverTile extends ConsumerWidget {
  const _CoverTile({
    required this.title,
    required this.courseId,
    required this.caption,
    required this.onTap,
    this.compact = false,
  });
  final bool compact;
  final String courseId;
  final String title;
  final String caption;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Semantics(
    button: true,
    label: '$title，$caption',
    onTap: onTap,
    excludeSemantics: true,
    child: Tooltip(
      message: title,
      child: Material(
        color: ShanganColors.inkSoft,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (!compact) ...[
                  AspectRatio(
                    aspectRatio: 1.6,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: ref
                          .watch(_courseCoverProvider(courseId))
                          .when(
                            data: (bytes) => Image.memory(
                              bytes,
                              fit: BoxFit.cover,
                              cacheWidth: 480,
                              errorBuilder: (_, _, _) =>
                                  _TextCover(title: title),
                            ),
                            loading: () => _TextCover(title: title),
                            error: (_, _) => _TextCover(title: title),
                          ),
                    ),
                  ),
                  const SizedBox(height: 8),
                ],
                Text(
                  title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.4,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const Spacer(),
                Text(
                  caption,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11,
                    height: 1.4,
                    color: ShanganColors.mutedInk,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

/// 文字书封：稳定配色、书脊和低对比暗纹；纯代码绘制，不生成或伪造课程图片。
class _TextCover extends StatelessWidget {
  const _TextCover({required this.title});
  final String title;
  static const _palettes = [
    [Color(0xFF182E49), Color(0xFF304966)],
    [Color(0xFF173D38), Color(0xFF34574D)],
    [Color(0xFF453243), Color(0xFF675065)],
    [Color(0xFF493B2D), Color(0xFF6B5640)],
  ];

  @override
  Widget build(BuildContext context) {
    // 标题相同始终使用同一配色，滚动和重建不会随机变色。
    final index = title.runes.fold<int>(
      0,
      (value, rune) => (value * 31 + rune) % _palettes.length,
    );
    final largeText = MediaQuery.textScalerOf(context).scale(12) > 16;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: _palettes[index],
        ),
      ),
      child: CustomPaint(
        painter: const _BookCoverLines(),
        child: Stack(
          children: [
            if (!largeText)
              const Positioned(
                left: 16,
                top: 10,
                child: Text(
                  '学习课程',
                  style: TextStyle(
                    fontSize: 8,
                    letterSpacing: 2,
                    color: Color(0xFFD6C29A),
                  ),
                ),
              ),
            Positioned.fill(
              left: 16,
              right: 12,
              top: largeText ? 10 : 28,
              bottom: 12,
              child: Align(
                alignment: Alignment.bottomLeft,
                child: RichText(
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  textScaler: MediaQuery.textScalerOf(context),
                  text: TextSpan(
                    text: title,
                    style: const TextStyle(
                      fontSize: 12,
                      height: 1.45,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFFFFF6E7),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 细金线模拟书脊，斜向暗纹形成材质层次；不依赖图片加载或动画。
class _BookCoverLines extends CustomPainter {
  const _BookCoverLines();
  @override
  void paint(Canvas canvas, Size size) {
    final texture = Paint()
      ..color = const Color(0x0CFFFFFF)
      ..strokeWidth = 0.5;
    for (double x = -size.height; x < size.width; x += 7) {
      canvas.drawLine(
        Offset(x, 0),
        Offset(x + size.height, size.height),
        texture,
      );
    }
    final spine = Paint()
      ..color = const Color(0x70D6C29A)
      ..strokeWidth = 0.8;
    canvas.drawLine(const Offset(6, 0), Offset(6, size.height), spine);
    final border = Paint()
      ..color = const Color(0x24FFFFFF)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.7;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0.5, 0.5, size.width - 1, size.height - 1),
        const Radius.circular(8),
      ),
      border,
    );
  }

  @override
  bool shouldRepaint(covariant _BookCoverLines oldDelegate) => false;
}

/// 课程详情：固定课程摘要与操作，仅剩余空间中的课时列表滚动（ADR-0044）。
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
            // 列表占用剩余高度；横屏和键盘下收起摘要，操作区仍保持固定。
            return LayoutBuilder(
              builder: (context, constraints) {
                final compact =
                    constraints.maxHeight < 460 ||
                    MediaQuery.textScalerOf(context).scale(14) > 22;
                return Padding(
                  padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
                  child: Column(
                    children: [
                      Row(
                        children: [
                          ShanganIconButton(
                            icon: Icons.chevron_right,
                            quarterTurns: 2,
                            semanticLabel: '返回',
                            onTap: () => Navigator.of(context).pop(),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: compact && _searchOpen
                                ? ShanganSearchField(
                                    controller: _keyword,
                                    hint: '搜索课时',
                                    onChanged: (_) => setState(() {}),
                                  )
                                : Tooltip(
                                    message: data.summary.title,
                                    child: Text(
                                      data.summary.title,
                                      key: const ValueKey(
                                        'course-detail-title',
                                      ),
                                      maxLines: compact ? 1 : 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        fontSize: 18,
                                        fontWeight: FontWeight.w800,
                                      ),
                                    ),
                                  ),
                          ),
                          ShanganIconButton(
                            icon: Icons.search,
                            highlighted: _searchOpen,
                            semanticLabel: '搜索课时',
                            onTap: () =>
                                setState(() => _searchOpen = !_searchOpen),
                          ),
                        ],
                      ),
                      if (!compact) ...[
                        const SizedBox(height: 8),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            '${data.resources.length} 课时 · ${formatDurationCompact(data.summary.totalDurationMs)}'
                            ' · 已完成 ${data.summary.completedCount}/${data.resources.length}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                        ),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            [
                              ...data.people,
                              ...data.genres,
                              ...data.tags,
                            ].join(' · '),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12,
                              color: ShanganColors.mutedInk,
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        TargetProgressBar(
                          value: data.summary.completedPercent / 100,
                        ),
                        if (_searchOpen) ...[
                          const SizedBox(height: 8),
                          ShanganSearchField(
                            controller: _keyword,
                            hint: '搜索课时',
                            onChanged: (_) => setState(() {}),
                          ),
                        ],
                      ],
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: FilledButton.icon(
                              style: _smallFilledStyle,
                              onPressed: next == null
                                  ? null
                                  : () => _addToToday(data.summary),
                              icon: const Icon(
                                Icons.play_arrow_rounded,
                                size: 18,
                              ),
                              label: Text(
                                data.summary.fullyWatched
                                    ? '已全部看完'
                                    : next == null
                                    ? '暂无可学课时'
                                    : '继续${next.title}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ),
                          const SizedBox(width: 9),
                          Expanded(
                            child: OutlinedButton.icon(
                              style: _smallOutlinedStyle,
                              onPressed: () => _addToToday(data.summary),
                              icon: const Icon(Icons.add, size: 18),
                              label: const Text(
                                '批量加入',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const Align(
                        alignment: Alignment.centerLeft,
                        child: Padding(
                          padding: EdgeInsets.symmetric(vertical: 6),
                          child: Text(
                            '课时',
                            style: TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                      ),
                      Expanded(
                        child: resources.isEmpty
                            ? const Center(child: Text('没有符合条件的课时'))
                            : ListView.separated(
                                key: const ValueKey('course-lessons'),
                                padding: const EdgeInsets.only(bottom: 24),
                                itemCount: resources.length,
                                separatorBuilder: (_, _) => const Divider(
                                  height: 1,
                                  color: ShanganColors.hair,
                                ),
                                itemBuilder: (context, index) => _ResourceRow(
                                  resource: resources[index],
                                  onAdd: () => _addToToday(data.summary),
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

  static final _smallFilledStyle = FilledButton.styleFrom(
    minimumSize: const Size.fromHeight(44),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  static final _smallOutlinedStyle = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(44),
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
