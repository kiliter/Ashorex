import 'package:flutter/widgets.dart';

/// 上岸移动端只维护手机 / Pad 两档布局，双栏是 Pad 内部的额外宽度门槛。
abstract final class AppBreakpoints {
  static const compact = 600.0;
  static const twoPane = 840.0;

  /// 不能只看宽度，否则 844x390 一类手机横屏会误切到 NavigationRail。
  static bool useNavigationRail(BoxConstraints constraints) =>
      constraints.maxWidth >= compact && constraints.maxHeight >= compact;

  /// 双栏只在内容区足够宽且高度仍属于 Pad 可用空间时开启。
  static bool useTwoPane(BoxConstraints constraints) =>
      constraints.maxWidth >= twoPane && constraints.maxHeight >= compact;
}
