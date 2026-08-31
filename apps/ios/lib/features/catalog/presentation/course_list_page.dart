import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';

/// “学习”Tab 的课程入口，只展示服务端标记为启用且可用的快照。
final class CourseListPage extends ConsumerWidget {
  const CourseListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('学习')),
      body: FutureBuilder<List<CourseSummary>>(
        future: ref.read(catalogRepositoryProvider).listCourses(),
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const ShanganLoading('正在读取课程快照');
          }
          if (snapshot.hasError) {
            return const Center(child: Text('课程加载失败，请稍后重试'));
          }
          final courses = snapshot.data ?? const [];
          if (courses.isEmpty) {
            return const Center(
              child: ShanganNotice(
                title: '暂无可学习课程',
                message: '管理员同步并启用课程后会显示在这里。',
              ),
            );
          }
          return ListView(
            padding: shanganPagePadding,
            children: [
              ShanganEyebrow('学习课程 · ${courses.length} 门'),
              const SizedBox(height: 7),
              Text(
                '按计划推进，不追求虚假完成',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 8),
              Text(
                '课程列表来自上岸服务端快照，可信进度不会使用 Emby 播放记录代替。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 22),
              const Divider(color: ShanganColors.ink, thickness: 2),
              ...courses.indexed.map((entry) {
                final course = entry.$2;
                return InkWell(
                  onTap: () => context.push('/courses/${course.id}'),
                  child: Container(
                    constraints: const BoxConstraints(minHeight: 76),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    decoration: const BoxDecoration(
                      border: Border(
                        bottom: BorderSide(color: ShanganColors.rule),
                      ),
                    ),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 32,
                          child: Text(
                            '${entry.$1 + 1}'.padLeft(2, '0'),
                            style: shanganNumberStyle(
                              context,
                              fontSize: 12,
                            ).copyWith(color: ShanganColors.mutedInk),
                          ),
                        ),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                course.name,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              if (course.description.isNotEmpty) ...[
                                const SizedBox(height: 4),
                                Text(
                                  course.description,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ],
                          ),
                        ),
                        const Icon(
                          Icons.chevron_right,
                          color: ShanganColors.mutedInk,
                        ),
                      ],
                    ),
                  ),
                );
              }),
            ],
          );
        },
      ),
    );
  }
}
