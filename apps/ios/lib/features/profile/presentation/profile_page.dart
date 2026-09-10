import 'app_update_page.dart';
import 'bark_settings_sheet.dart';
import 'package:shangan_ios/core/widgets/shangan_feedback.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/core/config/server_configuration_controller.dart';
import 'package:shangan_ios/core/models/shangan_models.dart';
import 'package:shangan_ios/core/state/shangan_providers.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_v2.dart';

/// 我的 Tab：账号与安全、考试目标、只读催办策略、督学端入口。
final class ProfilePage extends ConsumerWidget {
  const ProfilePage({this.supervisor = false, super.key});

  /// 督学端复用账号设置，隐藏学习专属目标和心跳信息。
  final bool supervisor;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(meSettingsProvider);
    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(meSettingsProvider);
        ref.invalidate(goalsProvider);
      },
      child: settings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ListView(
          padding: const EdgeInsets.all(18),
          children: [Text('资料加载失败：$error')],
        ),
        // 用户身份固定在顶部；心跳状态及以下设置共用独立滚动区域。
        data: (data) => Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 12, 18, 12),
              child: _ProfileHeader(profile: data.profile),
            ),
            Expanded(
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(18, 0, 18, 110),
                children: [
                  if (!supervisor) const _HeartbeatCard(),
                  const SizedBox(height: 10),
                  if (data.supervisors.isNotEmpty)
                    _SupervisionCard(settings: data),
                  if (!supervisor) ...[
                    const ShanganGroupLabel('考试目标'),
                    _GoalList(onManage: () => context.push('/goals')),
                  ],
                  const ShanganGroupLabel('个人推送'),
                  ShanganCard(
                    padding: EdgeInsets.zero,
                    child: _MenuItem(
                      icon: Icons.notifications_active_outlined,
                      title: '我的 Bark 推送',
                      subtitle: '配置个人设备，启用后替代 Server 酱',
                      onTap: () => showModalBottomSheet<void>(
                        context: context,
                        isScrollControlled: true,
                        builder: (_) => const BarkSettingsSheet(),
                      ),
                    ),
                  ),
                  const ShanganGroupLabel('账号与安全'),
                  ShanganCard(
                    padding: EdgeInsets.zero,
                    child: Column(
                      children: [
                        _MenuItem(
                          icon: Icons.person_outline,
                          title: '用户名',
                          value: data.profile.username,
                        ),
                        _MenuItem(
                          icon: Icons.lock_outline,
                          title: '修改密码',
                          onTap: () => _showPasswordSheet(context, ref, data),
                        ),
                        _MenuItem(
                          icon: Icons.public,
                          title: '时区',
                          subtitle: '影响每日边界与统计口径',
                          value: data.profile.timezone,
                          onTap: () => _showTimezoneSheet(context, ref, data),
                        ),
                        _MenuItem(
                          icon: Icons.dns_outlined,
                          title: '服务端地址',
                          subtitle: ref
                              .read(serverConfigurationControllerProvider)
                              .configuration
                              .displayLabel,
                          value: '已连接',
                        ),
                      ],
                    ),
                  ),
                  if (!supervisor) ...[
                    const ShanganGroupLabel('提醒与在线（只读）'),
                    ShanganCard(
                      padding: EdgeInsets.zero,
                      child: Column(
                        children: [
                          _MenuItem(
                            icon: Icons.monitor_heart_outlined,
                            title: '后台心跳',
                            // 原型 6-1「每 60 秒上报，由服务端下发间隔」。
                            subtitle:
                                '每 ${data.heartbeatIntervalSeconds} 秒上报，由服务端下发间隔',
                            value: '${data.heartbeatIntervalSeconds} 秒',
                          ),
                          _MenuItem(
                            icon: Icons.notifications_outlined,
                            title: '催办策略',
                            subtitle:
                                '无操作 ${data.firstThresholdMinutes} 分钟触发 · 每日最多 ${data.dailyMax} 次',
                            value: '服务端配置',
                          ),
                          _MenuItem(
                            icon: Icons.nights_stay_outlined,
                            title: '免打扰时段',
                            subtitle: '由管理员在服务端设置',
                            value: '${data.quietStart} – ${data.quietEnd}',
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '催办阈值和免打扰由服务端维护；个人 Bark 可在上方自行配置。',
                      style: TextStyle(
                        fontSize: 11.5,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                  ],
                  // 应用级信息统一放在设置底部，不混入推送或账号安全分组。
                  const ShanganGroupLabel('应用信息'),
                  ShanganCard(
                    padding: EdgeInsets.zero,
                    child: AppUpdateEntry(
                      builder: (version, open) => _MenuItem(
                        icon: Icons.system_update_outlined,
                        title: '检查更新',
                        value: version.isEmpty ? '读取中' : version,
                        onTap: open,
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  OutlinedButton(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: ShanganColors.red,
                      side: const BorderSide(
                        color: ShanganColors.red,
                        width: 1.5,
                      ),
                    ),
                    onPressed: () => ref.read(authControllerProvider).logout(),
                    child: const Text('退出登录'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showPasswordSheet(
    BuildContext context,
    WidgetRef ref,
    MeSettings settings,
  ) async {
    final current = TextEditingController();
    final next = TextEditingController();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => Padding(
        padding: EdgeInsets.fromLTRB(
          18,
          18,
          18,
          18 + MediaQuery.of(context).viewInsets.bottom,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              '修改密码',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: current,
              obscureText: true,
              decoration: const InputDecoration(labelText: '当前密码'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: next,
              obscureText: true,
              decoration: const InputDecoration(labelText: '新密码（至少 8 位）'),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () async {
                try {
                  await ref
                      .read(shanganRepositoryProvider)
                      .changePassword(
                        currentPassword: current.text,
                        newPassword: next.text,
                      );
                  if (context.mounted) {
                    Navigator.of(context).pop();
                    ShanganFeedback.show(context, '密码已修改，其他设备需要重新登录');
                  }
                } catch (error) {
                  if (context.mounted) {
                    ShanganFeedback.show(context, '修改失败：$error', error: true);
                  }
                }
              },
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showTimezoneSheet(
    BuildContext context,
    WidgetRef ref,
    MeSettings settings,
  ) async {
    const zones = [
      'Asia/Shanghai',
      'Asia/Tokyo',
      'Asia/Singapore',
      'Europe/London',
      'America/New_York',
    ];
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: zones
              .map(
                (zone) => ListTile(
                  title: Text(zone),
                  trailing: zone == settings.profile.timezone
                      ? const Icon(Icons.check, color: ShanganColors.blue)
                      : null,
                  onTap: () => Navigator.of(context).pop(zone),
                ),
              )
              .toList(growable: false),
        ),
      ),
    );
    if (picked == null) return;
    await ref.read(shanganRepositoryProvider).changeTimezone(picked);
    ref.invalidate(meSettingsProvider);
    ref.invalidate(dayViewProvider);
  }
}

final class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({required this.profile});

  final UserSummary profile;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 58,
          height: 58,
          decoration: BoxDecoration(
            color: ShanganColors.blueSoft,
            shape: BoxShape.circle,
            border: Border.all(color: ShanganColors.course, width: 1.5),
          ),
          alignment: Alignment.center,
          child: Text(
            profile.username.characters.first.toUpperCase(),
            style: const TextStyle(
              fontSize: 21,
              fontWeight: FontWeight.w800,
              color: ShanganColors.course,
            ),
          ),
        ),
        const SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                profile.displayName,
                style: const TextStyle(
                  fontSize: 19,
                  fontWeight: FontWeight.w800,
                ),
              ),
              Text(
                '${profile.username} · ${profile.timezone}',
                style: const TextStyle(
                  fontSize: 12,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

final class _SupervisionCard extends ConsumerWidget {
  const _SupervisionCard({required this.settings});

  final MeSettings settings;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final primary = settings.supervisors.isEmpty
        ? null
        : settings.supervisors.firstWhere(
            (supervisor) => supervisor.isPrimary,
            orElse: () => settings.supervisors.first,
          );
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (primary != null)
            Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: ShanganColors.ochreSoft,
                    shape: BoxShape.circle,
                    border: Border.all(color: ShanganColors.ochreLine),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    primary.displayName.characters.first,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w800,
                      color: ShanganColors.ochre,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '我的督学人 · ${primary.displayName}',
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const Text(
                        '删除待办与催办回应会同步给督学人',
                        style: TextStyle(
                          fontSize: 11.5,
                          color: ShanganColors.mutedInk,
                        ),
                      ),
                    ],
                  ),
                ),
                const ShanganBadge(label: '已绑定', tone: ShanganBadgeTone.ochre),
              ],
            ),
        ],
      ),
    );
  }
}

final class _GoalList extends ConsumerWidget {
  const _GoalList({required this.onManage});

  final VoidCallback onManage;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final goals = ref.watch(goalsProvider);
    return goals.when(
      loading: () => const ShanganCard(
        padding: EdgeInsets.all(18),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => ShanganCard(
        padding: const EdgeInsets.all(14),
        child: Text('目标加载失败：$error'),
      ),
      data: (list) => ShanganCard(
        padding: EdgeInsets.zero,
        child: Column(
          children: [
            for (final goal in list)
              _MenuItem(
                icon: Icons.adjust,
                iconTone: goal.primary
                    ? ShanganBadgeTone.blue
                    : ShanganBadgeTone.ink,
                title: goal.name,
                subtitle:
                    '${formatIsoDate(goal.examDate)} · 剩 ${goal.daysRemaining} 天',
                trailing: goal.primary
                    ? const ShanganBadge(
                        label: '主目标',
                        tone: ShanganBadgeTone.blue,
                      )
                    : null,
                onTap: onManage,
              ),
            _MenuItem(
              icon: Icons.add,
              iconTone: ShanganBadgeTone.blue,
              title: '新建目标',
              titleColor: ShanganColors.blue,
              subtitle: '只需名称与考试日期',
              onTap: onManage,
            ),
          ],
        ),
      ),
    );
  }
}

/// 原型 6-1 顶部的心跳状态卡：在线走绿底，离线走红底。
final class _HeartbeatCard extends ConsumerWidget {
  const _HeartbeatCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(heartbeatStatusProvider);
    final online = status.online;
    final last = status.lastReportedAt;
    final ago = last == null
        ? '尚未上报'
        : '${DateTime.now().difference(last).inSeconds} 秒前上报';
    return ShanganCard(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
      borderColor: online ? ShanganColors.greenLine : ShanganColors.redLine,
      backgroundColor: online ? ShanganColors.greenSoft : ShanganColors.redSoft,
      child: Row(
        children: [
          Icon(
            online ? Icons.monitor_heart_outlined : Icons.wifi_off,
            size: 18,
            color: online ? ShanganColors.green : ShanganColors.red,
          ),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
              online ? '在线 · 心跳正常' : '离线 · 心跳失败',
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: online ? ShanganColors.green : ShanganColors.red,
              ),
            ),
          ),
          Text(
            ago,
            style: const TextStyle(
              fontSize: 11.5,
              color: ShanganColors.mutedInk,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

final class _MenuItem extends StatelessWidget {
  const _MenuItem({
    required this.icon,
    required this.title,
    this.subtitle,
    this.value,
    this.trailing,
    this.onTap,
    this.iconTone = ShanganBadgeTone.ink,
    this.titleColor,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final String? value;
  final Widget? trailing;
  final VoidCallback? onTap;

  /// 原型 `.menu-item .mi` 的底色档位：主目标与新建用蓝，其余用墨灰。
  final ShanganBadgeTone iconTone;
  final Color? titleColor;

  @override
  Widget build(BuildContext context) {
    final (Color foreground, Color background) = switch (iconTone) {
      ShanganBadgeTone.blue => (ShanganColors.course, ShanganColors.blueSoft),
      ShanganBadgeTone.ochre => (ShanganColors.ochre, ShanganColors.ochreSoft),
      ShanganBadgeTone.green => (ShanganColors.green, ShanganColors.greenSoft),
      ShanganBadgeTone.red => (ShanganColors.red, ShanganColors.redSoft),
      ShanganBadgeTone.ink => (ShanganColors.ink, ShanganColors.inkSoft),
    };
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                color: background,
                borderRadius: BorderRadius.circular(11),
              ),
              child: Icon(icon, size: 20, color: foreground),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 14.5,
                      fontWeight: FontWeight.w600,
                      color: titleColor,
                    ),
                  ),
                  if (subtitle != null)
                    Text(
                      subtitle!,
                      style: const TextStyle(
                        fontSize: 11.5,
                        color: ShanganColors.mutedInk,
                      ),
                    ),
                ],
              ),
            ),
            if (value != null)
              Text(
                value!,
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                  color: ShanganColors.mutedInk,
                ),
              ),
            ?trailing,
            if (onTap != null && value == null && trailing == null)
              const Icon(Icons.chevron_right, color: ShanganColors.mutedInk),
          ],
        ),
      ),
    );
  }
}
