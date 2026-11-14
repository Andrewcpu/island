import 'package:flutter/material.dart';

import '../platform.dart';
import '../theme.dart';

class SetupPage extends StatefulWidget {
  const SetupPage({super.key});

  @override
  State<SetupPage> createState() => _SetupPageState();
}

class _SetupPageState extends State<SetupPage> with WidgetsBindingObserver {
  final IslandPlatform platform = IslandPlatform();
  Map<String, dynamic> grants = {};
  String reduced = 'system';
  bool keyline = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      platform.ensureOverlay();
      _refresh();
    }
  }

  Future<void> _refresh() async {
    final next = await platform.permissions();
    if (!mounted) return;
    setState(() => grants = next);
  }

  bool _on(String key) => grants[key] == true;

  Future<void> _command(String name, [Map<String, dynamic> args = const {}]) async {
    await platform.ensureOverlay();
    await platform.command(name, args);
  }

  @override
  Widget build(BuildContext context) {
    final overlay = _on('overlay');
    final above = _on('accessibility');
    return Scaffold(
      backgroundColor: IslandColors.page,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
          children: [
            const Text('Island', style: TextStyle(color: Colors.white, fontSize: 34, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            Text(
              above
                  ? 'Island is on in Accessibility, so the pill draws above the status bar. You can leave this screen.'
                  : 'Turn on Island in Accessibility. That puts the pill above the status bar. Until then, display-over-apps keeps it behind the status bar.',
              style: const TextStyle(color: IslandColors.secondary, fontSize: 15, height: 1.35),
            ),
            const SizedBox(height: 18),
            if (!above)
              FilledButton(
                onPressed: platform.openAccessibilitySettings,
                child: const Text('Enable Island in Accessibility'),
              ),
            const SizedBox(height: 16),
            const _Heading('Permissions'),
            _Row('Above the status bar', above, 'Enable', platform.openAccessibilitySettings),
            _Row('Display over apps', overlay, 'Allow', platform.openOverlaySettings),
            _Row('Notifications', _on('POST_NOTIFICATIONS'), 'Allow', () => platform.request(['android.permission.POST_NOTIFICATIONS'])),
            _Row('Phone', _on('READ_PHONE_STATE') && _on('ANSWER_PHONE_CALLS'), 'Allow', () => platform.request(['android.permission.READ_PHONE_STATE', 'android.permission.ANSWER_PHONE_CALLS'])),
            _Row('Messages', _on('notificationAccess'), 'Allow', platform.openNotificationAccess),
            _Row('SMS', _on('READ_SMS') && _on('RECEIVE_SMS'), 'Allow', () => platform.request(['android.permission.READ_SMS', 'android.permission.RECEIVE_SMS'])),
            _Row('Calendar', _on('READ_CALENDAR'), 'Allow', () => platform.request(['android.permission.READ_CALENDAR'])),
            _Row('Microphone', _on('RECORD_AUDIO'), 'Allow', () => platform.request(['android.permission.RECORD_AUDIO'])),
            const SizedBox(height: 18),
            const _Heading('Try an activity'),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _Chip('Timer', () => _command('startTimer')),
                _Chip('Music', () => _command('playSample')),
                _Chip('Call', () => _command('incomingCall', {'caller': 'Mochi'})),
                _Chip('Ride', () => _command('startEta', {'kind': 'ride'})),
                _Chip('Delivery', () => _command('startEta', {'kind': 'delivery'})),
                _Chip('Record', () => _command('startRecording', {'kind': 'audio'})),
                _Chip('Screen', () => _command('startRecording', {'kind': 'screen'})),
                _Chip('Camera', () => _command('startRecording', {'kind': 'camera'})),
                _Chip('Send', () => _command('startTransfer', {'direction': 'send'})),
                _Chip('Receive', () => _command('startTransfer', {'direction': 'receive'})),
                _Chip('Upload', () => _command('startUpload')),
                _Chip('Message', () => _command('showMessage', {'sender': 'Sam', 'body': 'The photos are in. Take a look when you can.'})),
                _Chip('Event', () => _command('pinEvent')),
                _Chip('Battery', () => _command('showBattery')),
                _Chip('Split', () => _command('split')),
                _Chip('Orders', () => _command('startOrders')),
                _Chip('Handoff', () => _command('handoff')),
                _Chip('Clear', () => _command('clear')),
              ],
            ),
            const SizedBox(height: 18),
            const _Heading('Island'),
            _Setting(
              label: 'Reduced motion',
              child: DropdownButton<String>(
                value: reduced,
                dropdownColor: IslandColors.card,
                style: const TextStyle(color: Colors.white),
                items: const [
                  DropdownMenuItem(value: 'system', child: Text('System')),
                  DropdownMenuItem(value: 'always', child: Text('Always')),
                  DropdownMenuItem(value: 'never', child: Text('Never')),
                ],
                onChanged: (value) {
                  if (value == null) return;
                  setState(() => reduced = value);
                  _command('setReduced', {'value': value});
                },
              ),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Keyline', style: TextStyle(color: Colors.white)),
              subtitle: const Text('Thin rim on a dark page', style: TextStyle(color: IslandColors.secondary)),
              value: keyline,
              activeThumbColor: IslandColors.lagoon,
              onChanged: (value) {
                setState(() => keyline = value);
                _command('setKeyline', {'value': value});
              },
            ),
            const SizedBox(height: 8),
            const Text(
              'Tap a sample again, or Clear, to return the island to idle. Tap the island for its action. Hold it to expand. A tap outside collapses it and still reaches the app underneath.',
              style: TextStyle(color: IslandColors.tertiary, fontSize: 13, height: 1.35),
            ),
          ],
        ),
      ),
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

class _Row extends StatelessWidget {
  final String title;
  final bool granted;
  final String action;
  final VoidCallback onPressed;
  const _Row(this.title, this.granted, this.action, this.onPressed);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(title, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600))),
          Text(granted ? 'On' : 'Off', style: TextStyle(color: granted ? IslandColors.lagoon : IslandColors.secondary)),
          const SizedBox(width: 8),
          TextButton(onPressed: granted ? null : onPressed, child: Text(action)),
        ],
      ),
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
