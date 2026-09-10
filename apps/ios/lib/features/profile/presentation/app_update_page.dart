import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/api/api_exception.dart';
import '../../../core/api/app_download.dart';
import '../../../core/models/app_release.dart';
import '../../../core/state/shangan_providers.dart';

const _platform = MethodChannel('com.shangan/app-update');

/// 两个角色共享入口；只读取本机版本，不在进入「我的」时请求服务器。
class AppUpdateEntry extends StatefulWidget {
  const AppUpdateEntry({super.key, required this.builder});

  /// 由「我的」页复用原有设置行，避免更新入口引入另一套列表样式。
  final Widget Function(String version, VoidCallback open) builder;
  @override
  State<AppUpdateEntry> createState() => _AppUpdateEntryState();
}

class _AppUpdateEntryState extends State<AppUpdateEntry> {
  late final Future<Map<dynamic, dynamic>?> info;
  @override
  void initState() {
    super.initState();
    info = _platform.invokeMapMethod('info');
  }

  @override
  Widget build(BuildContext context) => FutureBuilder(
    future: info,
    builder: (context, snapshot) => widget.builder(
      snapshot.data?['version'] as String? ?? '',
      () => Navigator.of(
        context,
      ).push(MaterialPageRoute<void>(builder: (_) => const AppUpdatePage())),
    ),
  );
}

/// 主动检查页面；离开时取消下载，权限设置返回仅继续安装，不隐式查询版本。
class AppUpdatePage extends ConsumerStatefulWidget {
  const AppUpdatePage({super.key});
  @override
  ConsumerState<AppUpdatePage> createState() => _AppUpdatePageState();
}

class _AppUpdatePageState extends ConsumerState<AppUpdatePage>
    with WidgetsBindingObserver {
  String current = '', directory = '', message = '', path = '';
  bool busy = false,
      installing = false,
      simulator = false,
      pendingPermission = false;
  AppRelease? release;
  AppDownload? download;
  double progress = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadInfo();
  }

  Future<void> _loadInfo() async {
    try {
      final info = await _platform.invokeMapMethod<String, dynamic>('info');
      if (!mounted) return;
      setState(() {
        current = info?['version'] as String? ?? '';
        directory = info?['directory'] as String? ?? '';
        simulator = info?['simulator'] == true;
      });
    } catch (_) {
      if (mounted) setState(() => message = '无法读取当前版本，请重新打开页面');
    }
  }

  @override
  void dispose() {
    download?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && pendingPermission) {
      pendingPermission = false;
      _install(fromPermission: true);
    }
  }

  /// 请求仅发生于按钮点击；重复点击在本地合并，旧服务端失败不伪装成最新。
  Future<void> _check() async {
    if (busy || current.isEmpty) return;
    setState(() {
      busy = true;
      message = '';
      release = null;
      path = '';
    });
    try {
      final result = await ref
          .read(shanganRepositoryProvider)
          .checkAppUpdate(Platform.isAndroid ? 'android' : 'ios');
      final newer = AppRelease.compare(result.version, current) > 0;
      if (mounted) {
        setState(() {
          release = newer ? result : null;
          message = newer
              ? (result.downloadable
                    ? '发现新版本 ${result.version}'
                    : '发现新版本 ${result.version}，对应平台安装包尚未发布完整')
              : '当前无需更新';
        });
      }
    } catch (error) {
      if (mounted) setState(() => message = _error(error));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  String _error(Object error) {
    if (error is ApiException &&
        error.statusCode == 404 &&
        error.errorCode != 'APP_UPDATE_NOT_FOUND') {
      return '当前服务端暂不支持检查更新，请联系管理员更新服务端';
    }
    if (error is ApiException) return error.message;
    if (error is PlatformException) return error.message ?? '系统操作失败，请重试';
    if (error is FormatException) return error.message;
    return '操作未完成，请检查网络或可用空间后重试';
  }

  /// 下载进度只代表文件接收；必须通过摘要校验后才出现系统安装按钮。
  Future<void> _download() async {
    final selected = release;
    if (selected == null || busy || installing) return;
    final task = AppDownload();
    setState(() {
      busy = true;
      download = task;
      progress = 0;
      message = '正在下载，可取消后续传';
    });
    try {
      final result = await ref
          .read(shanganRepositoryProvider)
          .downloadAppUpdate(task, selected, directory, (received, total) {
            if (mounted) setState(() => progress = received / total);
          });
      if (mounted) {
        setState(() {
          path = result;
          message = '下载完成并通过校验，请点击安装';
        });
      }
    } catch (error) {
      if (mounted) setState(() => message = '下载已停止，可点击下载继续。${_error(error)}');
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
          download = null;
        });
      }
    }
  }

  Future<void> _install({bool fromPermission = false}) async {
    if (path.isEmpty || installing || release == null) return;
    setState(() => installing = true);
    try {
      final allowed = await _platform.invokeMethod<bool>('canInstall') ?? false;
      if (!allowed) {
        if (fromPermission) {
          if (mounted) setState(() => message = '尚未允许安装，请点击安装重新授权');
          return;
        }
        pendingPermission = true;
        await _platform.invokeMethod<void>('requestInstallPermission');
        return;
      }
      await _platform.invokeMethod<void>('install', {
        'path': path,
        'sha256': release!.sha256,
        'version': release!.version,
      });
      if (mounted) setState(() => message = '已交给系统，请在安装器中确认；取消后可再次点击安装');
    } catch (error) {
      pendingPermission = false;
      if (mounted) setState(() => message = _error(error));
    } finally {
      if (mounted) setState(() => installing = false);
    }
  }

  Future<void> _openPage() async {
    try {
      final url = ref.read(shanganRepositoryProvider).appUpdatePage(release!);
      await _platform.invokeMethod<void>('openPage', {'url': url.toString()});
    } catch (error) {
      if (mounted) setState(() => message = _error(error));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('版本与更新')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            '当前版本 ${current.isEmpty ? '读取中' : current}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          const Text('点击检查更新后查询最新正式版本，不会自动下载或安装。'),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: busy || installing || current.isEmpty ? null : _check,
            child: Text(busy && download == null ? '检查中…' : '检查更新'),
          ),
          if (message.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Text(message),
            ),
          if (release != null) ...[
            Text('更新说明', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(release!.notes.isEmpty ? '本版本暂无更新说明' : release!.notes),
            if (release!.downloadable) ...[
              const SizedBox(height: 16),
              Text(
                '安装包 ${(release!.size / 1024 / 1024).toStringAsFixed(1)} MB',
              ),
              if (simulator)
                const Text('模拟器请通过开发工具安装，不能安装真机 IPA。')
              else if (Platform.isAndroid) ...[
                if (download != null) ...[
                  LinearProgressIndicator(value: progress),
                  Text('${(progress * 100).toStringAsFixed(0)}%'),
                  TextButton(
                    onPressed: () => download?.cancel(),
                    child: const Text('取消下载'),
                  ),
                ] else
                  FilledButton(
                    onPressed: busy || installing
                        ? null
                        : path.isEmpty
                        ? _download
                        : () => _install(),
                    child: Text(
                      installing
                          ? '准备安装…'
                          : path.isEmpty
                          ? '下载 APK'
                          : '安装更新',
                    ),
                  ),
              ] else ...[
                const Text('下载 IPA 后，使用轻松签自行签名安装。请沿用原签名方式。'),
                FilledButton(
                  onPressed: busy ? null : _openPage,
                  child: const Text('打开下载页面'),
                ),
              ],
            ],
          ],
        ],
      ),
    ),
  );
}
