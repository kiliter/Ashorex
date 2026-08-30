import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';

/// 展示课程课时；播放会话和加入计划由后续学习/计划 Task 接管。
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
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('播放学习会话将在下一阶段启用')),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
