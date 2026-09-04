import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';
import 'package:shangan_ios/features/focus/presentation/mock_exam_page.dart';

void main() {
  testWidgets('模拟考试进行中返回先确认，取消则继续计时', (tester) async {
    final wakeLock = _RecordingWakeLock();
    final repository = _FakeMockExamRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mockExamRepositoryProvider.overrideWithValue(repository),
          screenWakeLockProvider.overrideWithValue(wakeLock),
        ],
        child: const MaterialApp(
          home: MockExamPage(planItemId: 'item-1', title: '模拟考试'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('请勿切换到后台'), findsOneWidget);
    expect(wakeLock.enabled, isTrue);
    final popScope = tester.widget<PopScope<Object?>>(
      find.byKey(const Key('mockExamPopScope')),
    );
    expect(popScope.canPop, isFalse);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mockExamLeaveConfirmDialog')), findsOneWidget);

    await tester.tap(find.byKey(const Key('stayMockExam')));
    await tester.pumpAndSettle();
    expect(repository.submittedEarly, isFalse);
    expect(find.text('考试中'), findsOneWidget);
  });

  testWidgets('确认返回后立即停止考试倒计时并离开', (tester) async {
    final repository = _FakeMockExamRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mockExamRepositoryProvider.overrideWithValue(repository),
          screenWakeLockProvider.overrideWithValue(_RecordingWakeLock()),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) =>
                      const MockExamPage(planItemId: 'item-1', title: '模拟考试'),
                ),
              ),
              child: const Text('open-exam'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open-exam'));
    await tester.pumpAndSettle();

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmLeaveMockExam')));
    await tester.pumpAndSettle();

    expect(repository.submittedEarly, isTrue);
    expect(find.byType(MockExamPage), findsNothing);
    expect(find.text('open-exam'), findsOneWidget);
  });

  testWidgets('从后台回到考试页时提醒计时未暂停', (tester) async {
    final repository = _FakeMockExamRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mockExamRepositoryProvider.overrideWithValue(repository),
          screenWakeLockProvider.overrideWithValue(_RecordingWakeLock()),
        ],
        child: const MaterialApp(
          home: MockExamPage(planItemId: 'item-1', title: '模拟考试'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mockExamStayOnPageDialog')), findsOneWidget);
    expect(find.text('请留在考试页'), findsOneWidget);
  });

  testWidgets('交卷后可重考并重新开始倒计时', (tester) async {
    final repository = _FakeMockExamRepository()..status = 'AWAITING_UPLOAD';
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mockExamRepositoryProvider.overrideWithValue(repository),
          screenWakeLockProvider.overrideWithValue(_RecordingWakeLock()),
        ],
        child: const MaterialApp(
          home: MockExamPage(planItemId: 'item-1', title: '模拟考试'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('retakeMockExam')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmRetakeMockExam')));
    await tester.pumpAndSettle();

    expect(repository.retaken, isTrue);
    expect(find.text('考试中'), findsOneWidget);
  });
}

final class _RecordingWakeLock implements ScreenWakeLock {
  bool enabled = false;

  @override
  Future<void> enable() async => enabled = true;

  @override
  Future<void> disable() async => enabled = false;
}

final class _FakeMockExamRepository implements MockExamRepository {
  String status = 'RUNNING';
  bool submittedEarly = false;
  bool retaken = false;

  MockExamSessionData _session() {
    final now = DateTime.utc(2026, 9, 4, 8);
    return MockExamSessionData(
      id: 'exam-1',
      planItemId: 'item-1',
      name: '行测',
      status: status,
      deadlineAt: now.add(const Duration(minutes: 120)),
      serverNow: now,
      attachments: const [],
    );
  }

  @override
  Future<List<MockExamPresetData>> listPresets() async => const [];

  @override
  Future<MockExamPresetData> createPreset(String name, int seconds) {
    throw UnimplementedError();
  }

  @override
  Future<void> deletePreset(String id) async {}

  @override
  Future<MockExamSessionData> load(String sessionId) async => _session();

  @override
  Future<MockExamSessionData> start(String planItemId) async => _session();

  @override
  Future<MockExamSessionData> submitEarly(String sessionId) async {
    submittedEarly = true;
    status = 'AWAITING_UPLOAD';
    return _session();
  }

  @override
  Future<MockExamSessionData> retake(String sessionId) async {
    retaken = true;
    status = 'RUNNING';
    return _session();
  }

  @override
  Future<MockExamPresetData> updatePreset(
    MockExamPresetData preset,
    String name,
    int seconds,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> upload(
    String sessionId,
    String filename,
    List<int> bytes,
  ) {
    throw UnimplementedError();
  }
}
