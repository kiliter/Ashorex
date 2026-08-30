import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 展示课时并分别提供直接学习与加入今日 DRAFT 计划入口。
final class CourseDetailPage extends ConsumerWidget {
  const CourseDetailPage({required this.courseId, super.key});

  final String courseId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: const Text('课程详情')),
      body: FutureBuilder<CourseDetail>(
        future: ref.read(catalogRepositoryProvider).loadCourse(courseId),
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Center(child: Text('课程详情加载失败'));
          }
          final course = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                course.name,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              if (course.description.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(course.description),
              ],
              const SizedBox(height: 20),
              ...course.lessons.map(
                (lesson) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.play_circle_outline),
                  title: Text(lesson.title),
                  subtitle: Text('${(lesson.durationMs / 60000).ceil()} 分钟'),
                  trailing: IconButton(
                    constraints: const BoxConstraints.tightFor(
                      width: 48,
                      height: 48,
                    ),
                    tooltip: '加入今日计划',
                    icon: const Icon(Icons.playlist_add),
                    onPressed: () => _addToPlan(context, ref, lesson.id),
                  ),
                  onTap: () => context.push(
                    Uri(
                      path: '/player/${lesson.id}',
                      queryParameters: {'title': lesson.title},
                    ).toString(),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _addToPlan(
    BuildContext context,
    WidgetRef ref,
    String lessonId,
  ) async {
    try {
      await ref.read(planRepositoryProvider).addVideo(lessonId);
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('已加入今日 DRAFT 计划')));
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('当前计划不可修改或加入失败')));
      }
    }
  }
}
