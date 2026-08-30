import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/focus/data/focus_repository.dart';
import 'package:shangan_ios/features/focus/presentation/focus_timer_page.dart';

void main() {
  testWidgets('专注计时只展示服务端累计时间并支持暂停后继续', (tester) async {
    final repository = _FakeFocusRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [focusRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(
          home: FocusTimerPage(
            planItemId: 'item-1',
            title: '专注学习',
            plannedSeconds: 1500,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('00:02:00'), findsOneWidget);
    expect(find.text('剩余 00:23:00'), findsOneWidget);

    await tester.tap(find.byKey(const Key('pauseFocus')));
    await tester.pumpAndSettle();
    expect(repository.lastAction, 'pause');
    expect(find.text('已暂停'), findsOneWidget);

    await tester.tap(find.byKey(const Key('resumeFocus')));
    await tester.pumpAndSettle();
    expect(repository.lastAction, 'resume');
    expect(find.text('专注中'), findsOneWidget);
  });
}

final class _FakeFocusRepository implements FocusRepository {
  String? lastAction;

  FocusSessionData _session(String status) => FocusSessionData(
    id: 'focus-1',
    planItemId: 'item-1',
    mediaItemId: null,
    focusType: 'POMODORO',
    status: status,
    plannedSeconds: 1500,
    actualSeconds: 120,
    startedAt: DateTime.utc(2026, 8, 30),
    runningSince: status == 'RUNNING' ? DateTime.utc(2026, 8, 30) : null,
    pausedAt: status == 'PAUSED' ? DateTime.utc(2026, 8, 30, 0, 2) : null,
    endedAt: null,
    serverNow: DateTime.utc(2026, 8, 30, 0, 2),
  );

  @override
  Future<FocusSessionData?> loadActive() async => null;

  @override
  Future<FocusSessionData> start({
    String? planItemId,
    String? mediaItemId,
    required String focusType,
    required int plannedSeconds,
  }) async => _session('RUNNING');

  @override
  Future<FocusSessionData> pause(String sessionId) async {
    lastAction = 'pause';
    return _session('PAUSED');
  }

  @override
  Future<FocusSessionData> resume(String sessionId) async {
    lastAction = 'resume';
    return _session('RUNNING');
  }

  @override
  Future<FocusSessionData> finish(String sessionId) async {
    lastAction = 'finish';
    return _session('FINISHED');
  }

  @override
  Future<FocusSessionData> cancel(String sessionId) async {
    lastAction = 'cancel';
    return _session('CANCELLED');
  }
}
