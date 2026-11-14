import 'dart:math' as math;
import 'dart:ui';

import 'metrics.dart';

enum PresentationKind { idle, compact, minimal, expanded }

enum ShapeTransition {
  appear,
  expand,
  pressExpand,
  appearExpand,
  swap,
  collapse,
  alertCollapse,
  collapseSplit,
  split,
  unsplit,
  dismiss,
  resize,
}

class ShapeTarget {
  final double left;
  final double right;
  final double top;
  final double height;
  final double radius;
  final double bubbleLeft;
  final double bubbleRight;
  final double bubbleHeight;

  const ShapeTarget({
    required this.left,
    required this.right,
    required this.top,
    required this.height,
    required this.radius,
    required this.bubbleLeft,
    required this.bubbleRight,
    required this.bubbleHeight,
  });

  double get width => right - left;

  Rect pill(double centerX) => Rect.fromLTWH(centerX + left, top, width, height);

  Rect? bubble(double centerX) {
    if (bubbleHeight < 1 || bubbleRight - bubbleLeft < 1) return null;
    return Rect.fromLTWH(centerX + bubbleLeft, top, bubbleRight - bubbleLeft, bubbleHeight);
  }
}

class SlotPair {
  final double leading;
  final double trailing;
  const SlotPair(this.leading, this.trailing);
}

class ContentMeasure {
  final double leading;
  final double trailing;
  final double attached;
  final double detached;
  final SlotPair? splitAttached;
  final SlotPair? splitDetached;
  final double expandedBody;

  const ContentMeasure({
    this.leading = 0,
    this.trailing = 0,
    this.attached = 0,
    this.detached = 0,
    this.splitAttached,
    this.splitDetached,
    this.expandedBody = 48,
  });

  static const empty = ContentMeasure();
}

class HoleLayout {
  final Size screen;
  final Rect hole;
  final Rect sensor;
  final bool synthetic;

  const HoleLayout({
    required this.screen,
    required this.hole,
    required this.sensor,
    required this.synthetic,
  });

  double get centerX => sensor.center.dx;

  IslandMetrics metrics(IslandMetrics base) {
    return base.copyWith(
      top: sensor.top,
      idleWidth: sensor.width,
      idleHeight: sensor.height,
      sensorWidth: sensor.width,
      compactHeight: math.max(base.compactHeight, sensor.height),
      expandedTop: math.min(base.expandedTop, sensor.top),
    );
  }
}

/// A centered punch-hole when the device reports none, sized like a Pixel front camera.
Rect syntheticPunchHole(Size screen) {
  const diameter = 28.0;
  final cx = screen.width / 2;
  const top = 16.0;
  return Rect.fromLTWH(cx - diameter / 2, top, diameter, diameter);
}

HoleLayout placeHole(Size screen, List<Rect> cutouts, {IslandMetrics metrics = IslandMetrics.standard}) {
  final safe = screen.width > 0 && screen.height > 0 ? screen : const Size(402, 874);
  Rect hole;
  var synthetic = false;
  if (cutouts.isEmpty) {
    hole = syntheticPunchHole(safe);
    synthetic = true;
  } else {
    final topBand = cutouts.where((rect) => rect.top <= safe.height * 0.3).toList();
    final pool = topBand.isEmpty ? cutouts : topBand;
    hole = pool.reduce((a, b) {
      final ad = (a.center.dx - safe.width / 2).abs();
      final bd = (b.center.dx - safe.width / 2).abs();
      return ad <= bd ? a : b;
    });
    for (final rect in pool) {
      if (rect.overlaps(hole.inflate(8)) || (rect.center.dx - hole.center.dx).abs() < 24) {
        hole = hole.expandToInclude(rect);
      }
    }
  }

  const pad = 4.0;
  var width = math.max(metrics.idleWidth, hole.width + pad * 2);
  var height = math.max(metrics.idleHeight, hole.height + pad * 2);
  var top = metrics.top;
  if (top > hole.top - pad) top = math.max(2, hole.top - pad);
  if (top + height < hole.bottom + pad) {
    height = hole.bottom + pad - top;
  }
  final cx = hole.center.dx;
  var sensor = Rect.fromLTWH(cx - width / 2, top, width, height);
  if (sensor.right < hole.right + pad || sensor.left > hole.left - pad) {
    width = math.max(width, hole.width + pad * 2);
    sensor = Rect.fromLTWH(cx - width / 2, top, width, height);
  }
  return HoleLayout(screen: safe, hole: hole, sensor: sensor, synthetic: synthetic);
}

double _clamp(double value, double min, double max) => math.min(max, math.max(min, value));

class SideExtents {
  final double leading;
  final double trailing;
  const SideExtents(this.leading, this.trailing);
}

SideExtents compactSides(double leadingSlot, double trailingSlot, IslandMetrics m) {
  double side(double slot, double inner) => slot > 0 ? inner + math.min(slot, m.maxSlotWidth) + m.edgePadding : 0;
  final leading = side(leadingSlot, m.innerLeading);
  final trailing = side(trailingSlot, m.innerTrailing);
  if (leading <= 0 || trailing <= 0 || m.balance <= 0) return SideExtents(leading, trailing);
  return SideExtents(
    math.max(leading, trailing * m.balance),
    math.max(trailing, leading * m.balance),
  );
}

class SplitShape {
  final double left;
  final double right;
  final double bubbleLeft;
  final double bubbleRight;
  final bool pair;
  const SplitShape(this.left, this.right, this.bubbleLeft, this.bubbleRight, this.pair);
}

SplitShape? splitPair(ContentMeasure measure, IslandMetrics m) {
  final attached = measure.splitAttached;
  final detached = measure.splitDetached;
  if (attached == null || detached == null) return null;
  double pairWidth(SlotPair slots, double gap) =>
      slots.leading + slots.trailing + (slots.leading > 0 && slots.trailing > 0 ? gap : 0);
  final attachedWidth = m.splitOuter + pairWidth(attached, m.splitGap) + m.splitInner;
  final bubbleW = detached.trailing > 0
      ? m.bubbleWidth
      : math.max(m.detachedMin, m.bubbleOuter + pairWidth(detached, m.bubbleSlotGap) + m.bubbleOuter);
  final sensor = m.sensorWidth / 2;
  final left = -(sensor + attachedWidth);
  final bubbleLeft = sensor + m.minimalGap;
  final bubbleRight = bubbleLeft + bubbleW;
  if (bubbleRight - left > m.splitMaxSpan) return null;
  return SplitShape(left, sensor, bubbleLeft, bubbleRight, true);
}

double designWidth(double screenWidth, IslandMetrics m) =>
    screenWidth > 0 ? math.min(screenWidth, m.designWidth) : m.designWidth;

ShapeTarget shapeTarget({
  required PresentationKind kind,
  required ContentMeasure measure,
  required double screenWidth,
  required IslandMetrics metrics,
}) {
  final top = metrics.top;
  final sensor = metrics.sensorWidth / 2;
  switch (kind) {
    case PresentationKind.idle:
      final width = metrics.idleWidth;
      final height = metrics.idleHeight;
      return ShapeTarget(
        left: -width / 2,
        right: width / 2,
        top: top,
        height: height,
        radius: height / 2,
        bubbleLeft: width / 2 - height,
        bubbleRight: width / 2,
        bubbleHeight: 0,
      );
    case PresentationKind.compact:
      final sides = compactSides(measure.leading, measure.trailing, metrics);
      final left = -math.min(sensor + sides.leading, metrics.maxWidth / 2);
      final right = math.min(sensor + sides.trailing, metrics.maxWidth / 2);
      final height = metrics.compactHeight;
      return ShapeTarget(
        left: left,
        right: right,
        top: top,
        height: height,
        radius: height / 2,
        bubbleLeft: right - height,
        bubbleRight: right,
        bubbleHeight: 0,
      );
    case PresentationKind.minimal:
      final split = splitPair(measure, metrics);
      final height = metrics.compactHeight;
      if (split != null) {
        return ShapeTarget(
          left: split.left,
          right: split.right,
          top: top,
          height: height,
          radius: height / 2,
          bubbleLeft: split.bubbleLeft,
          bubbleRight: split.bubbleRight,
          bubbleHeight: height,
        );
      }
      final left = measure.attached > 0
          ? -(sensor + metrics.minimalInner + measure.attached + metrics.minimalOuter)
          : -sensor;
      final bubbleW = math.max(metrics.detachedMin, measure.detached + 2 * metrics.detachedPad);
      final bubbleLeft = sensor + metrics.minimalGap;
      return ShapeTarget(
        left: left,
        right: sensor,
        top: top,
        height: height,
        radius: height / 2,
        bubbleLeft: bubbleLeft,
        bubbleRight: bubbleLeft + bubbleW,
        bubbleHeight: height,
      );
    case PresentationKind.expanded:
      final half = (designWidth(screenWidth, metrics) - 2 * metrics.expandedInset) / 2;
      final height = _clamp(
        measure.expandedBody + metrics.padTop + metrics.padBottom,
        metrics.expandedMin,
        metrics.expandedMax,
      );
      return ShapeTarget(
        left: -half,
        right: half,
        top: metrics.expandedTop,
        height: height,
        radius: metrics.expandedRadius,
        bubbleLeft: half - metrics.compactHeight,
        bubbleRight: half,
        bubbleHeight: 0,
      );
  }
}

ShapeTransition shapeTransition(
  PresentationKind from,
  PresentationKind to, {
  required bool swap,
  required bool swelling,
  required bool alert,
}) {
  if (from == to) return ShapeTransition.resize;
  if (to == PresentationKind.expanded) {
    if (from == PresentationKind.idle) return ShapeTransition.appearExpand;
    if (from == PresentationKind.minimal || swap) return ShapeTransition.swap;
    return swelling ? ShapeTransition.pressExpand : ShapeTransition.expand;
  }
  if (from == PresentationKind.expanded) {
    if (to == PresentationKind.minimal) return ShapeTransition.collapseSplit;
    if (to == PresentationKind.idle) return ShapeTransition.dismiss;
    return alert ? ShapeTransition.alertCollapse : ShapeTransition.collapse;
  }
  if (to == PresentationKind.idle) return ShapeTransition.dismiss;
  if (from == PresentationKind.idle) return ShapeTransition.appear;
  if (to == PresentationKind.minimal) return ShapeTransition.split;
  return ShapeTransition.unsplit;
}

class ContentRects {
  final Rect? leading;
  final Rect? trailing;
  final Rect? bubble;
  final Rect pill;
  final Rect sensor;
  final Rect hole;

  const ContentRects({
    required this.leading,
    required this.trailing,
    required this.bubble,
    required this.pill,
    required this.sensor,
    required this.hole,
  });

  bool get clearsHole {
    bool clear(Rect? rect) => rect == null || !rect.overlaps(hole);
    return clear(leading) && clear(trailing) && clear(bubble);
  }
}

/// Where compact or split content sits once the shape has settled.
ContentRects contentRects({
  required PresentationKind kind,
  required ContentMeasure measure,
  required HoleLayout hole,
  IslandMetrics? metrics,
}) {
  final m = metrics ?? hole.metrics(IslandMetrics.standard);
  final shape = shapeTarget(kind: kind, measure: measure, screenWidth: hole.screen.width, metrics: m);
  final pill = shape.pill(hole.centerX);
  final sensor = hole.sensor;
  Rect? leading;
  Rect? trailing;
  if (kind == PresentationKind.compact) {
    final glyph = math.min(22.0, pill.height - 8);
    if (measure.leading > 0) {
      final w = math.min(measure.leading, m.maxSlotWidth);
      leading = Rect.fromLTWH(pill.left + m.edgePadding, pill.center.dy - glyph / 2, w, glyph);
    }
    if (measure.trailing > 0) {
      final w = math.min(measure.trailing, m.maxSlotWidth);
      trailing = Rect.fromLTWH(pill.right - m.edgePadding - w, pill.center.dy - 10, w, 20);
    }
  } else if (kind == PresentationKind.minimal) {
    final split = splitPair(measure, m);
    if (split != null && measure.splitAttached != null) {
      final a = measure.splitAttached!;
      if (a.leading > 0) {
        leading = Rect.fromLTWH(pill.left + m.splitOuter, pill.center.dy - 11, math.min(a.leading, 36), 22);
      }
      if (a.trailing > 0) {
        final w = math.min(a.trailing, 48.0);
        trailing = Rect.fromLTWH(sensor.left - m.splitInner - w, pill.center.dy - 10, w, 20);
      }
    } else if (measure.attached > 0) {
      leading = Rect.fromLTWH(pill.left + m.minimalOuter, pill.center.dy - 11, math.min(measure.attached, 36), 22);
    }
  } else if (kind == PresentationKind.expanded) {
    final tile = 52.0;
    leading = Rect.fromLTWH(pill.left + m.padSide, pill.top + m.padTop, tile, tile);
    final trailW = 88.0;
    trailing = Rect.fromLTWH(pill.right - m.padTrailing - trailW, pill.top + m.padTop, trailW, 36);
  }
  final bubble = shape.bubble(hole.centerX);
  return ContentRects(
    leading: leading,
    trailing: trailing,
    bubble: bubble,
    pill: pill,
    sensor: sensor,
    hole: hole.hole,
  );
}

bool containsHole(Rect pill, Rect hole) {
  return pill.left <= hole.left && pill.top <= hole.top && pill.right >= hole.right && pill.bottom >= hole.bottom;
}
