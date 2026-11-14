import 'dart:math' as math;
import 'dart:ui';

class _Ease {
  final double p;
  final double a;
  final double b;
  final double along;
  final double across;
  const _Ease(this.p, this.a, this.b, this.along, this.across);
}

_Ease _edgeEase(double r, double smoothing, double room) {
  final s = math.max(0.0, math.min(smoothing, r <= 0 ? 0 : room / r - 1));
  final angle = (math.pi / 4) * s;
  final p = (1 + s) * r;
  final along = r * (1 - math.sin(angle));
  final across = r * (1 - math.cos(angle));
  final c = r * math.tan(angle / 2) * math.cos(angle);
  final b = math.max(0.0, (p - c - along) / 3);
  return _Ease(p, 2 * b, b, along, across);
}

/// Continuous corner used by the island: a circular arc eased into the straight edges.
Path continuousRect(Rect rect, double radius, double smoothing, {double? leading}) {
  final w = math.max(0.0, rect.width);
  final h = math.max(0.0, rect.height);
  final path = Path();
  if (w == 0 || h == 0) return path;
  final r = math.max(0.0, math.min(radius, math.min(w / 2, h / 2)));
  final rl = math.max(0.0, math.min(leading ?? radius, math.min(w / 2, h / 2)));
  if (r < 1e-3 && rl < 1e-3) {
    path.addRect(rect);
    return path;
  }
  final across = _edgeEase(r, smoothing, w / 2);
  final down = _edgeEase(r, smoothing, h / 2);
  final acrossL = rl == r ? across : _edgeEase(rl, smoothing, w / 2);
  final downL = rl == r ? down : _edgeEase(rl, smoothing, h / 2);
  final x = rect.left;
  final y = rect.top;
  final right = x + w;
  final bottom = y + h;

  void corner(Offset k, Offset u, Offset v, _Ease inc, _Ease out, double rc) {
    path.lineTo(k.dx + u.dx * inc.p, k.dy + u.dy * inc.p);
    path.cubicTo(
      k.dx + u.dx * (inc.p - inc.a),
      k.dy + u.dy * (inc.p - inc.a),
      k.dx + u.dx * (inc.p - inc.a - inc.b),
      k.dy + u.dy * (inc.p - inc.a - inc.b),
      k.dx + u.dx * inc.along + v.dx * inc.across,
      k.dy + u.dy * inc.along + v.dy * inc.across,
    );
    path.arcToPoint(
      Offset(k.dx + v.dx * out.along + u.dx * out.across, k.dy + v.dy * out.along + u.dy * out.across),
      radius: Radius.circular(rc),
      clockwise: true,
    );
    path.cubicTo(
      k.dx + v.dx * (out.p - out.a - out.b),
      k.dy + v.dy * (out.p - out.a - out.b),
      k.dx + v.dx * (out.p - out.a),
      k.dy + v.dy * (out.p - out.a),
      k.dx + v.dx * out.p,
      k.dy + v.dy * out.p,
    );
  }

  path.moveTo(x + acrossL.p, y);
  corner(Offset(right, y), const Offset(-1, 0), const Offset(0, 1), across, down, r);
  corner(Offset(right, bottom), const Offset(0, -1), const Offset(-1, 0), down, across, r);
  corner(Offset(x, bottom), const Offset(1, 0), const Offset(0, -1), acrossL, downL, rl);
  corner(Offset(x, y), const Offset(0, 1), const Offset(1, 0), downL, acrossL, rl);
  path.close();
  return path;
}

/// A neck between two capsules while a split pinches apart. Empty once the gap passes [maxGap].
Path bridgeNeck(Rect pill, Rect bubble, {double maxGap = 5, double spread = 0.3, double handle = 2.4}) {
  final path = Path();
  final gap = bubble.left - pill.right;
  if (gap > maxGap || pill.height < 2 || bubble.height < 2) return path;
  final t = (1 - (gap / maxGap)).clamp(0.0, 1.0);
  final neck = math.min(pill.height, bubble.height) * math.pow(t, 0.65);
  final mid = (pill.center.dy + bubble.center.dy) / 2;
  final y0 = mid - neck / 2;
  final y1 = mid + neck / 2;
  final x0 = pill.right;
  final x1 = bubble.left;
  final pull = math.max(handle, (x1 - x0).abs() * spread + handle);
  path.moveTo(x0, y0);
  path.cubicTo(x0 + pull, y0, x1 - pull, y0, x1, y0);
  path.lineTo(x1, y1);
  path.cubicTo(x1 - pull, y1, x0 + pull, y1, x0, y1);
  path.close();
  return path;
}
