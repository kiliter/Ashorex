import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_markdown.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';

/// 课程详情只负责学习入口、可信进度和摘要预览，不在这里修改作战单。
final class CourseDetailPage extends ConsumerWidget {
  const CourseDetailPage({required this.courseId, super.key});

  final String courseId;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
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
            Text('课时列表', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 10),
            const Divider(color: ShanganColors.ink, thickness: 2),
            ...course.lessons.indexed.map((entry) {
              final lesson = entry.$2;
              return Container(
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: ShanganColors.rule)),
                ),
                // 整行都是播放入口；摘要按钮保留独立语义，右侧播放控件只显示图标。
                child: Material(
                  color: Colors.transparent,
                  child: InkWell(
                    onTap: () => _openLesson(context, ref, lesson),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 9),
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
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 7),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    lesson.title,
                                    style: Theme.of(context)
                                        .textTheme
                                        .titleMedium,
                                  ),
                                  const SizedBox(height: 5),
                                  ShanganWatchProgress(
                                    progressPercent: lesson.progressPercent,
                                    completed:
                                        lesson.learningStatus == 'COMPLETED',
                                    durationSeconds: (lesson.durationMs / 1000)
                                        .ceil(),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          if (lesson.summaryAvailable)
                            IconButton(
                              constraints: const BoxConstraints.tightFor(
                                width: 44,
                                height: 44,
                              ),
                              tooltip: '查看 AI 摘要',
                              onPressed: () =>
                                  _showSummary(context, ref, lesson),
                              icon: const Icon(Icons.visibility_outlined),
                            )
                          else
                            const SizedBox(width: 44),
                          IconButton(
                            constraints: const BoxConstraints.tightFor(
                              width: 44,
                              height: 44,
                            ),
                            tooltip: '播放课时',
                            onPressed: () => _openLesson(context, ref, lesson),
                            icon: const Icon(
                              Icons.play_arrow_rounded,
                              size: 24,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              );
            }),
          ],
        );
      },
    ),
  );

  /// 非作战单课时播放前明确说明它不会完成今日清单。
  Future<void> _openLesson(
    BuildContext context,
    WidgetRef ref,
    LessonSummary lesson,
  ) async {
    final plan = await ref.read(planRepositoryProvider).loadToday();
    if (!context.mounted) return;
    final item = plan.items
        .where((candidate) => candidate.mediaItemId == lesson.id)
        .firstOrNull;
    if (item != null) {
      _pushPlayer(context, lesson, item.id);
      return;
    }
    final action = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('本课时不在今日作战单'),
        content: const Text('继续观看会保留可信学习记录，但不会完成今日作战单。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, 'edit'),
            child: const Text('修改作战单'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, 'continue'),
            child: const Text('继续观看'),
          ),
        ],
      ),
    );
    if (!context.mounted) return;
    if (action == 'edit') context.push('/plan');
    if (action == 'continue') _pushPlayer(context, lesson, null);
  }

  void _pushPlayer(
    BuildContext context,
    LessonSummary lesson,
    String? planItemId,
  ) {
    context.push(
      Uri(
        path: '/player/${lesson.id}',
        queryParameters: {'title': lesson.title, 'planItemId': ?planItemId},
      ).toString(),
    );
  }

  Future<void> _showSummary(
    BuildContext context,
    WidgetRef ref,
    LessonSummary lesson,
  ) async {
    final content = await ref
        .read(catalogRepositoryProvider)
        .loadStudyContent(lesson.id);
    if (!context.mounted) return;
    final summary = content.summaryMarkdown?.trim();
    if (content.summaryStatus != 'READY' ||
        summary == null ||
        summary.isEmpty) {
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.68,
        maxChildSize: 0.9,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 36),
          children: [
            const ShanganEyebrow('AI 识别摘要'),
            const SizedBox(height: 8),
            Text(lesson.title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            ShanganMarkdown(data: summary),
          ],
        ),
      ),
    );
  }
}
