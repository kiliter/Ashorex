import 'dart:async';
import 'dart:typed_data';
import 'course_filter_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/layout/adaptive_breakpoints.dart';
import 'package:shangan_ios/core/layout/pad_chrome.dart';
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
  const LibraryPage({this.addDate, this.onCreated, this.onClose, super.key});

  /// 选课弹窗场景：课时加入待办的目标日期；为空时按账号「今天」计算。
  final DateTime? addDate;

  /// 选课弹窗场景：课时成功加入待办后的回调，供外层记录刷新标记。
  final VoidCallback? onCreated;

  /// 嵌入弹窗时在页头提供关闭按钮；独立 Tab 页面为 null。
  final VoidCallback? onClose;

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

  /// 仅用于 Pad 双栏的局部选中态；手机仍使用原有 /courses/:courseId 路由。
  /// 关闭动画播完前保留内容不清空，避免课时面板提前闪没。
  String? _selectedCourseId;

  /// 抽屉的目标开合状态；与 `_selectedCourseId` 分离后关闭才有滑出动画。
  bool _drawerOpen = false;

  /// 抽屉进出场动画时长：偏短的 easeOutCubic，跟手但不生硬。
  static const _kDrawerDuration = Duration(milliseconds: 260);

  /// 打开课时抽屉；重复点击另一门课程时只切换内容，不重新播动画。
  void _openCourseDrawer(String courseId) => setState(() {
    _selectedCourseId = courseId;
    _drawerOpen = true;
  });

  /// 开始关闭抽屉；子树在滑出动画结束后由 `_onDrawerExitEnd` 移除。
  void _closeCourseDrawer() => setState(() => _drawerOpen = false);

  /// 关闭动画播完后清空选中课程，让抽屉子树完全退出组件树。
  void _onDrawerExitEnd() {
    if (mounted && !_drawerOpen) {
      setState(() => _selectedCourseId = null);
    }
  }

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
        final pad = AppBreakpoints.usePadLayoutOf(context);
        final visibleCount = courses.maybeWhen(
          data: (list) => _visibleCourses(
            list: list,
            selectedCategory: selectedCategory,
            groupByPerson: filter.groupByPerson,
            hideFullyWatched: _hideFullyWatched,
            keyword: keyword,
          ).length,
          orElse: () => null,
        );
        final master = GestureDetector(
          behavior: HitTestBehavior.translucent,
          onHorizontalDragStart: (_) => _filterDrag = 0,
          onHorizontalDragUpdate: (details) => _filterDrag += details.delta.dx,
          onHorizontalDragEnd: (details) {
            if (_filterDrag > 60 || (details.primaryVelocity ?? 0) > 150) {
              _openFilters();
            }
          },
          child: Padding(
            padding: EdgeInsets.fromLTRB(
              pad ? 28 : 18,
              pad ? 12 : 8,
              pad ? 28 : 18,
              0,
            ),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (!compact)
                            Text(
                              'LIBRARY',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: pad ? 9 : 11.5,
                                fontWeight: FontWeight.w700,
                                letterSpacing: pad ? 1.8 : 1.6,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          Text(
                            '课程库',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: compact
                                  ? 20
                                  : pad
                                  ? 27
                                  : 26,
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
                      highlighted: pad || _searchOpen,
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
                    if (widget.onClose != null) ...[
                      const SizedBox(width: 9),
                      ShanganIconButton(
                        icon: Icons.close,
                        semanticLabel: '关闭课程选择',
                        onTap: widget.onClose!,
                      ),
                    ],
                  ],
                ),
                SizedBox(height: compact ? 4 : (pad ? 12 : 12)),
                if (pad)
                  _PadLibraryControls(
                    grouping: grouping,
                    categories: _CategoryRail(
                      key: ValueKey(filter.groupByPerson),
                      categories: categories,
                      selected: selectedCategory,
                      onSelected: (value) =>
                          setState(() => _browseCategory = value),
                    ),
                    search: ShanganSearchField(
                      controller: _keyword,
                      hint: '搜索课程或讲师',
                      onChanged: _search,
                    ),
                    options: _LibraryOptions(
                      count: visibleCount,
                      hideFullyWatched: _hideFullyWatched,
                      onToggleHide: () => setState(
                        () => _hideFullyWatched = !_hideFullyWatched,
                      ),
                    ),
                    selectedFilters: selectedFilters,
                  )
                else ...[
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
                  _CategoryRail(
                    key: ValueKey(filter.groupByPerson),
                    categories: categories,
                    selected: selectedCategory,
                    onSelected: (value) =>
                        setState(() => _browseCategory = value),
                  ),
                  if (!compact)
                    _LibraryOptions(
                      count: visibleCount,
                      hideFullyWatched: _hideFullyWatched,
                      onToggleHide: () => setState(
                        () => _hideFullyWatched = !_hideFullyWatched,
                      ),
                    ),
                ],
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
                        final visible = _visibleCourses(
                          list: list,
                          selectedCategory: selectedCategory,
                          groupByPerson: filter.groupByPerson,
                          hideFullyWatched: _hideFullyWatched,
                          keyword: keyword,
                        );
                        return _FolderCourses(
                          courses: visible,
                          padWall: pad,
                          selectedCourseId: _selectedCourseId,
                          onTap: (course) async {
                            ref.invalidate(courseDetailProvider(course.id));
                            if (pad) {
                              _openCourseDrawer(course.id);
                              return;
                            }
                            await context.push('/courses/${course.id}');
                            if (context.mounted) {
                              ref.invalidate(libraryCoursesSnapshotProvider);
                            }
                          },
                        );
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
        if (!pad) return master;
        final drawerWidth = constraints.maxWidth < PadChrome.drawerWidth + 26
            ? (constraints.maxWidth - 26).clamp(280.0, PadChrome.drawerWidth)
            : PadChrome.drawerWidth;
        return PopScope(
          canPop: !_drawerOpen,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop && _drawerOpen) _closeCourseDrawer();
          },
          child: Stack(
            children: [
              master,
              // 抽屉在关闭动画播完前一直留在树中（`_selectedCourseId` 不为空），
              // 用透明度 + 轻微右移做进出场，替代原来的生硬闪现。
              if (_selectedCourseId != null) ...[
                Positioned.fill(
                  child: AnimatedOpacity(
                    opacity: _drawerOpen ? 1 : 0,
                    duration: _kDrawerDuration,
                    curve: Curves.easeOutCubic,
                    onEnd: _onDrawerExitEnd,
                    child: IgnorePointer(
                      ignoring: !_drawerOpen,
                      child: GestureDetector(
                        key: const ValueKey('library-drawer-scrim'),
                        behavior: HitTestBehavior.opaque,
                        onTap: _closeCourseDrawer,
                        child: const ColoredBox(color: Color(0x1C263B60)),
                      ),
                    ),
                  ),
                ),
                AnimatedSlide(
                  offset: _drawerOpen ? Offset.zero : const Offset(0.06, 0),
                  duration: _kDrawerDuration,
                  curve: Curves.easeOutCubic,
                  child: AnimatedOpacity(
                    opacity: _drawerOpen ? 1 : 0,
                    duration: _kDrawerDuration,
                    curve: Curves.easeOutCubic,
                    child: IgnorePointer(
                      ignoring: !_drawerOpen,
                      child: Align(
                        alignment: Alignment.centerRight,
                        // 抽屉做成圆角浮层卡片：上下留呼吸边距，左侧柔和投影，
                        // 与 Pad 卡片语言（18+ 圆角、浅描边、轻投影）保持一致。
                        child: Container(
                          key: const ValueKey('library-lesson-drawer'),
                          width: drawerWidth,
                          margin: const EdgeInsets.symmetric(vertical: 10),
                          decoration: const BoxDecoration(
                            color: ShanganColors.surface,
                            borderRadius: BorderRadius.all(Radius.circular(22)),
                            boxShadow: [
                              BoxShadow(
                                color: Color(0x2E263B60),
                                blurRadius: 40,
                                offset: Offset(-10, 0),
                              ),
                            ],
                          ),
                          child: ClipRRect(
                            borderRadius: const BorderRadius.all(
                              Radius.circular(22),
                            ),
                            child: Material(
                              color: Colors.transparent,
                              child: CourseDetailPage(
                                key: ValueKey('pad-course-$_selectedCourseId'),
                                courseId: _selectedCourseId!,
                                embedded: true,
                                addDate: widget.addDate,
                                onCreated: widget.onCreated,
                                onClose: _closeCourseDrawer,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}

List<CourseSummary> _visibleCourses({
  required List<CourseSummary> list,
  required String? selectedCategory,
  required bool groupByPerson,
  required bool hideFullyWatched,
  required String keyword,
}) => list
    .where(
      (course) =>
          (selectedCategory == null ||
              _courseCategories(
                course,
                groupByPerson,
              ).contains(selectedCategory)) &&
          (!hideFullyWatched || !course.fullyWatched) &&
          (keyword.isEmpty ||
              course.title.contains(keyword) ||
              course.people.any((person) => person.contains(keyword))),
    )
    .toList(growable: false);

String _courseMeta(CourseSummary course) => [
  if (course.people.isNotEmpty) course.people.first,
  '${course.resourceCount} 课时',
].join(' · ');

/// 分类来自 Emby，空元数据仅使用可读的本地兜底分组。
List<String> _courseCategories(CourseSummary course, bool byPerson) => byPerson
    ? (course.people.isEmpty ? ['未标注人物'] : course.people)
    : (course.genres.isEmpty ? ['未分类'] : course.genres);

/// Pad 课程墙工具行：流派切换、分类、搜索同一行，不挤压课程网格。
final class _PadLibraryControls extends StatelessWidget {
  const _PadLibraryControls({
    required this.grouping,
    required this.categories,
    required this.search,
    required this.options,
    required this.selectedFilters,
  });

  final Widget grouping;
  final Widget categories;
  final Widget search;
  final Widget options;
  final Widget selectedFilters;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        LayoutBuilder(
          builder: (context, constraints) {
            final stacked = constraints.maxWidth < 800;
            if (stacked) {
              return Column(
                children: [
                  Row(
                    children: [
                      Expanded(child: search),
                      const SizedBox(width: 12),
                      SizedBox(width: 172, child: grouping),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(child: categories),
                      const SizedBox(width: 8),
                      Flexible(child: options),
                    ],
                  ),
                  selectedFilters,
                ],
              );
            }
            return Column(
              children: [
                Row(
                  children: [
                    SizedBox(width: 176, child: grouping),
                    const SizedBox(width: 15),
                    Expanded(child: categories),
                    const SizedBox(width: 15),
                    SizedBox(width: 250, child: search),
                  ],
                ),
                const SizedBox(height: 8),
                options,
                selectedFilters,
              ],
            );
          },
        ),
        const SizedBox(height: 8),
      ],
    );
  }
}

/// 高保真课程库的数量与隐藏开关；只消费已有课程快照，不增加任何接口。
final class _LibraryOptions extends StatelessWidget {
  const _LibraryOptions({
    required this.count,
    required this.hideFullyWatched,
    required this.onToggleHide,
  });

  final int? count;
  final bool hideFullyWatched;
  final VoidCallback onToggleHide;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(2, 2, 2, 8),
    child: Row(
      children: [
        Flexible(
          child: Text(
            count == null ? '课程加载中' : '共 $count 门课程',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 10.5,
              color: ShanganColors.mutedInk,
            ),
          ),
        ),
        const SizedBox(width: 8),
        Flexible(
          child: Semantics(
            button: true,
            selected: hideFullyWatched,
            label: '隐藏已看完',
            child: InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: onToggleHide,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 14,
                      height: 14,
                      decoration: BoxDecoration(
                        color: hideFullyWatched
                            ? ShanganColors.blue
                            : Colors.transparent,
                        border: Border.all(
                          color: hideFullyWatched
                              ? ShanganColors.blue
                              : ShanganColors.rule,
                        ),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: hideFullyWatched
                          ? const Icon(
                              Icons.check,
                              size: 10,
                              color: Colors.white,
                            )
                          : null,
                    ),
                    const SizedBox(width: 6),
                    const Flexible(
                      child: Text(
                        '隐藏已看完',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 10.5,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

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

/// 课程目录继续按需构建：手机保持封面网格，Pad 改为矮封面课程墙，点选后不重排。
final class _FolderCourses extends ConsumerWidget {
  const _FolderCourses({
    required this.courses,
    required this.onTap,
    required this.padWall,
    this.selectedCourseId,
  });

  final List<CourseSummary> courses;
  final ValueChanged<CourseSummary> onTap;
  final bool padWall;
  final String? selectedCourseId;

  @override
  Widget build(BuildContext context, WidgetRef ref) => LayoutBuilder(
    builder: (context, constraints) {
      final scaler = MediaQuery.textScalerOf(context);
      final compact = constraints.maxHeight < 110;
      final columns = padWall
          ? (constraints.maxWidth / 172).floor().clamp(2, 8)
          : constraints.maxWidth < 600
          ? 2
          : (constraints.maxWidth / 180).floor().clamp(3, 8);
      final gap = padWall ? 12.0 : 10.0;
      final tileWidth = (constraints.maxWidth - (columns - 1) * gap) / columns;
      final coverHeight = padWall ? 68.0 : (tileWidth - 22) / 1.15;
      final extent = compact
          ? scaler.scale(12) * 2.8 + 36
          : padWall
          ? coverHeight + scaler.scale(12) * 2.8 + scaler.scale(10) * 3 + 52
          : coverHeight + scaler.scale(12) * 2.8 + scaler.scale(10) * 3 + 70;
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
                crossAxisSpacing: gap,
                mainAxisSpacing: gap,
                mainAxisExtent: extent,
              ),
              delegate: SliverChildBuilderDelegate((context, index) {
                final course = courses[index];
                return _CoverTile(
                  compact: compact,
                  padCompact: padWall,
                  selected: selectedCourseId == course.id,
                  title: course.title,
                  courseId: course.id,
                  meta: _courseMeta(course),
                  caption: course.fullyWatched
                      ? '已全部看完'
                      : '已完成 ${course.completedCount}/${course.resourceCount}',
                  completedPercent: course.completedPercent,
                  onTap: () => onTap(course),
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

/// 手机课程卡片保持双列结构；Pad 课程墙改用矮封面，选中时只加描边，不改变网格。
final class _CoverTile extends ConsumerWidget {
  const _CoverTile({
    required this.title,
    required this.courseId,
    required this.meta,
    required this.caption,
    required this.completedPercent,
    required this.onTap,
    this.compact = false,
    this.padCompact = false,
    this.selected = false,
  });

  final bool compact;
  final bool padCompact;
  final bool selected;
  final String courseId;
  final String title;
  final String meta;
  final String caption;
  final int completedPercent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Semantics(
    button: true,
    selected: selected,
    label: '$title，$caption',
    onTap: onTap,
    excludeSemantics: true,
    child: Tooltip(
      message: title,
      child: Material(
        color: ShanganColors.surface,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(padCompact ? 12 : 15),
          side: BorderSide(
            color: selected
                ? ShanganColors.blue
                : padCompact
                ? ShanganColors.hair
                : ShanganColors.rule,
            width: selected || !padCompact ? ShanganRadius.borderWidth : 1,
          ),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(padCompact ? 12 : 15),
          onTap: onTap,
          child: Padding(
            padding: EdgeInsets.all(padCompact ? 8 : 11),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (!compact) ...[
                  SizedBox(
                    height: padCompact ? 68 : null,
                    width: double.infinity,
                    child: AspectRatio(
                      aspectRatio: padCompact ? 2.4 : 1.15,
                      child: Stack(
                        children: [
                          Positioned.fill(
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(7),
                              child: ref
                                  .watch(_courseCoverProvider(courseId))
                                  .when(
                                    data: (bytes) => Image.memory(
                                      bytes,
                                      fit: BoxFit.cover,
                                      cacheWidth: padCompact ? 320 : 480,
                                      errorBuilder: (_, _, _) =>
                                          _TextCover(title: title),
                                    ),
                                    loading: () => _TextCover(title: title),
                                    error: (_, _) => _TextCover(title: title),
                                  ),
                            ),
                          ),
                          if (selected)
                            const Positioned(
                              right: 6,
                              top: 6,
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  color: ShanganColors.blue,
                                  shape: BoxShape.circle,
                                  boxShadow: [
                                    BoxShadow(
                                      color: Color(0xA0FFFFFF),
                                      blurRadius: 4,
                                      spreadRadius: 3,
                                    ),
                                  ],
                                ),
                                child: SizedBox(width: 7, height: 7),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                  SizedBox(height: padCompact ? 8 : 8),
                ],
                Text(
                  title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.4,
                    fontWeight: FontWeight.w700,
                    color: padCompact
                        ? ShanganColors.ink
                        : ShanganColors.course,
                  ),
                ),
                if (!compact) ...[
                  const SizedBox(height: 4),
                  Text(
                    meta,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 10,
                      color: ShanganColors.mutedInk,
                    ),
                  ),
                  const Spacer(),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          caption,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 9.5,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        '$completedPercent%',
                        style: const TextStyle(
                          fontSize: 9.5,
                          fontWeight: FontWeight.w700,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  TargetProgressBar(value: completedPercent / 100, height: 5),
                ],
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
  const CourseDetailPage({
    required this.courseId,
    this.embedded = false,
    this.onClose,
    this.addDate,
    this.onCreated,
    super.key,
  });

  final String courseId;

  /// Pad 抽屉直接嵌入内容；手机路由保持完整 Scaffold 与返回按钮。
  final bool embedded;

  /// Pad 覆盖抽屉的收起回调；手机页仍走 Navigator.pop。
  final VoidCallback? onClose;

  /// 选课弹窗场景：课时加入待办的目标日期；为空时读取账号「今天」。
  final DateTime? addDate;

  /// 课时成功加入待办后的回调，供选课弹窗通知外层刷新。
  final VoidCallback? onCreated;

  @override
  ConsumerState<CourseDetailPage> createState() => _CourseDetailPageState();
}

/// 只剥离最外层 Scaffold，课程详情的数据、交互和 Provider 仍完全共用。
final class _CourseDetailSurface extends StatelessWidget {
  const _CourseDetailSurface({required this.embedded, required this.child});

  final bool embedded;
  final Widget child;

  @override
  Widget build(BuildContext context) =>
      embedded ? child : Scaffold(body: SafeArea(child: child));
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
    return _CourseDetailSurface(
      embedded: widget.embedded,
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
              .where((item) => item.measurable && item.progressPermille < 1000)
              .firstOrNull;
          // 列表占用剩余高度；横屏和键盘下收起摘要，操作区仍保持固定。
          return LayoutBuilder(
            builder: (context, constraints) {
              final compact =
                  constraints.maxHeight < 460 ||
                  MediaQuery.textScalerOf(context).scale(14) > 22;
              // 嵌入抽屉时顶部多留一点边距，内容不贴近浮层的圆角。
              return Padding(
                padding: EdgeInsets.fromLTRB(
                  18,
                  widget.embedded ? 14 : 8,
                  18,
                  0,
                ),
                child: Column(
                  children: [
                    Row(
                      children: [
                        if (!widget.embedded) ...[
                          ShanganIconButton(
                            icon: Icons.chevron_right,
                            quarterTurns: 2,
                            semanticLabel: '返回',
                            onTap: () => Navigator.of(context).pop(),
                          ),
                          const SizedBox(width: 8),
                        ],
                        if (widget.embedded)
                          const Padding(
                            padding: EdgeInsets.only(right: 8),
                            child: Text(
                              '学习 / 课程详情',
                              style: TextStyle(
                                fontSize: 10,
                                color: ShanganColors.mutedInk,
                              ),
                            ),
                          ),
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
                                    key: const ValueKey('course-detail-title'),
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
                        if (widget.onClose != null) ...[
                          const SizedBox(width: 8),
                          ShanganIconButton(
                            icon: Icons.close,
                            semanticLabel: '收起课程详情',
                            onTap: widget.onClose!,
                          ),
                        ],
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
                    // 头部信息与课时列表之间补一条细线，视觉上分出两个区域。
                    const Divider(height: 1, color: ShanganColors.hair),
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
      // 选课弹窗指定目标日期；否则重新读取账号“今天”，
      // 避免页面跨午夜停留后仍提交昨天。
      final today =
          widget.addDate ??
          (await ref.read(shanganRepositoryProvider).loadDay()).date;
      if (!mounted) return;
      final created = await showCourseResourcePicker(
        context,
        course: course,
        date: today,
      );
      if (created && mounted) {
        ref.invalidate(dayViewProvider);
        ref.invalidate(courseDetailProvider(widget.courseId));
        widget.onCreated?.call();
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
