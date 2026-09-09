import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/features/profile/presentation/bark_settings_sheet.dart';
import '../../support/fake_backend.dart';

/// 用真实主题验收个人推送表单，覆盖窄屏、横屏、键盘及启用开关。
void main() {
  for (final size in [
    const Size(390, 844),
    const Size(844, 390),
    const Size(320, 568),
  ]) {
    testWidgets('Bark 表单布局与键盘 $size', (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final backend = FakeBackend()
        ..on(
          'GET',
          '/api/v1/me/bark',
          json: {
            'baseUrl': 'https://api.day.app',
            'deviceKeyConfigured': true,
            'enabled': false,
          },
        );
      if (const bool.fromEnvironment('CAPTURE_BARK_UI')) {
        await tester.runAsync(() async {
          final font = FontLoader('BarkPreview');
          font.addFont(
            File(
              '/System/Library/Fonts/STHeiti Light.ttc',
            ).readAsBytes().then((b) => ByteData.sublistView(b)),
          );
          await font.load();
        });
      }
      final boundaryKey = GlobalKey();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            shanganRepositoryProvider.overrideWithValue(
              buildRepository(backend),
            ),
          ],
          child: RepaintBoundary(
            key: boundaryKey,
            child: MaterialApp(
              theme: const bool.fromEnvironment('CAPTURE_BARK_UI')
                  ? ShanganTheme.light().copyWith(
                      textTheme: ShanganTheme.light().textTheme.apply(
                        fontFamily: 'BarkPreview',
                      ),
                    )
                  : ShanganTheme.light(),
              home: Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => showModalBottomSheet<void>(
                      context: context,
                      isScrollControlled: true,
                      builder: (_) => const BarkSettingsSheet(),
                    ),
                    child: const Text('打开'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(find.text('已启用 · 替代 Server 酱'), findsOneWidget);
      expect(tester.takeException(), isNull);
      // 按需导出本地渲染图用于人工检查，不把机器截图写入仓库。
      if (const bool.fromEnvironment('CAPTURE_BARK_UI')) {
        final boundary =
            boundaryKey.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        await tester.runAsync(() async {
          final image = await boundary.toImage();
          final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
          await File(
            '/tmp/bark-${size.width.toInt()}.png',
          ).writeAsBytes(bytes!.buffer.asUint8List());
          image.dispose();
        });
      }
      await tester.ensureVisible(find.byType(TextField).last);
      await tester.tap(find.byType(TextField).last);
      tester.view.viewInsets = const FakeViewPadding(bottom: 220);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('保存'));
      await tester.pumpAndSettle();
      expect(
        tester.getBottomRight(find.text('保存')).dy,
        lessThanOrEqualTo(size.height - 220),
      );
      expect(find.byTooltip('关闭 Bark 设置'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
