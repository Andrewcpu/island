import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../island/metrics.dart';
import '../island/spring.dart';
import '../theme.dart';

class TurnoverText extends StatefulWidget {
  final String text;
  final TextStyle style;
  final bool reduced;

  const TurnoverText(this.text, {super.key, required this.style, this.reduced = false});

  @override
  State<TurnoverText> createState() => _TurnoverTextState();
}

class _TurnoverTextState extends State<TurnoverText> with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  String _from = '';
  String _to = '';

  @override
  void initState() {
    super.initState();
    _to = widget.text;
    _from = widget.text;
    _controller = AnimationController(vsync: this, duration: Duration(milliseconds: widget.reduced ? 200 : 480));
    _controller.value = 1;
  }

  @override
  void didUpdateWidget(TurnoverText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text) {
      _from = oldWidget.text;
      _to = widget.text;
      _controller.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final from = _from.padLeft(math.max(_from.length, _to.length));
    final to = _to.padLeft(from.length);
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < to.length; i++)
              _Digit(
                from: from[i],
                to: to[i],
                t: _controller.value,
                style: widget.style,
                reduced: widget.reduced,
              ),
          ],
        );
      },
    );
  }
}

class _Digit extends StatelessWidget {
  final String from;
  final String to;
  final double t;
  final TextStyle style;
  final bool reduced;

  const _Digit({required this.from, required this.to, required this.t, required this.style, required this.reduced});

  @override
  Widget build(BuildContext context) {
    if (from == to || t >= 1) {
      return Text(to, style: style);
    }
    if (reduced) {
      return Opacity(opacity: t < 0.5 ? 1 - t * 2 : (t - 0.5) * 2, child: Text(t < 0.5 ? from : to, style: style));
    }
    final outT = (t / 0.54).clamp(0.0, 1.0);
    final outFade = ((t - 0.4) / 0.15).clamp(0.0, 1.0);
    final inT = ((t - 0.42) / 0.52).clamp(0.0, 1.0);
    final inFade = ((t - 0.42) / 0.33).clamp(0.0, 1.0);
    return Stack(
      alignment: Alignment.center,
      clipBehavior: Clip.none,
      children: [
        Opacity(
          opacity: 1 - outFade,
          child: Transform.scale(
            scale: 1 - 0.3 * outT,
            child: Text(from, style: style.copyWith(color: style.color?.withValues(alpha: (style.color?.a ?? 1) * (1 - outFade * 0.2)))),
          ),
        ),
        Opacity(
          opacity: inFade,
          child: Transform.scale(scale: 0.72 + 0.28 * inT, child: Text(to, style: style)),
        ),
      ],
    );
  }
}

String formatClock(Duration duration, {bool signed = false}) {
  final negative = duration.isNegative;
  final total = duration.inSeconds.abs();
  final minutes = total ~/ 60;
  final seconds = total % 60;
  final text = '$minutes:${seconds.toString().padLeft(2, '0')}';
  if (!negative) return text;
  return signed ? '−$text' : text;
}

class TickingText extends StatefulWidget {
  final DateTime? until;
  final DateTime? since;
  final Duration? fixed;
  final TextStyle style;
  final bool reduced;
  final String Function(Duration elapsed)? format;

  const TickingText({super.key, this.until, this.since, this.fixed, required this.style, this.reduced = false, this.format});

  @override
  State<TickingText> createState() => _TickingTextState();
}

class _TickingTextState extends State<TickingText> {
  late Duration _value;

  @override
  void initState() {
    super.initState();
    _value = _read();
    if (widget.fixed == null) {
      _arm();
    }
  }

  void _arm() {
    Future<void>.delayed(const Duration(seconds: 1), () {
      if (!mounted || widget.fixed != null) return;
      setState(() => _value = _read());
      _arm();
    });
  }

  Duration _read() {
    if (widget.fixed != null) return widget.fixed!;
    final now = DateTime.now();
    if (widget.until != null) return widget.until!.difference(now);
    if (widget.since != null) return now.difference(widget.since!);
    return Duration.zero;
  }

  @override
  void didUpdateWidget(TickingText oldWidget) {
    super.didUpdateWidget(oldWidget);
    _value = _read();
  }

  @override
  Widget build(BuildContext context) {
    final text = widget.format != null ? widget.format!(_value) : formatClock(_value);
    return TurnoverText(text, style: widget.style, reduced: widget.reduced);
  }
}

class SpringFill extends StatefulWidget {
  final double value;
  final Color color;
  final Color track;
  final double height;
  final bool reduced;

  const SpringFill({super.key, required this.value, required this.color, this.track = IslandColors.track, this.height = 6, this.reduced = false});

  @override
  State<SpringFill> createState() => _SpringFillState();
}

class _SpringFillState extends State<SpringFill> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final SpringTrack _track = SpringTrack(0);
  double _clock = 0;

  @override
  void initState() {
    super.initState();
    _track.pos = widget.value.clamp(0, 1);
    _track.goal = _track.pos;
    _ticker = createTicker(_onTick)..start();
  }

  void _onTick(Duration elapsed) {
    final t = elapsed.inMicroseconds / 1e6;
    _track.step(t);
    _clock = t;
    if (mounted) setState(() {});
  }

  @override
  void didUpdateWidget(SpringFill oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value) {
      _track.aim(
        widget.value.clamp(0, 1),
        widget.reduced ? const Motion.tween(0.2) : Motion.spring(Spring(IslandSprings.progress.response, IslandSprings.progress.damping)),
        _clock,
      );
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(widget.height),
      child: SizedBox(
        height: widget.height,
        child: Stack(
          fit: StackFit.expand,
          children: [
            ColoredBox(color: widget.track),
            FractionallySizedBox(
              alignment: Alignment.centerLeft,
              widthFactor: _track.pos.clamp(0, 1),
              child: ColoredBox(color: widget.color),
            ),
          ],
        ),
      ),
    );
  }
}

class ProgressRing extends StatelessWidget {
  final double value;
  final Color color;
  final double size;
  final Widget? child;
  final double stroke;

  const ProgressRing({super.key, required this.value, required this.color, this.size = 22, this.child, this.stroke = 2.4});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _RingPainter(value.clamp(0, 1), color, stroke),
        child: Center(child: child),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  final double value;
  final Color color;
  final double stroke;
  _RingPainter(this.value, this.color, this.stroke);

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final deflated = rect.deflate(stroke);
    final track = Paint()
      ..color = color.withValues(alpha: 0.28)
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke;
    final arc = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(deflated, 0, math.pi * 2, false, track);
    canvas.drawArc(deflated, -math.pi / 2, math.pi * 2 * value, false, arc);
  }

  @override
  bool shouldRepaint(covariant _RingPainter oldDelegate) => oldDelegate.value != value || oldDelegate.color != color;
}

class LevelMeter extends StatefulWidget {
  final int bars;
  final Color color;
  final double height;
  final double barWidth;
  final double gap;
  final bool active;
  final double level;
  final List<double>? envelope;

  const LevelMeter({
    super.key,
    this.bars = 6,
    required this.color,
    this.height = 17,
    this.barWidth = 2.4,
    this.gap = 1.8,
    this.active = true,
    this.level = 0.6,
    this.envelope,
  });

  @override
  State<LevelMeter> createState() => _LevelMeterState();
}

class _LevelMeterState extends State<LevelMeter> with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 900))..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final width = widget.bars * widget.barWidth + (widget.bars - 1) * widget.gap;
        return SizedBox(
          width: width,
          height: widget.height,
          child: CustomPaint(
            painter: _MeterPainter(
              bars: widget.bars,
              color: widget.color,
              t: widget.active ? _controller.value : 0,
              level: widget.active ? widget.level : 0.18,
              barWidth: widget.barWidth,
              gap: widget.gap,
              envelope: widget.envelope,
            ),
          ),
        );
      },
    );
  }
}

class _MeterPainter extends CustomPainter {
  final int bars;
  final Color color;
  final double t;
  final double level;
  final double barWidth;
  final double gap;
  final List<double>? envelope;

  _MeterPainter({
    required this.bars,
    required this.color,
    required this.t,
    required this.level,
    required this.barWidth,
    required this.gap,
    required this.envelope,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = color;
    for (var i = 0; i < bars; i++) {
      final env = envelope != null && i < envelope!.length ? envelope![i] : 0.45 + 0.55 * math.sin(i * 1.3);
      final wave = 0.55 + 0.45 * math.sin((t * math.pi * 2) + i * 0.9);
      final h = size.height * (0.18 + 0.82 * env * wave * level.clamp(0.15, 1));
      final x = i * (barWidth + gap);
      final rect = RRect.fromRectAndRadius(Rect.fromLTWH(x, size.height - h, barWidth, h), const Radius.circular(1.2));
      canvas.drawRRect(rect, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _MeterPainter oldDelegate) => true;
}

class RoundButton extends StatelessWidget {
  final String label;
  final Color color;
  final Color iconColor;
  final double size;
  final VoidCallback? onPressed;
  final Widget child;

  const RoundButton({
    super.key,
    required this.label,
    required this.color,
    required this.child,
    this.iconColor = Colors.white,
    this.size = 48,
    this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: onPressed,
        behavior: HitTestBehavior.opaque,
        child: Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          child: IconTheme(data: IconThemeData(color: iconColor, size: size * 0.46), child: child),
        ),
      ),
    );
  }
}

class Artwork extends StatelessWidget {
  final String seed;
  final double size;
  final double radius;

  const Artwork({super.key, required this.seed, this.size = 22, this.radius = 6});

  @override
  Widget build(BuildContext context) {
    final hash = seed.codeUnits.fold<int>(0, (sum, unit) => (sum * 33 + unit) & 0x7fffffff);
    final a = Color.fromARGB(255, 40 + hash % 160, 50 + (hash >> 4) % 140, 80 + (hash >> 8) % 150);
    final b = Color.fromARGB(255, 20 + (hash >> 12) % 80, 30 + (hash >> 16) % 90, 40 + (hash >> 3) % 70);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [a, b]),
      ),
    );
  }
}

class InitialAvatar extends StatelessWidget {
  final String name;
  final double size;

  const InitialAvatar({super.key, required this.name, this.size = 22});

  @override
  Widget build(BuildContext context) {
    final parts = name.trim().split(RegExp(r'\s+')).where((part) => part.isNotEmpty).toList();
    final letters = parts.isEmpty
        ? '?'
        : parts.length == 1
            ? parts.first.characters.first.toUpperCase()
            : '${parts.first.characters.first}${parts.last.characters.first}'.toUpperCase();
    final hash = name.codeUnits.fold<int>(0, (sum, unit) => sum + unit);
    final hue = (hash % 360).toDouble();
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(shape: BoxShape.circle, color: HSLColor.fromAHSL(1, hue, 0.45, 0.42).toColor()),
      child: Text(letters, style: TextStyle(color: Colors.white, fontSize: size * 0.36, fontWeight: FontWeight.w600)),
    );
  }
}

class PillButton extends StatelessWidget {
  final String label;
  final Color color;
  final Color textColor;
  final VoidCallback? onPressed;

  const PillButton({super.key, required this.label, required this.onPressed, this.color = IslandColors.fill, this.textColor = Colors.white});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: onPressed,
        child: Container(
          height: 40,
          alignment: Alignment.center,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(20)),
          child: Text(label, style: TextStyle(color: textColor, fontSize: 15, fontWeight: FontWeight.w600)),
        ),
      ),
    );
  }
}
