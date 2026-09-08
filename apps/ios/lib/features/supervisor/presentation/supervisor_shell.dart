import 'dart:async';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:flutter/material.dart';
import 'package:shangan_ios/features/profile/presentation/profile_page.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 督学端外壳：学员 / 提醒 / 报告 / 退出登录（原型 9-2 ~ 9-6）。
///
/// 顶部固定赭色标识条以区分学习端；督学端只读学员数据 + 发起催办（见 ADR-0028）。
final class SupervisorShell extends ConsumerStatefulWidget {
  const SupervisorShell({super.key});

  @override
  ConsumerState<SupervisorShell> createState() => _SupervisorShellState();
}

class _SupervisorShellState extends ConsumerState<SupervisorShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        children: [
          Container(height: 4, color: ShanganColors.ochre),
          Expanded(
            child: SafeArea(
              // 督学页没有 AppBar，必须保留系统顶部安全区，避免标题覆盖刘海和状态栏。
              top: true,
              child: switch (_index) {
                1 => const _SupervisorFeedTab(),
                2 => const _SupervisorReportTab(),
                3 => const ProfilePage(supervisor: true),
                _ => const _SupervisorLearnersTab(),
              },
            ),
          ),
        ],
      ),
      bottomNavigationBar: DecoratedBox(
        // 原型 `.tabbar{border-top:1.5px solid var(--ink)}`（9-2 ~ 9-6 底部 Tab）。
        decoration: const BoxDecoration(
          border: Border(
            top: BorderSide(
              color: ShanganColors.ink,
              width: ShanganRadius.borderWidth,
            ),
          ),
        ),
        child: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (index) => setState(() => _index = index),
          // 退出后才能重新选择身份，不提供端内切换。
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.person_outline),
              selectedIcon: Icon(Icons.person_outline),
              label: '学员',
            ),
            NavigationDestination(
              icon: Icon(Icons.notifications_none),
              selectedIcon: Icon(Icons.notifications_none),
              label: '提醒',
            ),
            NavigationDestination(
              icon: Icon(Icons.bar_chart_outlined),
              selectedIcon: Icon(Icons.bar_chart_outlined),
              label: '报告',
            ),
            NavigationDestination(
              icon: Icon(Icons.settings_outlined),
              label: '我的',
            ),
          ],
        ),
      ),
    );
  }
}

/// 督学端页头：赭色 kicker + 大标题 + 右侧按钮（原型 9-2 / 9-5 / 9-6）。
final class _SupervisorHeader extends StatelessWidget {
  const _SupervisorHeader({required this.kicker, required this.title});

  final String kicker;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                kicker,
                style: const TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.6,
                  color: ShanganColors.ochre,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 26,
                  height: 1.1,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.8,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// 9-2 学员总览。
final class _SupervisorLearnersTab extends ConsumerStatefulWidget {
  const _SupervisorLearnersTab();

  @override
  ConsumerState<_SupervisorLearnersTab> createState() =>
      _SupervisorLearnersTabState();
}

class _SupervisorLearnersTabState
    extends ConsumerState<_SupervisorLearnersTab> {
  /// 原型 9-2「按完成率」排序开关。
  bool _sortByCompletion = false;

  @override
  Widget build(BuildContext context) {
    return _ActivityRefresh(
      onRefresh: () {
        if (!ref.read(learnersProvider).isLoading) {
          ref.invalidate(learnersProvider);
        }
      },
      child: _buildLearners(context),
    );
  }

  Widget _buildLearners(BuildContext context) {
    final learners = ref.watch(learnersProvider);
    final me = ref.watch(meSettingsProvider).value;
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(learnersProvider),
      child: learners.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ListView(
          padding: const EdgeInsets.all(18),
          children: [Text('学员列表加载失败：$error')],
        ),
        data: (list) {
          final ordered = [...list];
          if (_sortByCompletion) {
            ordered.sort((a, b) => _rate(a).compareTo(_rate(b)));
          }
          final needNag = ordered
              .where((item) => item.alerts.isNotEmpty)
              .toList(growable: false);
          return ListView(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
            children: [
              _SupervisorHeader(
                kicker: '督学端 · ${me?.profile.displayName ?? ''}',
                title: '今日学员',
              ),
              const SizedBox(height: 12),
              StatStrip(
                items: [
                  StatStripItem(
                    value: '${ordered.length}',
                    label: '学员',
                    highlighted: true,
                    highlightTone: ShanganBadgeTone.ochre,
                  ),
                  StatStripItem(
                    value:
                        '${ordered.fold<int>(0, (sum, item) => sum + item.todayDone)}'
                        '/${ordered.fold<int>(0, (sum, item) => sum + item.todayTotal)}',
                    label: '今日完成',
                  ),
                  StatStripItem(
                    value: formatDurationCompact(
                      ordered.fold<int>(
                        0,
                        (sum, item) =>
                            sum + item.todayWatchedMs + item.todayFocusedMs,
                      ),
                    ),
                    label: '合计时长',
                  ),
                  StatStripItem(value: '${needNag.length}', label: '需督学'),
                ],
              ),
              if (needNag.isNotEmpty) ...[
                const SizedBox(height: 12),
                _AlertStrip(learner: needNag.first),
              ],
              SectionTitle(
                title: '学员',
                count: '${ordered.length}',
                trailing: TextButton(
                  onPressed: () =>
                      setState(() => _sortByCompletion = !_sortByCompletion),
                  child: Text(_sortByCompletion ? '按绑定顺序' : '按完成率'),
                ),
              ),
              if (ordered.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 40),
                  child: Center(
                    child: Text(
                      '还没有绑定学员，请让管理员在后台建立督学关系',
                      style: TextStyle(color: ShanganColors.mutedInk),
                    ),
                  ),
                ),
              for (final learner in ordered)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _LearnerCard(learner: learner),
                ),
            ],
          );
        },
      ),
    );
  }

  double _rate(LearnerOverview learner) =>
      learner.todayTotal == 0 ? 0 : learner.todayDone / learner.todayTotal;
}

/// 原型 9-2 的红色异常条：点一下直接发起督学。
final class _AlertStrip extends ConsumerWidget {
  const _AlertStrip({required this.learner});

  final LearnerOverview learner;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reasons = <String>[
      if (learner.zeroDone) '今日零完成',
      if (learner.presenceState == PresenceState.offline)
        '离线 ${_hours(learner.idleMinutes)}',
      if (learner.unansweredNags > 0) '催办未回应 ${learner.unansweredNags}',
    ];
    return GestureDetector(
      onTap: learner.canNag
          ? () async {
              final sent = await _NagSheet.show(context, learner);
              if (sent) {
                ref.invalidate(learnersProvider);
                ref.invalidate(supervisorFeedProvider);
              }
            }
          : null,
      child: ShanganCard(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
        borderColor: ShanganColors.redLine,
        backgroundColor: ShanganColors.redSoft,
        child: Row(
          children: [
            const Icon(Icons.error_outline, size: 18, color: ShanganColors.red),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${learner.displayName} ${reasons.join('，')}',
                maxLines: 2,
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: ShanganColors.red,
                ),
              ),
            ),
            const SizedBox(width: 8),
            const ShanganBadge(label: '一键督学', tone: ShanganBadgeTone.red),
          ],
        ),
      ),
    );
  }

  String _hours(int minutes) {
    if (minutes < 60) return '$minutes 分钟';
    return '${minutes ~/ 60} 小时';
  }
}

final class _LearnerCard extends ConsumerWidget {
  const _LearnerCard({required this.learner});

  final LearnerOverview learner;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final needAttention = learner.alerts.isNotEmpty;
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      borderColor: needAttention ? ShanganColors.redLine : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ShanganAvatar(
                letter: learner.username.substring(0, 1).toUpperCase(),
                index: needAttention ? 3 : 0,
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            learner.displayName,
                            style: const TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                        ShanganDotState(
                          state: learner.presenceState,
                          label: _presenceLabel(learner),
                        ),
                      ],
                    ),
                    _CurrentActivityText(activity: learner.activity),
                    const SizedBox(height: 8),
                    TargetProgressBar(
                      value: learner.todayTotal == 0
                          ? 0
                          : learner.todayDone / learner.todayTotal,
                      color: learner.zeroDone
                          ? ShanganColors.red
                          : ShanganColors.blue,
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Text(
                          '今日 ${learner.todayDone} / ${learner.todayTotal} 完成',
                          style: const TextStyle(
                            fontSize: 11,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                        const Spacer(),
                        Text(
                          '${formatDurationCompact(learner.todayWatchedMs + learner.todayFocusedMs)} 学习',
                          style: const TextStyle(
                            fontSize: 11,
                            color: ShanganColors.mutedInk,
                          ),
                        ),
                      ],
                    ),
                    if (learner.alerts.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 5,
                        runSpacing: 4,
                        children: [
                          for (final alert in learner.alerts)
                            ShanganBadge(
                              label: _alertLabel(alert, learner),
                              tone: alert == 'NAG_UNANSWERED'
                                  ? ShanganBadgeTone.ink
                                  : ShanganBadgeTone.red,
                            ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Divider(height: 1, color: ShanganColors.hair),
          ),
          Row(
            children: [
              Expanded(
                // 原型按钮宽度为 1:1.2，整数 flex 对应 5:6，不能误写成 1:6。
                flex: 5,
                child: OutlinedButton(
                  style: _smallOutlined,
                  onPressed: () =>
                      context.push('/supervisor/${learner.userId}'),
                  child: const Text('查看详情'),
                ),
              ),
              if (learner.canNag) ...[
                const SizedBox(width: 9),
                Expanded(
                  flex: 6,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(44),
                      backgroundColor: needAttention
                          ? ShanganColors.red
                          : ShanganColors.ochre,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                        side: BorderSide(
                          color: needAttention
                              ? ShanganColors.red
                              : ShanganColors.ochre,
                        ),
                      ),
                      textStyle: const TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    onPressed: () => _openNagSheet(context, ref, learner),
                    icon: const Icon(Icons.notifications_none, size: 18),
                    label: const Text('一键督学'),
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  static final _smallOutlined = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(44),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  String _presenceLabel(LearnerOverview learner) {
    if (learner.presenceState == PresenceState.online) return '在线';
    final minutes = learner.idleMinutes;
    final suffix = minutes < 60 ? '$minutes 分钟' : '${minutes ~/ 60} 小时';
    return '${learner.presenceState.label} $suffix';
  }

  String _alertLabel(String alert, LearnerOverview learner) => switch (alert) {
    'ZERO_DONE' => '零完成',
    'OFFLINE' => '离线',
    'FREQUENT_DELETION' => '本月删除 ${learner.monthlyDeletions} 次',
    'NAG_UNANSWERED' => '催办未回应 ${learner.unansweredNags}',
    _ => alert,
  };

  Future<void> _openNagSheet(
    BuildContext context,
    WidgetRef ref,
    LearnerOverview learner,
  ) async {
    final sent = await _NagSheet.show(context, learner);
    if (sent) {
      ref.invalidate(learnersProvider);
      ref.invalidate(supervisorFeedProvider);
    }
  }
}

/// 9-4 一键督学：模板话术 + 渠道 + 是否必须回应。
final class _NagSheet extends ConsumerStatefulWidget {
  const _NagSheet({required this.learner});

  final LearnerOverview learner;

  static Future<bool> show(BuildContext context, LearnerOverview learner) {
    return showShanganSheet(
      context,
      heightFactor: 0.9,
      builder: (context) => _NagSheet(learner: learner),
    );
  }

  @override
  ConsumerState<_NagSheet> createState() => _NagSheetState();
}

class _NagSheetState extends ConsumerState<_NagSheet> {
  static const _templates = {
    '今天一项没动': '今天一项没动，先把第一条完成，完成了给我回一句。',
    '进度落后了': '进度落后了，今晚补一条，别再往后拖。',
    '别删任务': '别删任务，先做能做的部分。',
    '距考试不多了': '距考试不多了，保持每天的节奏。',
    '先看 30% 也行': '先看 30% 也行，别停下来。',
  };

  final _controller = TextEditingController();
  final _title = TextEditingController();
  String? _template;
  String _channel = 'AUTO';
  bool _requireReason = true;
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    _title.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final learner = widget.learner;
    final offline = learner.presenceState != PresenceState.online;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                ShanganAvatar(
                  letter: learner.username.substring(0, 1).toUpperCase(),
                  index: learner.alerts.isEmpty ? 0 : 3,
                  size: 38,
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: ShanganSheetHeader(
                    title: '督学 ${learner.displayName}',
                    subtitle:
                        '今日 ${learner.todayDone} / ${learner.todayTotal} · '
                        '${learner.presenceState.label}',
                  ),
                ),
              ],
            ),
            const ShanganGroupLabel('话术模板'),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: _templates.entries
                  .map(
                    (entry) => ShanganFilterChip(
                      label: entry.key,
                      selected: _template == entry.key,
                      onTap: () => setState(() {
                        _template = entry.key;
                        _controller.text = entry.value;
                      }),
                    ),
                  )
                  .toList(growable: false),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _title,
              maxLength: 80,
              decoration: const InputDecoration(
                labelText: '催办标题（可选）',
                hintText: '不填写时使用默认标题',
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _controller,
              maxLength: 1000,
              minLines: 3,
              maxLines: 5,
              onChanged: (_) => setState(() => _template = null),
              decoration: const InputDecoration(hintText: '自定义话术（可选）'),
            ),
            const ShanganGroupLabel('投递渠道'),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: [
                for (final entry in const {
                  'AUTO': '自动',
                  'FULLSCREEN': '客户端全屏',
                  'SERVERCHAN': 'Server 酱',
                  'BARK': '个人 Bark',
                }.entries)
                  ShanganFilterChip(
                    label: entry.value,
                    selected: _channel == entry.key,
                    onTap: () => setState(() => _channel = entry.key),
                  ),
              ],
            ),
            if (_channel == 'AUTO') ...[
              const SizedBox(height: 10),
              ShanganCard(
                padding: const EdgeInsets.symmetric(
                  horizontal: 13,
                  vertical: 11,
                ),
                borderColor: ShanganColors.ochreLine,
                backgroundColor: ShanganColors.ochreSoft,
                child: Text(
                  offline
                      ? '学员当前离线，自动模式优先走学员 Bark，未启用时走 Server 酱；App 下次上线时仍会补一次全屏弹框，且必须填写原因才能关闭。'
                      : '学员当前在线，自动模式会直接弹全屏催办，必须填写原因才能关闭。',
                  style: const TextStyle(
                    fontSize: 11.5,
                    height: 1.6,
                    color: shanganTipText,
                  ),
                ),
              ),
            ],
            const ShanganGroupLabel('要求学员回应'),
            ShanganSwitchCard(
              tiles: [
                ShanganSwitchTile(
                  title: '必须填写原因',
                  subtitle: '默认开启，至少 5 个字',
                  value: _requireReason,
                  onChanged: (value) => setState(() => _requireReason = value),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(false),
                    child: const Text('取消'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  flex: 2,
                  child: FilledButton(
                    style: FilledButton.styleFrom(
                      backgroundColor: ShanganColors.ochre,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(15),
                        side: const BorderSide(color: ShanganColors.ochre),
                      ),
                    ),
                    onPressed: _busy ? null : _send,
                    child: Text(_busy ? '发送中…' : '发送督学'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _send() async {
    setState(() => _busy = true);
    try {
      await ref
          .read(shanganRepositoryProvider)
          .nagLearner(
            widget.learner.userId,
            title: _title.text,
            message: _controller.text.trim(),
            channel: _channel,
            requireReason: _requireReason,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      setState(() => _busy = false);
      if (mounted) {
        ShanganFeedback.show(context, '发送失败：$error', error: true);
      }
    }
  }
}

/// 9-5 提醒记录：按日期分组的督学 / 催办 / 删除通知时间线。
final class _SupervisorFeedTab extends ConsumerStatefulWidget {
  const _SupervisorFeedTab();

  @override
  ConsumerState<_SupervisorFeedTab> createState() => _SupervisorFeedTabState();
}

class _SupervisorFeedTabState extends ConsumerState<_SupervisorFeedTab> {
  /// 0 全部 / 1 我发的 / 2 自动催办 / 3 删除通知。
  int _filter = 0;

  @override
  Widget build(BuildContext context) {
    final feed = ref.watch(supervisorFeedProvider);
    final learners = ref.watch(learnersProvider).value ?? const [];
    final names = <String, String>{
      for (final learner in learners) learner.userId: learner.displayName,
    };
    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(supervisorFeedProvider);
        await ref.read(supervisorFeedProvider.future);
      },
      child: feed.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ListView(
          padding: const EdgeInsets.all(18),
          children: [Text('提醒记录加载失败：$error')],
        ),
        data: (items) {
          final visible = items.where(_matchesFilter).toList(growable: false);
          final grouped = <String, List<SupervisorFeedItem>>{};
          for (final item in visible) {
            grouped
                .putIfAbsent(_dateLabel(item.occurredAt), () => [])
                .add(item);
          }
          return ListView(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
            children: [
              const _SupervisorHeader(kicker: '督学端', title: '提醒'),
              const SizedBox(height: 12),
              StatStrip(
                items: [
                  StatStripItem(
                    value: '${items.where((item) => !item.isDeletion).length}',
                    label: '近期督学',
                    highlighted: true,
                    highlightTone: ShanganBadgeTone.ochre,
                  ),
                  StatStripItem(
                    value:
                        '${items.where((item) => item.status == 'RESPONDED').length}',
                    label: '已回应',
                  ),
                  StatStripItem(
                    value:
                        '${items.where((item) => !item.isDeletion && item.status != 'RESPONDED').length}',
                    label: '未回应',
                  ),
                  StatStripItem(
                    value: '${items.where((item) => item.isDeletion).length}',
                    label: '删除通知',
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 7,
                runSpacing: 7,
                children: [
                  for (var index = 0; index < 4; index++)
                    ShanganFilterChip(
                      label: const ['全部', '我发的', '自动催办', '删除通知'][index],
                      selected: _filter == index,
                      onTap: () => setState(() => _filter = index),
                    ),
                ],
              ),
              if (visible.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 40),
                  child: Center(
                    child: Text(
                      '暂无催办或删除通知',
                      style: TextStyle(color: ShanganColors.mutedInk),
                    ),
                  ),
                ),
              for (final entry in grouped.entries) ...[
                ShanganGroupLabel(entry.key),
                for (final item in entry.value)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: _FeedCard(
                      item: item,
                      learnerName: names[item.learnerUserId] ?? '学员',
                    ),
                  ),
              ],
            ],
          );
        },
      ),
    );
  }

  bool _matchesFilter(SupervisorFeedItem item) => switch (_filter) {
    1 => !item.isDeletion && item.sentByMe,
    2 => !item.isDeletion && item.tag == 'AUTO',
    3 => item.isDeletion,
    _ => true,
  };

  String _dateLabel(DateTime moment) {
    final now = DateTime.now();
    final day = DateTime(moment.year, moment.month, moment.day);
    final today = DateTime(now.year, now.month, now.day);
    if (day.isAtSameMomentAs(today)) return '今天';
    if (day.isAtSameMomentAs(today.subtract(const Duration(days: 1)))) {
      return '昨天';
    }
    return '${moment.month} 月 ${moment.day} 日';
  }
}

final class _FeedCard extends StatelessWidget {
  const _FeedCard({required this.item, required this.learnerName});

  final SupervisorFeedItem item;
  final String learnerName;

  @override
  Widget build(BuildContext context) {
    final (String kindLabel, ShanganBadgeTone kindTone) = item.isDeletion
        ? ('删除通知', ShanganBadgeTone.red)
        : item.tag == 'AUTO'
        ? ('自动催办', ShanganBadgeTone.ink)
        : item.sentByMe
        ? ('我发的', ShanganBadgeTone.ochre)
        : (item.tag == 'MANUAL' ? '管理员催办' : '其他督学', ShanganBadgeTone.ink);
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      borderColor: item.isDeletion ? ShanganColors.redLine : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              ShanganBadge(label: kindLabel, tone: kindTone),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  learnerName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Text(
                '${item.occurredAt.hour.toString().padLeft(2, '0')}:'
                '${item.occurredAt.minute.toString().padLeft(2, '0')}',
                style: const TextStyle(
                  fontSize: 11.5,
                  color: ShanganColors.mutedInk,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            item.isDeletion ? item.title : '「${item.title}」',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
          ),
          if (item.reasonText.isNotEmpty) ...[
            const SizedBox(height: 6),
            _NoteBlock(
              text: item.isDeletion
                  ? '原因「${_deletionLabel(item.tag)}」：${item.reasonText}'
                  : '学员回应：${item.reasonText}',
            ),
          ],
          if (!item.isDeletion && item.status != 'RESPONDED') ...[
            const SizedBox(height: 8),
            Row(
              children: [
                const Icon(
                  Icons.error_outline,
                  size: 14,
                  color: ShanganColors.red,
                ),
                const SizedBox(width: 5),
                Text(
                  '${_statusLabel(item.status)} · 尚未回应',
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: ShanganColors.red,
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  String _deletionLabel(String wire) {
    for (final tag in DeletionReasonTag.values) {
      if (tag.wire == wire) return tag.label;
    }
    return wire;
  }

  String _statusLabel(String status) => switch (status) {
    'PENDING' => '待投递',
    'DELIVERED' => '已送达',
    'EXPIRED' => '已过期',
    _ => status,
  };
}

/// 原型 `.todo-note`：inkSoft 底、圆角 8、带笔记图标的备注块。
final class _NoteBlock extends StatelessWidget {
  const _NoteBlock({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: ShanganColors.inkSoft,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.sticky_note_2_outlined,
            size: 14,
            color: ShanganColors.mutedInk,
          ),
          const SizedBox(width: 5),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 11.5,
                height: 1.5,
                color: ShanganColors.mutedInk,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 9-6 学员报告：学员对比 + 课程分布。
final class _SupervisorReportTab extends ConsumerWidget {
  const _SupervisorReportTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final range = ref.watch(supervisorReportRangeProvider);
    final reports = ref.watch(supervisorReportProvider);
    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(supervisorReportProvider);
        await ref.read(supervisorReportProvider.future);
      },
      child: reports.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ListView(
          padding: const EdgeInsets.all(18),
          children: [Text('报告加载失败：$error')],
        ),
        data: (list) => ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
          children: [
            _SupervisorHeader(
              kicker: switch (range) {
                HomeRange.day => '督学端 · 今日',
                HomeRange.week => '督学端 · 本周',
                HomeRange.month => '督学端 · 本月',
              },
              title: '学员报告',
            ),
            const SizedBox(height: 12),
            ShanganSegmented(
              labels: const ['日', '周', '月'],
              selectedIndex: range.index,
              onChanged: (index) => ref
                  .read(supervisorReportRangeProvider.notifier)
                  .select(HomeRange.values[index]),
            ),
            if (list.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(
                  child: Text(
                    '暂无学员数据',
                    style: TextStyle(color: ShanganColors.mutedInk),
                  ),
                ),
              )
            else ...[
              const SectionTitle(title: '学员对比'),
              ShanganCard(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 13,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var index = 0; index < list.length; index++) ...[
                      if (index > 0)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Divider(height: 1, color: ShanganColors.hair),
                        ),
                      _ReportRow(report: list[index]),
                    ],
                  ],
                ),
              ),
              ?_focusLearnerSection(context, ref, list),
              const SectionTitle(title: '课程分布 · 全部学员'),
              ShanganCard(
                padding: const EdgeInsets.symmetric(horizontal: 13),
                child: Column(
                  children: [
                    for (
                      var index = 0;
                      index < _mergedCourses(list).length;
                      index++
                    ) ...[
                      if (index > 0)
                        const Divider(height: 1, color: ShanganColors.hair),
                      ShanganRankRow(
                        leading: ShanganRankNumber(rank: index),
                        title: _mergedCourses(list)[index].name,
                        subtitle:
                            '${_mergedCourses(list)[index].lessonCount} 课时',
                        value: formatDurationCompact(
                          _mergedCourses(list)[index].watchedMs,
                        ),
                      ),
                    ],
                    if (_mergedCourses(list).isEmpty)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 20),
                        child: Text(
                          '这个区间内没有课程观看记录',
                          style: TextStyle(color: ShanganColors.mutedInk),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// 原型 9-6「lisi · 每日时长」：挑完成率最低的学员单独画一条每日时长，
  /// 数据直接取 `/supervisor/report` 已经返回的 `stats.days`，不额外发请求。
  /// 零完成的那天按原型画成红柱，便于一眼看出断档。
  Widget? _focusLearnerSection(
    BuildContext context,
    WidgetRef ref,
    List<LearnerReport> reports,
  ) {
    final candidates = reports
        .where((report) => report.stats.days.isNotEmpty)
        .toList(growable: false);
    if (candidates.isEmpty) return null;
    final focus = candidates.reduce(
      (a, b) => _completion(a) <= _completion(b) ? a : b,
    );
    // 报告入口与学员卡遵循相同 canNag 权限，避免只读督学看到无效发送按钮。
    final canNag =
        ref
            .watch(learnersProvider)
            .value
            ?.where((learner) => learner.userId == focus.userId)
            .firstOrNull
            ?.canNag ==
        true;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionTitle(title: '${focus.displayName} · 每日时长'),
        ShanganCard(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ShanganBarChart(
                columns: focus.stats.days
                    .map(
                      (day) => ShanganBarColumn(
                        value: day.totalMs / 3600000,
                        caption: (day.totalMs / 3600000).toStringAsFixed(1),
                        label: weekdayLabel(day.date),
                        color: day.total > 0 && day.done == 0
                            ? ShanganColors.red
                            : day.totalMs < 3600000
                            ? ShanganHeatColors.l2
                            : ShanganColors.blue,
                      ),
                    )
                    .toList(growable: false),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Divider(height: 1, color: ShanganColors.hair),
              ),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      style: _reportButtonStyle,
                      onPressed: () =>
                          context.push('/supervisor/${focus.userId}'),
                      child: const Text('查看明细'),
                    ),
                  ),
                  if (canNag) ...[
                    const SizedBox(width: 9),
                    Expanded(
                      child: FilledButton(
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(44),
                          backgroundColor: ShanganColors.ochre,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                            side: const BorderSide(color: ShanganColors.ochre),
                          ),
                          textStyle: const TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        onPressed: () async {
                          final learner = ref
                              .read(learnersProvider)
                              .value
                              ?.where((item) => item.userId == focus.userId)
                              .firstOrNull;
                          if (learner == null) return;
                          final sent = await _NagSheet.show(context, learner);
                          if (sent) {
                            ref.invalidate(learnersProvider);
                            ref.invalidate(supervisorFeedProvider);
                          }
                        },
                        child: const Text('一键督学'),
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  static double _completion(LearnerReport report) =>
      report.stats.totalTodos == 0
      ? 0
      : report.stats.doneTodos / report.stats.totalTodos;

  static final _reportButtonStyle = OutlinedButton.styleFrom(
    minimumSize: const Size.fromHeight(44),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700),
  );

  /// 把所有学员的课程排行按课程名合并，得到「全部学员」的课程分布。
  List<RankingEntry> _mergedCourses(List<LearnerReport> reports) {
    final merged = <String, RankingEntry>{};
    for (final report in reports) {
      for (final entry in report.stats.courseRanking) {
        final existing = merged[entry.name];
        merged[entry.name] = existing == null
            ? entry
            : RankingEntry(
                name: entry.name,
                watchedMs: existing.watchedMs + entry.watchedMs,
                lessonCount: existing.lessonCount + entry.lessonCount,
              );
      }
    }
    final list = merged.values.toList()
      ..sort((a, b) => b.watchedMs.compareTo(a.watchedMs));
    return list;
  }
}

final class _ReportRow extends StatelessWidget {
  const _ReportRow({required this.report});

  final LearnerReport report;

  @override
  Widget build(BuildContext context) {
    final percent = report.stats.totalTodos == 0
        ? 0
        : report.stats.doneTodos * 100 ~/ report.stats.totalTodos;
    final deletions = report.stats.deletionCounts.values.fold<int>(
      0,
      (a, b) => a + b,
    );
    final behind = percent < 50;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                report.displayName,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            Text(
              '${formatDurationCompact(report.stats.totalMs)} · 完成 $percent%',
              style: const TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        TargetProgressBar(
          value: percent / 100,
          color: behind ? ShanganColors.red : ShanganColors.blue,
        ),
        const SizedBox(height: 6),
        Wrap(
          spacing: 5,
          runSpacing: 4,
          children: [
            ShanganBadge(
              label: '专注完成率 ${report.stats.focusCompletionPercent}%',
              tone: ShanganBadgeTone.ochre,
            ),
            ShanganBadge(
              label: '删除 $deletions',
              tone: deletions >= 5
                  ? ShanganBadgeTone.red
                  : ShanganBadgeTone.ink,
            ),
            ShanganBadge(
              label: '回应 ${report.nagRespondedCount}/${report.nagCount}',
              tone: report.nagCount > report.nagRespondedCount
                  ? ShanganBadgeTone.red
                  : ShanganBadgeTone.blue,
            ),
          ],
        ),
      ],
    );
  }
}

/// 9-3 学员详情：今日 Todo 只读镜像 + 删除记录。
final class SupervisorLearnerPage extends ConsumerWidget {
  const SupervisorLearnerPage({required this.learnerId, super.key});

  final String learnerId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(learnerDetailProvider(learnerId));
    // 详情页可由报告或路由直接进入，主动读取权限，不能依赖列表页已经加载过。
    final canNag =
        ref
            .watch(learnersProvider)
            .value
            ?.where((learner) => learner.userId == learnerId)
            .firstOrNull
            ?.canNag ==
        true;
    return _ActivityRefresh(
      onRefresh: () {
        if (!ref.read(learnerDetailProvider(learnerId)).isLoading) {
          ref.invalidate(learnerDetailProvider(learnerId));
        }
      },
      child: Scaffold(
        body: Column(
          children: [
            Container(height: 4, color: ShanganColors.ochre),
            Expanded(
              child: SafeArea(
                // 督学页没有 AppBar，必须保留系统顶部安全区，避免标题覆盖刘海和状态栏。
                top: true,
                child: detail.when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => Center(child: Text('学员详情加载失败：$error')),
                  data: (data) => ListView(
                    padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
                    children: [
                      Row(
                        children: [
                          ShanganIconButton(
                            icon: Icons.chevron_right,
                            quarterTurns: 2,
                            semanticLabel: '返回',
                            onTap: () => Navigator.of(context).pop(),
                          ),
                          Expanded(
                            child: Text(
                              data.displayName,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          if (canNag)
                            _RedIconButton(
                              icon: Icons.notifications_none,
                              semanticLabel: '督学 ${data.displayName}',
                              onTap: () => _nag(context, ref, data),
                            )
                          else
                            const SizedBox(width: 44),
                        ],
                      ),
                      const SizedBox(height: 12),
                      // 在线状态卡：dotstate + 最近上报 + 关键 kv。
                      ShanganCard(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 12,
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Row(
                              children: [
                                ShanganDotState(
                                  state: data.presenceState,
                                  label: _presenceLabel(data),
                                ),
                                const Spacer(),
                                Text(
                                  data.idleMinutes < 0
                                      ? '今日无有效操作'
                                      : '空闲 ${data.idleMinutes} 分钟',
                                  style: const TextStyle(
                                    fontSize: 11.5,
                                    color: ShanganColors.mutedInk,
                                  ),
                                ),
                              ],
                            ),
                            _CurrentActivityText(activity: data.activity),
                            const Padding(
                              padding: EdgeInsets.symmetric(vertical: 12),
                              child: Divider(
                                height: 1,
                                color: ShanganColors.hair,
                              ),
                            ),
                            _KeyValueRow(
                              label: '今日完成',
                              value:
                                  '${data.day.totals.done} / ${data.day.totals.total} · '
                                  '${formatDurationCompact(data.day.totals.watchedMs + data.day.totals.focusedMs)}',
                            ),
                            const SizedBox(height: 6),
                            _KeyValueRow(
                              label: '区间统计',
                              value:
                                  '${formatDurationCompact(data.stats.totalMs)} · '
                                  '完成 ${data.stats.doneTodos}/${data.stats.totalTodos}',
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                      StatStrip(
                        items: [
                          StatStripItem(
                            value:
                                '${data.day.totals.done}/${data.day.totals.total}',
                            label: '完成',
                          ),
                          StatStripItem(
                            value: formatDurationCompact(
                              data.day.totals.watchedMs,
                            ),
                            label: '观看',
                          ),
                          StatStripItem(
                            value: formatDurationCompact(
                              data.day.totals.focusedMs,
                            ),
                            label: '专注',
                          ),
                          StatStripItem(
                            value: '${data.deletions.length}',
                            label: '今日删除',
                          ),
                        ],
                      ),
                      SectionTitle(
                        title: '今日待办',
                        count:
                            '${data.day.totals.done} / ${data.day.totals.total}',
                        trailing: const ShanganBadge(label: '只读'),
                      ),
                      ShanganCard(
                        padding: const EdgeInsets.symmetric(horizontal: 13),
                        child: Column(
                          children: [
                            for (
                              var index = 0;
                              index < data.day.todos.length;
                              index++
                            ) ...[
                              if (index > 0)
                                const Divider(
                                  height: 1,
                                  color: ShanganColors.hair,
                                ),
                              _ReadonlyTodoRow(todo: data.day.todos[index]),
                            ],
                            if (data.day.todos.isEmpty)
                              const Padding(
                                padding: EdgeInsets.symmetric(vertical: 20),
                                child: Text(
                                  '该学员今日没有待办',
                                  style: TextStyle(
                                    color: ShanganColors.mutedInk,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                      if (data.deletions.isNotEmpty) ...[
                        SectionTitle(
                          title: '今日删除',
                          count: '${data.deletions.length}',
                        ),
                        ShanganCard(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 14,
                            vertical: 13,
                          ),
                          borderColor: ShanganColors.redLine,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              for (
                                var index = 0;
                                index < data.deletions.length;
                                index++
                              ) ...[
                                if (index > 0)
                                  const Padding(
                                    padding: EdgeInsets.symmetric(vertical: 12),
                                    child: Divider(
                                      height: 1,
                                      color: ShanganColors.hair,
                                    ),
                                  ),
                                _DeletionBlock(deletion: data.deletions[index]),
                              ],
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _presenceLabel(LearnerDetail detail) {
    if (detail.presenceState == PresenceState.online) return '在线';
    final minutes = detail.idleMinutes;
    if (minutes <= 0) return detail.presenceState.label;
    final suffix = minutes < 60
        ? '$minutes 分钟'
        : '${minutes ~/ 60} 小时 ${minutes % 60} 分';
    return '${detail.presenceState.label} $suffix';
  }

  Future<void> _nag(
    BuildContext context,
    WidgetRef ref,
    LearnerDetail detail,
  ) async {
    final learners = ref.read(learnersProvider).value ?? const [];
    final overview = learners
        .where((item) => item.userId == detail.userId)
        .firstOrNull;
    if (overview == null) {
      ShanganFeedback.show(context, '学员信息还在加载，请稍后再试');
      return;
    }
    final sent = await _NagSheet.show(context, overview);
    if (sent) {
      ref.invalidate(learnerDetailProvider(detail.userId));
      ref.invalidate(supervisorFeedProvider);
    }
  }
}

final class _KeyValueRow extends StatelessWidget {
  const _KeyValueRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 74,
          child: Text(
            label,
            style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 11.5,
              color: ShanganColors.mutedInk,
            ),
          ),
        ),
      ],
    );
  }
}

final class _RedIconButton extends StatelessWidget {
  const _RedIconButton({
    required this.icon,
    required this.onTap,
    this.semanticLabel,
  });

  final IconData icon;
  final VoidCallback onTap;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: semanticLabel,
      child: GestureDetector(
        onTap: onTap,
        child: SizedBox(
          width: 44,
          height: 44,
          child: Center(
            child: Container(
              width: 42,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: ShanganColors.redSoft,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: ShanganColors.red, width: 1.5),
              ),
              child: Icon(icon, size: 20, color: ShanganColors.red),
            ),
          ),
        ),
      ),
    );
  }
}

/// 9-3 的删除记录块：原因徽标 + 时间 + 标题 + 说明。
final class _DeletionBlock extends StatelessWidget {
  const _DeletionBlock({required this.deletion});

  final LearnerDeletion deletion;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            ShanganBadge(
              label: _reasonLabel(deletion.reasonTag),
              tone: ShanganBadgeTone.red,
            ),
            const Spacer(),
            Text(
              '${deletion.deletedAt.hour.toString().padLeft(2, '0')}:'
              '${deletion.deletedAt.minute.toString().padLeft(2, '0')}',
              style: const TextStyle(
                fontSize: 11.5,
                color: ShanganColors.mutedInk,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          deletion.title,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
        ),
        if (deletion.reasonText.isNotEmpty) ...[
          const SizedBox(height: 6),
          _NoteBlock(text: deletion.reasonText),
        ],
      ],
    );
  }

  String _reasonLabel(String wire) {
    for (final tag in DeletionReasonTag.values) {
      if (tag.wire == wire) return tag.label;
    }
    return wire;
  }
}

/// 督学端的 Todo 行是只读镜像，不提供任何交互（原型 9-3）。
final class _ReadonlyTodoRow extends StatelessWidget {
  const _ReadonlyTodoRow({required this.todo});

  final TodoItem todo;

  @override
  Widget build(BuildContext context) {
    final color = switch (todo.todoType) {
      TodoType.course => ShanganColors.course,
      TodoType.focus => ShanganColors.ochre,
      TodoType.task => ShanganColors.green,
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 11),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Opacity(
              opacity: 0.5,
              child: ShanganTick(
                color: color,
                done: todo.isDone,
                half: todo.status == TodoStatus.inProgress,
              ),
            ),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  todo.title,
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.35,
                    fontWeight: FontWeight.w700,
                    color: todo.isDone
                        ? ShanganColors.mutedInk
                        : ShanganColors.ink,
                  ),
                ),
                const SizedBox(height: 5),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    TodoTypeBadge(type: todo.todoType),
                    if (todo.targetProgressPermille != null)
                      ShanganBadge(
                        label: '目标 ${todo.targetProgressPermille! ~/ 10}%',
                        tone: ShanganBadgeTone.blue,
                      ),
                    ShanganMeta(_progressText()),
                  ],
                ),
                if (todo.note.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  _NoteBlock(text: todo.note),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _progressText() {
    if (todo.status == TodoStatus.todo) {
      return todo.requireEvidence ? '需凭证 · 未开始' : '未开始';
    }
    return switch (todo.todoType) {
      TodoType.course =>
        '${todo.progressPermille ~/ 10}% · ${formatDurationCompact(todo.watchedMs)}',
      TodoType.focus =>
        '${formatDurationCompact(todo.focusedMs)} / ${(todo.plannedSeconds ?? 0) ~/ 60} 分',
      TodoType.task => todo.isDone ? '已完成' : '未完成',
    };
  }
}

/// 当前 App 位置与服务端接收时间一起显示，未知值不会渲染成“正在学习”。
class _CurrentActivityText extends StatelessWidget {
  const _CurrentActivityText({required this.activity});
  final CurrentAppActivity activity;
  @override
  Widget build(BuildContext context) {
    final time = activity.updatedAt?.toLocal();
    final updated = time == null
        ? '尚未上报位置'
        : '更新于 ${time.year}-${time.month.toString().padLeft(2, '0')}-${time.day.toString().padLeft(2, '0')} ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        '${activity.summary}\n$updated',
        style: const TextStyle(
          fontSize: 12,
          height: 1.5,
          color: ShanganColors.mutedInk,
        ),
      ),
    );
  }
}

/// 查看页每 15 秒刷新一次；后台或被其他路由覆盖时不读取，离页释放定时器。
class _ActivityRefresh extends StatefulWidget {
  const _ActivityRefresh({required this.onRefresh, required this.child});
  final VoidCallback onRefresh;
  final Widget child;
  @override
  State<_ActivityRefresh> createState() => _ActivityRefreshState();
}

class _ActivityRefreshState extends State<_ActivityRefresh>
    with WidgetsBindingObserver {
  Timer? _timer;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _timer = Timer.periodic(const Duration(seconds: 15), (_) => _refresh());
  }

  void _refresh() {
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    if (mounted &&
        (ModalRoute.of(context)?.isCurrent ?? true) &&
        (lifecycle == null || lifecycle == AppLifecycleState.resumed)) {
      widget.onRefresh();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
