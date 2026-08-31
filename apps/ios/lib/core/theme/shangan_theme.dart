import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// “白纸荧光笔”视觉系统的固定颜色。
///
/// 颜色来自高保真原型的 OKLCH 语义，并转换为 Flutter 可稳定渲染的 sRGB。
abstract final class ShanganColors {
  static const paper = Color(0xFFF8FAFC);
  static const surface = Color(0xFFFEFFFF);
  static const ink = Color(0xFF263B60);
  static const mutedInk = Color(0xFF66758E);
  static const rule = Color(0xFF9AAAC1);
  static const red = Color(0xFFC84235);
  static const blue = Color(0xFF2C68B7);
  static const green = Color(0xFF2D7957);
  static const ochre = Color(0xFF80672C);

  static const blueSoft = Color(0xFFEAF1FB);
  static const redSoft = Color(0xFFFBECEA);
  static const greenSoft = Color(0xFFE9F4EE);
  static const ochreSoft = Color(0xFFF8F1DE);
  static const inkSoft = Color(0xFFF0F3F7);
}

/// 上岸 V1 的 iOS-only 亮色主题。
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
      outlineVariant: Color(0xFFC7D0DD),
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
        fontSize: 32,
        height: 1.22,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.5,
      ),
      headlineMedium: base.textTheme.headlineMedium?.copyWith(
        color: ShanganColors.ink,
        fontSize: 25,
        height: 1.3,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.3,
      ),
      headlineSmall: base.textTheme.headlineSmall?.copyWith(
        color: ShanganColors.ink,
        fontSize: 22,
        height: 1.34,
        fontWeight: FontWeight.w700,
      ),
      titleLarge: base.textTheme.titleLarge?.copyWith(
        color: ShanganColors.ink,
        fontSize: 18,
        height: 1.35,
        fontWeight: FontWeight.w700,
      ),
      titleMedium: base.textTheme.titleMedium?.copyWith(
        color: ShanganColors.ink,
        fontSize: 15,
        height: 1.45,
        fontWeight: FontWeight.w600,
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
        fontSize: 12,
        height: 1.5,
      ),
      labelLarge: base.textTheme.labelLarge?.copyWith(
        fontSize: 15,
        fontWeight: FontWeight.w600,
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
        shape: Border(
          bottom: BorderSide(color: ShanganColors.rule, width: 1.5),
        ),
      ),
      cardTheme: const CardThemeData(
        margin: EdgeInsets.zero,
        elevation: 0,
        color: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(18)),
          side: BorderSide(color: ShanganColors.rule, width: 1.5),
        ),
      ),
      dividerTheme: const DividerThemeData(
        color: ShanganColors.rule,
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
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          backgroundColor: ShanganColors.ink,
          foregroundColor: ShanganColors.surface,
          disabledBackgroundColor: ShanganColors.rule,
          elevation: 4,
          shadowColor: ShanganColors.blue,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
            side: const BorderSide(color: ShanganColors.ink),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          foregroundColor: ShanganColors.ink,
          backgroundColor: ShanganColors.surface,
          side: const BorderSide(color: ShanganColors.ink, width: 1.5),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(44, 44),
          foregroundColor: ShanganColors.blue,
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size(44, 44),
          foregroundColor: ShanganColors.ink,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 70,
        elevation: 0,
        backgroundColor: ShanganColors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: ShanganColors.blueSoft,
        indicatorShape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(15),
          side: const BorderSide(color: ShanganColors.blue),
        ),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          return TextStyle(
            color: states.contains(WidgetState.selected)
                ? ShanganColors.blue
                : ShanganColors.mutedInk,
            fontSize: 11,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w600
                : FontWeight.w500,
          );
        }),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          return IconThemeData(
            color: states.contains(WidgetState.selected)
                ? ShanganColors.blue
                : ShanganColors.mutedInk,
            size: 22,
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
        linearTrackColor: ShanganColors.inkSoft,
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: ShanganColors.ink,
        contentTextStyle: TextStyle(color: ShanganColors.surface),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
