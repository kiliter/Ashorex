import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
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
  testWidgets('首次引导保存成功后以 pop 返回首页，触发首页重新读取', (tester) async {
    final repository = _SavingExamRepository();
    var poppedBackToHome = 0;
    final observer = RouteObserver<ModalRoute<void>>();
    final router = GoRouter(
      initialLocation: '/home',
      observers: [observer],
      routes: [
        GoRoute(
          path: '/home',
          builder: (context, state) => _HomeStub(
            observer: observer,
            onPoppedBack: () => poppedBackToHome++,
          ),
        ),
        GoRoute(
          path: '/exam-goal',
          builder: (context, state) => const ExamGoalPage(),
        ),
      ],
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          catalogRepositoryProvider.overrideWithValue(_CatalogRepository()),
          examRepositoryProvider.overrideWithValue(repository),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('openExamGoalGuide')));
    await tester.pumpAndSettle();
    expect(find.text('首次设置 · 01/03'), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, '2026 国考');
    await tester.tap(find.byKey(const Key('selectExamCourses')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('exam-course-course-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmExamCourses')));
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.text('保存并进入首页'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('保存并进入首页'));
    await tester.pumpAndSettle();

    expect(repository.savedDrafts, hasLength(1));
    expect(find.text('首次设置 · 01/03'), findsNothing);
    expect(find.byKey(const Key('openExamGoalGuide')), findsOneWidget);
    // 必须走 pop 返回，AppShell 的 didPopNext 才会让首页重新读取考试列表并停止引导。
    expect(poppedBackToHome, 1);
  });
}

/// 记录被引导页 pop 回来的次数，等价于 AppShell 依赖的 RouteAware 刷新时机。
final class _HomeStub extends StatefulWidget {
  const _HomeStub({required this.observer, required this.onPoppedBack});

  final RouteObserver<ModalRoute<void>> observer;
  final VoidCallback onPoppedBack;

  @override
  State<_HomeStub> createState() => _HomeStubState();
}

final class _HomeStubState extends State<_HomeStub> with RouteAware {
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != null) {
      widget.observer.subscribe(this, route);
    }
  }

  @override
  void dispose() {
    widget.observer.unsubscribe(this);
    super.dispose();
  }

  @override
  void didPopNext() => widget.onPoppedBack();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: TextButton(
          key: const Key('openExamGoalGuide'),
          onPressed: () => context.push('/exam-goal'),
          child: const Text('打开考试引导'),
        ),
      ),
    );
  }
}

/// 首次引导场景：服务端尚无考试目标，保存后返回新建结果。
final class _SavingExamRepository implements ExamRepository {
  final List<ExamGoalDraft> savedDrafts = [];

  @override
  Future<ExamGoal?> loadGoal() async => null;

  @override
  Future<List<ExamGoal>> listGoals() async => const [];

  @override
  Future<ExamGoal> loadGoalById(String goalId) {
    throw UnimplementedError();
  }

  @override
  Future<ExamGoal> saveGoal(ExamGoalDraft draft) async {
    savedDrafts.add(draft);
    return ExamGoal(
      id: 'goal-new',
      name: draft.name,
      examDate: draft.examDate,
      targetCompletionDate: draft.targetCompletionDate,
      reviewBufferDays: draft.reviewBufferDays,
      timezone: 'Asia/Shanghai',
      courseIds: draft.courseIds,
    );
  }

  @override
  Future<ExamGoal> updateGoal(String goalId, ExamGoalDraft draft) {
    throw UnimplementedError();
  }

  @override
  Future<void> deleteGoal(String goalId) {
    throw UnimplementedError();
  }
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

  @override
  Future<void> deleteGoal(String goalId) {
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
