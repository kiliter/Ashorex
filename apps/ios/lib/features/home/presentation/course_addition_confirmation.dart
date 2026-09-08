import 'package:flutter/material.dart';
import 'package:shangan_ios/core/models/course_addition_models.dart';

/// 只收集明确确认的原待办 ID；取消返回 null，调用方不得提交创建或顺延。
Future<List<String>?> confirmCourseAdditions(
  BuildContext context,
  List<CourseAdditionItem> items, {
  int targetProgressPermille = 1000,
}) {
  final selected = <String, String>{};
  for (final item in items.where((item) => item.history.isNotEmpty)) {
    // 只有一条历史时默认勾选；有多条时必须明确选一条，避免任意合并历史。
    selected[item.resourceId] = item.history.length == 1
        ? item.history.single.id
        : '';
  }
  return showDialog<List<String>>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: const Text('确认添加课时'),
        scrollable: true,
        content: SizedBox(
          width: 480,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '可新增 ${items.where((item) => item.status == 'NEW').length} 个课时；当日已有的安排将跳过。',
              ),
              const SizedBox(height: 12),
              const Text('复用或顺延原待办，目标取两者较高值；保留进度、时长、备注和附件。选择跳过则不添加第二条。'),
              for (final item in items.where(
                (item) => item.status != 'NEW',
              )) ...[
                const SizedBox(height: 16),
                Text(
                  item.title,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (item.history.isNotEmpty)
                  DropdownButtonFormField<String>(
                    initialValue: selected[item.resourceId],
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: '选择要复用或顺延的原待办',
                    ),
                    items: [
                      const DropdownMenuItem(value: '', child: Text('跳过此课时')),
                      for (final old in item.history)
                        DropdownMenuItem(
                          value: old.id,
                          child: Text(
                            '${old.localDate} · 目标 ${old.targetProgressPermille / 10}% → ${(old.targetProgressPermille > targetProgressPermille ? old.targetProgressPermille : targetProgressPermille) / 10}% · 已看 ${old.progressPositionMs ~/ 60000} 分钟',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (value) =>
                        setState(() => selected[item.resourceId] = value ?? ''),
                  )
                else
                  Text(
                    item.status == 'DUPLICATE'
                        ? '本批重复选择，将跳过。'
                        : item.status == 'TARGET_CONFLICT'
                        ? '当日已有课时已完成，保留完成记录，本次跳过。'
                        : '当日已添加，将跳过。',
                  ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(
              context,
              selected.values.where((id) => id.isNotEmpty).toSet().toList(),
            ),
            child: const Text('确认添加与复用'),
          ),
        ],
      ),
    ),
  );
}
