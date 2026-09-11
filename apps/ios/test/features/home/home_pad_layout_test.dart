import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';
import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

void main() {
  testWidgets('Pad 首页使用工作台：页头添加、四格指标、目标与今日节奏', (tester) async {
    tester.view.physicalSize = const Size(1180, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    var openedStats = false;
    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/exam-goals',
        json: [
          goalJson(id: 'g-1', name: '2026 国考 · 全力以赴', daysRemaining: 80),
          goalJson(
            id: 'g-2',
            name: '计算机',
            examDate: '2026-12-09',
            daysRemaining: 90,
            primary: false,
          ),
        ],
      )
      ..on('GET', '/api/v1/nags/pending')
      ..on('GET', '/api/v1/me', json: meJson())
      ..on(
        'GET',
        '/api/v1/todos/pending-summary',
        json: {
          'total': 0,
          'countByType': <String, Object?>{},
          'items': <Object?>[],
        },
      )
      ..on(
        'GET',
        '/api/v1/todos',
        json: dayViewJson(
          todos: [
            todoJson(id: 'c-1', title: '数量关系', todoType: 'COURSE'),
            todoJson(
              id: 'f-1',
              title: '申论专注',
              todoType: 'FOCUS',
              status: 'DONE',
            ),
          ],
          totals: {
            'total': 2,
            'done': 1,
            'watchedMs': 4800000,
            'focusedMs': 3000000,
            'attachmentCount': 1,
          },
        ),
      );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: MaterialApp(
          theme: ShanganTheme.light(),
          home: Scaffold(body: HomePage(onOpenStats: () => openedStats = true)),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('添加待办'), findsOneWidget);
    expect(find.text('今日节奏'), findsOneWidget);
    expect(find.text('我的考试目标'), findsOneWidget);
    expect(find.text('2026 国考 · 全力以赴'), findsOneWidget);
    expect(find.text('计算机'), findsOneWidget);
    expect(find.text('90'), findsOneWidget);
    expect(find.text('另有 1 个目标'), findsNothing);
    expect(find.text('查看数据 ›'), findsOneWidget);
    expect(find.text('课程学习'), findsOneWidget);
    expect(find.textContaining('ONE DAY CLOSER TO SHORE'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('查看数据 ›'));
    expect(openedStats, isTrue);
  });

  testWidgets('Pad 添加待办以居中弹框呈现课程/专注/待办', (tester) async {
    tester.view.physicalSize = const Size(1180, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final backend = FakeBackend()
      ..on('GET', '/api/v1/exam-goals', json: <Object>[])
      ..on('GET', '/api/v1/nags/pending')
      ..on('GET', '/api/v1/me', json: meJson())
      ..on(
        'GET',
        '/api/v1/todos/pending-summary',
        json: {
          'total': 0,
          'countByType': <String, Object?>{},
          'items': <Object?>[],
        },
      )
      ..on('GET', '/api/v1/todos', json: dayViewJson())
      ..on('GET', '/api/v1/catalog/courses', json: <Object>[]);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: MaterialApp(
          theme: ShanganTheme.light(),
          home: const Scaffold(body: HomePage()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('添加待办'));
    await tester.pumpAndSettle();

    expect(find.text('添加今日待办'), findsOneWidget);
    expect(find.text('从课程库选择要学的课时'), findsOneWidget);
    expect(find.text('选择课程'), findsOneWidget);
    expect(find.byType(BottomSheet), findsNothing);

    await tester.tap(find.text('专注'));
    await tester.pumpAndSettle();
    expect(find.text('名称'), findsOneWidget);
    expect(find.text('倒计时时长'), findsOneWidget);
    expect(find.text('保存并加入今日'), findsOneWidget);

    await tester.tap(find.text('待办'));
    await tester.pumpAndSettle();
    expect(find.text('标题'), findsOneWidget);
    expect(find.text('完成时要求凭证'), findsOneWidget);
    expect(find.text('保存'), findsOneWidget);
  });

  testWidgets('Pad 编辑态不展示目标看板与今日节奏，避免误触', (tester) async {
    tester.view.physicalSize = const Size(1180, 820);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final backend = FakeBackend()
      ..on(
        'GET',
        '/api/v1/exam-goals',
        json: [goalJson(id: 'g-1', name: '2026 国考 · 全力以赴')],
      )
      ..on('GET', '/api/v1/nags/pending')
      ..on('GET', '/api/v1/me', json: meJson())
      ..on(
        'GET',
        '/api/v1/todos/pending-summary',
        json: {
          'total': 0,
          'countByType': <String, Object?>{},
          'items': <Object?>[],
        },
      )
      ..on(
        'GET',
        '/api/v1/todos',
        json: dayViewJson(
          todos: [todoJson(id: 't-1', title: '行政法第 1 讲')],
        ),
      );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        ],
        child: MaterialApp(
          theme: ShanganTheme.light(),
          home: const Scaffold(body: HomePage()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('我的考试目标'), findsOneWidget);
    expect(find.text('今日节奏'), findsOneWidget);

    await tester.tap(find.text('编辑'));
    await tester.pumpAndSettle();

    expect(find.text('编辑今日'), findsOneWidget);
    expect(find.text('我的考试目标'), findsNothing);
    expect(find.text('今日节奏'), findsNothing);
  });
}
