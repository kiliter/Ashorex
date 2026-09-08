import 'package:flutter/material.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';

/// 共享的课程筛选面板：每类单选，只有应用时才提交草稿。
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
  pageBuilder: (context, animation, secondaryAnimation) => Align(
    alignment: Alignment.centerRight,
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
);

class _FilterPanel extends StatefulWidget {
  const _FilterPanel({required this.selected, required this.groups});
  final CatalogFilter selected;
  final List<List<String>> groups;
  @override
  State<_FilterPanel> createState() => _FilterPanelState();
}

class _FilterPanelState extends State<_FilterPanel> {
  late final List<String?> _values = [
    widget.selected.genre,
    widget.selected.person,
    widget.selected.tag,
  ];
  int _category = 0;
  String _query = '';

  /// 保留调用方分组和搜索状态，面板只改变三个元数据条件。
  CatalogFilter _result() => CatalogFilter(
    genre: _values[0],
    person: _values[1],
    tag: _values[2],
    groupByPerson: widget.selected.groupByPerson,
    query: widget.selected.query,
    year: widget.selected.year,
  );

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(16),
    child: Column(
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '筛选课程',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
              ),
            ),
            IconButton(
              tooltip: '取消筛选',
              onPressed: () => Navigator.pop(context),
              icon: const Icon(Icons.close),
            ),
          ],
        ),
        TextField(
          decoration: const InputDecoration(
            hintText: '搜索筛选标签',
            prefixIcon: Icon(Icons.search),
          ),
          onChanged: (value) => setState(() => _query = value.trim()),
        ),
        const SizedBox(height: 12),
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
              Expanded(
                child: SingleChildScrollView(
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      ChoiceChip(
                        label: const Text('全部'),
                        selected: _values[_category] == null,
                        onSelected: (_) =>
                            setState(() => _values[_category] = null),
                      ),
                      for (final value in widget.groups[_category].where(
                        (v) => v.contains(_query),
                      ))
                        ChoiceChip(
                          label: Text(value),
                          selected: _values[_category] == value,
                          onSelected: (on) => setState(
                            () => _values[_category] = on ? value : null,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        Row(
          children: [
            TextButton(
              onPressed: () => setState(() => _values.fillRange(0, 3, null)),
              child: const Text('清空选择'),
            ),
            const Spacer(),
            FilledButton(
              onPressed: () => Navigator.pop(context, _result()),
              child: const Text('应用筛选'),
            ),
          ],
        ),
      ],
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
              if (filter.genre != null)
                InputChip(
                  label: Text('流派：${filter.genre}'),
                  onDeleted: () => onChanged(filter.copyWith(clearGenre: true)),
                ),
              if (filter.person != null)
                InputChip(
                  label: Text('人物：${filter.person}'),
                  onDeleted: () =>
                      onChanged(filter.copyWith(clearPerson: true)),
                ),
              if (filter.tag != null)
                InputChip(
                  label: Text('标签：${filter.tag}'),
                  onDeleted: () => onChanged(filter.copyWith(clearTag: true)),
                ),
              if (filter.genre == null &&
                  filter.person == null &&
                  filter.tag == null)
                const Text('全部课程'),
            ],
          ),
        ),
      ),
      TextButton(onPressed: onClear, child: const Text('一键清空')),
    ],
  );
}
