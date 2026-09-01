import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/theme/shangan_theme.dart';
import 'package:shangan_ios/core/widgets/shangan_ui.dart';
import 'package:shangan_ios/features/focus/data/exam_photo_picker.dart';
import 'package:shangan_ios/features/focus/data/mock_exam_repository.dart';

/// 模拟考试页以服务端 deadlineAt 为唯一截止依据，后台停留不会暂停倒计时。
final class MockExamPage extends ConsumerStatefulWidget {
  const MockExamPage({
    required this.planItemId,
    required this.title,
    super.key,
  });

  final String planItemId;
  final String title;

  @override
  ConsumerState<MockExamPage> createState() => _MockExamPageState();
}

final class _MockExamPageState extends ConsumerState<MockExamPage>
    with WidgetsBindingObserver {
  static const _photoPicker = ExamPhotoPicker();

  MockExamSessionData? _session;
  Duration _serverOffset = Duration.zero;
  Timer? _ticker;
  Object? _error;
  bool _busy = false;
  bool _refreshingExpiry = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _start();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _ticker?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _session != null) {
      _reloadSession();
    }
  }

  Future<void> _start() async {
    try {
      final session = await ref
          .read(mockExamRepositoryProvider)
          .start(widget.planItemId);
      _accept(session);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  Future<void> _reloadSession() async {
    final current = _session;
    if (current == null) return;
    try {
      final session = await ref
          .read(mockExamRepositoryProvider)
          .load(current.id);
      _accept(session);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  void _accept(MockExamSessionData session) {
    if (!mounted) return;
    setState(() {
      _session = session;
      // 将设备当前时钟校准到 serverNow，展示倒计时不信任设备绝对时间。
      _serverOffset = session.serverNow.difference(DateTime.now().toUtc());
      _error = null;
      _busy = false;
      _refreshingExpiry = false;
    });
    _ticker?.cancel();
    if (session.status == 'RUNNING') {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
    }
  }

  void _tick() {
    if (!mounted) return;
    setState(() {});
    final session = _session;
    if (session != null &&
        session.status == 'RUNNING' &&
        _remaining(session) == Duration.zero &&
        !_refreshingExpiry) {
      _refreshingExpiry = true;
      _reloadSession();
    }
  }

  Duration _remaining(MockExamSessionData session) {
    final serverNow = DateTime.now().toUtc().add(_serverOffset);
    final remaining = session.deadlineAt.difference(serverNow);
    return remaining.isNegative ? Duration.zero : remaining;
  }

  @override
  Widget build(BuildContext context) {
    final session = _session;
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: _error != null
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const ShanganNotice(
                      title: '模拟考试同步失败',
                      message: '倒计时和完成状态以服务端为准，请重新连接后继续。',
                      tone: ShanganTagTone.warning,
                    ),
                    const SizedBox(height: 18),
                    OutlinedButton.icon(
                      onPressed: session == null ? _start : _reloadSession,
                      icon: const Icon(Icons.refresh),
                      label: const Text('重新连接'),
                    ),
                  ],
                ),
              ),
            )
          : session == null
          ? const ShanganLoading('正在创建模拟考试会话')
          : _buildSession(context, session),
    );
  }

  Widget _buildSession(BuildContext context, MockExamSessionData session) {
    final remaining = _remaining(session);
    final waiting = session.status == 'AWAITING_UPLOAD';
    final completed = session.status == 'COMPLETED';
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const ShanganEyebrow('模拟考试'),
                  const SizedBox(height: 6),
                  Text(
                    session.name,
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                ],
              ),
            ),
            ShanganStatusTag(
              _statusLabel(session.status),
              tone: completed
                  ? ShanganTagTone.success
                  : waiting
                  ? ShanganTagTone.warning
                  : ShanganTagTone.info,
            ),
          ],
        ),
        const SizedBox(height: 26),
        ShanganSurface(
          borderColor: waiting ? ShanganColors.ochre : ShanganColors.blue,
          child: Column(
            children: [
              Text(
                session.status == 'RUNNING' ? '剩余时间' : '考试计时已结束',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 8),
              Text(
                _formatDuration(remaining),
                style: shanganNumberStyle(
                  context,
                  fontSize: 52,
                ).copyWith(color: ShanganColors.ink),
              ),
              const SizedBox(height: 8),
              Text(
                '截止 ${_formatDateTime(session.deadlineAt.toLocal())}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        if (session.status == 'RUNNING') ...[
          const ShanganNotice(
            title: '计时不会因切到后台而暂停',
            message: '完成答题后可以提前交卷；交卷后必须上传至少一张试卷照片。',
          ),
          const SizedBox(height: 18),
          OutlinedButton.icon(
            key: const Key('submitMockExamEarly'),
            onPressed: _busy ? null : _submitEarly,
            icon: const Icon(Icons.stop_circle_outlined),
            label: const Text('提前交卷'),
          ),
        ],
        if (waiting || completed) ...[
          ShanganNotice(
            title: completed ? '考试已完成' : '等待上传试卷',
            message: completed
                ? '已上传 ${session.attachments.length} 张试卷照片，可继续补充其他页面。'
                : '上传第一张合法试卷照片后，本次模拟考试才会记为完成。',
            tone: completed ? ShanganTagTone.success : ShanganTagTone.warning,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            key: const Key('uploadExamPhoto'),
            onPressed: _busy || session.attachments.length >= 9
                ? null
                : _choosePhotoSource,
            icon: const Icon(Icons.add_a_photo_outlined),
            label: Text(
              _busy ? '正在上传' : '上传试卷照片（${session.attachments.length}/9）',
            ),
          ),
        ],
        if (session.attachments.isNotEmpty) ...[
          const SizedBox(height: 22),
          const Divider(color: ShanganColors.ink, thickness: 2),
          const SizedBox(height: 10),
          ...session.attachments.indexed.map(
            (entry) => ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: ShanganColors.greenSoft,
                  border: Border.all(color: ShanganColors.green),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Text('${entry.$1 + 1}'.padLeft(2, '0')),
              ),
              title: Text(entry.$2.filename),
              subtitle: const Text('已安全保存'),
              trailing: const Icon(
                Icons.verified_outlined,
                color: ShanganColors.green,
              ),
            ),
          ),
        ],
      ],
    );
  }

  Future<void> _submitEarly() async {
    final session = _session;
    if (session == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('确认提前交卷？'),
        content: const Text('交卷后倒计时立即结束，不能恢复；接下来需要上传试卷照片。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('继续考试'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('确认交卷'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _busy = true);
    try {
      final next = await ref
          .read(mockExamRepositoryProvider)
          .submitEarly(session.id);
      _accept(next);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  Future<void> _choosePhotoSource() async {
    final camera = await showModalBottomSheet<bool>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              minTileHeight: 56,
              leading: const Icon(Icons.camera_alt_outlined),
              title: const Text('拍摄试卷'),
              onTap: () => Navigator.pop(sheetContext, true),
            ),
            ListTile(
              minTileHeight: 56,
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('从相册选择'),
              onTap: () => Navigator.pop(sheetContext, false),
            ),
          ],
        ),
      ),
    );
    if (camera == null) return;
    try {
      final photo = await _photoPicker.pick(camera: camera);
      if (photo == null || !mounted) return;
      final session = _session;
      if (session == null) return;
      setState(() => _busy = true);
      final next = await ref
          .read(mockExamRepositoryProvider)
          .upload(session.id, photo.filename, photo.bytes);
      _accept(next);
    } catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('试卷照片上传失败，请重试')));
    }
  }
}

String _statusLabel(String status) => switch (status) {
  'RUNNING' => '考试中',
  'AWAITING_UPLOAD' => '待传试卷',
  'COMPLETED' => '已完成',
  _ => status,
};

String _formatDuration(Duration value) {
  final totalSeconds = value.inSeconds;
  final hours = totalSeconds ~/ 3600;
  final minutes = totalSeconds ~/ 60 % 60;
  final seconds = totalSeconds % 60;
  return '${hours.toString().padLeft(2, '0')}:'
      '${minutes.toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}';
}

String _formatDateTime(DateTime value) =>
    '${value.month.toString().padLeft(2, '0')}月'
    '${value.day.toString().padLeft(2, '0')}日 '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
