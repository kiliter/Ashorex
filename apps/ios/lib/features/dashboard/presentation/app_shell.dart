import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/catalog/presentation/course_list_page.dart';
import 'package:shangan_ios/features/dashboard/presentation/home_page.dart';
import 'package:shangan_ios/features/reporting/presentation/daily_report_page.dart';

/// 登录后的四 Tab 根壳层，保留学习闭环、数据和个人设置入口。
final class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

final class _AppShellState extends ConsumerState<AppShell> {
  int _selectedIndex = 0;

  static const _pages = <Widget>[
    HomePage(),
    CourseListPage(),
    DailyReportPage(),
    _ProfileTab(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _selectedIndex, children: _pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (index) =>
            setState(() => _selectedIndex = index),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined), label: '首页'),
          NavigationDestination(
            icon: Icon(Icons.play_circle_outline),
            label: '学习',
          ),
          NavigationDestination(
            icon: Icon(Icons.insights_outlined),
            label: '数据',
          ),
          NavigationDestination(icon: Icon(Icons.person_outline), label: '我的'),
        ],
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
        children: [
          ListTile(
            leading: const CircleAvatar(child: Icon(Icons.person_outline)),
            title: Text(user?.displayName ?? '学习用户'),
            subtitle: Text(user?.username ?? ''),
          ),
          const Divider(),
          ListTile(
            key: const Key('settingsEntry'),
            minTileHeight: 52,
            leading: const Icon(Icons.settings_outlined),
            title: const Text('学习偏好'),
            subtitle: const Text('验活等级与日终时间'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/settings'),
          ),
          ListTile(
            minTileHeight: 52,
            leading: const Icon(Icons.logout),
            title: const Text('退出登录'),
            onTap: controller.logout,
          ),
        ],
      ),
    );
  }
}
