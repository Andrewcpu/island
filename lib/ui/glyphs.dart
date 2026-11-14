import 'dart:math' as math;

import 'package:flutter/material.dart';

enum GlyphId {
  timer,
  phone,
  hangup,
  pause,
  play,
  close,
  check,
  upload,
  battery,
  bolt,
  mic,
  stop,
  car,
  package,
  message,
  calendar,
  note,
  back,
  forward,
  speaker,
  person,
  arrowUp,
  arrowDown,
}

class Glyph extends StatelessWidget {
  final GlyphId id;
  final double size;
  final Color color;
  final bool filled;

  const Glyph(this.id, {super.key, this.size = 22, this.color = Colors.white, this.filled = false});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(painter: _GlyphPainter(id, color)),
    );
  }
}

class _GlyphPainter extends CustomPainter {
  final GlyphId id;
  final Color color;
  _GlyphPainter(this.id, this.color);

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 24;
    canvas.save();
    canvas.scale(s, s);
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final fill = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    switch (id) {
      case GlyphId.timer:
        canvas.drawCircle(const Offset(12, 13), 7.2, stroke);
        canvas.drawLine(const Offset(12, 13), const Offset(12, 9.2), stroke);
        canvas.drawLine(const Offset(12, 13), const Offset(14.6, 14.6), stroke);
        canvas.drawLine(const Offset(9.2, 3.2), const Offset(14.8, 3.2), stroke);
      case GlyphId.phone:
        final phone = Path()
          ..moveTo(8, 5.5)
          ..cubicTo(8, 5.5, 10, 5.2, 11.2, 7.2)
          ..cubicTo(12.2, 8.8, 11.4, 10.2, 12.6, 11.6)
          ..cubicTo(13.8, 13, 15.4, 12.4, 17, 13.4)
          ..cubicTo(19, 14.6, 18.6, 16.6, 18.6, 16.6)
          ..cubicTo(16.2, 20.2, 8.2, 14.2, 6.4, 10.2)
          ..cubicTo(5.2, 7.4, 8, 5.5, 8, 5.5)
          ..close();
        canvas.drawPath(phone, fill);
      case GlyphId.hangup:
        canvas.save();
        canvas.translate(12, 12);
        canvas.rotate(-0.9);
        final hand = RRect.fromRectAndRadius(const Rect.fromLTWH(-8, -3.2, 16, 6.4), const Radius.circular(3));
        canvas.drawRRect(hand, fill);
        canvas.restore();
      case GlyphId.pause:
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(6, 5, 4, 14), const Radius.circular(1.4)), fill);
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(14, 5, 4, 14), const Radius.circular(1.4)), fill);
      case GlyphId.play:
        final play = Path()
          ..moveTo(8, 5)
          ..lineTo(19, 12)
          ..lineTo(8, 19)
          ..close();
        canvas.drawPath(play, fill);
      case GlyphId.close:
        canvas.drawLine(const Offset(7, 7), const Offset(17, 17), stroke);
        canvas.drawLine(const Offset(17, 7), const Offset(7, 17), stroke);
      case GlyphId.check:
        canvas.drawLine(const Offset(6, 12.5), const Offset(10.2, 17), stroke..strokeWidth = 2.4);
        canvas.drawLine(const Offset(10.2, 17), const Offset(18, 7.5), stroke);
      case GlyphId.upload:
        canvas.drawLine(const Offset(12, 15.5), const Offset(12, 5.5), stroke);
        canvas.drawLine(const Offset(7.5, 9.5), const Offset(12, 5), stroke);
        canvas.drawLine(const Offset(16.5, 9.5), const Offset(12, 5), stroke);
        canvas.drawLine(const Offset(5.5, 19), const Offset(18.5, 19), stroke);
      case GlyphId.bolt:
        final bolt = Path()
          ..moveTo(13.2, 3)
          ..lineTo(6.5, 13.2)
          ..lineTo(11.2, 13.2)
          ..lineTo(10.4, 21)
          ..lineTo(17.6, 10.2)
          ..lineTo(12.6, 10.2)
          ..close();
        canvas.drawPath(bolt, fill);
      case GlyphId.mic:
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(9, 3.5, 6, 10), const Radius.circular(3)), stroke);
        canvas.drawArc(const Rect.fromLTWH(6.5, 8, 11, 9), 0, math.pi, false, stroke);
        canvas.drawLine(const Offset(12, 17.2), const Offset(12, 20.2), stroke);
      case GlyphId.stop:
        canvas.drawCircle(const Offset(12, 12), 8, stroke);
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(8.2, 8.2, 7.6, 7.6), const Radius.circular(1.5)), fill);
      case GlyphId.car:
        final car = Path()
          ..moveTo(4, 14)
          ..lineTo(6, 10)
          ..cubicTo(7, 8.2, 8.5, 7.5, 12, 7.5)
          ..cubicTo(15.5, 7.5, 17, 8.2, 18, 10)
          ..lineTo(20, 14)
          ..lineTo(20, 17.5)
          ..lineTo(4, 17.5)
          ..close();
        canvas.drawPath(car, stroke);
        canvas.drawCircle(const Offset(8, 17.2), 1.6, fill);
        canvas.drawCircle(const Offset(16, 17.2), 1.6, fill);
      case GlyphId.package:
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(4.5, 7, 15, 12), const Radius.circular(2)), stroke);
        canvas.drawLine(const Offset(4.5, 11), const Offset(19.5, 11), stroke);
        canvas.drawLine(const Offset(12, 11), const Offset(12, 19), stroke);
      case GlyphId.message:
        final bubble = Path()
          ..addRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(3.5, 4.5, 17, 12), const Radius.circular(4)))
          ..moveTo(8, 16.5)
          ..lineTo(7, 20)
          ..lineTo(13, 16.5);
        canvas.drawPath(bubble, stroke);
      case GlyphId.calendar:
        canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(4.5, 5.5, 15, 14), const Radius.circular(2)), stroke);
        canvas.drawLine(const Offset(4.5, 9.5), const Offset(19.5, 9.5), stroke);
        canvas.drawLine(const Offset(8, 3.5), const Offset(8, 7), stroke);
        canvas.drawLine(const Offset(16, 3.5), const Offset(16, 7), stroke);
      case GlyphId.note:
        canvas.drawLine(const Offset(7, 17), const Offset(17, 7), stroke);
        canvas.drawCircle(const Offset(8.2, 15.8), 1.3, fill);
        canvas.drawCircle(const Offset(15.8, 8.2), 1.3, fill);
      case GlyphId.back:
        final back = Path()
          ..moveTo(14, 6)
          ..lineTo(8, 12)
          ..lineTo(14, 18)
          ..moveTo(10, 12)
          ..lineTo(18, 12);
        canvas.drawPath(back, stroke);
      case GlyphId.forward:
        final forward = Path()
          ..moveTo(10, 6)
          ..lineTo(16, 12)
          ..lineTo(10, 18)
          ..moveTo(14, 12)
          ..lineTo(6, 12);
        canvas.drawPath(forward, stroke);
      case GlyphId.speaker:
        final speaker = Path()
          ..moveTo(5, 10)
          ..lineTo(9, 10)
          ..lineTo(13, 6.5)
          ..lineTo(13, 17.5)
          ..lineTo(9, 14)
          ..lineTo(5, 14)
          ..close();
        canvas.drawPath(speaker, fill);
        canvas.drawArc(const Rect.fromLTWH(12, 8, 6, 8), -0.8, 1.6, false, stroke);
      case GlyphId.person:
        canvas.drawCircle(const Offset(12, 8), 3.1, stroke);
        canvas.drawArc(const Rect.fromLTWH(5.5, 12, 13, 10), math.pi, math.pi, false, stroke);
      case GlyphId.arrowUp:
        canvas.drawLine(const Offset(12, 18), const Offset(12, 6), stroke);
        canvas.drawLine(const Offset(7, 10.5), const Offset(12, 5.5), stroke);
        canvas.drawLine(const Offset(17, 10.5), const Offset(12, 5.5), stroke);
      case GlyphId.arrowDown:
        canvas.drawLine(const Offset(12, 6), const Offset(12, 18), stroke);
        canvas.drawLine(const Offset(7, 13.5), const Offset(12, 18.5), stroke);
        canvas.drawLine(const Offset(17, 13.5), const Offset(12, 18.5), stroke);
      case GlyphId.battery:
        break;
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _GlyphPainter oldDelegate) => oldDelegate.id != id || oldDelegate.color != color;
}

class BatteryGlyph extends StatelessWidget {
  final double level;
  final bool charging;
  final Color color;
  final double width;

  const BatteryGlyph({super.key, required this.level, required this.charging, required this.color, this.width = 28});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      height: width * 0.48,
      child: CustomPaint(
        painter: _BatteryPainter(level.clamp(0, 1), charging, color),
      ),
    );
  }
}

class _BatteryPainter extends CustomPainter {
  final double level;
  final bool charging;
  final Color color;
  _BatteryPainter(this.level, this.charging, this.color);

  @override
  void paint(Canvas canvas, Size size) {
    final body = RRect.fromRectAndRadius(Rect.fromLTWH(0, size.height * 0.08, size.width * 0.86, size.height * 0.84), Radius.circular(size.height * 0.22));
    final nub = RRect.fromRectAndRadius(
      Rect.fromLTWH(size.width * 0.88, size.height * 0.32, size.width * 0.1, size.height * 0.36),
      Radius.circular(2),
    );
    final outline = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;
    canvas.drawRRect(body, outline);
    canvas.drawRRect(nub, Paint()..color = color);
    final inset = 2.2;
    final fillWidth = (size.width * 0.86 - inset * 2) * level;
    if (fillWidth > 0.5) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(inset, size.height * 0.08 + inset, fillWidth, size.height * 0.84 - inset * 2),
          const Radius.circular(2),
        ),
        Paint()..color = color,
      );
    }
    if (charging) {
      final bolt = Path()
        ..moveTo(size.width * 0.48, size.height * 0.22)
        ..lineTo(size.width * 0.34, size.height * 0.54)
        ..lineTo(size.width * 0.46, size.height * 0.54)
        ..lineTo(size.width * 0.40, size.height * 0.8)
        ..lineTo(size.width * 0.58, size.height * 0.44)
        ..lineTo(size.width * 0.46, size.height * 0.44)
        ..close();
      canvas.drawPath(bolt, Paint()..color = const Color(0xFF10210F));
    }
  }

  @override
  bool shouldRepaint(covariant _BatteryPainter oldDelegate) => oldDelegate.level != level || oldDelegate.charging != charging || oldDelegate.color != color;
}
