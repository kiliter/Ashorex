import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Codex v2 精灵表：8×11 格，每格 192×208。
const petAtlasAsset = 'assets/pets/maoxian-tuantuan/spritesheet.webp';
const petCellSize = Size(192, 208);
const petDisplaySize = Size(96, 104);

/// 伙伴用到的动作行。帧数按精灵表实际占用格，空格不播。
enum PetPose {
  idle(row: 0, frames: 6, frameMs: 360, loop: true),
  runRight(row: 1, frames: 8, frameMs: 160, loop: true),
  runLeft(row: 2, frames: 8, frameMs: 160, loop: true),
  wave(row: 3, frames: 4, frameMs: 280, loop: false),
  jumping(row: 4, frames: 5, frameMs: 160, loop: true),
  lookRight(row: 9, frames: 1, frameMs: 1000, loop: false, column: 4),
  lookDown(row: 10, frames: 1, frameMs: 1000, loop: false, column: 0),
  lookLeft(row: 10, frames: 1, frameMs: 1000, loop: false, column: 4);

  const PetPose({
    required this.row,
    required this.frames,
    required this.frameMs,
    required this.loop,
    this.column = 0,
  });

  final int row;
  final int frames;
  final int frameMs;
  final bool loop;
  final int column;
}

/// 拖动按主轴选动作：左右跑、往上跳、往下看。
PetPose petPoseForDrag(Offset delta) {
  if (delta.dy.abs() > delta.dx.abs()) {
    return delta.dy < 0 ? PetPose.jumping : PetPose.lookDown;
  }
  return delta.dx < 0 ? PetPose.runLeft : PetPose.runRight;
}

ui.Image? _atlas;
Future<ui.Image>? _atlasFuture;

/// 整表只解码一次，避免每个伙伴实例重复读 2.5MB WebP。
Future<ui.Image> loadPetAtlas() {
  final cached = _atlas;
  if (cached != null) return Future.value(cached);
  return _atlasFuture ??= () async {
    final data = await rootBundle.load(petAtlasAsset);
    final codec = await ui.instantiateImageCodec(
      data.buffer.asUint8List(),
      targetWidth: 1536,
      targetHeight: 2288,
    );
    final frame = await codec.getNextFrame();
    _atlas = frame.image;
    return frame.image;
  }();
}

/// 从精灵表裁切当前动作帧。帧推进用 Timer，不用 repeat()。
final class PetSprite extends StatefulWidget {
  const PetSprite({required this.pose, super.key, this.onCycleFinished});

  final PetPose pose;
  final VoidCallback? onCycleFinished;

  @override
  State<PetSprite> createState() => _PetSpriteState();
}

final class _PetSpriteState extends State<PetSprite> {
  ui.Image? _image;
  int _frame = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void didUpdateWidget(PetSprite oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.pose != widget.pose) {
      _frame = 0;
      _restartTimer();
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _restartTimer();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final image = await loadPetAtlas();
    if (!mounted) return;
    setState(() => _image = image);
    _restartTimer();
  }

  bool get _reduceMotion => MediaQuery.disableAnimationsOf(context);

  void _restartTimer() {
    _timer?.cancel();
    if (!mounted ||
        _image == null ||
        _reduceMotion ||
        widget.pose.frames <= 1) {
      return;
    }
    _timer = Timer.periodic(Duration(milliseconds: widget.pose.frameMs), (_) {
      if (!mounted) return;
      setState(() {
        final next = _frame + 1;
        if (next >= widget.pose.frames) {
          if (widget.pose.loop) {
            _frame = 0;
          } else {
            _frame = widget.pose.frames - 1;
            widget.onCycleFinished?.call();
          }
        } else {
          _frame = next;
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final image = _image;
    return SizedBox(
      width: petDisplaySize.width,
      height: petDisplaySize.height,
      child: image == null
          ? const SizedBox.expand()
          : CustomPaint(
              painter: _PetCellPainter(
                image: image,
                row: widget.pose.row,
                column: widget.pose.column + (_reduceMotion ? 0 : _frame),
              ),
            ),
    );
  }
}

final class _PetCellPainter extends CustomPainter {
  const _PetCellPainter({
    required this.image,
    required this.row,
    required this.column,
  });

  final ui.Image image;
  final int row;
  final int column;

  @override
  void paint(Canvas canvas, Size size) {
    final src = Rect.fromLTWH(
      column * petCellSize.width,
      row * petCellSize.height,
      petCellSize.width,
      petCellSize.height,
    );
    canvas.drawImageRect(
      image,
      src,
      Offset.zero & size,
      Paint()..filterQuality = FilterQuality.medium,
    );
  }

  @override
  bool shouldRepaint(_PetCellPainter oldDelegate) =>
      oldDelegate.image != image ||
      oldDelegate.row != row ||
      oldDelegate.column != column;
}
