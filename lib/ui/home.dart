import 'dart:async';

import 'dart:ui' show DisplayFeatureType;

import 'package:flutter/material.dart';

import '../controller.dart';
import '../island/geometry.dart';
import '../theme.dart';
import 'activity_view.dart';
import 'island_view.dart';

class IslandHome extends StatefulWidget {
  final IslandController controller;

  const IslandHome({super.key, required this.controller});

  @override
  State<IslandHome> createState() => _IslandHomeState();
}

class _IslandHomeState extends State<IslandHome> {
  IslandController get controller => widget.controller;
  Timer? _clock;

  @override
  void initState() {
    super.initState();
    controller.addListener(_rebuild);
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (controller.systemReduced != reduced) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) controller.setSystemReduced(reduced);
      });
    }
  }

  @override
  void dispose() {
    _clock?.cancel();
    controller.removeListener(_rebuild);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final features = MediaQuery.displayFeaturesOf(context);
    final holes = [
      ...controller.deviceHoles,
      for (final feature in features)
        if (feature.type == DisplayFeatureType.cutout) feature.bounds,
    ];
    final layout = placeHole(size, holes);
    if (layout.hole != controller.engine.hole.hole || layout.screen != controller.engine.hole.screen) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) controller.engine.bindHole(layout);
      });
    }
    final now = DateTime.now();
    final time = TimeOfDay.fromDateTime(now).format(context);
    final pill = controller.engine.pillRect;
    return IslandScope(
      controller: controller,
      child: PopScope(
        canPop: !controller.engine.expanded,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && controller.engine.expanded) controller.engine.collapse();
        },
        child: Scaffold(
        backgroundColor: IslandColors.page,
        body: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTap: () {
            if (controller.engine.expanded) controller.engine.collapse();
          },
          child: Stack(
            children: [
              const Positioned.fill(child: _Wallpaper()),
              IslandClearance(
                engine: controller.engine,
                child: _Sheet(controller: controller, now: now),
              ),
              Positioned(
                top: layout.sensor.top,
                left: 18,
                right: 18,
                height: layout.sensor.height,
                child: Row(
                  children: [
                    Opacity(opacity: pill.left > 88 ? 1 : 0, child: Text(time, style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600))),
                    const Spacer(),
                    Opacity(
                      opacity: pill.right < size.width - 88 ? 1 : 0,
                      child: Text('${(controller.battery.level * 100).round()}%', style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
                    ),
                  ],
                ),
              ),
              IslandView(
                engine: controller.engine,
                builder: (spec, slot) => ActivityBody(spec: spec, slot: slot),
              ),
            ],
          ),
        ),
      ),
      ),
    );
  }
}

class _Wallpaper extends StatelessWidget {
  const _Wallpaper();

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: Alignment(0, -0.6),
          radius: 1.2,
          colors: [Color(0xFF20243A), Color(0xFF0B0B0F)],
        ),
      ),
    );
  }
}

class _Sheet extends StatelessWidget {
  final IslandController controller;
  final DateTime now;

  const _Sheet({required this.controller, required this.now});

  @override
  Widget build(BuildContext context) {
    final months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
    final days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Text('${days[now.weekday - 1]}, ${months[now.month - 1]} ${now.day}', style: const TextStyle(color: IslandColors.secondary, fontSize: 18, fontWeight: FontWeight.w500)),
        Text(TimeOfDay.fromDateTime(now).format(context), style: const TextStyle(color: Colors.white, fontSize: 64, fontWeight: FontWeight.w300, height: 1.05, letterSpacing: -1.5)),
        if (controller.status.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(controller.status, style: const TextStyle(color: IslandColors.secondary, fontSize: 13)),
        ],
        const SizedBox(height: 22),
        const _Heading('Live'),
        _LiveRow(title: 'Battery', detail: '${(controller.battery.level * 100).round()}%${controller.battery.charging ? ' · charging' : ''}', action: 'Show', onPressed: controller.showBatteryNow),
        _LiveRow(
          title: 'Phone',
          detail: controller.grants['READ_PHONE_STATE'] == true ? 'Call state is live' : 'Permission off · simulation still works',
          action: 'Allow',
          onPressed: () => controller.request(['android.permission.READ_PHONE_STATE', 'android.permission.ANSWER_PHONE_CALLS']),
        ),
        _LiveRow(
          title: 'Messages',
          detail: controller.notificationAccess ? 'Notification access on' : 'Allow notifications or SMS',
          action: 'Access',
          onPressed: controller.platform.openNotificationAccess,
        ),
        _LiveRow(
          title: 'Calendar',
          detail: controller.upcoming.isEmpty ? 'No upcoming event' : controller.upcoming.first.title,
          action: 'Allow',
          onPressed: () => controller.request(['android.permission.READ_CALENDAR']),
        ),
        const SizedBox(height: 18),
        const _Heading('Activities'),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _Chip('Upload', controller.startUpload),
            _Chip('Timer', controller.startTimer),
            _Chip('Music', controller.playSample),
            _Chip('Call', () => controller.incomingCall(caller: 'Mochi')),
            _Chip('Ride', () => controller.startEta()),
            _Chip('Delivery', () => controller.startEta(kind: 'delivery')),
            _Chip('Record', controller.startRecording),
            _Chip('Screen', () => controller.startRecording(kind: 'screen')),
            _Chip('Camera', () => controller.startRecording(kind: 'camera')),
            _Chip('Send', controller.startTransfer),
            _Chip('Receive', () => controller.startTransfer(direction: 'receive')),
            _Chip('Message', () => controller.showMessage(sender: 'Sam', body: 'The photos are in. Take a look when you can.')),
            _Chip('Event', controller.pinNextEvent),
            _Chip('Split', () {
              controller.startTimer();
              controller.playSample();
            }),
            _Chip('Orders', controller.startOrders),
            _Chip('Handoff', controller.handoffOrder),
          ],
        ),
        const SizedBox(height: 16),
        if (controller.call != null)
          _Card(
            title: controller.call!.ringing ? 'Incoming call' : 'On a call',
            body: controller.call!.caller,
            children: [
              if (controller.call!.ringing) TextButton(onPressed: controller.acceptCall, child: const Text('Accept')),
              TextButton(onPressed: controller.endCall, child: Text(controller.call!.ringing ? 'Decline' : 'End')),
            ],
          ),
        if (controller.timer != null)
          _Card(
            title: controller.timer!.label,
            body: controller.timer!.paused ? 'Paused' : 'Running',
            children: [
              TextButton(onPressed: controller.toggleTimer, child: Text(controller.timer!.paused ? 'Resume' : 'Pause')),
              TextButton(onPressed: controller.cancelTimer, child: const Text('Cancel')),
            ],
          ),
        if (controller.player != null)
          _Card(
            title: controller.player!.title,
            body: controller.player!.external ? 'From another app' : controller.player!.artist,
            children: [
              TextButton(onPressed: controller.togglePlayer, child: Text(controller.player!.playing ? 'Pause' : 'Play')),
              TextButton(onPressed: () => controller.skip(1), child: const Text('Next')),
              TextButton(onPressed: controller.stopPlayer, child: const Text('Close')),
            ],
          ),
        const SizedBox(height: 8),
        const _Heading('Settings'),
        _Setting(
          label: 'Reduced motion',
          child: DropdownButton<ReducedMotionSetting>(
            value: controller.reducedSetting,
            dropdownColor: IslandColors.card,
            items: const [
              DropdownMenuItem(value: ReducedMotionSetting.system, child: Text('Match system')),
              DropdownMenuItem(value: ReducedMotionSetting.always, child: Text('Always')),
              DropdownMenuItem(value: ReducedMotionSetting.never, child: Text('Never')),
            ],
            onChanged: (value) {
              if (value != null) controller.setReducedSetting(value);
            },
          ),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Keyline', style: TextStyle(color: Colors.white)),
          subtitle: const Text('Thin rim on a dark page', style: TextStyle(color: IslandColors.secondary)),
          value: controller.keyline,
          activeThumbColor: IslandColors.lagoon,
          onChanged: controller.setKeyline,
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Show over other apps', style: TextStyle(color: Colors.white)),
          subtitle: Text(controller.overlayAllowed ? 'Compact pill while this app is in the background' : 'Needs the overlay permission', style: const TextStyle(color: IslandColors.secondary)),
          value: controller.wantOverlay,
          activeThumbColor: IslandColors.lagoon,
          onChanged: controller.setWantOverlay,
        ),
        TextButton(onPressed: controller.platform.openAppSettings, child: const Text('App settings')),
        const SizedBox(height: 12),
        const Text(
          'Long-press the island to expand it. A short tap runs the activity action. Tap outside, or Back, to collapse. Two activities split around the camera.',
          style: TextStyle(color: IslandColors.tertiary, fontSize: 13, height: 1.35),
        ),
      ],
    );
  }
}

class _Heading extends StatelessWidget {
  final String text;
  const _Heading(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(text, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600, letterSpacing: 0.4)),
    );
  }
}

class _Chip extends StatelessWidget {
  final String label;
  final VoidCallback onPressed;
  const _Chip(this.label, this.onPressed);

  @override
  Widget build(BuildContext context) {
    return ActionChip(
      label: Text(label),
      backgroundColor: IslandColors.card,
      labelStyle: const TextStyle(color: Colors.white),
      side: const BorderSide(color: IslandColors.cardEdge),
      onPressed: onPressed,
    );
  }
}

class _LiveRow extends StatelessWidget {
  final String title;
  final String detail;
  final String action;
  final VoidCallback onPressed;
  const _LiveRow({required this.title, required this.detail, required this.action, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
                Text(detail, style: const TextStyle(color: IslandColors.secondary, fontSize: 13)),
              ],
            ),
          ),
          TextButton(onPressed: onPressed, child: Text(action)),
        ],
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final String title;
  final String body;
  final List<Widget> children;
  const _Card({required this.title, required this.body, required this.children});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(14, 10, 8, 6),
      decoration: BoxDecoration(color: IslandColors.card, borderRadius: BorderRadius.circular(16), border: Border.all(color: IslandColors.cardEdge)),
      child: Row(
        children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
              Text(body, style: const TextStyle(color: IslandColors.secondary, fontSize: 13)),
            ]),
          ),
          ...children,
        ],
      ),
    );
  }
}

class _Setting extends StatelessWidget {
  final String label;
  final Widget child;
  const _Setting({required this.label, required this.child});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Text(label, style: const TextStyle(color: Colors.white)),
        const Spacer(),
        child,
      ],
    );
  }
}
