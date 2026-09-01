import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/auth/auth_repository.dart';
import 'package:shangan_ios/core/storage/token_store.dart';
import 'package:shangan_ios/features/exam/data/exam_repository.dart';
import 'package:shangan_ios/features/planning/data/plan_repository.dart';
import 'package:shangan_ios/features/player/data/watch_repository.dart';
import 'package:shangan_ios/features/player/domain/learning_player_state.dart';
import 'package:shangan_ios/features/player/presentation/learning_player_controller.dart';
import 'package:shangan_ios/features/quiz/data/quiz_repository.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Fake 服务端下完成登录、作战单保存、学习和答题的核心流程', (tester) async {
    final tokens = _MemoryTokenStore();
    final auth = AuthController(
      repository: _FakeAuthRepository(),
      tokenStore: tokens,
    );
    await auth.initialize();
    expect(auth.state.status, AuthStatus.unauthenticated);
    await auth.login('learner', 'strong-password');
    expect(auth.state.status, AuthStatus.authenticated);

    final exams = _FakeExamRepository();
    await exams.saveGoal(
      ExamGoalDraft(
        name: '公务员考试',
        examDate: DateTime(2026, 12),
        targetCompletionDate: DateTime(2026, 11),
        reviewBufferDays: 30,
        courseIds: const ['course-1'],
      ),
    );
    expect((await exams.loadGoal())?.name, '公务员考试');

    final plans = _FakePlanRepository();
    final savedPlan = await plans.saveToday(
      expectedVersion: 0,
      items: const [
        BattleOrderDraft(
          existingItemId: null,
          itemType: 'VIDEO',
          title: '资料分析',
          mediaItemId: 'lesson-1',
          mockExamPresetId: null,
          plannedSeconds: 600,
          immutable: false,
          catalogOrder: 0,
        ),
      ],
    );
    expect(savedPlan.status, 'ACTIVE');

    final playerAdapter = _FakePlayerAdapter();
    final watch = _FakeWatchRepository();
    final player = LearningPlayerController(
      repository: watch,
      player: playerAdapter,
    );
    await player.initialize(lessonId: 'lesson-1', planItemId: 'video-1');
    await player.play();
    playerAdapter.emit(const Duration(milliseconds: 580000));
    await tester.pump();
    await player.sendHeartbeat();
    expect(player.state.completed, isTrue);
    plans.completeVideo();

    final quiz = _FakeQuizRepository(onPassed: plans.completeQuiz);
    final result = await quiz.submit(
      'lesson-1',
      planItemId: 'video-1',
      durationMs: 12000,
      answers: const {'question-1': 'option-b'},
    );
    expect(result.score, 100);
    expect(plans.videoCompleted, isTrue);

    expect(await plans.loadDebts(), isEmpty);

    await player.close();
    auth.dispose();
  });
}

final class _MemoryTokenStore implements TokenStore {
  TokenPair? value;

  @override
  Future<void> clear() async => value = null;

  @override
  Future<TokenPair?> read() async => value;

  @override
  Future<void> write(TokenPair tokens) async => value = tokens;
}

final class _FakeAuthRepository implements AuthRepository {
  @override
  Future<UserProfile> loadCurrentUser() async => const UserProfile(
    id: 'user-1',
    username: 'learner',
    displayName: '学习者',
    role: 'USER',
    timezone: 'Asia/Shanghai',
  );

  @override
  Future<TokenPair> login(String username, String password) async =>
      const TokenPair(accessToken: 'access', refreshToken: 'refresh');

  @override
  Future<void> logout(String refreshToken) async {}

  @override
  Future<TokenPair> refresh(String refreshToken) async =>
      const TokenPair(accessToken: 'next-access', refreshToken: 'next-refresh');
}

final class _FakeExamRepository implements ExamRepository {
  ExamGoal? goal;

  @override
  Future<ExamGoal?> loadGoal() async => goal;

  @override
  Future<ExamGoal> saveGoal(ExamGoalDraft draft) async {
    goal = ExamGoal(
      id: 'goal-1',
      name: draft.name,
      examDate: draft.examDate,
      targetCompletionDate: draft.targetCompletionDate,
      reviewBufferDays: draft.reviewBufferDays,
      timezone: 'Asia/Shanghai',
      courseIds: draft.courseIds,
    );
    return goal!;
  }
}

final class _FakePlanRepository implements PlanRepository {
  String status = 'NONE';
  int version = 0;
  bool videoWatched = false;
  bool quizPassed = false;
  final List<PlanItemData> _items = [];

  bool get videoCompleted => videoWatched && quizPassed;

  DailyPlanData get _plan => DailyPlanData(
    id: 'plan-1',
    date: DateTime(2026, 8, 30),
    status: status,
    version: version,
    items: List.unmodifiable(_items),
  );

  @override
  Future<DailyPlanData> saveToday({
    required int expectedVersion,
    required List<BattleOrderDraft> items,
  }) async {
    expect(expectedVersion, version);
    _items
      ..clear()
      ..addAll(
        items.indexed.map(
          (entry) => PlanItemData(
            id: entry.$2.existingItemId ?? 'video-${entry.$1 + 1}',
            itemType: entry.$2.itemType,
            title: entry.$2.title,
            mediaItemId: entry.$2.mediaItemId,
            mockExamPresetId: entry.$2.mockExamPresetId,
            mockExamName: null,
            plannedSeconds: entry.$2.plannedSeconds,
            completedSeconds: 0,
            status: 'PENDING',
            sortOrder: entry.$1,
            immutable: false,
          ),
        ),
      );
    status = 'ACTIVE';
    version += 1;
    return _plan;
  }

  void completeVideo() => videoWatched = true;

  void completeQuiz() => quizPassed = true;

  @override
  Future<List<LearningDebtData>> loadDebts() async => const [];

  @override
  Future<DailyPlanData> loadToday() async => _plan;
}

final class _FakePlayerAdapter implements PlayerAdapter {
  final StreamController<Duration> _positions = StreamController.broadcast();
  bool playing = false;

  @override
  Stream<Duration> get positionStream => _positions.stream;

  void emit(Duration value) => _positions.add(value);

  @override
  Future<void> dispose() async => _positions.close();

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {}

  @override
  Future<void> pause() async => playing = false;

  @override
  Future<void> play() async => playing = true;

  @override
  Future<void> seek(Duration position) async => emit(position);

  @override
  Future<void> setPlaybackSpeed(double speed) async {}
}

final class _FakeWatchRepository implements WatchRepository {
  @override
  Future<WatchSessionData> createSession(
    String lessonId, {
    String? planItemId,
  }) async => WatchSessionData(
    sessionId: 'session-1',
    ticketUri: Uri.parse('https://media.invalid/ticket'),
    trustedPositionMs: 0,
    durationMs: 600000,
    heartbeatIntervalSeconds: 3600,
  );

  @override
  Future<WatchHeartbeatData> heartbeat(
    String sessionId,
    WatchHeartbeatCommand command,
  ) async => WatchHeartbeatData(
    trustedPositionMs: command.positionMs,
    verifiedWatchMs: command.positionMs,
    seekAllowed: true,
    aliveCheckRequired: false,
    completed: command.positionMs >= 570000,
    status: command.positionMs >= 570000 ? 'COMPLETED' : 'ACTIVE',
  );

  @override
  Future<WatchHeartbeatData> confirmAliveCheck(String sessionId) async =>
      const WatchHeartbeatData(
        trustedPositionMs: 0,
        verifiedWatchMs: 0,
        seekAllowed: true,
        aliveCheckRequired: false,
        completed: false,
        status: 'ACTIVE',
      );

  @override
  Future<void> stop(String sessionId) async {}
}

final class _FakeQuizRepository implements QuizRepository {
  _FakeQuizRepository({required this.onPassed});

  final void Function() onPassed;

  @override
  Future<QuizData> loadQuiz(String lessonId) async => const QuizData(
    mediaItemId: 'lesson-1',
    questions: [
      QuizQuestionData(
        id: 'question-1',
        questionType: 'SINGLE_CHOICE',
        content: '正确答案是什么？',
        options: [
          QuizOptionData(id: 'option-a', content: 'A'),
          QuizOptionData(id: 'option-b', content: 'B'),
        ],
      ),
    ],
  );

  @override
  Future<QuizAttemptResultData> submit(
    String lessonId, {
    String? planItemId,
    required int durationMs,
    required Map<String, String> answers,
  }) async {
    onPassed();
    return const QuizAttemptResultData(
      id: 'attempt-1',
      score: 100,
      correctCount: 1,
      totalCount: 1,
      answers: [
        QuizAnswerResultData(
          questionId: 'question-1',
          selectedOptionId: 'option-b',
          correct: true,
          explanation: '解析',
        ),
      ],
    );
  }
}
