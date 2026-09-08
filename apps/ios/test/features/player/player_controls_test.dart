import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'dart:async';
import 'package:shangan_ios/core/player/playback_adapter.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/device/screen_wake_lock.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/features/player/presentation/player_page.dart';

import '../../support/fake_backend.dart';
import '../../support/fixtures.dart';

/// 播放控件：V2 允许自由拖动与 ±10 秒；倍速只改变位置推进速度，
/// 不改变时长口径；播放期间保持屏幕常亮，暂停与退出必须释放。
/// 是否达标一律由服务端裁决，客户端不自行判定完成。
void main() {
  test('iOS 默认选用 AVPlayer 以隔离 media_kit 模拟器音频问题', () {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final adapter = container.read(playbackAdapterFactoryProvider)();
    expect(adapter, isA<NativePlaybackAdapter>());
    adapter.dispose();
  });

  testWidgets('历史课时按原日期加载，跨今日不会查错待办', (tester) async {
    final backend = _backend();
    final date = DateTime(2026, 9, 1);
    await _pump(tester, backend, _RecordingWakeLock(), localDate: date);
    expect(
      backend.requests
          .where((r) => r.path.startsWith('/api/v1/todos?'))
          .single
          .path,
      '/api/v1/todos?view=DAY&date=2026-09-01',
    );
    expect(find.byKey(const Key('playerPicture')), findsOneWidget);
    await _dispose(tester);
  });

  testWidgets('后台暂停超时释放常亮且不继续累计观看时长', (tester) async {
    final backend = _backend();
    final wake = _RecordingWakeLock();
    final playback = _StalledPausePlayback();
    await _pump(tester, backend, wake, playback: playback);
    await tester.tap(find.bySemanticsLabel('播放'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 8));
    await tester.pumpAndSettle();
    expect(wake.enabled, false);
    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    expect(body['deltaWatchedMs'], 2000);
    expect(body['eventType'], 'PAUSE');
    expect(tester.takeException(), isNull);
    await _dispose(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
  });

  testWidgets('全屏返回恢复竖屏并展示日期时间电量', (tester) async {
    final calls = <MethodCall>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        calls.add(call);
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await _pump(tester, _backend(), _RecordingWakeLock());
    await tester.tap(find.bySemanticsLabel('全屏'));
    await tester.pumpAndSettle();
    expect(find.textContaining('电量'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('退出全屏').last);
    await tester.pumpAndSettle();
    expect(
      calls
          .lastWhere(
            (call) => call.method == 'SystemChrome.setPreferredOrientations',
          )
          .arguments,
      ['DeviceOrientation.portraitUp'],
    );
    expect(find.bySemanticsLabel('全屏'), findsOneWidget);
    await _dispose(tester);
  });

  testWidgets('横竖屏移除快进快退按钮，保留画面分区双击', (tester) async {
    final wakeLock = _RecordingWakeLock();
    await _pump(tester, _backend(), wakeLock);

    expect(find.bySemanticsLabel('快退 10 秒'), findsNothing);
    expect(find.bySemanticsLabel('播放'), findsOneWidget);
    expect(find.bySemanticsLabel('快进 10 秒'), findsNothing);
    expect(find.byIcon(Icons.replay_10), findsNothing);
    expect(find.byIcon(Icons.forward_10), findsNothing);
    final picture = tester.getRect(find.byKey(const Key('playerPicture')));
    expect(picture.width / picture.height, closeTo(16 / 9, 0.01));
    final timeline = tester.getRect(find.byType(Slider));
    final play = tester.getRect(find.bySemanticsLabel('播放'));
    final time = tester.getRect(find.byKey(const Key('playerTime')));
    expect(timeline.center.dy, closeTo(play.center.dy, 1));
    expect(time.center.dy, closeTo(play.center.dy, 1));
    expect(timeline.right, lessThanOrEqualTo(time.left));

    await _dispose(tester);
  });

  testWidgets('窄屏附件备注保持单行，倍速面板留在视频内部', (tester) async {
    await _pump(tester, _backend(), _RecordingWakeLock());
    tester.view.physicalSize = const Size(960, 2532);
    await tester.pumpAndSettle();
    // 两字标签的高度应为一行；按钮可以整组换行，但文字不能逐字折行。
    expect(tester.getSize(find.text('附件')).height, lessThan(28));
    expect(tester.getSize(find.text('备注')).height, lessThan(28));
    await tester.tap(find.byTooltip('选择倍速'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('playerSpeed-0.75')), findsOneWidget);
    expect(find.byKey(const ValueKey('playerSpeed-2.0')), findsOneWidget);
    final panel = tester.getRect(
      find
          .ancestor(of: find.text('播放速度'), matching: find.byType(Material))
          .first,
    );
    final video = tester.getRect(find.byKey(const Key('nativeVideo')));
    expect(video.contains(panel.topLeft), isTrue);
    expect(panel.bottom, lessThanOrEqualTo(video.bottom));
    expect(tester.takeException(), isNull);
    await tester.tap(find.byTooltip('关闭倍速选择'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('playerSpeed-2.0')), findsNothing);
    await _dispose(tester);
  });

  testWidgets('横屏倍速面板贴右侧，关闭后恢复操作层', (tester) async {
    await _pump(tester, _backend(), _RecordingWakeLock());
    await tester.tap(find.bySemanticsLabel('全屏'));
    tester.view.physicalSize = const Size(2532, 1170);
    await tester.pumpAndSettle();
    // 横屏时间在进度条上方，播放按钮在进度条下方。
    expect(
      tester.getRect(find.byKey(const Key('playerTime'))).bottom,
      lessThanOrEqualTo(tester.getRect(find.byType(Slider)).top),
    );
    expect(
      tester.getRect(find.bySemanticsLabel('播放')).top,
      greaterThanOrEqualTo(tester.getRect(find.byType(Slider)).bottom),
    );
    await tester.tap(find.byTooltip('选择倍速'));
    await tester.pumpAndSettle();
    final panel = tester.getRect(find.byKey(const Key('playerSpeedPanel')));
    final picture = tester.getRect(find.byKey(const Key('playerPicture')));
    expect(panel.width, 240);
    expect(panel.right, picture.right);
    expect(panel.height, picture.height);
    expect(find.byType(Slider), findsNothing);
    await tester.tap(find.byTooltip('关闭倍速选择'));
    await tester.pumpAndSettle();
    expect(find.byType(Slider), findsOneWidget);
    expect(tester.takeException(), isNull);
    await _dispose(tester);
  });

  testWidgets('片尾即使先暂停也补报最终位置，尾差不增加观看时长', (tester) async {
    final backend = _backend();
    final playback = _FakePlayback();
    await _pump(tester, backend, _RecordingWakeLock(), playback: playback);
    playback.positionMs = 1799500;
    playback.ended = true;
    playback.notifyListeners();
    await tester.pumpAndSettle();
    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    expect(body['positionMs'], 1800000);
    expect(body['deltaWatchedMs'], 0);
    expect(body['eventType'], 'PAUSE');
    await _dispose(tester);
  });

  testWidgets('快进 10 秒会立即补一次上报', (tester) async {
    final backend = _backend();
    await _pump(tester, backend, _RecordingWakeLock());

    await tester.tap(find.bySemanticsLabel('全屏'));
    await tester.pumpAndSettle();
    // 双击画面边侧执行跳转，工具栏不再提供快进快退按钮。
    final picture = tester.getRect(find.byKey(const Key('playerPicture')));
    final point = Offset(
      picture.left + picture.width * 5 / 6,
      picture.center.dy,
    );
    await tester.tapAt(point);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tapAt(point);
    await tester.pumpAndSettle();

    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    expect(body['positionMs'], 10000);
    expect(body['deltaWatchedMs'], 0);

    await _dispose(tester);
  });

  testWidgets('快退不会把位置退到负数', (tester) async {
    final backend = _backend();
    await _pump(tester, backend, _RecordingWakeLock());

    await tester.tap(find.bySemanticsLabel('全屏'));
    await tester.pumpAndSettle();
    // 双击画面边侧执行跳转，工具栏不再提供快进快退按钮。
    final picture = tester.getRect(find.byKey(const Key('playerPicture')));
    final point = Offset(
      picture.left + picture.width * 1 / 6,
      picture.center.dy,
    );
    await tester.tapAt(point);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tapAt(point);
    await tester.pumpAndSettle();

    expect(
      backend
          .lastRequest('POST', '/api/v1/todos/t-1/progress')
          .json['positionMs'],
      0,
    );

    await _dispose(tester);
  });

  testWidgets('播放时保持屏幕常亮，暂停时释放', (tester) async {
    final wakeLock = _RecordingWakeLock();
    await _pump(tester, _backend(), wakeLock);

    await tester.tap(find.bySemanticsLabel('播放'));
    await tester.pumpAndSettle();
    expect(wakeLock.enabled, isTrue);
    expect(find.bySemanticsLabel('暂停'), findsOneWidget);

    await tester.tap(find.bySemanticsLabel('暂停'));
    await tester.pumpAndSettle();
    expect(wakeLock.enabled, isFalse);

    await _dispose(tester);
  });

  testWidgets('倍速不改变观看时长口径，只让位置推进更快', (tester) async {
    final backend = _backend();
    await _pump(tester, backend, _RecordingWakeLock());

    await tester.tap(find.byTooltip('选择倍速'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('2.0x'));
    await tester.tap(find.byKey(const ValueKey('playerSpeed-2.0')));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel('播放'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 5));
    expect(find.bySemanticsLabel('暂停'), findsNothing);
    expect(find.bySemanticsLabel('全屏'), findsNothing);
    expect(find.byType(Slider), findsNothing);
    await tester.tap(find.byKey(const Key('playerPicture')));
    // 画面单击要等双击识别窗口结束，控件自身不受此等待影响。
    await tester.pump(const Duration(milliseconds: 350));
    await tester.tap(find.bySemanticsLabel('暂停'));
    await tester.pumpAndSettle();

    final body = backend.lastRequest('POST', '/api/v1/todos/t-1/progress').json;
    // 播放 5 秒加单击识别的 350ms，倍速不放大真实时长。
    expect(body['deltaWatchedMs'], 5350);
    expect(body['positionMs'], 10000);
    expect(body['eventType'], 'PAUSE');

    await _dispose(tester);
  });

  testWidgets('服务端裁定达标后提示已完成，客户端不自行判定', (tester) async {
    final backend = _backend(
      progressJson: {
        'status': 'DONE',
        'positionMs': 10000,
        'watchedMs': 10000,
        'progressPermille': 320,
        'completed': true,
        'targetProgressPermille': 300,
      },
    );
    await _pump(tester, backend, _RecordingWakeLock());

    await tester.tap(find.bySemanticsLabel('播放'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 15));
    await tester.pump();

    expect(find.text('已达到目标进度，这条待办已完成'), findsOneWidget);

    await _dispose(tester);
  });

  testWidgets('退出播放页会释放屏幕常亮', (tester) async {
    final wakeLock = _RecordingWakeLock();
    await _pump(tester, _backend(), wakeLock);

    await tester.tap(find.bySemanticsLabel('播放'));
    await tester.pumpAndSettle();
    expect(wakeLock.enabled, isTrue);

    await _dispose(tester);
    expect(wakeLock.enabled, isFalse);
  });
}

/// 卸载页面以取消播放定时器并触发 dispose。
Future<void> _dispose(WidgetTester tester) async {
  await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
  await tester.pumpAndSettle();
}

FakeBackend _backend({Map<String, Object?>? progressJson}) {
  return FakeBackend()
    ..on('GET', '/api/v1/me', json: meJson())
    ..on(
      'GET',
      '/api/v1/todos',
      json: dayViewJson(
        todos: [
          todoJson(
            id: 't-1',
            title: '行政法第 1 讲',
            targetProgressPermille: 300,
            resourceId: 'r-1',
            resourceDurationMs: 1800000,
          ),
        ],
      ),
    )
    ..on(
      'POST',
      '/api/v1/todos/t-1/progress',
      json:
          progressJson ??
          {
            'status': 'IN_PROGRESS',
            'positionMs': 10000,
            'watchedMs': 10000,
            'progressPermille': 5,
            'completed': false,
            'targetProgressPermille': 300,
          },
    );
}

Future<void> _pump(
  WidgetTester tester,
  FakeBackend backend,
  ScreenWakeLock wakeLock, {
  _FakePlayback? playback,
  DateTime? localDate,
}) async {
  // 播放页按 iPhone 竖屏布局；默认 800x600 测试视窗会让底部按钮组溢出。
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        playbackAdapterFactoryProvider.overrideWithValue(
          () => playback ?? _FakePlayback(),
        ),
        playbackTimeProvider.overrideWithValue(
          () => tester.binding.clock.now().millisecondsSinceEpoch,
        ),
        shanganRepositoryProvider.overrideWithValue(buildRepository(backend)),
        screenWakeLockProvider.overrideWithValue(wakeLock),
      ],
      child: MaterialApp(
        home: PlayerPage(todoId: 't-1', localDate: localDate),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// 记录常亮开关状态的假实现，测试不触碰平台通道。
final class _RecordingWakeLock implements ScreenWakeLock {
  bool enabled = false;

  @override
  Future<void> enable() async => enabled = true;

  @override
  Future<void> disable() async => enabled = false;
}

/// 假原生播放器仅模拟平台位置回调，页面必须通过适配器完成控制。
class _FakePlayback extends PlaybackAdapter {
  @override
  bool ended = false;
  @override
  bool playing = false;
  @override
  bool buffering = false;
  @override
  String? error;
  @override
  int positionMs = 0;
  @override
  int get durationMs => 1800000;
  double rate = 1;
  Timer? timer;
  @override
  Widget buildVideo() => const SizedBox(key: Key('nativeVideo'));
  @override
  Future<void> initialize(Uri uri, Map<String, String> headers) async {
    expect(uri.path, '/api/v1/playback/r-1/stream');
    expect(headers['Authorization'], 'Bearer access');
  }

  @override
  Future<void> play() async {
    playing = true;
    notifyListeners();
    timer = Timer.periodic(const Duration(seconds: 1), (_) {
      positionMs += (1000 * rate).round();
      notifyListeners();
    });
  }

  @override
  Future<void> pause() async {
    timer?.cancel();
    playing = false;
    notifyListeners();
  }

  @override
  Future<void> seek(int milliseconds) async {
    positionMs = milliseconds;
    notifyListeners();
  }

  @override
  Future<void> speed(double value) async {
    rate = value;
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }
}

/// 原生暂停回调丢失时保持 Future 未完成，用超时驱动页面恢复。
class _StalledPausePlayback extends _FakePlayback {
  @override
  Future<void> pause() {
    timer?.cancel();
    return Completer<void>().future;
  }
}
