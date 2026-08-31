import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
            return const ShanganLoading('正在读取课程课时');
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return const Center(child: Text('课程详情加载失败'));
          }
          final course = snapshot.data!;
          final totalSeconds = course.lessons.fold<int>(
            0,
            (sum, lesson) => sum + lesson.durationMs ~/ 1000,
          );
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            children: [
              const ShanganEyebrow('课程详情'),
              const SizedBox(height: 7),
              Text(
                course.name,
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              if (course.description.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(course.description),
              ],
              const SizedBox(height: 18),
              ShanganMetricGrid(
                metrics: [
                  (
                    value: '${course.lessons.length}',
                    label: '总课时',
                    tone: ShanganTagTone.info,
                  ),
                  (
                    value: shanganDuration(totalSeconds),
                    label: '课程总时长',
                    tone: ShanganTagTone.success,
                  ),
                ],
              ),
              const SizedBox(height: 24),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '课时列表',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  const ShanganStatusTag('服务端快照', tone: ShanganTagTone.info),
                ],
              ),
              const SizedBox(height: 10),
              const Divider(color: ShanganColors.ink, thickness: 2),
              ...course.lessons.indexed.map((entry) {
                final lesson = entry.$2;
                return Container(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: ShanganColors.rule),
                    ),
                  ),
                  child: Row(
                    children: [
                      SizedBox(
                        width: 34,
                        child: Text(
                          '${entry.$1 + 1}'.padLeft(2, '0'),
                          style: shanganNumberStyle(
                            context,
                            fontSize: 12,
                          ).copyWith(color: ShanganColors.mutedInk),
                        ),
                      ),
                      Expanded(
                        child: InkWell(
                          onTap: () => context.push(
                            Uri(
                              path: '/player/${lesson.id}',
                              queryParameters: {'title': lesson.title},
                            ).toString(),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  lesson.title,
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                                const SizedBox(height: 3),
                                Text(
                                  '${(lesson.durationMs / 60000).ceil()} 分钟',
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                      IconButton.outlined(
                        tooltip: '加入今日计划',
                        icon: const Icon(
                          Icons.playlist_add,
                          color: ShanganColors.blue,
                        ),
                        onPressed: () => _addToPlan(context, ref, lesson.id),
                      ),
                    ],
                  ),
                );
              }),
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
