import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';
import 'package:shangan_ios/features/focus/presentation/mock_exam_preset_page.dart';

void main() {
  testWidgets('保存模拟考试预置后安全关闭编辑面板并刷新列表', (tester) async {
    final repository = _MockExamRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [mockExamRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: MockExamPresetPage()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('新增预置'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('mockExamPresetNameField')),
      '申论',
    );
    await tester.enterText(
      find.byKey(const Key('mockExamPresetMinutesField')),
      '180',
    );
    await tester.tap(find.byKey(const Key('saveMockExamPreset')));
    await tester.pumpAndSettle();

    expect(repository.createdName, '申论');
    expect(repository.createdSeconds, 10_800);
    expect(find.text('申论'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

/// 记录预置保存参数，并在保存后返回刷新列表所需的数据。
final class _MockExamRepository implements MockExamRepository {
  String? createdName;
  int? createdSeconds;

  @override
  Future<List<MockExamPresetData>> listPresets() async => createdName == null
      ? const []
      : [
          MockExamPresetData(
            id: 'preset-created',
            name: createdName!,
            durationSeconds: createdSeconds!,
            sortOrder: 0,
          ),
        ];

  @override
  Future<MockExamPresetData> createPreset(String name, int seconds) async {
    createdName = name;
    createdSeconds = seconds;
    return MockExamPresetData(
      id: 'preset-created',
      name: name,
      durationSeconds: seconds,
      sortOrder: 0,
    );
  }

  @override
  Future<void> deletePreset(String id) async {}

  @override
  Future<MockExamSessionData> load(String sessionId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> start(String planItemId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> submitEarly(String sessionId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> retake(String sessionId) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamPresetData> updatePreset(
    MockExamPresetData preset,
    String name,
    int seconds,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<MockExamSessionData> upload(
    String sessionId,
    String filename,
    List<int> bytes,
  ) {
    throw UnimplementedError();
  }
}
