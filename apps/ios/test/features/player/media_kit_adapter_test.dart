import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:media_kit/media_kit.dart' as mk;
import 'package:media_kit_video/media_kit_video.dart' as mv;
import 'package:shangan_ios/core/player/media_kit_playback_adapter.dart';

/// 模拟第三方内核边界，验证认证、控制、事件和资源释放，不启动解码器。
class _Player extends Mock implements mk.Player {}

class _Streams extends Mock implements mk.PlayerStream {}

class _Controller extends Mock implements mv.VideoController {}

void main() {
  setUpAll(() {
    registerFallbackValue(mk.Media('https://example.invalid/video'));
    registerFallbackValue(Duration.zero);
  });

  test('认证头只传给内核，初始化暂停，控制命令准确转交', () async {
    final fixture = _Fixture();
    await fixture.open();
    final media =
        verify(
              () => fixture.player.open(captureAny(), play: false),
            ).captured.single
            as mk.Media;
    expect(media.uri, 'https://example.invalid/api/v1/playback/r/stream');
    expect(media.httpHeaders, {'Authorization': 'Bearer test-only'});
    expect(fixture.adapter.durationMs, 60000);
    await fixture.adapter.play();
    await fixture.adapter.seek(10000);
    await fixture.adapter.speed(2);
    await fixture.adapter.pause();
    verify(() => fixture.player.play()).called(1);
    verify(() => fixture.player.seek(const Duration(seconds: 10))).called(1);
    verify(() => fixture.player.setRate(2)).called(1);
    verify(() => fixture.player.pause()).called(1);
    await fixture.close();
  });

  testWidgets('位置高频事件合并刷新，播放恢复清除旧错误', (tester) async {
    final fixture = _Fixture();
    await fixture.open();
    fixture.playing.add(true);
    var notifications = 0;
    fixture.adapter.addListener(() => notifications++);
    for (var i = 1; i <= 100; i++) {
      fixture.position.add(Duration(milliseconds: i));
    }
    expect(fixture.adapter.positionMs, 100);
    expect(notifications, 0);
    await tester.pump(const Duration(milliseconds: 250));
    expect(notifications, 1);
    fixture.error.add('decoder error');
    expect(fixture.adapter.playing, isFalse);
    fixture.position.add(const Duration(seconds: 1));
    expect(fixture.adapter.error, isNull);
    expect(fixture.adapter.playing, isTrue);
    await fixture.close();
  });

  test('初始化错误及时失败且不泄露原始媒体地址', () async {
    final fixture = _Fixture();
    when(() => fixture.player.open(any(), play: false)).thenAnswer((_) async {
      fixture.error.add('failed https://example.invalid/?token=secret');
    });
    await expectLater(fixture.open(), throwsStateError);
    expect(fixture.adapter.error, '视频加载或播放失败，请重试');
    await fixture.close();
  });

  test('缓冲和位置来自真实事件，完成停止，错误脱敏，销毁取消订阅', () async {
    final fixture = _Fixture();
    await fixture.open();
    fixture.playing.add(true);
    fixture.buffering.add(true);
    fixture.position.add(const Duration(seconds: 12));
    expect(fixture.adapter.playing, isTrue);
    expect(fixture.adapter.buffering, isTrue);
    expect(fixture.adapter.positionMs, 12000);
    fixture.error.add('https://example.invalid/?token=secret');
    expect(fixture.adapter.error, '视频加载或播放失败，请重试');
    fixture.completed.add(true);
    expect(fixture.adapter.playing, isFalse);
    expect(fixture.adapter.ended, isTrue);
    expect(fixture.adapter.positionMs, 60000);
    expect(fixture.adapter.error, isNull);
    fixture.error.add('EOF');
    expect(fixture.adapter.error, isNull);
    await fixture.adapter.seek(0);
    fixture.error.add('new failure');
    expect(fixture.adapter.error, '视频加载或播放失败，请重试');
    await fixture.close();
    expect(fixture.playing.hasListener, isFalse);
    verify(() => fixture.player.dispose()).called(1);
  });
}

/// 同步事件控制器确保测试可精确排列状态变化，无等待与平台通道。
class _Fixture {
  final player = _Player();
  final streams = _Streams();
  final playing = StreamController<bool>.broadcast(sync: true);
  final buffering = StreamController<bool>.broadcast(sync: true);
  final position = StreamController<Duration>.broadcast(sync: true);
  final duration = StreamController<Duration>.broadcast(sync: true);
  final completed = StreamController<bool>.broadcast(sync: true);
  final error = StreamController<String>.broadcast(sync: true);
  late final adapter = MediaKitPlaybackAdapter(
    createPlayer: () => player,
    createController: (_) => _Controller(),
  );

  _Fixture() {
    when(() => player.stream).thenReturn(streams);
    when(() => streams.playing).thenAnswer((_) => playing.stream);
    when(() => streams.buffering).thenAnswer((_) => buffering.stream);
    when(() => streams.position).thenAnswer((_) => position.stream);
    when(() => streams.duration).thenAnswer((_) => duration.stream);
    when(() => streams.completed).thenAnswer((_) => completed.stream);
    when(() => streams.error).thenAnswer((_) => error.stream);
    when(() => player.open(any(), play: false)).thenAnswer((_) async {
      duration.add(const Duration(minutes: 1));
    });
    when(() => player.play()).thenAnswer((_) async {});
    when(() => player.pause()).thenAnswer((_) async {});
    when(() => player.seek(any())).thenAnswer((_) async {});
    when(() => player.setRate(any())).thenAnswer((_) async {});
    when(() => player.dispose()).thenAnswer((_) async {});
  }

  Future<void> open() => adapter.initialize(
    Uri.parse('https://example.invalid/api/v1/playback/r/stream'),
    {'Authorization': 'Bearer test-only'},
  );

  Future<void> close() async {
    adapter.dispose();
    await Future.wait([
      playing.close(),
      buffering.close(),
      position.close(),
      duration.close(),
      completed.close(),
      error.close(),
    ]);
  }
}
