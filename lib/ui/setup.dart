import 'package:flutter/material.dart';

import '../platform.dart';
import 'notifications_page.dart';
import 'settings_ui.dart';

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

  Future<void> _pickMotion() async {
    final choice = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Reduced motion'),
        contentPadding: const EdgeInsets.only(top: 12, bottom: 8),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final option in const [
              ('system', 'System'),
              ('always', 'Always'),
              ('never', 'Never'),
            ])
              RadioListTile<String>(
                value: option.$1,
                groupValue: reduced,
                title: Text(option.$2),
                onChanged: (value) => Navigator.of(context).pop(value),
              ),
          ],
        ),
      ),
    );
    if (choice == null) return;
    setState(() => reduced = choice);
    await _command('setReduced', {'value': choice});
  }

  String get _motionLabel => switch (reduced) {
        'always' => 'Always',
        'never' => 'Never',
        _ => 'System',
      };

  @override
  Widget build(BuildContext context) {
    final overlay = _on('overlay');
    final above = _on('accessibility');
    return SettingsPage(
      title: 'Island',
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
          child: Text(
            above
                ? 'Island is on in Accessibility, so the pill draws above the status bar. You can leave this screen.'
                : 'Turn on Island in Accessibility. That puts the pill above the status bar. Until then, display-over-apps keeps it behind the status bar.',
            style: const TextStyle(color: SettingsColors.subtitle, fontSize: 14, height: 1.35),
          ),
        ),
        SettingsSection(
          label: 'Island',
          children: [
            SettingsRow(
              icon: Icons.notifications_none,
              title: 'Active notifications',
              subtitle: 'Every notification on the phone right now',
              trailing: const Icon(Icons.chevron_right, color: SettingsColors.subtitle),
              onTap: () {
                Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsPage()));
              },
            ),
            SettingsCard(
              child: SwitchListTile(
                contentPadding: const EdgeInsets.fromLTRB(16, 4, 12, 4),
                secondary: const Icon(Icons.border_outer, color: SettingsColors.subtitle),
                title: const Text('Keyline', style: TextStyle(color: SettingsColors.title, fontSize: 16, fontWeight: FontWeight.w500)),
                subtitle: const Text('Thin rim on a dark page', style: TextStyle(color: SettingsColors.subtitle, fontSize: 14)),
                value: keyline,
                onChanged: (value) {
                  setState(() => keyline = value);
                  _command('setKeyline', {'value': value});
                },
              ),
            ),
            SettingsRow(
              icon: Icons.motion_photos_off_outlined,
              title: 'Reduced motion',
              subtitle: _motionLabel,
              trailing: const Icon(Icons.chevron_right, color: SettingsColors.subtitle),
              onTap: _pickMotion,
            ),
          ],
        ),
        SettingsSection(
          label: 'Permissions',
          children: [
            _permit(Icons.accessibility_new, 'Above the status bar', above, platform.openAccessibilitySettings),
            _permit(Icons.layers_outlined, 'Display over apps', overlay, platform.openOverlaySettings),
            _permit(Icons.notifications_outlined, 'Notifications', _on('POST_NOTIFICATIONS'), () => platform.request(['android.permission.POST_NOTIFICATIONS'])),
            _permit(Icons.call_outlined, 'Phone', _on('READ_PHONE_STATE') && _on('ANSWER_PHONE_CALLS') && _on('READ_CALL_LOG') && _on('READ_CONTACTS'), () => platform.request(['android.permission.READ_PHONE_STATE', 'android.permission.ANSWER_PHONE_CALLS', 'android.permission.READ_CALL_LOG', 'android.permission.READ_CONTACTS'])),
            _permit(Icons.chat_bubble_outline, 'Messages', _on('notificationAccess'), platform.openNotificationAccess),
            _permit(Icons.sms_outlined, 'SMS', _on('READ_SMS') && _on('RECEIVE_SMS'), () => platform.request(['android.permission.READ_SMS', 'android.permission.RECEIVE_SMS'])),
            _permit(Icons.calendar_today_outlined, 'Calendar', _on('READ_CALENDAR'), () => platform.request(['android.permission.READ_CALENDAR'])),
            _permit(Icons.mic_none, 'Microphone', _on('RECORD_AUDIO'), () => platform.request(['android.permission.RECORD_AUDIO'])),
          ],
        ),
        SettingsSection(
          label: 'Samples',
          children: [
            for (final sample in _samples)
              SettingsRow(
                icon: sample.$1,
                title: sample.$2,
                onTap: sample.$3,
              ),
          ],
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(12, 18, 12, 0),
          child: Text(
            'Tap a sample again, or Clear, to dismiss that preview. A live call, message, timer, or playing session stays, and replaces a preview of the same kind. Hold the island to expand. A tap outside collapses it and still reaches the app underneath.',
            style: TextStyle(color: SettingsColors.subtitle, fontSize: 14, height: 1.35),
          ),
        ),
      ],
    );
  }

  SettingsRow _permit(IconData icon, String title, bool granted, VoidCallback onPressed) {
    return SettingsRow(
      icon: icon,
      title: title,
      subtitle: granted ? 'On' : 'Off',
      onTap: granted ? null : onPressed,
    );
  }

  List<(IconData, String, VoidCallback)> get _samples => [
        (Icons.timer_outlined, 'Timer', () => _command('startTimer')),
        (Icons.music_note_outlined, 'Music', () => _command('playSample')),
        (Icons.call, 'Call', () => _command('incomingCall', {'caller': 'Mochi'})),
        (Icons.local_taxi_outlined, 'Ride', () => _command('startEta', {'kind': 'ride'})),
        (Icons.local_shipping_outlined, 'Delivery', () => _command('startEta', {'kind': 'delivery'})),
        (Icons.mic, 'Record', () => _command('startRecording', {'kind': 'audio'})),
        (Icons.screenshot_monitor_outlined, 'Screen', () => _command('startRecording', {'kind': 'screen'})),
        (Icons.photo_camera_outlined, 'Camera', () => _command('startRecording', {'kind': 'camera'})),
        (Icons.upload_outlined, 'Send', () => _command('startTransfer', {'direction': 'send'})),
        (Icons.download_outlined, 'Receive', () => _command('startTransfer', {'direction': 'receive'})),
        (Icons.cloud_upload_outlined, 'Upload', () => _command('startUpload')),
        (Icons.chat_outlined, 'Message', () => _command('showMessage', {'sender': 'Sam', 'body': 'The photos are in. Take a look when you can.'})),
        (Icons.event_outlined, 'Event', () => _command('pinEvent')),
        (Icons.battery_charging_full, 'Battery', () => _command('showBattery')),
        (Icons.splitscreen_outlined, 'Split', () => _command('split')),
        (Icons.receipt_long_outlined, 'Orders', () => _command('startOrders')),
        (Icons.swap_horiz, 'Handoff', () => _command('handoff')),
        (Icons.clear, 'Clear', () => _command('clear')),
      ];
}
