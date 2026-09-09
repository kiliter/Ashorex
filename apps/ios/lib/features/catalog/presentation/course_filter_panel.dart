import 'package:flutter/material.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

/// 共享的课程筛选面板：每类多选，只有应用时才提交草稿。
Future<CatalogFilter?> showCourseFilterPanel(
  BuildContext context, {
  required CatalogFilter selected,
  required List<String> genres,
  required List<String> people,
  required List<String> tags,
}) => showGeneralDialog<CatalogFilter>(
  context: context,
  barrierDismissible: true,
  barrierLabel: '关闭筛选',
  transitionDuration: const Duration(milliseconds: 220),
  // 只平移缓存的弹层子树，动画帧不重新构建全部候选。
  transitionBuilder: (context, animation, secondaryAnimation, child) =>
      SlideTransition(
        position: Tween<Offset>(begin: const Offset(-1, 0), end: Offset.zero)
            .animate(
              CurvedAnimation(
                parent: animation,
                curve: Curves.easeOutCubic,
                reverseCurve: Curves.easeInCubic,
              ),
            ),
        child: child,
      ),
  // 弹层不会由 Scaffold 自动避让键盘，必须显式缩减可用高度。
  pageBuilder: (context, animation, secondaryAnimation) => Padding(
    padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
    child: Align(
      alignment: Alignment.centerLeft,
      child: SizedBox(
        width: MediaQuery.sizeOf(context).width.clamp(0, 420).toDouble(),
        child: Material(
          child: SafeArea(
            child: _FilterPanel(
              selected: selected,
              groups: [genres, people, tags],
            ),
          ),
        ),
      ),
    ),
  ),
);

class _FilterPanel extends StatefulWidget {
  const _FilterPanel({required this.selected, required this.groups});
  final CatalogFilter selected;
  final List<List<String>> groups;
  @override
  State<_FilterPanel> createState() => _FilterPanelState();
}

class _FilterPanelState extends State<_FilterPanel> {
  late final List<Set<String>> _values = [
    widget.selected.selectedGenres,
    widget.selected.selectedPeople,
    widget.selected.selectedTags,
  ];
  double _dismissDrag = 0;
  int _category = 0;
  String _query = '';
  final _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  /// 高度不足时搜索框与关闭入口共用一行，给候选列表和应用按钮留空间。
  Widget _searchField() => TextField(
    controller: _searchController,
    decoration: const InputDecoration(
      hintText: '搜索筛选标签',
      prefixIcon: Icon(Icons.search),
    ),
    onChanged: (value) => setState(() => _query = value.trim()),
  );

  /// 保留调用方分组和搜索状态，面板只改变三个元数据条件。
  CatalogFilter _result() => CatalogFilter(
    genres: Set.unmodifiable(_values[0]),
    people: Set.unmodifiable(_values[1]),
    tags: Set.unmodifiable(_values[2]),
    groupByPerson: widget.selected.groupByPerson,
    query: widget.selected.query,
    year: widget.selected.year,
  );

  /// 长候选列表只构建可见标签，避免打开动画前一次布局全部 Emby 元数据。
  Widget _candidates() {
    final candidates = widget.groups[_category]
        .where((v) => v.contains(_query))
        .toList(growable: false);
    return GridView.builder(
      key: ValueKey('$_category/$_query'),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 180,
        mainAxisExtent: 52,
        crossAxisSpacing: 8,
        mainAxisSpacing: 4,
      ),
      itemCount: candidates.length + 1,
      itemBuilder: (context, index) {
        if (index == 0) {
          return ChoiceChip(
            label: const Text('全部'),
            selected: _values[_category].isEmpty,
            onSelected: (_) => setState(() => _values[_category].clear()),
          );
        }
        final value = candidates[index - 1];
        return Tooltip(
          message: value,
          child: ChoiceChip(
            label: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis),
            selected: _values[_category].contains(value),
            onSelected: (on) => setState(() {
              if (on) {
                _values[_category].add(value);
              } else {
                _values[_category].remove(value);
              }
            }),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    // 左滑按取消处理，不提交草稿；竖向手势仍交给候选列表滚动。
    onHorizontalDragStart: (_) => _dismissDrag = 0,
    onHorizontalDragUpdate: (details) => _dismissDrag += details.delta.dx,
    onHorizontalDragEnd: (details) {
      if (_dismissDrag < -60 || (details.primaryVelocity ?? 0) < -350) {
        Navigator.pop(context);
      }
    },
    child: LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxHeight < 280;
        return Padding(
          padding: EdgeInsets.all(compact ? 8 : 16),
          child: Column(
            children: [
              if (!compact)
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '筛选课程',
                        style: TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '取消筛选',
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              if (compact)
                Row(
                  children: [
                    Expanded(child: _searchField()),
                    IconButton(
                      tooltip: '取消筛选',
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                )
              else
                _searchField(),
              SizedBox(height: compact ? 4 : 12),
              Expanded(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 84,
                      child: ListView(
                        children: [
                          for (var i = 0; i < 3; i++)
                            ListTile(
                              contentPadding: EdgeInsets.zero,
                              title: Text(['流派', '人物', '标签'][i]),
                              selected: _category == i,
                              onTap: () => setState(() => _category = i),
                            ),
                        ],
                      ),
                    ),
                    Expanded(child: _candidates()),
                  ],
                ),
              ),
              Row(
                children: [
                  TextButton(
                    onPressed: () => setState(() {
                      for (final values in _values) {
                        values.clear();
                      }
                    }),
                    child: const Text('清空选择'),
                  ),
                  const SizedBox(width: 12),
                  // 全局主按钮为全宽样式，必须约束宽度，避免 Row 中无限宽导致按钮消失。
                  Expanded(
                    child: FilledButton(
                      onPressed: () => Navigator.pop(context, _result()),
                      child: const Text('应用筛选'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    ),
  );
}

/// 固定区仅展示已选标签；横向滚动防止长标签挤占课程空间。
class SelectedCourseFilters extends StatelessWidget {
  const SelectedCourseFilters({
    super.key,
    required this.filter,
    required this.onChanged,
    required this.onClear,
  });
  final CatalogFilter filter;
  final ValueChanged<CatalogFilter> onChanged;
  final VoidCallback onClear;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (final value in filter.selectedGenres)
                InputChip(
                  label: Text('流派：$value'),
                  onDeleted: () => onChanged(
                    filter.copyWith(
                      genres: filter.selectedGenres..remove(value),
                    ),
                  ),
                ),
              for (final value in filter.selectedPeople)
                InputChip(
                  label: Text('人物：$value'),
                  onDeleted: () => onChanged(
                    filter.copyWith(
                      people: filter.selectedPeople..remove(value),
                    ),
                  ),
                ),
              for (final value in filter.selectedTags)
                InputChip(
                  label: Text('标签：$value'),
                  onDeleted: () => onChanged(
                    filter.copyWith(tags: filter.selectedTags..remove(value)),
                  ),
                ),
              if (!filter.hasTags) const Text('全部课程'),
            ],
          ),
        ),
      ),
      TextButton(onPressed: onClear, child: const Text('一键清空')),
    ],
  );
}
