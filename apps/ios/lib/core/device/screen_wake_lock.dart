import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// 控制设备是否保持屏幕常亮。模拟考试页在进行中启用，离开时必须关闭。
abstract interface class ScreenWakeLock {
  Future<void> enable();

  Future<void> disable();
}

/// 生产环境使用 wakelock_plus；Widget 测试应覆盖为无操作或可记录实现。
final class WakelockPlusScreenWakeLock implements ScreenWakeLock {
  const WakelockPlusScreenWakeLock();

  @override
  Future<void> enable() => WakelockPlus.enable();

  @override
  Future<void> disable() => WakelockPlus.disable();
}

/// 测试默认实现，避免在没有插件的 VM 上调用原生通道。
final class NoOpScreenWakeLock implements ScreenWakeLock {
  const NoOpScreenWakeLock();

  @override
  Future<void> enable() async {}

  @override
  Future<void> disable() async {}
}

final screenWakeLockProvider = Provider<ScreenWakeLock>(
  (ref) => const NoOpScreenWakeLock(),
);
