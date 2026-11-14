import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../island/engine.dart';
import '../island/geometry.dart';
import '../island/path.dart';

enum IslandSlot { leading, trailing, expanded, bubble, minimal }

class IslandView extends StatefulWidget {
  final IslandEngine engine;
  final Widget Function(ActivitySpec spec, IslandSlot slot) builder;

  const IslandView({super.key, required this.engine, required this.builder});

  @override
  State<IslandView> createState() => _IslandViewState();
}

class _IslandViewState extends State<IslandView> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  int _token = -1;
  double _revealAt = 0;

  IslandEngine get engine => widget.engine;

  @override
  void initState() {
    super.initState();
    engine.frame.addListener(_onFrame);
    engine.addListener(_onFrame);
    _ticker = createTicker((elapsed) {
      engine.sync(elapsed.inMicroseconds / 1e6);
    })..start();
  }

  void _onFrame() {
    if (mounted) setState(() {});
  }

  @override
  void didUpdateWidget(IslandView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.engine != engine) {
      oldWidget.engine.frame.removeListener(_onFrame);
      oldWidget.engine.removeListener(_onFrame);
      engine.frame.addListener(_onFrame);
      engine.addListener(_onFrame);
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    engine.frame.removeListener(_onFrame);
    engine.removeListener(_onFrame);
    super.dispose();
  }

  double _reveal() {
    if (_token != engine.fadeToken) {
      _token = engine.fadeToken;
      _revealAt = engine.now;
    }
    final t = engine.now - _revealAt;
    if (engine.reduced) return (t / 0.2).clamp(0.0, 1.0);
    if (t < 0.16) return 0;
    return ((t - 0.16) / 0.22).clamp(0.0, 1.0);
  }

  @override
  Widget build(BuildContext context) {
    final reveal = _reveal();
    final scale = 0.92 + 0.08 * reveal;
    final pill = engine.pillRect;
    final bubble = engine.bubbleRect;
    final hole = engine.hole.hole;
    final sensor = engine.hole.sensor;
    final smoothing = engine.metrics.cornerSmoothing;
    final pillPath = continuousRect(pill, math.min(engine.radius.pos, pill.shortestSide / 2), smoothing);
    final sensorPath = continuousRect(sensor, sensor.height / 2, smoothing);
    final bubblePath = bubble == null ? null : continuousRect(bubble, bubble.height / 2, smoothing);
    final neck = bubble == null ? null : bridgeNeck(pill, bubble, maxGap: engine.metrics.bridgeMaxGap);

    return SizedBox.expand(
      child: Stack(
        children: [
          IgnorePointer(
            ignoring: engine.kind != PresentationKind.expanded,
            child: Stack(children: [
          CustomPaint(
            painter: _IslandPainter(
              pill: pillPath,
              sensor: sensorPath,
              bubble: bubblePath,
              neck: neck,
              expand: ((engine.height.pos - engine.metrics.compactHeight) / (engine.metrics.expandedMin - engine.metrics.compactHeight)).clamp(0.0, 1.0),
              keyline: _keyline(pill.width),
              scale: engine.scale.pos,
              center: pill.center,
            ),
            size: Size.infinite,
          ),
          ..._content(pill, bubble, hole, reveal, engine.reduced ? 1 : scale),
            ]),
          ),
          if (engine.kind != PresentationKind.expanded && pill.width > 1)
            _hit(pill, 'pill', engine.primary?.label ?? 'Dynamic Island'),
          if (engine.kind != PresentationKind.expanded && bubble != null) _hit(bubble, 'bubble', engine.secondary?.label ?? 'Second activity'),
        ],
      ),
    );
  }

  double _keyline(double width) {
    final extra = ((width - engine.metrics.idleWidth) / 6).clamp(0.0, 1.0);
    final dim = engine.kind == PresentationKind.expanded ? 0.93 : 1.0;
    if (!engine.showKeyline) return 0;
    return engine.metrics.keylineAlpha * extra * dim;
  }

  Widget _hit(Rect rect, String target, String label) {
    return Positioned.fromRect(
      rect: rect.inflate(6),
      child: Semantics(
        button: true,
        label: label,
        hint: 'Long press to expand',
        child: Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (_) => engine.pointerDown(target),
          onPointerUp: (_) => engine.pointerUp(),
          onPointerCancel: (_) => engine.pointerUp(),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }

  List<Widget> _content(Rect pill, Rect? bubble, Rect hole, double reveal, double scale) {
    final primary = engine.primary;
    if (primary == null || engine.kind == PresentationKind.idle) return const [];
    final focus = engine.expandedId != null ? engine.spec(engine.expandedId!) : primary;
    Widget fade(Widget child) {
      return Opacity(
        opacity: reveal,
        child: Transform.scale(scale: scale, alignment: Alignment.center, child: child),
      );
    }

    if (engine.kind == PresentationKind.expanded && focus != null) {
      final clip = _localClip(pill, hole);
      return [
        Positioned.fromRect(
          rect: pill,
          child: ClipPath(
            clipper: _PathClipper(clip),
            child: fade(widget.builder(focus, IslandSlot.expanded)),
          ),
        ),
      ];
    }

    if (engine.kind == PresentationKind.minimal && bubble != null) {
      final second = engine.secondary;
      final attachedClip = _localClip(pill, hole);
      return [
        Positioned.fromRect(
          rect: pill,
          child: ClipPath(
            clipper: _PathClipper(attachedClip),
            child: fade(_keyed(engine.splitIsPair ? IslandSlot.leading : IslandSlot.minimal, primary, const Key('island-leading'))),
          ),
        ),
        if (second != null)
          Positioned.fromRect(
            rect: bubble,
            child: ClipPath(
              clipper: _PathClipper(continuousRect(bubble, bubble.height / 2, engine.metrics.cornerSmoothing).shift(-bubble.topLeft)),
              child: fade(_keyed(engine.splitIsPair ? IslandSlot.bubble : IslandSlot.minimal, second, const Key('island-bubble'))),
            ),
          ),
      ];
    }

    // Slots sit on the settled shape so glyphs stay beside the camera while the
    // shell is still springing open. The animated pill clips them until it arrives.
    final layout = engine.target.pill(engine.hole.centerX);
    final leading = _slotRect(layout, primary.leading, leading: true);
    final trailing = _slotRect(layout, primary.trailing, leading: false);
    final clip = _localClip(pill, hole);
    return [
      Positioned.fromRect(
        rect: pill,
        child: ClipPath(
          clipper: _PathClipper(clip),
          child: fade(Stack(
            children: [
              if (leading != null)
                Positioned.fromRect(
                  rect: leading.shift(-pill.topLeft),
                  child: KeyedSubtree(key: const Key('island-leading'), child: widget.builder(primary, IslandSlot.leading)),
                ),
              if (trailing != null)
                Positioned.fromRect(
                  rect: trailing.shift(-pill.topLeft),
                  child: KeyedSubtree(key: const Key('island-trailing'), child: Align(alignment: Alignment.centerRight, child: widget.builder(primary, IslandSlot.trailing))),
                ),
            ],
          )),
        ),
      ),
    ];
  }

  Widget _keyed(IslandSlot slot, ActivitySpec spec, Key key) {
    return KeyedSubtree(
      key: key,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 7),
        child: Align(alignment: Alignment.centerLeft, child: widget.builder(spec, slot)),
      ),
    );
  }

  Path _localClip(Rect bounds, Rect hole) {
    final shape = continuousRect(bounds, math.min(bounds.height / 2, engine.radius.pos), engine.metrics.cornerSmoothing).shift(-bounds.topLeft);
    final opening = Path()..addOval(hole.inflate(1).shift(-bounds.topLeft));
    return Path.combine(PathOperation.difference, shape, opening);
  }

  Rect? _slotRect(Rect pill, double slotWidth, {required bool leading}) {
    if (slotWidth <= 0) return null;
    final width = math.min(slotWidth, engine.metrics.maxSlotWidth);
    final height = math.min(22.0, math.max(8.0, pill.height - 8));
    final top = pill.center.dy - height / 2;
    final hole = engine.hole.hole.inflate(2);
    if (leading) {
      var left = pill.left + engine.metrics.edgePadding;
      if (left + width > hole.left) left = hole.left - width;
      return Rect.fromLTWH(left, top, width, height);
    }
    var left = pill.right - engine.metrics.edgePadding - width;
    if (left < hole.right) left = hole.right;
    return Rect.fromLTWH(left, top, width, height);
  }
}

class _PathClipper extends CustomClipper<Path> {
  final Path path;
  _PathClipper(this.path);

  @override
  Path getClip(Size size) => path;

  @override
  bool shouldReclip(covariant _PathClipper oldClipper) => true;
}

class _IslandPainter extends CustomPainter {
  final Path pill;
  final Path sensor;
  final Path? bubble;
  final Path? neck;
  final double expand;
  final double keyline;
  final double scale;
  final Offset center;

  _IslandPainter({
    required this.pill,
    required this.sensor,
    required this.bubble,
    required this.neck,
    required this.expand,
    required this.keyline,
    required this.scale,
    required this.center,
  });

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.scale(scale, scale);
    canvas.translate(-center.dx, -center.dy);
    if (expand > 0.02) {
      final shadow = Paint()
        ..color = Colors.black.withValues(alpha: 0.35 * expand)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 16);
      canvas.save();
      canvas.translate(0, 8 * expand);
      canvas.drawPath(pill, shadow);
      canvas.restore();
    }
    final black = Paint()..color = Colors.black;
    canvas.drawPath(sensor, black);
    canvas.drawPath(pill, black);
    if (bubble != null) canvas.drawPath(bubble!, black);
    if (neck != null) canvas.drawPath(neck!, black);
    if (keyline > 0.01) {
      final line = Paint()
        ..color = Colors.white.withValues(alpha: keyline)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5;
      canvas.drawPath(pill, line);
      if (bubble != null) canvas.drawPath(bubble!, line);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _IslandPainter oldDelegate) => true;
}

/// Keeps the side clock clear of the live pill.
class IslandClearance extends StatelessWidget {
  final IslandEngine engine;
  final Widget child;

  const IslandClearance({super.key, required this.engine, required this.child});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: engine.frame,
      builder: (context, _) {
        final bottom = math.max(engine.pillRect.bottom, engine.bubbleRect?.bottom ?? 0) + 12;
        return Padding(padding: EdgeInsets.only(top: bottom), child: child);
      },
    );
  }
}
