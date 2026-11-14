import 'dart:math' as math;

/// SwiftUI-style spring: [response] is the undamped period in seconds,
/// [dampingFraction] is the damping ratio (1 settles without a bounce).
class Spring {
  final double response;
  final double dampingFraction;

  const Spring(this.response, this.dampingFraction);

  const Spring.critically([double response = 0.5]) : this(response, 1);
}

class SpringSample {
  final double x;
  final double v;
  const SpringSample(this.x, this.v);
}

/// Displacement and velocity [t] seconds after release from [x0] with velocity [v0].
SpringSample solveSpring(Spring config, double x0, double v0, double t) {
  final response = math.max(config.response, 1e-4);
  final zeta = math.max(config.dampingFraction, 0);
  final w0 = (2 * math.pi) / response;
  if (t <= 0) return SpringSample(x0, v0);

  if (zeta < 1) {
    final wd = w0 * math.sqrt(1 - zeta * zeta);
    final decay = math.exp(-zeta * w0 * t);
    final b = (v0 + zeta * w0 * x0) / wd;
    final cos = math.cos(wd * t);
    final sin = math.sin(wd * t);
    final x = decay * (x0 * cos + b * sin);
    final v = decay * (-zeta * w0 * (x0 * cos + b * sin) + (-x0 * wd * sin + b * wd * cos));
    return SpringSample(x, v);
  }

  if (zeta == 1) {
    final decay = math.exp(-w0 * t);
    final b = v0 + w0 * x0;
    final x = decay * (x0 + b * t);
    final v = decay * (b - w0 * (x0 + b * t));
    return SpringSample(x, v);
  }

  final root = math.sqrt(zeta * zeta - 1);
  final r1 = -w0 * (zeta - root);
  final r2 = -w0 * (zeta + root);
  final c2 = (v0 - r1 * x0) / (r2 - r1);
  final c1 = x0 - c2;
  final e1 = math.exp(r1 * t);
  final e2 = math.exp(r2 * t);
  return SpringSample(c1 * e1 + c2 * e2, c1 * r1 * e1 + c2 * r2 * e2);
}

class _Queued {
  final double at;
  final double goal;
  final Motion motion;
  const _Queued(this.at, this.goal, this.motion);
}

class Motion {
  final Spring? spring;
  final double tween;

  const Motion.spring(Spring this.spring) : tween = 0;
  const Motion.tween(this.tween) : spring = null;

  bool get isTween => spring == null;
}

/// One animated number. Retargeting keeps the current value and velocity.
class SpringTrack {
  double pos;
  double vel;
  double goal;
  double anchorT;
  double anchorX;
  double anchorV;
  Motion motion;
  _Queued? _queued;

  SpringTrack(double value)
      : pos = value,
        vel = 0,
        goal = value,
        anchorT = 0,
        anchorX = value,
        anchorV = 0,
        motion = const Motion.spring(Spring.critically());

  bool get busy => _queued != null || (pos - goal).abs() > 0.04 || vel.abs() > 0.04;

  void catchUp(double now) {
    if (_queued != null && now >= _queued!.at) {
      _apply(_queued!.at);
    }
  }

  (double, double) _eval(double t) {
    final elapsed = math.max(0.0, t - anchorT);
    if (motion.isTween) {
      final d = motion.tween;
      if (d <= 1e-4) return (goal, 0);
      final u = (elapsed / d).clamp(0.0, 1.0);
      final eased = 1 - math.pow(1 - u, 3).toDouble();
      final value = anchorX + (goal - anchorX) * eased;
      final speed = u >= 1 ? 0.0 : (goal - anchorX) * 3 * math.pow(1 - u, 2) / d;
      return (value, speed.toDouble());
    }
    final sample = solveSpring(motion.spring!, anchorX - goal, anchorV, elapsed);
    return (goal + sample.x, sample.v);
  }

  void _apply(double at) {
    final queued = _queued!;
    final sample = _eval(at);
    anchorT = at;
    anchorX = sample.$1;
    anchorV = sample.$2;
    goal = queued.goal;
    motion = queued.motion;
    _queued = null;
    pos = sample.$1;
    vel = sample.$2;
  }

  /// Heads toward [target]. When [via] is set, heads there first and retargets
  /// to [then] after [viaDelay] seconds, keeping speed.
  /// When [hold] is set, stays put for that long, then springs to [target].
  void aim(
    double target,
    Motion next,
    double now, {
    double? via,
    double viaDelay = 0,
    Motion? viaMotion,
    double hold = 0,
  }) {
    catchUp(now);
    final sample = _eval(now);
    anchorT = now;
    anchorX = sample.$1;
    anchorV = sample.$2;
    pos = sample.$1;
    vel = sample.$2;
    if (hold > 0) {
      goal = sample.$1;
      anchorV = 0;
      vel = 0;
      motion = const Motion.tween(0);
      _queued = _Queued(now + hold, target, next);
      return;
    }
    if (via != null && viaDelay > 0) {
      goal = via;
      motion = viaMotion ?? next;
      _queued = _Queued(now + viaDelay, target, next);
      return;
    }
    goal = target;
    motion = next;
    _queued = null;
  }

  void step(double now) {
    catchUp(now);
    final sample = _eval(now);
    pos = sample.$1;
    vel = sample.$2;
  }
}
