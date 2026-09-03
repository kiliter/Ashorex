import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/companion/presentation/companion_fullscreen.dart';
import 'package:shangan_ios/features/companion/presentation/pet_sprite.dart';
import 'package:shangan_ios/features/companion/presentation/study_companion_overlay.dart';

void main() {
  test('拖动主轴：往上跳、往下看、左右跑', () {
    expect(petPoseForDrag(const Offset(0, -12)), PetPose.jumping);
    expect(petPoseForDrag(const Offset(0, 12)), PetPose.lookDown);
    expect(petPoseForDrag(const Offset(-12, -4)), PetPose.runLeft);
    expect(petPoseForDrag(const Offset(12, 4)), PetPose.runRight);
  });

  setUp(() {
    videoFullscreenListenable.value = false;
  });

  tearDown(() {
    videoFullscreenListenable.value = false;
  });

  testWidgets('未登录不出现学习伙伴，登录后可拖动且文案不含 AI', (tester) async {
    final auth = AuthController(
      repository: _AuthRepository(),
      tokenStore: _MemoryTokenStore(),
    );
    addTearDown(auth.dispose);
    await auth.initialize();

    await tester.pumpWidget(_host(auth));
    await tester.pump();
    expect(find.byKey(const Key('studyCompanion')), findsNothing);

    await auth.login('alice', 'password');
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('studyCompanion')), findsOneWidget);
    expect(find.text('AI'), findsNothing);
    expect(
      tester.getSemantics(find.byKey(const Key('studyCompanion'))).label,
      '学习伙伴 毛线团团',
    );

    final before = tester.getCenter(find.byKey(const Key('studyCompanion')));
    await tester.drag(
      find.byKey(const Key('studyCompanion')),
      const Offset(-140, -90),
    );
    await tester.pumpAndSettle();
    final after = tester.getCenter(find.byKey(const Key('studyCompanion')));
    expect(after.dx, lessThan(before.dx - 80));
    expect(after.dy, lessThan(before.dy - 40));
  });

  testWidgets('往上拖播跳跃，往下拖朝下看', (tester) async {
    final auth = AuthController(
      repository: _AuthRepository(),
      tokenStore: _MemoryTokenStore(),
    );
    addTearDown(auth.dispose);
    await auth.login('alice', 'password');
    await tester.pumpWidget(_host(auth));
    await tester.pump();
    await tester.pump();

    final center = tester.getCenter(find.byKey(const Key('studyCompanion')));
    final gesture = await tester.startGesture(center);
    await gesture.moveBy(const Offset(0, -80));
    await tester.pump();
    expect(
      tester.widget<PetSprite>(find.byType(PetSprite)).pose,
      PetPose.jumping,
    );

    await gesture.moveBy(const Offset(0, 160));
    await tester.pump();
    expect(
      tester.widget<PetSprite>(find.byType(PetSprite)).pose,
      PetPose.lookDown,
    );
    await gesture.up();
    await tester.pumpAndSettle();
    expect(tester.widget<PetSprite>(find.byType(PetSprite)).pose, PetPose.idle);
  });

  testWidgets('全屏播放时学习伙伴贴到最近边缘，只留下探出宽度', (tester) async {
    final auth = AuthController(
      repository: _AuthRepository(),
      tokenStore: _MemoryTokenStore(),
    );
    addTearDown(auth.dispose);
    await auth.login('alice', 'password');

    await tester.pumpWidget(_host(auth));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('studyCompanion')), findsOneWidget);

    videoFullscreenListenable.value = true;
    await tester.pumpAndSettle();

    final box = tester.getRect(find.byKey(const Key('studyCompanion')));
    expect(box.width, 44);
    final screen = tester.getSize(find.byType(MaterialApp));
    expect(
      box.left <= 1 || (screen.width - box.right).abs() <= 1,
      isTrue,
      reason: '贴边后应贴在左或右边缘',
    );
  });
}

Widget _host(AuthController auth) {
  return MaterialApp(
    home: StudyCompanionHost(
      authController: auth,
      child: const Scaffold(body: Text('home')),
    ),
  );
}

final class _MemoryTokenStore implements TokenStore {
  TokenPair? _tokens;

  @override
  Future<void> clear() async => _tokens = null;

  @override
  Future<TokenPair?> read() async => _tokens;

  @override
  Future<void> write(TokenPair tokens) async => _tokens = tokens;
}

final class _AuthRepository implements AuthRepository {
  @override
  Future<TokenPair> login(String username, String password) async {
    return const TokenPair(accessToken: 'access', refreshToken: 'refresh');
  }

  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'alice',
    displayName: 'Alice',
    role: 'USER',
    timezone: 'Asia/Shanghai',
  );

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) {
    throw UnimplementedError();
  }
}
