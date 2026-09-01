import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/catalog/presentation/course_detail_page.dart';

void main() {
  testWidgets('课程详情的每个课时明确展示观看百分比和已看完状态', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
        ],
        child: const MaterialApp(home: CourseDetailPage(courseId: 'course-1')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('未观看'), findsOneWidget);
    expect(find.text('已观看 35%'), findsOneWidget);
    expect(find.text('已看完'), findsOneWidget);
  });
}

/// 提供三种观看状态，验证所有课程详情共用的课时列表展示规则。
final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [];

  @override
  Future<CourseDetail> loadCourse(String courseId) async => const CourseDetail(
    id: 'course-1',
    name: '行测系统课',
    description: '状态展示测试',
    lessons: [
      LessonSummary(
        id: 'lesson-1',
        courseId: 'course-1',
        title: '未观看课时',
        durationMs: 600000,
        sortOrder: 0,
        maxVerifiedPositionMs: 0,
        progressPercent: 0,
        learningStatus: 'NOT_STARTED',
        summaryAvailable: false,
      ),
      LessonSummary(
        id: 'lesson-2',
        courseId: 'course-1',
        title: '学习中课时',
        durationMs: 600000,
        sortOrder: 1,
        maxVerifiedPositionMs: 210000,
        progressPercent: 35,
        learningStatus: 'IN_PROGRESS',
        summaryAvailable: false,
      ),
      LessonSummary(
        id: 'lesson-3',
        courseId: 'course-1',
        title: '已看完课时',
        durationMs: 600000,
        sortOrder: 2,
        maxVerifiedPositionMs: 588000,
        progressPercent: 100,
        learningStatus: 'COMPLETED',
        summaryAvailable: false,
      ),
    ],
  );

  @override
  Future<LessonSummary> loadLesson(String lessonId) async => (await loadCourse(
    'course-1',
  )).lessons.firstWhere((lesson) => lesson.id == lessonId);

  @override
  Future<LessonStudyContentData> loadStudyContent(String lessonId) {
    throw UnimplementedError();
  }
}
