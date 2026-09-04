import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
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

  testWidgets('未指定时长时先选择预设再开始倒计时', (tester) async {
    final repository = _FakeFocusRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [focusRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: FocusTimerPage(title: '专注计时')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('focusDuration-900')), findsOneWidget);
    expect(find.byKey(const Key('focusDuration-1800')), findsOneWidget);
    expect(find.byKey(const Key('focusDuration-3600')), findsOneWidget);
    expect(find.byKey(const Key('focusDuration-7200')), findsOneWidget);
    expect(repository.startedPlannedSeconds, isNull);

    await tester.tap(find.byKey(const Key('focusDuration-1800')));
    await tester.pumpAndSettle();
    expect(repository.startedPlannedSeconds, 1800);
    expect(find.text('专注中'), findsOneWidget);
    expect(find.text('剩余 00:28:00'), findsOneWidget);
  });

  testWidgets('自定义分钟后开始倒计时', (tester) async {
    final repository = _FakeFocusRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [focusRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: FocusTimerPage(title: '专注计时')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('focusCustomDurationSlider')), findsOneWidget);
    await tester.ensureVisible(
      find.byKey(const Key('startCustomFocusDuration')),
    );
    await tester.tap(find.byKey(const Key('startCustomFocusDuration')));
    await tester.pumpAndSettle();
    expect(repository.startedPlannedSeconds, 30 * 60);
    expect(find.text('专注中'), findsOneWidget);
  });

  testWidgets('专注进行中返回先确认，确认后立即停表并离开', (tester) async {
    final repository = _FakeFocusRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          focusRepositoryProvider.overrideWithValue(repository),
          screenWakeLockProvider.overrideWithValue(_RecordingWakeLock()),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) =>
                      const FocusTimerPage(title: '专注计时', plannedSeconds: 1500),
                ),
              ),
              child: const Text('open-focus'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open-focus'));
    await tester.pumpAndSettle();

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('focusLeaveConfirmDialog')), findsOneWidget);

    await tester.tap(find.byKey(const Key('confirmLeaveFocus')));
    await tester.pumpAndSettle();
    expect(repository.lastAction, 'finish');
    expect(find.byType(FocusTimerPage), findsNothing);
    expect(find.text('open-focus'), findsOneWidget);
  });
}

final class _RecordingWakeLock implements ScreenWakeLock {
  bool enabled = false;

  @override
  Future<void> enable() async => enabled = true;

  @override
  Future<void> disable() async => enabled = false;
}

final class _FakeFocusRepository implements FocusRepository {
  String? lastAction;
  int? startedPlannedSeconds;
  int plannedSeconds = 1500;

  FocusSessionData _session(String status) => FocusSessionData(
    id: 'focus-1',
    planItemId: 'item-1',
    mediaItemId: null,
    focusType: 'POMODORO',
    status: status,
    plannedSeconds: plannedSeconds,
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
  }) async {
    startedPlannedSeconds = plannedSeconds;
    this.plannedSeconds = plannedSeconds;
    return _session('RUNNING');
  }

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
