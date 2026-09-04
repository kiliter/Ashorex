import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/dashboard/presentation/home_page.dart';
import 'package:shangan_ios/features/planning/presentation/study_calendar_page.dart';
import 'package:shangan_ios/features/reporting/presentation/daily_report_page.dart';

/// 供 GoRouter 通知首页壳层：从播放器或编排页返回后刷新当前 Tab。
final shanganRouteObserver = RouteObserver<ModalRoute<void>>();

/// 登录后的四 Tab 根壳层，保留学习闭环、数据和个人设置入口。
final class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

final class _AppShellState extends ConsumerState<AppShell>
    with RouteAware, WidgetsBindingObserver {
  int _selectedIndex = 0;

  static const _pages = <Widget>[
    HomePage(),
    StudyCalendarPage(),
    DailyReportPage(),
    _ProfileTab(),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != null) {
      shanganRouteObserver.subscribe(this, route);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    shanganRouteObserver.unsubscribe(this);
    super.dispose();
  }

  @override
  void didPopNext() {
    // 作战单同时出现在首页和学习 Tab，返回后两边都要重拉，避免考试状态只更新当前页。
    bumpHomeRefresh();
    bumpStudyCalendarRefresh();
    bumpDailyReportRefresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      bumpHomeRefresh();
      bumpStudyCalendarRefresh();
      bumpDailyReportRefresh();
    }
  }

  void _refreshTab(int index) {
    switch (index) {
      case 0:
        bumpHomeRefresh();
      case 1:
        bumpStudyCalendarRefresh();
      case 2:
        bumpDailyReportRefresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _selectedIndex, children: _pages),
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          color: ShanganColors.surface,
          border: Border(
            top: BorderSide(color: ShanganColors.blue, width: 1.5),
          ),
          boxShadow: [
            BoxShadow(color: ShanganColors.blueSoft, offset: Offset(0, -4)),
          ],
        ),
        child: SafeArea(
          top: false,
          child: NavigationBar(
            selectedIndex: _selectedIndex,
            onDestinationSelected: (index) {
              setState(() => _selectedIndex = index);
              _refreshTab(index);
            },
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home),
                label: '首页',
              ),
              NavigationDestination(
                icon: Icon(Icons.menu_book_outlined),
                selectedIcon: Icon(Icons.menu_book),
                label: '学习',
              ),
              NavigationDestination(
                icon: Icon(Icons.bar_chart_outlined),
                selectedIcon: Icon(Icons.bar_chart),
                label: '数据',
              ),
              NavigationDestination(
                icon: Icon(Icons.person_outline),
                selectedIcon: Icon(Icons.person),
                label: '我的',
              ),
            ],
          ),
        ),
      ),
    );
  }
}

final class _ProfileTab extends ConsumerWidget {
  const _ProfileTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(authControllerProvider);
    final user = controller.state.user;
    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        padding: shanganPagePadding,
        children: [
          ShanganSurface(
            borderColor: ShanganColors.blue,
            child: Row(
              children: [
                Container(
                  width: 58,
                  height: 58,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: ShanganColors.ink,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Text(
                    (user?.displayName.isNotEmpty == true
                        ? user!.displayName.substring(0, 1)
                        : '学'),
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      color: ShanganColors.surface,
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const ShanganEyebrow('学习账号'),
                      const SizedBox(height: 4),
                      Text(
                        user?.displayName ?? '学习用户',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 3),
                      Text(
                        '用户名：${user?.username ?? ''}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          ShanganSurface(
            borderColor: ShanganColors.blue,
            child: Column(
              children: [
                const Align(
                  alignment: Alignment.centerLeft,
                  child: ShanganEyebrow('备考与偏好'),
                ),
                const SizedBox(height: 8),
                ShanganNavRow(
                  key: const Key('examSettingsEntry'),
                  icon: Icons.flag_outlined,
                  title: '考试设置',
                  trailing: '目标与日期',
                  onTap: () => context.push('/exam-settings'),
                ),
                const Divider(),
                ShanganNavRow(
                  key: const Key('mockExamPresetEntry'),
                  icon: Icons.assignment_outlined,
                  title: '模拟考试预置',
                  trailing: '名称与时长',
                  onTap: () => context.push('/mock-exam-presets'),
                ),
                const Divider(),
                ShanganNavRow(
                  key: const Key('settingsEntry'),
                  icon: Icons.tune,
                  title: '学习偏好',
                  trailing: '验活与日终',
                  onTap: () => context.push('/settings'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          OutlinedButton.icon(
            onPressed: controller.logout,
            icon: const Icon(Icons.logout),
            label: const Text('退出登录'),
          ),
          const SizedBox(height: 8),
          Text(
            '退出不会删除服务端已记录的可信学习数据。',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}
