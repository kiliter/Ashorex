import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/catalog/data/catalog_repository.dart';
import 'package:shangan_ios/features/catalog/presentation/course_list_page.dart';

void main() {
  testWidgets('学习页展示服务端返回的课程快照', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
        ],
        child: const MaterialApp(home: CourseListPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('行测系统课'), findsOneWidget);
    expect(find.text('资料分析与判断推理'), findsOneWidget);
  });
}

final class _CatalogRepository implements CatalogRepository {
  @override
  Future<List<CourseSummary>> listCourses() async => const [
    CourseSummary(id: 'course-1', name: '行测系统课', description: '资料分析与判断推理'),
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
