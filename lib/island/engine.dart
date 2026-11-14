import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/foundation.dart';

import 'geometry.dart';
import 'metrics.dart';
import 'spring.dart';

enum ExpandReason { user, alert }

class ActivitySpec {
  final String id;
  final String label;
  final int priority;
  final int order;
  final String? group;
  final double leading;
  final double trailing;
  final double minimal;
  final double expandedBody;
  final int revision;

  const ActivitySpec({
    required this.id,
    required this.label,
    this.priority = 0,
    this.order = 0,
    this.group,
    this.leading = 22,
    this.trailing = 36,
    this.minimal = 22,
    this.expandedBody = 48,
    this.revision = 0,
  });

  ActivitySpec copyWith({
    String? label,
    int? priority,
    int? order,
    String? group,
    bool clearGroup = false,
    double? leading,
    double? trailing,
    double? minimal,
    double? expandedBody,
    int? revision,
  }) {
    return ActivitySpec(
      id: id,
      label: label ?? this.label,
      priority: priority ?? this.priority,
      order: order ?? this.order,
      group: clearGroup ? null : (group ?? this.group),
      leading: leading ?? this.leading,
      trailing: trailing ?? this.trailing,
      minimal: minimal ?? this.minimal,
      expandedBody: expandedBody ?? this.expandedBody,
      revision: revision ?? this.revision,
    );
  }
}

class _Sched {
  final int id;
  final double at;
  final void Function() run;
  bool fired = false;
  _Sched(this.id, this.at, this.run);
}

class _Press {
  final String target;
  final double t0;
  bool decided = false;
  bool up = false;
  bool expanded = false;
  bool returned = false;
  _Press(this.target, this.t0);
}

/// Activity stack, presentations, and the springs that move the pill.
class IslandEngine extends ChangeNotifier {
  IslandMetrics metrics;
  HoleLayout hole;
  final ValueNotifier<int> frame = ValueNotifier<int>(0);
  void Function(String id)? onPressed;

  final Map<String, ActivitySpec> _byId = {};
  final Map<String, String> _groupShown = {};
  int _order = 0;
  int _schedGen = 0;
  final List<_Sched> _sched = [];
  _Press? _press;

  PresentationKind kind = PresentationKind.idle;
  String? expandedId;
  ExpandReason? expandReason;
  bool reduced = false;
  bool showKeyline = true;
  double now = 0;
  int fadeToken = 0;
  bool splitIsPair = false;

  late SpringTrack left;
  late SpringTrack right;
  late SpringTrack top;
  late SpringTrack height;
  late SpringTrack radius;
  late SpringTrack bubbleLeft;
  late SpringTrack bubbleRight;
  late SpringTrack bubbleHeight;
  late SpringTrack scale;

  ShapeTarget target = const ShapeTarget(
    left: -47.4,
    right: 47.4,
    top: 13.91,
    height: 36.84,
    radius: 18.42,
    bubbleLeft: 0,
    bubbleRight: 0,
    bubbleHeight: 0,
  );

  IslandEngine({
    IslandMetrics? metrics,
    HoleLayout? hole,
    this.onPressed,
  })  : metrics = metrics ?? IslandMetrics.standard,
        hole = hole ?? placeHole(const Size(402, 874), const []) {
    this.metrics = this.hole.metrics(this.metrics);
    _seedIdle();
  }

  void _seedIdle() {
    target = shapeTarget(
      kind: PresentationKind.idle,
      measure: ContentMeasure.empty,
      screenWidth: hole.screen.width,
      metrics: metrics,
    );
    left = SpringTrack(target.left);
    right = SpringTrack(target.right);
    top = SpringTrack(target.top);
    height = SpringTrack(target.height);
    radius = SpringTrack(target.radius);
    bubbleLeft = SpringTrack(target.bubbleLeft);
    bubbleRight = SpringTrack(target.bubbleRight);
    bubbleHeight = SpringTrack(target.bubbleHeight);
    scale = SpringTrack(1);
  }

  List<ActivitySpec> get activities => _byId.values.toList()
    ..sort((a, b) {
      final p = b.priority.compareTo(a.priority);
      return p != 0 ? p : a.order.compareTo(b.order);
    });

  List<ActivitySpec> get visible {
    final list = <ActivitySpec>[];
    for (final activity in _byId.values) {
      final group = activity.group;
      if (group != null && _groupShown[group] != activity.id) continue;
      list.add(activity);
    }
    list.sort((a, b) {
      final p = b.priority.compareTo(a.priority);
      return p != 0 ? p : a.order.compareTo(b.order);
    });
    return list;
  }

  ActivitySpec? get primary => visible.isEmpty ? null : visible.first;

  ActivitySpec? get secondary => visible.length < 2 ? null : visible[1];

  ActivitySpec? spec(String id) => _byId[id];

  bool get expanded => kind == PresentationKind.expanded;

  Rect get pillRect => Rect.fromLTWH(hole.centerX + left.pos, top.pos, math.max(0, right.pos - left.pos), math.max(0, height.pos));

  Rect? get bubbleRect {
    if (bubbleHeight.pos < 1) return null;
    final width = bubbleRight.pos - bubbleLeft.pos;
    if (width < 1) return null;
    return Rect.fromLTWH(hole.centerX + bubbleLeft.pos, top.pos, width, bubbleHeight.pos);
  }

  void bindHole(HoleLayout next) {
    hole = next;
    metrics = next.metrics(IslandMetrics.standard);
    _retarget(resize: true);
    _structure();
  }

  void setReduced(bool value) {
    if (reduced == value) return;
    reduced = value;
    _retarget(resize: true);
    _structure();
  }

  void start(ActivitySpec spec, {bool alert = false}) {
    final existing = _byId[spec.id];
    final next = spec.copyWith(order: existing?.order ?? _order++);
    _byId[next.id] = next;
    final group = next.group;
    if (group != null) {
      _groupShown.putIfAbsent(group, () => next.id);
    }
    if (alert) {
      _beginAlert(next.id, fromIdle: existing == null && visible.length <= 1 && expandedId == null);
    }
    _retarget();
    _structure();
  }

  void updateSpec(ActivitySpec spec, {bool alert = false, bool crossfade = false}) {
    final existing = _byId[spec.id];
    if (existing == null) {
      start(spec, alert: alert);
      return;
    }
    _byId[spec.id] = spec.copyWith(order: existing.order, revision: crossfade ? existing.revision + 1 : spec.revision);
    if (crossfade) fadeToken++;
    if (alert) _beginAlert(spec.id, fromIdle: false);
    _retarget(resize: !alert);
    _structure();
  }

  void end(String id) {
    final existing = _byId.remove(id);
    if (existing == null) return;
    final group = existing.group;
    if (group != null && _groupShown[group] == id) {
      ActivitySpec? oldest;
      for (final activity in _byId.values) {
        if (activity.group != group) continue;
        if (oldest == null || activity.order < oldest.order) oldest = activity;
      }
      if (oldest == null) {
        _groupShown.remove(group);
      } else {
        _groupShown[group] = oldest.id;
      }
    }
    if (expandedId == id) {
      expandedId = null;
      expandReason = null;
    }
    _retarget();
    _structure();
  }

  void expand([String? id]) {
    final targetId = id ?? primary?.id;
    if (targetId == null || !_byId.containsKey(targetId)) return;
    final activity = _byId[targetId]!;
    final group = activity.group;
    if (group != null && _groupShown[group] != targetId) {
      _groupShown[group] = targetId;
    }
    expandedId = targetId;
    expandReason = ExpandReason.user;
    _cancelAlerts();
    _retarget(swelling: _press?.decided ?? false);
    _structure();
  }

  void collapse() {
    if (expandedId == null && kind != PresentationKind.expanded) return;
    final wasAlert = expandReason == ExpandReason.alert;
    expandedId = null;
    expandReason = null;
    _retarget(alertReturn: wasAlert);
    _structure();
  }

  void alert(String id) {
    if (!_byId.containsKey(id)) return;
    _beginAlert(id, fromIdle: kind == PresentationKind.idle && visible.length <= 1);
    _retarget();
    _structure();
  }

  void pointerDown(String target) {
    _press = _Press(target, now);
    final bubble = target == 'bubble';
    scale.aim(
      bubble ? IslandSprings.bubbleSwellTarget : IslandSprings.swellTarget,
      _motion(Spring(bubble ? IslandSprings.bubbleSwell.response : IslandSprings.swell.response,
          bubble ? IslandSprings.bubbleSwell.damping : IslandSprings.swell.damping)),
      now,
    );
    _frame();
  }

  void pointerUp() {
    final press = _press;
    if (press == null) return;
    press.up = true;
    final held = now - press.t0;
    if (!press.decided && held < IslandSprings.decide) {
      final id = press.target == 'bubble' ? secondary?.id : primary?.id;
      if (id != null) onPressed?.call(id);
      final idle = kind == PresentationKind.idle || primary == null;
      final backAt = press.t0 + (idle ? IslandSprings.idleTapReturn : IslandSprings.tapReturn);
      _releaseScale(math.max(now, backAt));
      _press = null;
      _frame();
      return;
    }
    _frame();
  }

  void sync(double time) {
    if (time < now) time = now;
    now = time;
    final press = _press;
    if (press != null) {
      final held = now - press.t0;
      final idle = primary == null && expandedId == null;
      if (!press.decided && !press.up && held >= IslandSprings.decide) {
        press.decided = true;
      }
      if (press.decided && !press.expanded && held >= (press.target == 'bubble' ? IslandSprings.longPressBubble : IslandSprings.longPress)) {
        press.expanded = true;
        if (!idle) {
          _releaseScale(now);
          expand(press.target == 'bubble' ? secondary?.id : primary?.id);
        }
      }
      if (idle && !press.returned && held >= IslandSprings.idleHoldReturn) {
        press.returned = true;
        _releaseScale(now, idle: true);
        if (press.up) _press = null;
      }
      if (press.up && press.expanded) _press = null;
    }
    final due = _sched.where((item) => !item.fired && item.at <= now).toList();
    for (final item in due) {
      item.fired = true;
      item.run();
    }
    _sched.removeWhere((item) => item.fired);
    _step();
    if (_moving() || _press != null) _frame();
  }

  int schedule(double at, void Function() run) {
    final id = ++_schedGen;
    _sched.add(_Sched(id, at, run));
    return id;
  }

  void _releaseScale(double at, {bool idle = false}) {
    final spring = idle
        ? Spring(IslandSprings.idleRelease.response, IslandSprings.idleRelease.damping)
        : Spring(IslandSprings.release.response, IslandSprings.release.damping);
    void go() => scale.aim(1, _motion(spring), now);
    if (at > now + 0.001) {
      schedule(at, go);
    } else {
      go();
    }
  }

  void _beginAlert(String id, {required bool fromIdle}) {
    final activity = _byId[id];
    if (activity == null) return;
    final group = activity.group;
    if (group != null && _groupShown[group] != null && _groupShown[group] != id) {
      _handoff(group, id);
      return;
    }
    expandedId = id;
    expandReason = ExpandReason.alert;
    _cancelAlerts();
    final hold = fromIdle ? IslandSprings.alertHoldIdle : IslandSprings.alertHold;
    final token = expandedId;
    schedule(now + hold, () {
      if (expandReason == ExpandReason.alert && expandedId == token) collapse();
    });
  }

  void _handoff(String group, String id) {
    _groupShown[group] = '';
    expandedId = null;
    expandReason = null;
    _cancelAlerts();
    final leftAt = now;
    schedule(leftAt + IslandSprings.groupHandoff, () {
      if (!_byId.containsKey(id)) return;
      _groupShown[group] = id;
      expandedId = id;
      expandReason = ExpandReason.alert;
      _retarget();
      _structure();
    });
    schedule(leftAt + IslandSprings.groupWindow, () {
      if (expandReason == ExpandReason.alert && expandedId == id) collapse();
    });
    _retarget();
  }

  void _cancelAlerts() {
    // Alert collapses check expandReason, so a user expand simply wins.
  }

  ContentMeasure _measure() {
    final shown = visible;
    if (shown.isEmpty) return ContentMeasure.empty;
    final first = shown.first;
    final focus = (expandedId != null ? _byId[expandedId!] : null) ?? first;
    if (shown.length == 1) {
      return ContentMeasure(
        leading: first.leading,
        trailing: first.trailing,
        attached: first.minimal,
        detached: 0,
        expandedBody: focus.expandedBody,
      );
    }
    final second = shown[1];
    return ContentMeasure(
      leading: first.leading,
      trailing: first.trailing,
      attached: first.minimal,
      detached: second.minimal,
      splitAttached: SlotPair(first.leading, first.trailing),
      splitDetached: SlotPair(second.leading, second.trailing),
      expandedBody: focus.expandedBody,
    );
  }

  PresentationKind _desired() {
    if (expandedId != null && _byId.containsKey(expandedId)) return PresentationKind.expanded;
    final shown = visible;
    if (shown.isEmpty) return PresentationKind.idle;
    if (shown.length == 1) return PresentationKind.compact;
    return PresentationKind.minimal;
  }

  void _retarget({bool resize = false, bool alertReturn = false, bool swelling = false}) {
    final next = _desired();
    final from = resize && next == kind ? kind : kind;
    final swap = next == PresentationKind.expanded && from == PresentationKind.minimal;
    final transition = from == next && resize
        ? ShapeTransition.resize
        : shapeTransition(from, next, swap: swap, swelling: swelling, alert: alertReturn);
    final measure = _measure();
    splitIsPair = next == PresentationKind.minimal && splitPair(measure, metrics) != null;
    target = shapeTarget(kind: next, measure: measure, screenWidth: hole.screen.width, metrics: metrics);
    final idle = shapeTarget(kind: PresentationKind.idle, measure: ContentMeasure.empty, screenWidth: hole.screen.width, metrics: metrics);
    _aimEdges(transition, idle);
    if (next != kind) fadeToken++;
    kind = next;
  }

  Motion _motion(Spring spring) => reduced ? const Motion.tween(IslandSprings.reducedDuration) : Motion.spring(spring);

  Spring _edgeSpring(ShapeTransition transition) {
    return switch (transition) {
      ShapeTransition.appear => Spring(IslandSprings.appear.response, IslandSprings.appear.damping),
      ShapeTransition.expand => Spring(IslandSprings.expand.response, IslandSprings.expand.damping),
      ShapeTransition.pressExpand => Spring(IslandSprings.pressExpand.response, IslandSprings.pressExpand.damping),
      ShapeTransition.appearExpand => Spring(IslandSprings.appearExpand.response, IslandSprings.appearExpand.damping),
      ShapeTransition.swap => Spring(IslandSprings.swap.response, IslandSprings.swap.damping),
      ShapeTransition.collapse => Spring(IslandSprings.collapse.response, IslandSprings.collapse.damping),
      ShapeTransition.alertCollapse => Spring(IslandSprings.alertCollapse.response, IslandSprings.alertCollapse.damping),
      ShapeTransition.collapseSplit => Spring(IslandSprings.collapseSplit.response, IslandSprings.collapseSplit.damping),
      ShapeTransition.split => Spring(IslandSprings.split.response, IslandSprings.split.damping),
      ShapeTransition.unsplit => Spring(IslandSprings.unsplit.response, IslandSprings.unsplit.damping),
      ShapeTransition.dismiss => Spring(IslandSprings.dismiss.response, IslandSprings.dismiss.damping),
      ShapeTransition.resize => Spring(IslandSprings.resize.response, IslandSprings.resize.damping),
    };
  }

  Spring _bodySpring(ShapeTransition transition) {
    return switch (transition) {
      ShapeTransition.expand => Spring(IslandSprings.expandBody.response, IslandSprings.expandBody.damping),
      ShapeTransition.pressExpand => Spring(IslandSprings.pressExpandBody.response, IslandSprings.pressExpandBody.damping),
      ShapeTransition.appearExpand => Spring(IslandSprings.appearExpandBody.response, IslandSprings.appearExpandBody.damping),
      ShapeTransition.collapse => Spring(IslandSprings.collapseBody.response, IslandSprings.collapseBody.damping),
      ShapeTransition.alertCollapse => Spring(IslandSprings.alertCollapseBody.response, IslandSprings.alertCollapseBody.damping),
      _ => _edgeSpring(transition),
    };
  }

  double _via(ShapeTransition transition) {
    if (reduced) return 0;
    return switch (transition) {
      ShapeTransition.swap => IslandSprings.swapVia,
      ShapeTransition.collapse => IslandSprings.collapseVia,
      ShapeTransition.alertCollapse => IslandSprings.alertCollapseVia,
      ShapeTransition.split => IslandSprings.splitVia,
      ShapeTransition.unsplit => IslandSprings.unsplitVia,
      _ => 0,
    };
  }

  void _aimEdges(ShapeTransition transition, ShapeTarget idle) {
    final edge = _motion(_edgeSpring(transition));
    final body = _motion(_bodySpring(transition));
    final corner = _motion(transition == ShapeTransition.expand || transition == ShapeTransition.pressExpand
        ? Spring(IslandSprings.cornerExpand.response, IslandSprings.cornerExpand.damping)
        : _bodySpring(transition));
    final via = _via(transition);
    Motion retarget = edge;
    if (transition == ShapeTransition.collapse) {
      retarget = _motion(Spring(IslandSprings.collapseRetarget.response, IslandSprings.collapseRetarget.damping));
    } else if (transition == ShapeTransition.alertCollapse) {
      retarget = _motion(Spring(IslandSprings.alertCollapseRetarget.response, IslandSprings.alertCollapseRetarget.damping));
    }
    void edgeAim(SpringTrack track, double to, double idleTo) {
      if (via > 0) {
        track.aim(to, retarget, now, via: idleTo, viaDelay: via, viaMotion: edge);
      } else {
        track.aim(to, edge, now);
      }
    }

    edgeAim(left, target.left, idle.left);
    edgeAim(right, target.right, idle.right);
    top.aim(target.top, body, now);
    height.aim(target.height, body, now);
    radius.aim(target.radius, corner, now);
    if (transition == ShapeTransition.split) {
      bubbleLeft.aim(
        target.bubbleLeft,
        _motion(Spring(IslandSprings.bubbleSplit.response, IslandSprings.bubbleSplit.damping)),
        now,
        hold: reduced ? 0 : IslandSprings.bubbleSplitDelay,
      );
    } else if (via > 0 && (transition == ShapeTransition.unsplit || transition == ShapeTransition.split)) {
      bubbleLeft.aim(target.bubbleLeft, edge, now, via: idle.bubbleLeft, viaDelay: via, viaMotion: edge);
    } else {
      bubbleLeft.aim(target.bubbleLeft, edge, now);
    }
    bubbleRight.aim(target.bubbleRight, edge, now, via: via > 0 ? idle.bubbleRight : null, viaDelay: via, viaMotion: edge);
    bubbleHeight.aim(target.bubbleHeight, body, now);
  }

  void _step() {
    left.step(now);
    right.step(now);
    top.step(now);
    height.step(now);
    radius.step(now);
    bubbleLeft.step(now);
    bubbleRight.step(now);
    bubbleHeight.step(now);
    scale.step(now);
  }

  bool _moving() =>
      left.busy ||
      right.busy ||
      top.busy ||
      height.busy ||
      radius.busy ||
      bubbleLeft.busy ||
      bubbleRight.busy ||
      bubbleHeight.busy ||
      scale.busy;

  void _frame() => frame.value++;

  void _structure() {
    _frame();
    notifyListeners();
  }
}
