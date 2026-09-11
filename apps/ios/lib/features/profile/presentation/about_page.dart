import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_exception.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log.dart';
import 'package:shangan_ios/core/diagnostics/diagnostic_log_store.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

const _platform = MethodChannel('com.shangan/app-update');

/// 关于页：展示本机版本与诊断日志，用户确认后手动上报。
final class AboutPage extends ConsumerStatefulWidget {
  const AboutPage({super.key});

  @override
  ConsumerState<AboutPage> createState() => _AboutPageState();
}

final class _AboutPageState extends ConsumerState<AboutPage> {
  String _version = '';
  String _platformName = '';
  DiagnosticLogSnapshot? _snapshot;
  bool _loading = true;
  bool _uploading = false;

  @override
  void initState() {
    super.initState();
    unawaited(_reload());
  }

  Future<void> _reload() async {
    String version = '';
    String platformName = '';
    try {
      final info = await _platform.invokeMapMethod<String, dynamic>('info');
      version = info?['version'] as String? ?? '';
      platformName = info?['platform'] as String? ?? '';
    } catch (_) {
      // 原生通道不可用时仍允许上报日志，版本留空。
    }
    final snapshot = await DiagnosticLog.snapshot();
    if (!mounted) return;
    setState(() {
      _version = version;
      _platformName = platformName;
      _snapshot = snapshot;
      _loading = false;
    });
  }

  Future<void> _confirmUpload() async {
    final snapshot = _snapshot;
    if (snapshot == null || snapshot.isEmpty || _uploading) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('上报诊断日志？'),
        content: const Text('将把本机诊断日志上传到服务器，管理员可在后台查看。日志已去掉密码、Token 和带查询串的地址。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('上报'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _uploading = true);
    try {
      await ref
          .read(shanganRepositoryProvider)
          .uploadDiagnosticLog(
            filename: 'diagnostic.log',
            bytes: snapshot.bytes,
            appVersion: _version,
            platform: _platformName.isEmpty ? _guessPlatform() : _platformName,
          );
      DiagnosticLog.info('diag', 'upload ok', {
        'bytes': snapshot.sizeBytes,
        'version': _version,
      });
      if (!mounted) return;
      ShanganFeedback.show(context, '诊断日志已上报');
    } catch (error) {
      DiagnosticLog.error('diag', 'upload failed', error: error);
      if (!mounted) return;
      ShanganFeedback.show(
        context,
        shanganErrorMessage(error, '上报失败，请稍后重试'),
        error: true,
      );
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  String _guessPlatform() {
    switch (Theme.of(context).platform) {
      case TargetPlatform.iOS:
        return 'ios';
      case TargetPlatform.android:
        return 'android';
      default:
        return Theme.of(context).platform.name;
    }
  }

  String _sizeLabel(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    final empty = snapshot == null || snapshot.isEmpty;
    return Scaffold(
      appBar: AppBar(title: const Text('关于')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 32),
        children: [
          ShanganCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '上岸',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 8),
                Text(
                  _loading
                      ? '正在读取版本…'
                      : '当前版本 ${_version.isEmpty ? '未知' : _version}'
                            '${_platformName.isEmpty ? '' : ' · $_platformName'}',
                  style: const TextStyle(color: ShanganColors.mutedInk),
                ),
                const SizedBox(height: 8),
                Text(
                  empty
                      ? '暂无诊断日志。使用一段时间后再来上报。'
                      : '本机日志 ${_sizeLabel(snapshot.sizeBytes)}，保存在此设备，不会自动上传。',
                  style: const TextStyle(
                    fontSize: 13,
                    color: ShanganColors.mutedInk,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: empty || _uploading ? null : _confirmUpload,
            child: Text(_uploading ? '上报中…' : '上报日志'),
          ),
          const SizedBox(height: 10),
          const Text(
            '日志包含启动、页面跳转、登录结果、接口错误、播放状态变化、专注、催办和崩溃信息，已脱敏。',
            style: TextStyle(fontSize: 12, color: ShanganColors.mutedInk),
          ),
        ],
      ),
    );
  }
}
