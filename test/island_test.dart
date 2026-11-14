import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:island/island/engine.dart';
import 'package:island/island/geometry.dart';
import 'package:island/island/metrics.dart';
import 'package:island/island/path.dart';
import 'package:island/island/spring.dart';
import 'package:island/ui/island_view.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('underdamped spring overshoots and a critically damped one does not', () {
    final rest = solveSpring(const Spring(0.483, 0.62), 1, 0, 0);
    expect(rest.x, 1);
    var crossed = false;
    for (var i = 1; i <= 80; i++) {
      final sample = solveSpring(const Spring(0.483, 0.62), 1, 0, i / 40);
      if (sample.x < 0) crossed = true;
    }
    expect(crossed, isTrue);
    for (var i = 1; i <= 80; i++) {
      final sample = solveSpring(const Spring(0.5, 1), 1, 0, i / 40);
      expect(sample.x, greaterThanOrEqualTo(-0.001));
    }
  });

  test('centered punch hole is covered and compact content stays off it', () {
    const screen = Size(448, 998);
    final hole = Rect.fromCenter(center: const Offset(224, 28), width: 24, height: 24);
    final layout = placeHole(screen, [hole]);
    expect(layout.synthetic, isFalse);
    expect(layout.hole.center.dx, closeTo(screen.width / 2, 0.1));
    expect(containsHole(layout.sensor, hole), isTrue);

    final measure = const ContentMeasure(leading: 22, trailing: 46, expandedBody: 50);
    final compact = contentRects(kind: PresentationKind.compact, measure: measure, hole: layout);
    expect(containsHole(compact.pill, hole), isTrue);
    expect(compact.clearsHole, isTrue);
    expect(compact.leading!.right, lessThanOrEqualTo(hole.left + 0.01));
    expect(compact.trailing!.left, greaterThanOrEqualTo(hole.right - 0.01));

    final expanded = contentRects(kind: PresentationKind.expanded, measure: measure, hole: layout);
    expect(containsHole(expanded.pill, hole), isTrue);
    expect(expanded.clearsHole, isTrue);

    final missing = placeHole(screen, const []);
    expect(missing.synthetic, isTrue);
    expect(missing.hole.center.dx, closeTo(screen.width / 2, 0.1));
    expect(missing.hole.width, lessThan(missing.hole.height * 2));
    expect(containsHole(missing.sensor, missing.hole), isTrue);
  });

  test('a tall cutout does not stretch the idle capsule', () {
    const screen = Size(448, 998);
    final hole = Rect.fromCenter(center: const Offset(224, 48), width: 28, height: 90);
    final layout = placeHole(screen, [hole]);
    expect(layout.sensor.height, closeTo(IslandMetrics.standard.idleHeight, 0.1));
    expect(layout.metrics(IslandMetrics.standard).compactHeight, closeTo(IslandMetrics.standard.compactHeight, 0.1));
    expect(layout.sensor.width, greaterThanOrEqualTo(hole.width));
  });

  test('two activities split to the right of the sensor', () {
    const screen = Size(448, 998);
    final layout = placeHole(screen, [Rect.fromCenter(center: const Offset(224, 28), width: 28, height: 28)]);
    final metrics = layout.metrics(IslandMetrics.standard);
    final measure = const ContentMeasure(
      leading: 22,
      trailing: 40,
      attached: 22,
      detached: 22,
      splitAttached: SlotPair(22, 40),
      splitDetached: SlotPair(22, 36),
      expandedBody: 48,
    );
    final shape = shapeTarget(kind: PresentationKind.minimal, measure: measure, screenWidth: screen.width, metrics: metrics);
    expect(shape.bubbleLeft, greaterThan(metrics.sensorWidth / 2));
    final bubble = shape.bubble(layout.centerX)!;
    expect(bubble.left, greaterThan(layout.hole.right));
    expect(containsHole(shape.pill(layout.centerX), layout.hole), isTrue);
  });

  test('continuous corner path stays on its rectangle', () {
    final path = continuousRect(const Rect.fromLTWH(10, 20, 120, 48), 20, 0.72);
    final bounds = path.getBounds();
    expect(bounds.left, closeTo(10, 0.8));
    expect(bounds.top, closeTo(20, 0.8));
    expect(bounds.width, closeTo(120, 1.2));
    expect(bounds.height, closeTo(48, 1.2));
  });

  test('gestures, alerts, and group handoff follow the island timings', () {
    final engine = IslandEngine(hole: placeHole(const Size(448, 998), [Rect.fromCenter(center: const Offset(224, 28), width: 24, height: 24)]));
    final taps = <String>[];
    engine.onPressed = taps.add;
    engine.sync(0);
    engine.start(const ActivitySpec(id: 'timer', label: 'Timer', leading: 22, trailing: 46));
    expect(engine.kind, PresentationKind.compact);

    engine.pointerDown('pill');
    engine.sync(0.05);
    engine.pointerUp();
    engine.sync(0.4);
    expect(engine.kind, PresentationKind.compact);
    expect(taps, ['timer']);

    engine.pointerDown('pill');
    engine.sync(0.65);
    expect(engine.kind, PresentationKind.expanded);

    engine.collapse();
    expect(engine.kind, PresentationKind.compact);

    engine.start(const ActivitySpec(id: 'music', label: 'Music', priority: 1, leading: 22, trailing: 30));
    expect(engine.kind, PresentationKind.minimal);
    expect(engine.secondary?.id, 'timer');

    engine.end('music');
    engine.end('timer');
    expect(engine.kind, PresentationKind.idle);

    engine.sync(1);
    engine.start(const ActivitySpec(id: 'upload', label: 'Upload', leading: 22, trailing: 22), alert: true);
    expect(engine.kind, PresentationKind.expanded);
    engine.sync(1 + 5.9);
    expect(engine.kind, PresentationKind.expanded);
    engine.sync(1 + 6.05);
    expect(engine.kind, PresentationKind.compact);

    engine.end('upload');
    engine.sync(10);
    engine.start(const ActivitySpec(id: 'order-a', label: '1042', group: 'orders', leading: 22, trailing: 40));
    engine.start(const ActivitySpec(id: 'order-b', label: '1043', group: 'orders', leading: 22, trailing: 40));
    expect(engine.visible.map((activity) => activity.id), ['order-a']);
    engine.alert('order-b');
    expect(engine.kind, PresentationKind.idle);
    engine.sync(10 + 1.5);
    expect(engine.expandedId, 'order-b');
    engine.sync(10 + 6.05);
    expect(engine.kind, PresentationKind.compact);
    expect(engine.visible.single.id, 'order-b');
  });

  testWidgets('a short tap runs the press action and a hold expands', (tester) async {
    final hole = Rect.fromCenter(center: const Offset(224, 28), width: 24, height: 24);
    final engine = IslandEngine(hole: placeHole(const Size(448, 998), [hole]));
    final taps = <String>[];
    engine.onPressed = taps.add;
    engine.start(const ActivitySpec(id: 'timer', label: 'Timer', leading: 22, trailing: 46));
    await tester.pumpWidget(MaterialApp(
      home: SizedBox(
        width: 448,
        height: 998,
        child: IslandView(engine: engine, builder: (spec, slot) => const ColoredBox(color: Color(0xFFFFFFFF))),
      ),
    ));
    await tester.pump(const Duration(milliseconds: 400));
    final center = tester.getTopLeft(find.byType(IslandView)) + Offset(engine.pillRect.center.dx, engine.pillRect.center.dy);

    final tap = await tester.startGesture(center);
    await tester.pump(const Duration(milliseconds: 40));
    await tap.up();
    await tester.pump();
    expect(taps, ['timer']);
    expect(engine.kind, PresentationKind.compact);

    final hold = await tester.startGesture(center);
    await tester.pump(const Duration(milliseconds: 250));
    expect(engine.kind, PresentationKind.expanded);
    await hold.up();
    await tester.pump();
  });

  testWidgets('compact glyphs stay off a centered punch hole', (tester) async {
    final hole = Rect.fromCenter(center: const Offset(224, 28), width: 24, height: 24);
    final engine = IslandEngine(hole: placeHole(const Size(448, 998), [hole]));
    engine.start(const ActivitySpec(id: 'timer', label: 'Timer running', leading: 22, trailing: 46, minimal: 22, expandedBody: 56));
    await tester.pumpWidget(MaterialApp(
      home: MediaQuery(
        data: const MediaQueryData(size: Size(448, 998)),
        child: SizedBox(
          width: 448,
          height: 998,
          child: IslandView(
            engine: engine,
            builder: (spec, slot) => const ColoredBox(color: Color(0xFFFFFFFF)),
          ),
        ),
      ),
    ));
    await tester.pump(const Duration(milliseconds: 1200));
    await tester.pump();
    final origin = tester.getTopLeft(find.byType(IslandView));
    final camera = hole.shift(origin);
    final leading = tester.getRect(find.byKey(const Key('island-leading')));
    final trailing = tester.getRect(find.byKey(const Key('island-trailing')));
    expect(leading.overlaps(camera), isFalse);
    expect(trailing.overlaps(camera), isFalse);
    expect(leading.right, lessThanOrEqualTo(camera.left + 0.5));
    expect(trailing.left, greaterThanOrEqualTo(camera.right - 0.5));
  });
}
