import 'package:shangan_ios/core/presence/app_activity.dart';
import 'package:shangan_ios/core/player/progress_queue.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/presence/heartbeat_service.dart';
import 'package:shangan_ios/core/presence/nag_event_service.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/catalog/presentation/library_page.dart';
import 'package:shangan_ios/features/home/presentation/home_page.dart';
import 'package:shangan_ios/features/nag/presentation/fullscreen_nag_page.dart';
import 'package:shangan_ios/features/profile/presentation/profile_page.dart';
import 'package:shangan_ios/features/stats/presentation/stats_page.dart';

/// 全局路由观察者，供播放页等需要感知页面进出的组件订阅。
final shanganRouteObserver = RouteObserver<ModalRoute<void>>();

/// 学习端外壳：首页 / 学习 / 数据 / 我的 四个 Tab。
///
/// 同时负责启动心跳、展示离线条与拉起全屏催办。
final class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> {
  final _homeKey = GlobalKey<HomePageState>();
  HeartbeatService? _heartbeat;
  NagEventService? _nagEvents;

  /// 在线状态同步轮询；必须持有句柄，否则 Shell 卸载后定时器仍会存活到下一个周期。
  Timer? _presencePoll;
  bool _nagVisible = false;
  bool _offline = false;

  @override
  void initState() {
    super.initState();
    // 首帧后再启动心跳，避免拿不到 ProviderScope。
    WidgetsBinding.instance.addPostFrameCallback((_) => _startHeartbeat());
  }

  @override
  void dispose() {
    _presencePoll?.cancel();
    _heartbeat?.dispose();
    _nagEvents?.dispose();
    super.dispose();
  }

  void _startHeartbeat() {
    if (!mounted) return;
    final events = NagEventService(
      connect: ref.read(shanganRepositoryProvider).watchNagEvents,
      onPendingNag: () => unawaited(_showPendingNag()),
    );
    events.start();
    _nagEvents = events;
    final outbox = ref.read(progressOutboxProvider);
    final service = HeartbeatService(
      repository: ref.read(shanganRepositoryProvider),
      clientVersion: '2.0.0',
      activity: ref.read(appActivityProvider),
      queueDepth: () => outbox.depth,
      onConnected: () async {
        await outbox.flush();
      },
      onTransportMode: events.setMode,
      onPendingNag: (_) => unawaited(_showPendingNag()),
    );
    service.start();
    _heartbeat = service;
    // 心跳状态变化不频繁，用轮询同步给「我的」页与离线条即可。
    _presencePoll = Timer.periodic(const Duration(seconds: 20), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      final heartbeat = _heartbeat;
      final online = heartbeat?.online ?? true;
      ref
          .read(heartbeatStatusProvider.notifier)
          .update(
            online: online,
            queuedEvents: heartbeat?.queuedEvents ?? 0,
            lastReportedAt: heartbeat?.lastSuccessAt,
          );
      if (online == _offline) {
        setState(() => _offline = !online);
      }
    });
  }

  /// 收到待回应催办时拉起全屏页；同一时刻只展示一个。
  Future<void> _showPendingNag() async {
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    if (_nagVisible ||
        !mounted ||
        (lifecycle != null && lifecycle != AppLifecycleState.resumed)) {
      return;
    }
    // 在首个 await 前占位，SSE 与心跳同时到达也只能开启一条弹框流程。
    _nagVisible = true;
    final repository = ref.read(shanganRepositoryProvider);
    try {
      while (mounted) {
        final nag = await repository.loadPendingNag();
        if (nag == null || !mounted) return;
        final settings = await repository.loadMe();
        if (!mounted) return;
        await FullscreenNagPage.show(
          context,
          nag: nag,
          minReasonLength: settings.minReasonLength,
        );
        if (!mounted) return;
        ref.invalidate(dayViewProvider);
        ref.invalidate(pendingNagProvider);
        // 回应后继续补查，展示期间到达的后续催办无需等待下一次心跳。
      }
    } catch (_) {
      // 网络失败交由下一次 SSE 重连或心跳补查。
    } finally {
      _nagVisible = false;
    }
  }

  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final pages = [
      HomePage(key: _homeKey),
      const LibraryPage(),
      const StatsPage(),
      const ProfilePage(),
    ];
    return AppActivityScope(
      activity: AppActivity(
        const ['HOME', 'LIBRARY', 'STATS', 'PROFILE'][_index],
        'BROWSING',
      ),
      child: Scaffold(
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              if (_offline) const _OfflineBanner(),
              Expanded(child: pages[_index]),
            ],
          ),
        ),
        floatingActionButton: _index == 0
            ? _AddTodoFab(onTap: () => _homeKey.currentState?.addTodo())
            : null,
        bottomNavigationBar: DecoratedBox(
          // 原型 `.tabbar{border-top:1.5px solid var(--ink)}`；NavigationBar 自身
          // 没有描边属性，只能在外层补一条上边线（1-1 ~ 1-9 底部 Tab）。
          decoration: const BoxDecoration(
            border: Border(
              top: BorderSide(
                color: ShanganColors.ink,
                width: ShanganRadius.borderWidth,
              ),
            ),
          ),
          child: NavigationBar(
            selectedIndex: _index,
            onDestinationSelected: (index) => setState(() => _index = index),
            // 原型 tabbar 选中态只换底色与描边，图标本身保持同一套线性图标。
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home_outlined),
                label: '首页',
              ),
              NavigationDestination(
                icon: Icon(Icons.menu_book_outlined),
                selectedIcon: Icon(Icons.menu_book_outlined),
                label: '学习',
              ),
              NavigationDestination(
                icon: Icon(Icons.bar_chart_outlined),
                selectedIcon: Icon(Icons.bar_chart_outlined),
                label: '数据',
              ),
              NavigationDestination(
                icon: Icon(Icons.person_outline),
                selectedIcon: Icon(Icons.person_outline),
                label: '我的',
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 原型 `.fab`：60x60、圆角 22 的红色添加按钮，带同色投影。
final class _AddTodoFab extends StatelessWidget {
  const _AddTodoFab({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: '添加待办',
      child: Container(
        width: 60,
        height: 60,
        decoration: BoxDecoration(
          color: ShanganColors.red,
          borderRadius: BorderRadius.circular(22),
          boxShadow: const [
            BoxShadow(
              color: Color(0x6BC84235),
              blurRadius: 22,
              offset: Offset(0, 10),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(22),
          child: InkWell(
            borderRadius: BorderRadius.circular(22),
            onTap: onTap,
            child: const Center(
              child: Icon(Icons.add, size: 27, color: Colors.white),
            ),
          ),
        ),
      ),
    );
  }
}

/// 离线条：心跳失败时提示，但不影响本地播放与计时。
final class _OfflineBanner extends ConsumerWidget {
  const _OfflineBanner();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(heartbeatStatusProvider);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(18, 8, 18, 0),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: ShanganColors.redSoft,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: ShanganColors.redLine),
      ),
      child: Row(
        children: [
          const Icon(Icons.wifi_off, size: 18, color: ShanganColors.red),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              status.queuedEvents > 0
                  ? '离线 · ${status.queuedEvents} 条进度待同步'
                  : '离线 · 进度会在恢复后自动同步',
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: ShanganColors.red,
              ),
            ),
          ),
          GestureDetector(
            onTap: () {
              ref.invalidate(dayViewProvider);
              ref.invalidate(meSettingsProvider);
            },
            child: const Text(
              '重试',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w800,
                color: ShanganColors.red,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 未完成汇总入口，供首页与我的页跳转。
void openPendingSummary(BuildContext context) => context.push('/todos/pending');
