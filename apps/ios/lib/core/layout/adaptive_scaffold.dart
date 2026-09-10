import 'package:flutter/material.dart';
import 'package:shangan_ios/core/layout/adaptive_breakpoints.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// Shell 共用的导航目标；业务页面不感知 BottomNavigation / NavigationRail 差异。
final class AdaptiveDestination {
  const AdaptiveDestination({required this.icon, required this.label});

  final IconData icon;
  final String label;
}

/// 最小响应式外壳：手机保留底部导航，Pad 改为左侧 NavigationRail。
final class AdaptiveScaffold extends StatelessWidget {
  const AdaptiveScaffold({
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.destinations,
    required this.body,
    this.floatingActionButton,
    super.key,
  });

  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final List<AdaptiveDestination> destinations;
  final Widget body;
  final Widget? floatingActionButton;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final useRail = AppBreakpoints.useNavigationRail(constraints);
      return Scaffold(
        body: SafeArea(
          // 手机底部由 NavigationBar 处理安全区；Pad 没有底栏，正文自己避开手势区。
          bottom: useRail,
          child: useRail
              ? Row(
                  children: [
                    DecoratedBox(
                      decoration: const BoxDecoration(
                        color: ShanganColors.surface,
                        border: Border(
                          right: BorderSide(
                            color: ShanganColors.ink,
                            width: ShanganRadius.borderWidth,
                          ),
                        ),
                      ),
                      child: NavigationRail(
                        key: const ValueKey('adaptive-navigation-rail'),
                        selectedIndex: selectedIndex,
                        onDestinationSelected: onDestinationSelected,
                        labelType: NavigationRailLabelType.all,
                        // Pad 侧栏按原型约 84pt 再留出触控余量，避免图标和文字挤在一起。
                        minWidth: 104,
                        groupAlignment: -0.85,
                        backgroundColor: ShanganColors.surface,
                        indicatorColor: ShanganColors.blueSoft,
                        indicatorShape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: const BorderSide(color: ShanganColors.blue),
                        ),
                        selectedIconTheme: const IconThemeData(
                          color: ShanganColors.blue,
                          size: 28,
                        ),
                        unselectedIconTheme: const IconThemeData(
                          color: ShanganColors.mutedInk,
                          size: 28,
                        ),
                        selectedLabelTextStyle: const TextStyle(
                          color: ShanganColors.blue,
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                        unselectedLabelTextStyle: const TextStyle(
                          color: ShanganColors.mutedInk,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                        destinations: [
                          for (final destination in destinations)
                            NavigationRailDestination(
                              icon: Icon(destination.icon),
                              selectedIcon: Icon(destination.icon),
                              label: Text(destination.label),
                              padding: const EdgeInsets.symmetric(vertical: 12),
                            ),
                        ],
                      ),
                    ),
                    Expanded(child: body),
                  ],
                )
              : body,
        ),
        floatingActionButton: floatingActionButton,
        bottomNavigationBar: useRail
            ? null
            : DecoratedBox(
                key: const ValueKey('adaptive-bottom-navigation'),
                decoration: const BoxDecoration(
                  border: Border(
                    top: BorderSide(
                      color: ShanganColors.ink,
                      width: ShanganRadius.borderWidth,
                    ),
                  ),
                ),
                child: NavigationBar(
                  selectedIndex: selectedIndex,
                  onDestinationSelected: onDestinationSelected,
                  destinations: [
                    for (final destination in destinations)
                      NavigationDestination(
                        icon: Icon(destination.icon),
                        selectedIcon: Icon(destination.icon),
                        label: destination.label,
                      ),
                  ],
                ),
              ),
      );
    },
  );
}
