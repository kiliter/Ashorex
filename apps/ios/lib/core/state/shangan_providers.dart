import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/data/shangan_repository.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';

/// 全局数据仓库；由启动装配注入具体实例。
final shanganRepositoryProvider = Provider<ShanganRepository>((ref) {
  throw UnimplementedError('shanganRepositoryProvider 必须在启动时注入');
});

/// 由不带日期的日视图取得账号时区的今天，设备时区不参与业务日期裁决。
final serverTodayProvider = FutureProvider<DateTime>((ref) async {
  return (await ref.watch(shanganRepositoryProvider).loadDay()).date;
});

/// 首页视图档位。
enum HomeRange { day, week, month }

/// 首页当前选择的视图与日期。
final class HomeSelection {
  const HomeSelection({
    required this.range,
    required this.date,
    this.followsToday = false,
  });

  final HomeRange range;
  final DateTime date;
  final bool followsToday;

  HomeSelection copyWith({HomeRange? range, DateTime? date}) => HomeSelection(
    range: range ?? this.range,
    date: date ?? this.date,
    followsToday: followsToday,
  );
}

/// 首页选择状态；切换视图与日期都走这里。
final class HomeSelectionController extends Notifier<HomeSelection> {
  @override
  HomeSelection build() {
    // 服务端日期就绪后只更新“今天”，不覆盖用户已经明确选中的历史日期。
    // 首页 build 期间监听可能同步触发，改到微任务再写 state，避免打断播放页首帧。
    ref.listen(serverTodayProvider, (previous, next) {
      final today = next.asData?.value;
      if (today == null || !state.followsToday || state.date == today) {
        return;
      }
      Future.microtask(() {
        if (state.followsToday && state.date != today) {
          state = state.copyWith(date: today);
        }
      });
    });
    final today = ref.read(serverTodayProvider).asData?.value;
    final now = DateTime.now();
    return HomeSelection(
      range: HomeRange.day,
      // 请求始终不传此占位日期，仅在服务器尚未返回时用于绘制日期头。
      date: today ?? DateTime(now.year, now.month, now.day),
      followsToday: true,
    );
  }

  void selectRange(HomeRange range) => state = state.copyWith(range: range);

  void selectDate(DateTime date) => state = HomeSelection(
    range: HomeRange.day,
    date: DateTime(date.year, date.month, date.day),
  );

  /// 月视图里点日历格：只换日期，仍留在月视图（原型 1-6）。
  void selectDateKeepingRange(DateTime date) => state = HomeSelection(
    range: state.range,
    date: DateTime(date.year, date.month, date.day),
  );

  void goToday() {
    state = HomeSelection(
      range: HomeRange.day,
      date: ref.read(serverTodayProvider).asData?.value ?? state.date,
      followsToday: true,
    );
    ref.invalidate(serverTodayProvider);
  }
}

final homeSelectionProvider =
    NotifierProvider<HomeSelectionController, HomeSelection>(
      HomeSelectionController.new,
    );

/// 目标看板数据。
final goalsProvider = FutureProvider<List<ExamGoal>>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadGoals();
});

/// 待回应催办；首页铃铛红点与红色待回应条依赖它（原型 7-2）。
final pendingNagProvider = FutureProvider<PendingNag?>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadPendingNag();
});

/// 当前选中日期的日视图。
final dayViewProvider = FutureProvider<DayView>((ref) async {
  final selection = ref.watch(homeSelectionProvider);
  return ref
      .watch(shanganRepositoryProvider)
      .loadDay(date: selection.followsToday ? null : selection.date);
});

/// 周视图；以选中日期所在周的周一为起点。
final weekViewProvider = FutureProvider<RangeView>((ref) async {
  final selection = ref.watch(homeSelectionProvider);
  final anchor = selection.followsToday
      ? await ref.watch(serverTodayProvider.future)
      : selection.date;
  final monday = anchor.subtract(Duration(days: anchor.weekday - 1));
  return ref.watch(shanganRepositoryProvider).loadWeek(weekStart: monday);
});

/// 月视图。
final monthViewProvider = FutureProvider<RangeView>((ref) async {
  final selection = ref.watch(homeSelectionProvider);
  final anchor = selection.followsToday
      ? await ref.watch(serverTodayProvider.future)
      : selection.date;
  return ref.watch(shanganRepositoryProvider).loadMonth(month: anchor);
});

/// 未完成汇总。
final pendingSummaryProvider = FutureProvider<PendingSummary>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadPendingSummary();
});

/// 课程库筛选维度。
final catalogFacetsProvider = FutureProvider<CatalogFacets>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadFacets();
});

/// 课程库筛选条件。
/// 不可变筛选条件：同类标签取并集，三个分类之间取交集。
final class CatalogFilter {
  const CatalogFilter({
    this.genre,
    this.tag,
    this.person,
    this.genres = const {},
    this.tags = const {},
    this.people = const {},
    this.year,
    this.query,
    this.groupByPerson = false,
  });
  // 单值入口保留给既有调用方；展示和匹配统一使用完整选集。
  final String? genre, tag, person;
  final Set<String> genres, tags, people;
  final int? year;
  final String? query;
  final bool groupByPerson;
  Set<String> get selectedGenres => {...genres, ?genre};
  Set<String> get selectedTags => {...tags, ?tag};
  Set<String> get selectedPeople => {...people, ?person};
  bool get hasTags =>
      selectedGenres.isNotEmpty ||
      selectedTags.isNotEmpty ||
      selectedPeople.isNotEmpty;

  /// 两个课程入口共用实际匹配规则，不依赖旧服务端理解多选参数。
  bool matches(CourseSummary course) {
    bool overlaps(Set<String> selected, List<String> values) =>
        selected.isEmpty || values.any(selected.contains);
    final keyword = (query ?? '').trim().toLowerCase();
    return overlaps(selectedGenres, course.genres) &&
        overlaps(selectedTags, course.tags) &&
        // 人物文件夹的兜底分组对应空元数据，不向 Emby 写入虚构人物。
        overlaps(
          selectedPeople,
          course.people.isEmpty ? ['未标注人物'] : course.people,
        ) &&
        (year == null || course.productionYear == year) &&
        (keyword.isEmpty ||
            course.title.toLowerCase().contains(keyword) ||
            course.people.any((p) => p.toLowerCase().contains(keyword)));
  }

  /// 替换某类选集时清掉兼容单值，避免删除后旧条件残留。
  CatalogFilter copyWith({
    String? genre,
    String? tag,
    String? person,
    Set<String>? genres,
    Set<String>? tags,
    Set<String>? people,
    int? year,
    String? query,
    bool? groupByPerson,
    bool clearGenre = false,
    bool clearTag = false,
    bool clearPerson = false,
  }) => CatalogFilter(
    genre: clearGenre || genres != null ? null : genre ?? this.genre,
    tag: clearTag || tags != null ? null : tag ?? this.tag,
    person: clearPerson || people != null ? null : person ?? this.person,
    genres: Set.unmodifiable(clearGenre ? <String>{} : genres ?? this.genres),
    tags: Set.unmodifiable(clearTag ? <String>{} : tags ?? this.tags),
    people: Set.unmodifiable(clearPerson ? <String>{} : people ?? this.people),
    year: year ?? this.year,
    query: query ?? this.query,
    groupByPerson: groupByPerson ?? this.groupByPerson,
  );
}

final class CatalogFilterController extends Notifier<CatalogFilter> {
  @override
  CatalogFilter build() => const CatalogFilter();

  /// 面板应用一次替换条件，避免逐字段更新触发多次加载。
  void apply(CatalogFilter filter) => state = filter;

  void toggleGenre(String genre) {
    state = state.genre == genre
        ? state.copyWith(clearGenre: true)
        : state.copyWith(genre: genre);
  }

  void toggleTag(String tag) {
    state = state.tag == tag
        ? state.copyWith(clearTag: true)
        : state.copyWith(tag: tag);
  }

  void togglePerson(String person) {
    state = state.person == person
        ? state.copyWith(clearPerson: true)
        : state.copyWith(person: person);
  }

  void setGroupByPerson(bool value) =>
      state = state.copyWith(groupByPerson: value);

  void setQuery(String value) => state = state.copyWith(query: value);
}

final catalogFilterProvider =
    NotifierProvider<CatalogFilterController, CatalogFilter>(
      CatalogFilterController.new,
    );

/// 完整课程快照与筛选状态分离，应用条件不重新下载或闪回旧结果。
final libraryCoursesSnapshotProvider = FutureProvider<List<CourseSummary>>(
  (ref) => ref.watch(shanganRepositoryProvider).loadCourses(),
);

/// 同类并集、跨类交集作用于完整快照，学习页与今日选课口径一致。
final coursesProvider = FutureProvider<List<CourseSummary>>((ref) async {
  final filter = ref.watch(catalogFilterProvider);
  final courses = await ref.watch(libraryCoursesSnapshotProvider.future);
  return courses.where(filter.matches).toList(growable: false);
});

/// 添加待办专用课程快照，不依赖课程库的筛选条件。
/// 面板关闭后释放，下次打开重新加载；搜索、流派和标签仅在面板内筛选。
final todoPickerCoursesProvider =
    FutureProvider.autoDispose<List<CourseSummary>>((ref) async {
      return ref.watch(shanganRepositoryProvider).loadCourses();
    });

/// 课程详情。
final courseDetailProvider = FutureProvider.family<CourseDetail, String>((
  ref,
  courseId,
) async {
  return ref.watch(shanganRepositoryProvider).loadCourse(courseId);
});

/// 数据 Tab 的范围选择。
final statsRangeProvider = NotifierProvider<StatsRangeController, HomeRange>(
  StatsRangeController.new,
);

final class StatsRangeController extends Notifier<HomeRange> {
  @override
  HomeRange build() => HomeRange.day;

  void select(HomeRange range) => state = range;
}

/// 数据 Tab 的锚定日期；对应原型 5-1 右上角日历按钮。
final class StatsDateController extends Notifier<DateTime> {
  bool followsToday = true;

  @override
  DateTime build() {
    followsToday = true;
    ref.listen(serverTodayProvider, (previous, next) {
      final today = next.asData?.value;
      if (today != null && followsToday) state = today;
    });
    final now = DateTime.now();
    return ref.read(serverTodayProvider).asData?.value ??
        DateTime(now.year, now.month, now.day);
  }

  /// 显式选择才把日期传给统计接口，默认今天由服务端按账号时区确定。
  void select(DateTime date) {
    followsToday = false;
    state = DateTime(date.year, date.month, date.day);
  }
}

final statsDateProvider = NotifierProvider<StatsDateController, DateTime>(
  StatsDateController.new,
);

/// 统计数据。
final statsProvider = FutureProvider<StatsView>((ref) async {
  final range = ref.watch(statsRangeProvider);
  final date = ref.watch(statsDateProvider);
  final wire = switch (range) {
    HomeRange.day => 'DAY',
    HomeRange.week => 'WEEK',
    HomeRange.month => 'MONTH',
  };
  return ref
      .watch(shanganRepositoryProvider)
      .loadStats(
        range: wire,
        date: ref.read(statsDateProvider.notifier).followsToday ? null : date,
      );
});

/// 我的页设置（含只读催办策略）。
final meSettingsProvider = FutureProvider<MeSettings>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadMe();
});

/// 督学端学员总览。
final learnersProvider = FutureProvider<List<LearnerOverview>>((ref) async {
  return ref.watch(shanganRepositoryProvider).loadLearners();
});

/// 督学端学员详情。
final learnerDetailProvider = FutureProvider.family<LearnerDetail, String>((
  ref,
  learnerId,
) async {
  return ref.watch(shanganRepositoryProvider).loadLearner(learnerId);
});

/// 督学端时间线。
final supervisorFeedProvider = FutureProvider<List<SupervisorFeedItem>>((
  ref,
) async {
  return ref.watch(shanganRepositoryProvider).loadFeed();
});

/// 督学端学员报告。
/// 督学端报告区间（原型 9-6 的日 / 周 / 月分段）。
final class SupervisorReportRangeController extends Notifier<HomeRange> {
  @override
  HomeRange build() => HomeRange.week;

  void select(HomeRange range) => state = range;
}

final supervisorReportRangeProvider =
    NotifierProvider<SupervisorReportRangeController, HomeRange>(
      SupervisorReportRangeController.new,
    );

final supervisorReportProvider = FutureProvider<List<LearnerReport>>((
  ref,
) async {
  final range = ref.watch(supervisorReportRangeProvider);
  final wire = switch (range) {
    HomeRange.day => 'DAY',
    HomeRange.week => 'WEEK',
    HomeRange.month => 'MONTH',
  };
  return ref.watch(shanganRepositoryProvider).loadReports(range: wire);
});

/// 本机心跳状态快照，供「我的」页顶部的在线卡与首页离线条展示（原型 6-1、7-2）。
final class HeartbeatStatus {
  const HeartbeatStatus({
    required this.online,
    required this.queuedEvents,
    this.lastReportedAt,
  });

  final bool online;
  final int queuedEvents;
  final DateTime? lastReportedAt;
}

final class HeartbeatStatusController extends Notifier<HeartbeatStatus> {
  @override
  HeartbeatStatus build() =>
      const HeartbeatStatus(online: true, queuedEvents: 0);

  void update({
    required bool online,
    required int queuedEvents,
    DateTime? lastReportedAt,
  }) {
    state = HeartbeatStatus(
      online: online,
      queuedEvents: queuedEvents,
      lastReportedAt: lastReportedAt ?? state.lastReportedAt,
    );
  }
}

final heartbeatStatusProvider =
    NotifierProvider<HeartbeatStatusController, HeartbeatStatus>(
      HeartbeatStatusController.new,
    );
