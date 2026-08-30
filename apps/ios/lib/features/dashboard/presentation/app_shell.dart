import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/catalog/presentation/course_list_page.dart';

/// 登录后的五 Tab 根壳层；后续业务 Task 在各占位页内逐步落地。
final class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

final class _AppShellState extends ConsumerState<AppShell> {
  int _selectedIndex = 0;

  static const _pages = <Widget>[
    _ShellPlaceholder(
      icon: Icons.home_outlined,
      title: '首页',
      message: '考试目标、今日计划和学习欠债将在这里汇总。',
    ),
    CourseListPage(),
    _ShellPlaceholder(
      icon: Icons.auto_awesome_outlined,
      title: 'AI',
      message: '只读问答入口，不会修改你的学习数据。',
    ),
    _ShellPlaceholder(
      icon: Icons.insights_outlined,
      title: '数据',
      message: '日报、周报和晚间审判将在这里展示。',
    ),
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
            icon: Icon(Icons.auto_awesome_outlined),
            label: 'AI',
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

final class _ShellPlaceholder extends StatelessWidget {
  const _ShellPlaceholder({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 56,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 16),
              Text(title, style: Theme.of(context).textTheme.headlineMedium),
              const SizedBox(height: 10),
              Text(
                message,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyLarge,
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
