import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

import 'controller.dart';
import 'island/engine.dart';
import 'island/geometry.dart';
import 'platform.dart';
import 'ui/activity_view.dart';
import 'ui/island_view.dart';

const _commands = MethodChannel('dev.islandcn/commands');

@pragma('vm:entry-point')
Future<void> overlayMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  final platform = IslandPlatform();
  final controller = IslandController(platform: platform);
  // Accept commands before init. A chip tapped while preferences or the
  // display query is still running must still reach this isolate.
  _commands.setMethodCallHandler((call) => _handle(controller, platform, call));
  await platform.overlayReady();
  var screen = Size.zero;
  try {
    await controller.init().timeout(const Duration(milliseconds: 600));
    final display = await platform.display().timeout(const Duration(milliseconds: 400));
    screen = display.size;
    if (display.size.width > 0 && display.size.height > 0) {
      controller.engine.bindHole(placeHole(display.size, display.cutouts));
      controller.deviceHoles = display.cutouts;
    }
  } catch (error, stack) {
    debugPrint('Island overlay init failed: $error\n$stack');
  }
  final frame = await platform.overlayFrame();
  runApp(OverlayApp(controller: controller, screen: screen, initialWindow: frame));
  // This engine does not get display vsync. scheduleFrame and scheduleForcedFrame
  // wait for a callback that never arrives, which is why v1.5.0 stayed frozen.
  // Advance the island on a timer and draw with a warm-up frame, which does not
  // wait for vsync.
  final clock = Stopwatch()..start();
  Timer.periodic(const Duration(milliseconds: 16), (_) {
    controller.engine.sync(clock.elapsedMicroseconds / 1e6);
    final binding = WidgetsBinding.instance;
    if (binding.schedulerPhase == SchedulerPhase.idle) {
      binding.scheduleWarmUpFrame();
    }
  });
}

Future<void> _handle(IslandController controller, IslandPlatform platform, MethodCall call) async {
  final args = call.arguments is Map ? (call.arguments as Map).map((key, value) => MapEntry('$key', value)) : const <String, dynamic>{};
  switch (call.method) {
    case 'outside':
      if (controller.engine.expanded) controller.engine.collapse();
      break;
    case 'metrics':
      final display = await platform.display();
      if (display.size.width > 0) controller.engine.bindHole(placeHole(display.size, display.cutouts));
      break;
    case 'startTimer':
      controller.startTimer();
      break;
    case 'playSample':
      controller.playSample();
      break;
    case 'incomingCall':
      controller.incomingCall(caller: args['caller']?.toString() ?? 'Mochi');
      break;
    case 'acceptCall':
      await controller.acceptCall();
      break;
    case 'endCall':
      await controller.endCall();
      break;
    case 'startUpload':
      controller.startUpload();
      break;
    case 'startEta':
      controller.startEta(kind: args['kind']?.toString() ?? 'ride');
      break;
    case 'startRecording':
      await controller.startRecording(kind: args['kind']?.toString() ?? 'audio');
      break;
    case 'stopRecording':
      await controller.stopRecording();
      break;
    case 'toggleRecording':
      controller.toggleRecording();
      break;
    case 'startTransfer':
      controller.startTransfer(direction: args['direction']?.toString() ?? 'send');
      break;
    case 'showMessage':
      controller.showMessage(sender: args['sender']?.toString() ?? 'Sam', body: args['body']?.toString() ?? 'The photos are in.');
      break;
    case 'pinEvent':
      controller.pinNextEvent();
      break;
    case 'startOrders':
      controller.startOrders();
      break;
    case 'handoff':
      controller.handoffOrder();
      break;
    case 'showBattery':
      controller.showBatteryNow();
      break;
    case 'split':
      controller.startTimer();
      controller.playSample();
      break;
    case 'setKeyline':
      await controller.setKeyline(args['value'] == true);
      break;
    case 'setReduced':
      final name = args['value']?.toString() ?? 'system';
      final setting = ReducedMotionSetting.values.where((item) => item.name == name);
      if (setting.isNotEmpty) await controller.setReducedSetting(setting.first);
      break;
  }
}

class OverlayApp extends StatelessWidget {
  final IslandController controller;
  final Size screen;
  final Rect? initialWindow;

  const OverlayApp({super.key, required this.controller, required this.screen, this.initialWindow});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      color: const Color(0x00000000),
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0x00000000),
        canvasColor: const Color(0x00000000),
      ),
      home: IslandScope(
        controller: controller,
        child: OverlayShell(controller: controller, screen: screen, initialWindow: initialWindow),
      ),
    );
  }
}

class OverlayShell extends StatefulWidget {
  final IslandController controller;
  final Size screen;
  final Rect? initialWindow;

  const OverlayShell({super.key, required this.controller, required this.screen, this.initialWindow});

  @override
  State<OverlayShell> createState() => _OverlayShellState();
}

class _OverlayShellState extends State<OverlayShell> {
  late Rect? _window = widget.initialWindow;
  final IslandPlatform _platform = IslandPlatform();

  IslandEngine get engine => widget.controller.engine;

  @override
  void initState() {
    super.initState();
    engine.frame.addListener(_publish);
    engine.addListener(_publish);
    WidgetsBinding.instance.addPostFrameCallback((_) => _publish());
  }

  @override
  void dispose() {
    engine.frame.removeListener(_publish);
    engine.removeListener(_publish);
    super.dispose();
  }

  Rect _tight() {
    // Springs lag the command. Size the window to the target immediately so a
    // chip changes the island before the next tick.
    final center = engine.hole.centerX;
    var rect = engine.pillRect.expandToInclude(engine.target.pill(center));
    final bubble = engine.bubbleRect;
    if (bubble != null) rect = rect.expandToInclude(bubble);
    final targetBubble = engine.target.bubble(center);
    if (targetBubble != null) rect = rect.expandToInclude(targetBubble);
    rect = rect.expandToInclude(engine.hole.sensor);
    return rect;
  }

  Rect _place(Rect tight) {
    final screen = widget.screen.width > 0 ? widget.screen : const Size(402, 874);
    const pad = 2.0;
    final needed = tight.inflate(pad);
    final current = _window;
    if (current != null && current.contains(needed.topLeft) && current.contains(needed.bottomRight) && current.width <= needed.width + 8 && current.height <= needed.height + 8) {
      return current;
    }
    var next = needed;
    final left = next.left.clamp(0.0, screen.width - 8);
    final top = next.top.clamp(0.0, screen.height - 8);
    final right = next.right.clamp(left + 8, screen.width);
    final bottom = next.bottom.clamp(top + 8, screen.height);
    return Rect.fromLTRB(left, top, right, bottom);
  }

  String? _held;

  void _publish() {
    final next = _place(_tight());
    _window = next;
    final touches = <Rect>[engine.pillRect];
    final bubble = engine.bubbleRect;
    if (bubble != null) touches.add(bubble);
    _platform.setFrame(next, touches: touches);
    if (mounted) setState(() {});
    final binding = WidgetsBinding.instance;
    if (binding.schedulerPhase == SchedulerPhase.idle) {
      binding.scheduleWarmUpFrame();
    }
  }

  void _down(Offset local, Rect window) {
    if (engine.kind == PresentationKind.expanded) return;
    final screen = local + window.topLeft;
    final bubble = engine.bubbleRect;
    final target = bubble != null && bubble.inflate(6).contains(screen) ? 'bubble' : 'pill';
    _held = target;
    engine.pointerDown(target);
  }

  void _up() {
    if (_held == null) return;
    _held = null;
    engine.pointerUp();
  }

  @override
  Widget build(BuildContext context) {
    final window = _window ?? _place(_tight());
    final screen = widget.screen.width > 0 ? widget.screen : const Size(402, 874);
    final expanded = engine.kind == PresentationKind.expanded;
    return SizedBox(
      width: window.width,
      height: window.height,
      child: Listener(
        behavior: expanded ? HitTestBehavior.deferToChild : HitTestBehavior.opaque,
        onPointerDown: (event) => _down(event.localPosition, window),
        onPointerUp: (_) => _up(),
        onPointerCancel: (_) => _up(),
        child: ClipRect(
          child: OverflowBox(
            alignment: Alignment.topLeft,
            minWidth: screen.width,
            maxWidth: screen.width,
            minHeight: screen.height,
            maxHeight: screen.height,
            child: Transform.translate(
              offset: Offset(-window.left, -window.top),
              child: IslandView(
                engine: engine,
                builder: (spec, slot) => ActivityBody(spec: spec, slot: slot),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
