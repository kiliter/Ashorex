import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 全屏标题栏显示设备本地日期时间与真实电量；模拟器无法读取时显示未知。
final class PlayerDeviceStatus extends StatefulWidget {
  const PlayerDeviceStatus({super.key});
  @override
  State<PlayerDeviceStatus> createState() => _PlayerDeviceStatusState();
}

class _PlayerDeviceStatusState extends State<PlayerDeviceStatus> {
  static const _channel = MethodChannel('com.shangan/device-status');
  Timer? _timer;
  int? _battery;
  DateTime _now = DateTime.now();
  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refresh());
  }

  /// 状态信息读取失败不影响视频播放，不伪造电量。
  Future<void> _refresh() async {
    int? value;
    try {
      value = await _channel.invokeMethod<int>('battery');
    } catch (_) {
      /* 平台不支持时显示未知。 */
    }
    if (mounted) {
      setState(() {
        _now = DateTime.now();
        _battery = value != null && value >= 0 && value <= 100 ? value : null;
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    String two(int value) => value.toString().padLeft(2, '0');
    return Text(
      '${_now.month}/${_now.day} ${two(_now.hour)}:${two(_now.minute)} · 电量 ${_battery == null ? "未知" : "$_battery%"}',
      style: const TextStyle(color: Colors.white, fontSize: 12),
    );
  }
}
