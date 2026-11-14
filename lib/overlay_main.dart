import 'package:flutter/material.dart';
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
  await controller.init();
  final display = await platform.display();
  if (display.size.width > 0 && display.size.height > 0) {
    controller.engine.bindHole(placeHole(display.size, display.cutouts));
    controller.deviceHoles = display.cutouts;
  }
  _commands.setMethodCallHandler((call) => _handle(controller, platform, call));
  final frame = await platform.overlayFrame();
  await platform.overlayReady();
  runApp(OverlayApp(controller: controller, screen: display.size, initialWindow: frame));
}

Future<void> _handle(IslandController controller, IslandPlatform platform, MethodCall call) async {
  final args = call.arguments is Map ? (call.arguments as Map).map((key, value) => MapEntry('$key', value)) : const <String, dynamic>{};
  switch (call.method) {
    case 'outside':
      if (controller.engine.expanded) controller.engine.collapse();
    case 'metrics':
      final display = await platform.display();
      if (display.size.width > 0) controller.engine.bindHole(placeHole(display.size, display.cutouts));
    case 'startTimer':
      controller.startTimer();
    case 'playSample':
      controller.playSample();
    case 'incomingCall':
      controller.incomingCall(caller: args['caller']?.toString() ?? 'Mochi');
    case 'acceptCall':
      await controller.acceptCall();
    case 'endCall':
      await controller.endCall();
    case 'startUpload':
      controller.startUpload();
    case 'startEta':
      controller.startEta(kind: args['kind']?.toString() ?? 'ride');
    case 'startRecording':
      await controller.startRecording(kind: args['kind']?.toString() ?? 'audio');
    case 'stopRecording':
      await controller.stopRecording();
    case 'toggleRecording':
      controller.toggleRecording();
    case 'startTransfer':
      controller.startTransfer(direction: args['direction']?.toString() ?? 'send');
    case 'showMessage':
      controller.showMessage(sender: args['sender']?.toString() ?? 'Sam', body: args['body']?.toString() ?? 'The photos are in.');
    case 'pinEvent':
      controller.pinNextEvent();
    case 'startOrders':
      controller.startOrders();
    case 'handoff':
      controller.handoffOrder();
    case 'showBattery':
      controller.showBatteryNow();
    case 'split':
      controller.startTimer();
      controller.playSample();
    case 'setKeyline':
      await controller.setKeyline(args['value'] == true);
    case 'setReduced':
      final name = args['value']?.toString() ?? 'system';
      final setting = ReducedMotionSetting.values.where((item) => item.name == name);
      if (setting.isNotEmpty) await controller.setReducedSetting(setting.first);
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
    var rect = engine.pillRect;
    final bubble = engine.bubbleRect;
    if (bubble != null) rect = rect.expandToInclude(bubble);
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
    final previous = _window;
    _window = next;
    final moved = previous == null || (previous.left - next.left).abs() > 0.5 || (previous.top - next.top).abs() > 0.5 || (previous.width - next.width).abs() > 0.5 || (previous.height - next.height).abs() > 0.5;
    final touches = <Rect>[engine.pillRect];
    final bubble = engine.bubbleRect;
    if (bubble != null) touches.add(bubble);
    _platform.setFrame(next, touches: touches);
    if (moved && mounted) setState(() {});
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
