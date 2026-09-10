import 'package:flutter/widgets.dart';

/// 上岸移动端只维护手机 / Pad 两档布局。
///
/// 断点与 `docs/prototypes/ashorex-pad-v2.html` 的 `adapt()` 对齐：
/// 侧栏看整屏宽高；Pad 工作台在宽 ≥768 且高 ≥500 时开启。
abstract final class AppBreakpoints {
  static const compact = 600.0;
  static const pad = 768.0;
  static const padMinHeight = 500.0;

  /// 播放页宽屏门槛；课程库 Pad 模式改用覆盖抽屉，不再走双栏。
  static const twoPane = 840.0;

  /// 不能只看宽度，否则 844x390 一类手机横屏会误切到 NavigationRail。
  static bool useNavigationRail(BoxConstraints constraints) =>
      constraints.maxWidth >= compact && constraints.maxHeight >= compact;

  /// Pad 工作台：首页双栏、课程墙 + 课时抽屉、数据多栏。
  ///
  /// 必须用整屏尺寸判断，不能用扣除侧栏后的内容宽，否则 820 竖屏会被误判成手机。
  static bool usePadLayout(Size size) =>
      size.width >= pad && size.height >= padMinHeight;

  static bool usePadLayoutOf(BuildContext context) =>
      usePadLayout(MediaQuery.sizeOf(context));

  /// 旧双栏仅留给未进入 Pad 工作台、且内容区仍足够宽的中等宽度。
  static bool useTwoPane(BoxConstraints constraints) =>
      constraints.maxWidth >= twoPane && constraints.maxHeight >= compact;
}
