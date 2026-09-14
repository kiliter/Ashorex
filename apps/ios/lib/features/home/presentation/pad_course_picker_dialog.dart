import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';

/// Pad 添加课程待办的选课弹窗：直接复用学习页的课程库与课时抽屉，
/// 左侧选课程、右侧选课时，与「学习」Tab 保持同一套交互与筛选口径。
final class PadCoursePickerDialog extends StatelessWidget {
  const PadCoursePickerDialog({
    required this.date,
    required this.onCreated,
    super.key,
  });

  /// 课时加入待办的目标日期（首页当前查看的日期，不一定是今天）。
  final DateTime date;

  /// 有课时成功加入待办时回调；用闭包回传而不是弹窗返回值，
  /// 这样点遮罩关闭也不会丢失「需要刷新」的标记。
  final VoidCallback onCreated;

  /// 返回 true 表示弹窗期间有课时成功加入待办，调用方需要刷新。
  static Future<bool> show(BuildContext context, DateTime date) async {
    var created = false;
    await showDialog<void>(
      context: context,
      barrierColor: const Color(0x55263B60),
      builder: (context) =>
          PadCoursePickerDialog(date: date, onCreated: () => created = true),
    );
    return created;
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: ShanganColors.paper,
      insetPadding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
      elevation: 24,
      shadowColor: const Color(0x33263B60),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: ShanganColors.hair),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1060, maxHeight: 700),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(22),
          child: ProviderScope(
            overrides: [
              // 弹窗内的筛选状态独立于学习页：子作用域里重起一份筛选条件，
              // coursesProvider 按新条件过滤共享的课程快照，两边互不污染。
              catalogFilterProvider.overrideWith(CatalogFilterController.new),
              coursesProvider.overrideWith(loadFilteredCourses),
            ],
            child: LibraryPage(
              addDate: date,
              onCreated: onCreated,
              onClose: () => Navigator.of(context).pop(),
            ),
          ),
        ),
      ),
    );
  }
}
