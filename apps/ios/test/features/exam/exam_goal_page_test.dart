import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/exam/presentation/exam_goal_page.dart';

void main() {
  testWidgets('从我的进入时回填已有考试目标', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          examRepositoryProvider.overrideWithValue(_ExamRepository()),
        ],
        child: const MaterialApp(
          home: ExamGoalPage(allowBack: true, goalId: 'goal-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('考试设置'), findsWidgets);
    await tester.scrollUntilVisible(
      find.text('保存考试设置'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('保存考试设置'), findsOneWidget);
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller?.text,
      '2026 国考',
    );
    expect(find.text('已选 1 门课程'), findsOneWidget);

    await tester.tap(find.byKey(const Key('selectExamCourses')));
    await tester.pumpAndSettle();
    expect(find.text('选择课程'), findsOneWidget);
    expect(find.byKey(const Key('examCourseSearch')), findsOneWidget);
    expect(find.text('行测系统课'), findsWidgets);
    await tester.enterText(find.byKey(const Key('examCourseSearch')), '没有这门课');
    await tester.pump();
    expect(find.text('没有匹配的课程'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('examCourseSearch')), '行测');
    await tester.pump();
    expect(find.text('行测系统课'), findsWidgets);
    await tester.tap(find.byKey(const Key('confirmExamCourses')));
    await tester.pumpAndSettle();
    expect(find.text('选择课程'), findsNothing);
  });
}

final class _ExamRepository implements ExamRepository {
  @override
  Future<ExamGoal?> loadGoal() async => ExamGoal(
    id: 'goal-1',
    name: '2026 国考',
    examDate: DateTime(2026, 11, 1),
    targetCompletionDate: DateTime(2026, 10, 18),
    reviewBufferDays: 14,
    timezone: 'Asia/Shanghai',
    courseIds: const ['course-1'],
  );

  @override
  Future<List<ExamGoal>> listGoals() async => [await loadGoal() as ExamGoal];

  @override
  Future<ExamGoal> loadGoalById(String goalId) async =>
      await loadGoal() as ExamGoal;

  @override
  Future<ExamGoal> saveGoal(ExamGoalDraft draft) {
    throw UnimplementedError();
  }

  @override
  Future<ExamGoal> updateGoal(String goalId, ExamGoalDraft draft) {
    throw UnimplementedError();
  }
}

final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [
    CourseSummary(id: 'course-1', name: '行测系统课', description: ''),
  ];

  @override
  Future<CourseDetail> loadCourse(String courseId) {
    throw UnimplementedError();
  }

  @override
  Future<LessonSummary> loadLesson(String lessonId) {
    throw UnimplementedError();
  }

  @override
  Future<LessonStudyContentData> loadStudyContent(String lessonId) {
    throw UnimplementedError();
  }
}
