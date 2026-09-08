import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// “白纸荧光笔”视觉系统的固定颜色。
///
/// 全部取自 `docs/prototypes/shangan-v2-prototype.html` 的 `:root` CSS 变量，
/// 命名与原型一一对应；新增颜色必须先改原型再改这里。
abstract final class ShanganColors {
  static const paper = Color(0xFFF8FAFC);
  static const surface = Color(0xFFFEFFFF);
  static const ink = Color(0xFF263B60);
  static const mutedInk = Color(0xFF66758E);

  /// 卡片主描边（--rule）。
  static const rule = Color(0xFF9AAAC1);

  /// 卡片内部细分隔线与次级边框（--hair）；比 rule 浅得多，不能混用。
  static const hair = Color(0xFFDCE3EC);

  /// 进度专用完整轨道，在浅色卡片上仍清晰可见，低进度也能辨认总长度。
  static const progressTrack = Color(0xFFD5DFEC);
  static const progressOutline = Color(0xFFBCCADD);

  static const red = Color(0xFFC84235);
  static const blue = Color(0xFF2C68B7);

  /// 课程名专用：比主色略深的钴蓝，统一、收敛，仍是蓝色。
  static const course = Color(0xFF1F5EC8);
  static const green = Color(0xFF2D7957);
  static const ochre = Color(0xFF80672C);

  static const blueSoft = Color(0xFFEAF1FB);
  static const redSoft = Color(0xFFFBECEA);
  static const greenSoft = Color(0xFFE9F4EE);
  static const ochreSoft = Color(0xFFF8F1DE);
  static const inkSoft = Color(0xFFF0F3F7);

  /// 各语义色对应的浅色描边，用于 badge、act 按钮与高亮卡片。
  static const blueLine = Color(0xFFB9CDEB);
  static const redLine = Color(0xFFEFC7C1);
  static const greenLine = Color(0xFFBCDCC9);
  static const ochreLine = Color(0xFFE2D3A8);
  static const inkLine = Color(0xFFCFD8E4);

  /// 输入框占位文字（原型 `.field .ph`）。
  static const placeholder = Color(0xFFA7B2C4);
}

/// 原型中的几何令牌：卡片 18、输入框 12、胶囊全圆角、描边 1.5。
abstract final class ShanganRadius {
  static const card = 18.0;
  static const field = 12.0;
  static const chip = 999.0;
  static const borderWidth = 1.5;
}

/// 上岸 V2 亮色主题；所有取值以高保真原型为准。
abstract final class ShanganTheme {
  static ThemeData light() {
    const scheme = ColorScheme.light(
      primary: ShanganColors.ink,
      onPrimary: ShanganColors.surface,
      secondary: ShanganColors.blue,
      onSecondary: ShanganColors.surface,
      error: ShanganColors.red,
      onError: ShanganColors.surface,
      surface: ShanganColors.surface,
      onSurface: ShanganColors.ink,
      outline: ShanganColors.rule,
      outlineVariant: ShanganColors.hair,
      surfaceContainerHighest: ShanganColors.inkSoft,
    );
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: ShanganColors.paper,
      fontFamilyFallback: const ['PingFang SC', 'SF Pro Text'],
      materialTapTargetSize: MaterialTapTargetSize.padded,
      visualDensity: VisualDensity.standard,
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
        },
      ),
    );

    final text = base.textTheme.copyWith(
      displaySmall: base.textTheme.displaySmall?.copyWith(
        color: ShanganColors.ink,
        fontSize: 31,
        height: 1.1,
        fontWeight: FontWeight.w800,
        letterSpacing: -1,
      ),
      headlineMedium: base.textTheme.headlineMedium?.copyWith(
        color: ShanganColors.ink,
        fontSize: 26,
        height: 1.15,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.8,
      ),
      headlineSmall: base.textTheme.headlineSmall?.copyWith(
        color: ShanganColors.ink,
        fontSize: 19,
        height: 1.3,
        fontWeight: FontWeight.w800,
      ),
      titleLarge: base.textTheme.titleLarge?.copyWith(
        color: ShanganColors.ink,
        fontSize: 17,
        height: 1.32,
        fontWeight: FontWeight.w800,
      ),
      titleMedium: base.textTheme.titleMedium?.copyWith(
        color: ShanganColors.ink,
        fontSize: 15,
        height: 1.35,
        fontWeight: FontWeight.w700,
      ),
      bodyLarge: base.textTheme.bodyLarge?.copyWith(
        color: ShanganColors.ink,
        fontSize: 15,
        height: 1.65,
      ),
      bodyMedium: base.textTheme.bodyMedium?.copyWith(
        color: ShanganColors.ink,
        fontSize: 14,
        height: 1.6,
      ),
      bodySmall: base.textTheme.bodySmall?.copyWith(
        color: ShanganColors.mutedInk,
        fontSize: 11.5,
        height: 1.5,
      ),
      labelLarge: base.textTheme.labelLarge?.copyWith(
        fontSize: 15,
        fontWeight: FontWeight.w700,
      ),
    );

    const fieldBorder = OutlineInputBorder(
      borderRadius: BorderRadius.all(Radius.circular(12)),
      borderSide: BorderSide(color: ShanganColors.rule, width: 1.5),
    );
    return base.copyWith(
      textTheme: text,
      primaryTextTheme: text,
      appBarTheme: const AppBarTheme(
        // 原型移动端没有 Material AppBar，页头由 home-head 承担；
        // 二级页保留一个无描边、无阴影的极简顶栏。
        centerTitle: true,
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: ShanganColors.paper,
        foregroundColor: ShanganColors.ink,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: TextStyle(
          color: ShanganColors.ink,
          fontSize: 17,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: const CardThemeData(
        margin: EdgeInsets.zero,
        elevation: 0,
        color: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(ShanganRadius.card)),
          side: BorderSide(
            color: ShanganColors.rule,
            width: ShanganRadius.borderWidth,
          ),
        ),
      ),
      dividerTheme: const DividerThemeData(
        // 原型 `.hr` 是 hair 1px；`.hr.strong` 才用 rule，需要时在局部覆盖。
        color: ShanganColors.hair,
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: ShanganColors.surface,
        border: fieldBorder,
        enabledBorder: fieldBorder,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(12)),
          borderSide: BorderSide(color: ShanganColors.blue, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(12)),
          borderSide: BorderSide(color: ShanganColors.red, width: 1.5),
        ),
        contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        labelStyle: TextStyle(color: ShanganColors.mutedInk),
        helperStyle: TextStyle(color: ShanganColors.mutedInk),
        hintStyle: TextStyle(color: ShanganColors.placeholder, fontSize: 15),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          backgroundColor: ShanganColors.ink,
          foregroundColor: ShanganColors.surface,
          disabledBackgroundColor: ShanganColors.rule,
          // 原型 `.btn` 是纯扁平描边按钮，没有任何投影。
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
            side: const BorderSide(color: ShanganColors.ink),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          foregroundColor: ShanganColors.ink,
          backgroundColor: ShanganColors.surface,
          side: const BorderSide(
            color: ShanganColors.ink,
            width: ShanganRadius.borderWidth,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(44, 44),
          foregroundColor: ShanganColors.blue,
          // 原型 `.sec-title a` 是 12.5px / 700。
          textStyle: const TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size(44, 44),
          foregroundColor: ShanganColors.ink,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        // 原型 `.tabbar` 是 76px（padding 8 / 14）。
        height: 76,
        elevation: 0,
        backgroundColor: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: ShanganColors.blueSoft,
        indicatorShape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: const BorderSide(color: ShanganColors.blue),
        ),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          return TextStyle(
            color: states.contains(WidgetState.selected)
                ? ShanganColors.blue
                : ShanganColors.mutedInk,
            fontSize: 10.5,
            fontWeight: FontWeight.w600,
          );
        }),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          return IconThemeData(
            color: states.contains(WidgetState.selected)
                ? ShanganColors.blue
                : ShanganColors.mutedInk,
            size: 21,
          );
        }),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        dragHandleColor: ShanganColors.rule,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          side: BorderSide(color: ShanganColors.blue, width: 1.5),
        ),
      ),
      dialogTheme: const DialogThemeData(
        backgroundColor: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(20)),
          side: BorderSide(color: ShanganColors.blue, width: 2),
        ),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: ShanganColors.blue,
        linearTrackColor: ShanganColors.progressTrack,
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: ShanganColors.ink,
        contentTextStyle: TextStyle(color: ShanganColors.surface),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
