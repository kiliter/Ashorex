import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
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
            onDestinationSelected: (index) =>
                setState(() => _selectedIndex = index),
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
          Row(
            children: [
              Container(
                width: 58,
                height: 58,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: ShanganColors.ink,
                  borderRadius: BorderRadius.circular(18),
                  boxShadow: const [
                    BoxShadow(
                      color: ShanganColors.blueSoft,
                      offset: Offset(4, 4),
                    ),
                  ],
                ),
                child: Text(
                  (user?.displayName.isNotEmpty == true
                      ? user!.displayName.substring(0, 1)
                      : '学'),
                  style: Theme.of(context).textTheme.titleLarge
                      ?.copyWith(color: ShanganColors.surface),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
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
          const SizedBox(height: 22),
          const Divider(),
          InkWell(
            key: const Key('settingsEntry'),
            onTap: () => context.push('/settings'),
            child: const Padding(
              padding: EdgeInsets.symmetric(vertical: 15),
              child: Row(
                children: [
                  Icon(Icons.tune, color: ShanganColors.blue),
                  SizedBox(width: 12),
                  Expanded(child: Text('学习偏好')),
                  Text(
                    '验活与日终 ›',
                    style: TextStyle(color: ShanganColors.mutedInk),
                  ),
                ],
              ),
            ),
          ),
          const Divider(),
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
