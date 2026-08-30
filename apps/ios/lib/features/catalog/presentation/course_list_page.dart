import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return const Center(child: Text('课程加载失败，请稍后重试'));
          }
          final courses = snapshot.data ?? const [];
          if (courses.isEmpty) {
            return const Center(child: Text('暂无可学习课程'));
          }
          return ListView.separated(
            padding: const EdgeInsets.symmetric(vertical: 12),
            itemCount: courses.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final course = courses[index];
              return ListTile(
                minTileHeight: 64,
                title: Text(course.name),
                subtitle: course.description.isEmpty
                    ? null
                    : Text(course.description),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/courses/${course.id}'),
              );
            },
          );
        },
      ),
    );
  }
}
