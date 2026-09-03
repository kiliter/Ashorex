import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:shangan_ios/core/auth/auth_controller.dart';
import 'package:shangan_ios/features/companion/presentation/companion_fullscreen.dart';
import 'package:shangan_ios/features/companion/presentation/pet_sprite.dart';

/// 登录后把学习伙伴叠在整棵路由树上；未登录只渲染子页。
final class StudyCompanionHost extends StatelessWidget {
  const StudyCompanionHost({
    required this.authController,
    required this.child,
    super.key,
  });

  final AuthController authController;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: authController,
      builder: (context, _) {
        final show = authController.state.status == AuthStatus.authenticated;
        return Stack(
          fit: StackFit.expand,
          children: [child, if (show) const StudyCompanionOverlay()],
        );
      },
    );
  }
}

/// 可拖动的「毛线团团」。全屏播放时贴边，点击只挥手。
final class StudyCompanionOverlay extends StatefulWidget {
  const StudyCompanionOverlay({super.key});

  @override
  State<StudyCompanionOverlay> createState() => _StudyCompanionOverlayState();
}

final class _StudyCompanionOverlayState extends State<StudyCompanionOverlay>
    with SingleTickerProviderStateMixin {
  static const _peekExtent = 44.0;
  static const _dragSlop = 8.0;

  Offset? _freeOffset;
  Offset _offset = Offset.zero;
  bool _placed = false;
  bool _docked = false;
  bool _dockLeft = false;
  bool _dragging = false;
  PetPose _pose = PetPose.idle;
  Timer? _redock;

  late final AnimationController _move;
  Animation<Offset> _moveAnim = const AlwaysStoppedAnimation(Offset.zero);

  @override
  void initState() {
    super.initState();
    _move =
        AnimationController(
          vsync: this,
          duration: const Duration(milliseconds: 720),
        )..addListener(() {
          if (!mounted) return;
          setState(() => _offset = _moveAnim.value);
        });
    videoFullscreenListenable.addListener(_onFullscreenChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _offset = _defaultOffset();
      _freeOffset = _offset;
      setState(() => _placed = true);
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_placed) {
      _offset = _clamp(_offset, docked: _docked, dockLeft: _dockLeft);
    }
  }

  @override
  void dispose() {
    _redock?.cancel();
    videoFullscreenListenable.removeListener(_onFullscreenChanged);
    _move.dispose();
    super.dispose();
  }

  Size get _screen => MediaQuery.sizeOf(context);

  EdgeInsets get _padding => MediaQuery.paddingOf(context);

  bool get _fullscreen => videoFullscreenListenable.value;

  bool get _reduceMotion => MediaQuery.disableAnimationsOf(context);

  /// 默认停在右下，避开底部导航。
  Offset _defaultOffset() {
    final size = _screen;
    final padding = _padding;
    return Offset(
      size.width - padding.right - petDisplaySize.width - 12,
      size.height - padding.bottom - petDisplaySize.height - 92,
    );
  }

  Offset _clamp(Offset offset, {required bool docked, required bool dockLeft}) {
    final size = _screen;
    final padding = _padding;
    final minY = padding.top + 8;
    final maxY = size.height - padding.bottom - petDisplaySize.height - 8;
    final y = offset.dy.clamp(minY, math.max(minY, maxY)).toDouble();
    if (docked) {
      final x = dockLeft
          ? -petDisplaySize.width + _peekExtent
          : size.width - _peekExtent;
      return Offset(x, y);
    }
    final minX = padding.left + 8;
    final maxX = size.width - padding.right - petDisplaySize.width - 8;
    return Offset(offset.dx.clamp(minX, math.max(minX, maxX)).toDouble(), y);
  }

  void _onFullscreenChanged() {
    if (!mounted) return;
    if (_fullscreen) {
      _dockToNearestEdge();
    } else {
      _redock?.cancel();
      final rest = _freeOffset ?? _defaultOffset();
      _animateTo(rest, docked: false, pose: PetPose.idle);
    }
  }

  void _dockToNearestEdge() {
    final centerX = _offset.dx + petDisplaySize.width / 2;
    final dockLeft = centerX < _screen.width / 2;
    final target = _clamp(_offset, docked: true, dockLeft: dockLeft);
    _animateTo(
      target,
      docked: true,
      dockLeft: dockLeft,
      pose: dockLeft ? PetPose.lookRight : PetPose.lookLeft,
    );
  }

  void _animateTo(
    Offset target, {
    required bool docked,
    bool? dockLeft,
    required PetPose pose,
  }) {
    final nextDockLeft = dockLeft ?? _dockLeft;
    final end = _clamp(target, docked: docked, dockLeft: nextDockLeft);
    _docked = docked;
    _dockLeft = nextDockLeft;
    _pose = pose;
    if (_reduceMotion) {
      setState(() => _offset = end);
      return;
    }
    _moveAnim = Tween<Offset>(
      begin: _offset,
      end: end,
    ).animate(CurvedAnimation(parent: _move, curve: Curves.easeOutCubic));
    _move.forward(from: 0);
  }

  void _scheduleRedock() {
    _redock?.cancel();
    if (!_fullscreen) return;
    _redock = Timer(const Duration(seconds: 4), () {
      if (!mounted || !_fullscreen || _dragging) return;
      _dockToNearestEdge();
    });
  }

  void _onPanStart(DragStartDetails details) {
    _dragging = false;
    _redock?.cancel();
    if (_move.isAnimating) _move.stop();
  }

  void _onPanUpdate(DragUpdateDetails details) {
    final delta = details.delta;
    if (!_dragging && delta.distance < _dragSlop) return;
    if (!_dragging) {
      _dragging = true;
      _docked = false;
    }
    final next = Offset(_offset.dx + delta.dx, _offset.dy + delta.dy);
    setState(() {
      _offset = _clamp(next, docked: false, dockLeft: _dockLeft);
      _pose = petPoseForDrag(delta);
    });
  }

  void _onPanEnd(DragEndDetails details) {
    if (_dragging) {
      _dragging = false;
      _freeOffset = _offset;
      setState(() => _pose = PetPose.idle);
      if (_fullscreen) _scheduleRedock();
      return;
    }
    _onTap();
  }

  void _onTap() {
    if (_docked) {
      final inset = _clamp(
        Offset(
          _dockLeft ? 12 : _screen.width - petDisplaySize.width - 12,
          _offset.dy,
        ),
        docked: false,
        dockLeft: _dockLeft,
      );
      _freeOffset = inset;
      _animateTo(inset, docked: false, pose: PetPose.wave);
      _scheduleRedock();
      return;
    }
    setState(() => _pose = PetPose.wave);
  }

  @override
  Widget build(BuildContext context) {
    if (!_placed) return const SizedBox.shrink();
    final visibleWidth = _docked ? _peekExtent : petDisplaySize.width;
    return Positioned(
      left: _offset.dx,
      top: _offset.dy,
      width: visibleWidth,
      height: petDisplaySize.height,
      child: GestureDetector(
        key: const Key('studyCompanion'),
        onPanStart: _onPanStart,
        onPanUpdate: _onPanUpdate,
        onPanEnd: _onPanEnd,
        behavior: HitTestBehavior.opaque,
        child: Semantics(
          button: true,
          label: '学习伙伴 毛线团团',
          child: ClipRect(
            child: Align(
              alignment: _docked
                  ? (_dockLeft ? Alignment.centerRight : Alignment.centerLeft)
                  : Alignment.center,
              widthFactor: visibleWidth / petDisplaySize.width,
              child: PetSprite(
                pose: _pose,
                onCycleFinished: () {
                  if (!mounted || _pose != PetPose.wave) return;
                  setState(() => _pose = PetPose.idle);
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}
