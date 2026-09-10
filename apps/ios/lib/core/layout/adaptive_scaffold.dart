import 'package:flutter/material.dart';
import 'package:shangan_ios/core/layout/adaptive_breakpoints.dart';
import 'package:shangan_ios/core/layout/pad_chrome.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';

/// Shell 共用的导航目标；业务页面不感知 BottomNavigation / NavigationRail 差异。
final class AdaptiveDestination {
  const AdaptiveDestination({required this.icon, required this.label});

  final IconData icon;
  final String label;
}

/// 最小响应式外壳：手机保留底部导航，Pad 改为左侧品牌侧栏。
final class AdaptiveScaffold extends StatelessWidget {
  const AdaptiveScaffold({
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.destinations,
    required this.body,
    this.floatingActionButton,
    this.accountName,
    super.key,
  });

  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final List<AdaptiveDestination> destinations;
  final Widget body;
  final Widget? floatingActionButton;

  /// Pad 侧栏底部竖排展示的账号显示名；手机底栏不使用。
  final String? accountName;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final useRail = AppBreakpoints.useNavigationRail(constraints);
      final pad = AppBreakpoints.usePadLayout(
        Size(constraints.maxWidth, constraints.maxHeight),
      );
      return Scaffold(
        body: SafeArea(
          // 手机底部由 NavigationBar 处理安全区；Pad 没有底栏，正文自己避开手势区。
          bottom: useRail,
          child: useRail
              ? Row(
                  children: [
                    _PadNavigationRail(
                      selectedIndex: selectedIndex,
                      onDestinationSelected: onDestinationSelected,
                      destinations: destinations,
                      pad: pad,
                      accountName: accountName,
                    ),
                    Expanded(child: body),
                  ],
                )
              : body,
        ),
        // Pad 首页把添加入口放到页头，不再叠红色 FAB。
        floatingActionButton: pad ? null : floatingActionButton,
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

/// 原型 `#nav-rail`：84pt 侧栏；顶部放 App 图标，底部竖排账号显示名。
final class _PadNavigationRail extends StatelessWidget {
  const _PadNavigationRail({
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.destinations,
    required this.pad,
    this.accountName,
  });

  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final List<AdaptiveDestination> destinations;
  final bool pad;
  final String? accountName;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('adaptive-navigation-rail'),
      width: PadChrome.railWidth,
      decoration: BoxDecoration(
        color: ShanganColors.surface,
        border: Border(
          right: BorderSide(
            color: pad ? ShanganColors.hair : ShanganColors.ink,
            width: pad ? 1 : ShanganRadius.borderWidth,
          ),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(9, 16, 9, 14),
      child: Column(
        children: [
          const _PadAppIcon(),
          const SizedBox(height: 22),
          for (var index = 0; index < destinations.length; index++) ...[
            if (index > 0) const SizedBox(height: 11),
            _PadNavItem(
              destination: destinations[index],
              selected: index == selectedIndex,
              onTap: () => onDestinationSelected(index),
            ),
          ],
          const Spacer(),
          _PadAccountName(
            name: accountName,
            onTap: destinations.length > 3
                ? () => onDestinationSelected(3)
                : null,
          ),
        ],
      ),
    );
  }
}

/// 侧栏顶部 App 图标：圆角书封、浅投影和顶部高光，避免纯色色块。
final class _PadAppIcon extends StatelessWidget {
  const _PadAppIcon();

  static const _size = 42.0;
  static const _radius = 12.0;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: '上岸',
      image: true,
      child: Container(
        key: const ValueKey('pad-app-icon'),
        width: _size,
        height: _size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(_radius),
          boxShadow: const [
            BoxShadow(
              color: Color(0x33263B60),
              blurRadius: 12,
              offset: Offset(0, 5),
            ),
            BoxShadow(
              color: Color(0x142C68B7),
              blurRadius: 2,
              offset: Offset(0, 1),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(_radius),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Image.asset(
                'assets/branding/app_icon.png',
                fit: BoxFit.cover,
                filterQuality: FilterQuality.high,
                errorBuilder: (_, _, _) => const ColoredBox(
                  color: ShanganColors.ink,
                  child: Center(
                    child: Text(
                      '岸',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: ShanganColors.surface,
                      ),
                    ),
                  ),
                ),
              ),
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x38FFFFFF), Color(0x00FFFFFF)],
                    stops: [0, 0.42],
                  ),
                ),
              ),
              DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(_radius),
                  border: Border.all(
                    color: const Color(0x66FFFFFF),
                    width: 0.7,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 侧栏底部竖排显示名，点按进入「我的」。
///
/// 用浅赭铭牌和描边从白底侧栏里托出来，避免字和背景糊成一块。
final class _PadAccountName extends StatelessWidget {
  const _PadAccountName({this.name, this.onTap});

  final String? name;
  final VoidCallback? onTap;

  /// 窄栏只竖排前 4 个字，避免把导航区顶上去。
  List<String> get _glyphs {
    final trimmed = name?.trim() ?? '';
    if (trimmed.isEmpty) return const [];
    return trimmed.characters.take(4).toList();
  }

  @override
  Widget build(BuildContext context) {
    final glyphs = _glyphs;
    if (glyphs.isEmpty) return const SizedBox.shrink();
    return Semantics(
      button: true,
      label: name ?? '我的',
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
          child: Container(
            width: 44,
            padding: const EdgeInsets.symmetric(vertical: 10),
            decoration: BoxDecoration(
              color: ShanganColors.ochreSoft,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: ShanganColors.ochreLine),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x14263B60),
                  blurRadius: 8,
                  offset: Offset(0, 2),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (var index = 0; index < glyphs.length; index++) ...[
                  if (index > 0) const SizedBox(height: 4),
                  Text(
                    glyphs[index],
                    style: const TextStyle(
                      fontSize: 14,
                      height: 1.1,
                      fontWeight: FontWeight.w800,
                      color: ShanganColors.ochre,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

final class _PadNavItem extends StatelessWidget {
  const _PadNavItem({
    required this.destination,
    required this.selected,
    required this.onTap,
  });

  final AdaptiveDestination destination;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? ShanganColors.blue : ShanganColors.mutedInk;
    return Semantics(
      button: true,
      selected: selected,
      label: destination.label,
      child: GestureDetector(
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 65, minWidth: 44),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: selected ? ShanganColors.blueSoft : Colors.transparent,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: selected ? ShanganColors.blue : Colors.transparent,
              ),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(destination.icon, size: 21, color: color),
                const SizedBox(height: 6),
                Text(
                  destination.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
