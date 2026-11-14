import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'island/engine.dart';
import 'platform.dart';

enum ReducedMotionSetting { system, always, never }

class Track {
  final String title;
  final String artist;
  final Duration duration;
  const Track(this.title, this.artist, this.duration);
}

class BatteryLive {
  final double level;
  final bool charging;
  final bool plugged;
  final int? fullInMinutes;
  const BatteryLive({required this.level, required this.charging, required this.plugged, this.fullInMinutes});
}

class CallLive {
  final String caller;
  final String subtitle;
  final bool simulated;
  final bool ringing;
  final DateTime? since;
  const CallLive({required this.caller, required this.subtitle, required this.simulated, required this.ringing, this.since});

  CallLive copy({bool? ringing, DateTime? since}) => CallLive(
        caller: caller,
        subtitle: subtitle,
        simulated: simulated,
        ringing: ringing ?? this.ringing,
        since: since ?? this.since,
      );
}

class TimerLive {
  final String label;
  final Duration length;
  final DateTime? endsAt;
  final Duration remaining;
  bool get paused => endsAt == null;
  const TimerLive({required this.label, required this.length, required this.endsAt, required this.remaining});
}

class PlayerLive {
  final bool external;
  final String title;
  final String artist;
  final Duration duration;
  final bool playing;
  final DateTime? startedAt;
  final Duration position;
  const PlayerLive({
    required this.external,
    required this.title,
    required this.artist,
    required this.duration,
    required this.playing,
    required this.startedAt,
    required this.position,
  });
}

class UploadLive {
  final String name;
  final bool done;
  const UploadLive(this.name, this.done);
}

class EtaLive {
  final String kind;
  final String driver;
  final String plate;
  final String vehicle;
  final double rating;
  final DateTime arrives;
  const EtaLive({required this.kind, required this.driver, required this.plate, required this.vehicle, required this.rating, required this.arrives});
}

class RecordingLive {
  final String kind;
  final String title;
  final bool paused;
  final bool simulated;
  final DateTime? since;
  final Duration held;
  const RecordingLive({required this.kind, required this.title, required this.paused, required this.simulated, required this.since, required this.held});
}

class TransferLive {
  final String direction;
  final String device;
  final String item;
  final bool done;
  const TransferLive({required this.direction, required this.device, required this.item, required this.done});
}

class MessageLive {
  final String sender;
  final String body;
  final String app;
  final DateTime at;
  final bool simulated;
  const MessageLive({required this.sender, required this.body, required this.app, required this.at, required this.simulated});
}

class CalendarLive {
  final String title;
  final DateTime start;
  final DateTime end;
  final String location;
  const CalendarLive({required this.title, required this.start, required this.end, required this.location});
}

class CalendarEvent {
  final String title;
  final DateTime start;
  final DateTime end;
  final String location;
  const CalendarEvent({required this.title, required this.start, required this.end, required this.location});
}

class IslandController extends ChangeNotifier with WidgetsBindingObserver {
  final IslandEngine engine;
  final IslandPlatform platform;

  IslandController({IslandEngine? engine, IslandPlatform? platform})
      : engine = engine ?? IslandEngine(),
        platform = platform ?? IslandPlatform() {
    this.engine.onPressed = _onPressed;
    this.engine.addListener(_onStructure);
  }

  static const tracks = [
    Track('Low Tide Radio', 'Harbor Birds', Duration(minutes: 3, seconds: 42)),
    Track('Saltwater Static', 'Night Ferry', Duration(minutes: 4, seconds: 5)),
    Track('Glass Pier', 'Juniper Lane', Duration(minutes: 2, seconds: 58)),
  ];

  BatteryLive battery = const BatteryLive(level: 1, charging: false, plugged: false);
  bool batteryOnIsland = false;
  CallLive? call;
  TimerLive? timer;
  PlayerLive? player;
  UploadLive? upload;
  EtaLive? eta;
  RecordingLive? recording;
  TransferLive? transfer;
  MessageLive? message;
  CalendarLive? calendar;
  List<CalendarEvent> upcoming = const [];
  ReducedMotionSetting reducedSetting = ReducedMotionSetting.system;
  bool systemReduced = false;
  bool keyline = true;
  bool wantOverlay = false;
  bool overlayAllowed = false;
  bool notificationAccess = false;
  String speakerRoute = 'Phone';
  String status = '';
  int orderWave = 0;
  final uploadProgress = ValueNotifier<double>(0);
  final transferProgress = ValueNotifier<double>(0);
  final micLevel = ValueNotifier<double>(0.45);

  List<Rect> deviceHoles = const [];
  final Map<String, bool> grants = {};
  Timer? _uploadTimer;
  Timer? _transferTimer;
  Timer? _timerEnd;
  Timer? _batteryHide;
  Timer? _messageHide;
  Timer? _calendarTimer;
  bool _sawCharging = false;
  bool _chargingKnown = false;

  bool get reduced => switch (reducedSetting) {
        ReducedMotionSetting.always => true,
        ReducedMotionSetting.never => false,
        ReducedMotionSetting.system => systemReduced,
      };

  Future<void> init() async {
    WidgetsBinding.instance.addObserver(this);
    final prefs = await SharedPreferences.getInstance();
    reducedSetting = ReducedMotionSetting.values[prefs.getInt('reduced') ?? 0];
    keyline = prefs.getBool('keyline') ?? true;
    wantOverlay = prefs.getBool('overlay') ?? false;
    engine.showKeyline = keyline;
    engine.setReduced(reduced);
    platform.listen(_onEvent);
    deviceHoles = await platform.cutouts();
    await refreshDevice();
    _calendarTimer = Timer.periodic(const Duration(minutes: 1), (_) => refreshCalendar());
  }

  Future<void> refreshDevice() async {
    final batteryMap = await platform.battery();
    if (batteryMap['level'] != null) _applyBattery(batteryMap, initial: true);
    final perms = await platform.permissions();
    _readPerms(perms);
    await refreshCalendar();
    notifyListeners();
  }

  void _readPerms(Map<String, dynamic> perms) {
    for (final entry in perms.entries) {
      if (entry.value is bool) grants[entry.key] = entry.value as bool;
    }
    notificationAccess = grants['notificationAccess'] ?? false;
    overlayAllowed = grants['overlay'] ?? false;
  }

  void setSystemReduced(bool value) {
    if (systemReduced == value) return;
    systemReduced = value;
    engine.setReduced(reduced);
    notifyListeners();
  }

  Future<void> setReducedSetting(ReducedMotionSetting value) async {
    reducedSetting = value;
    engine.setReduced(reduced);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('reduced', value.index);
    notifyListeners();
  }

  Future<void> setKeyline(bool value) async {
    keyline = value;
    engine.showKeyline = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('keyline', value);
    notifyListeners();
  }

  Future<void> setWantOverlay(bool value) async {
    wantOverlay = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('overlay', value);
    if (value && !overlayAllowed) await platform.openOverlaySettings();
    notifyListeners();
  }

  Future<void> request(List<String> names) async {
    final result = await platform.request(names);
    _readPerms(result);
    if (result['error'] != null) status = 'Permission was not granted.';
    notifyListeners();
  }

  void _onEvent(Map<String, dynamic> event) {
    switch (event['type']) {
      case 'battery':
        _applyBattery(event, initial: false);
      case 'phone':
        _onPhone('${event['state']}', event['number']?.toString());
      case 'message':
        showMessage(
          sender: event['sender']?.toString() ?? 'Message',
          body: event['body']?.toString() ?? '',
          app: event['app']?.toString() ?? 'Messages',
          simulated: false,
        );
      case 'media':
        _onMedia(event);
      case 'mic':
        final level = (event['level'] as num?)?.toDouble();
        if (level != null) micLevel.value = level.clamp(0, 1);
      case 'permissions':
        _readPerms(event);
        notifyListeners();
    }
  }

  void _applyBattery(Map<String, dynamic> event, {required bool initial}) {
    final level = ((event['level'] as num?)?.toDouble() ?? 0) / ((event['scale'] as num?)?.toDouble() ?? 100);
    final charging = event['charging'] == true || event['full'] == true;
    final plugged = event['plugged'] == true || charging;
    final minutes = (event['fullInMinutes'] as num?)?.toInt();
    final becameCharging = charging && _chargingKnown && !_sawCharging;
    battery = BatteryLive(level: level.clamp(0, 1), charging: charging, plugged: plugged, fullInMinutes: minutes);
    _sawCharging = charging;
    _chargingKnown = true;
    final low = level < 0.2 && !charging;
    if (!initial && becameCharging) {
      _showBattery(transient: true);
    } else if (low) {
      _showBattery(transient: false);
    } else if (batteryOnIsland && !low && !becameCharging) {
      if (!charging) _hideBattery();
    }
    if (batteryOnIsland) _syncBattery();
    notifyListeners();
  }

  void _showBattery({required bool transient}) {
    batteryOnIsland = true;
    _batteryHide?.cancel();
    _syncBattery();
    if (transient) {
      _batteryHide = Timer(const Duration(milliseconds: 3300), _hideBattery);
    }
  }

  void _hideBattery() {
    if (battery.level < 0.2 && !battery.charging) return;
    batteryOnIsland = false;
    engine.end('battery');
    notifyListeners();
  }

  void _syncBattery() {
    final charging = battery.charging;
    final low = battery.level < 0.2 && !charging;
    final label = charging ? 'Charging' : (low ? 'Low Battery' : 'Battery');
    engine.start(ActivitySpec(
      id: 'battery',
      label: '$label ${(battery.level * 100).round()}%',
      priority: 10,
      leading: 72,
      trailing: 72,
      minimal: 22,
      expandedBody: 48,
    ));
  }

  void showBatteryNow() => _showBattery(transient: battery.charging || battery.level >= 0.2);

  void _onPhone(String state, String? number) {
    if (state == 'ringing') {
      incomingCall(caller: (number == null || number.isEmpty) ? 'Unknown' : number, subtitle: 'Phone', simulated: false);
    } else if (state == 'offhook') {
      if (call != null && !call!.simulated && call!.ringing) {
        acceptCall(fromSystem: true);
      }
      else if (call != null && !call!.simulated) {
        call = call!.copy(ringing: false, since: call!.since ?? DateTime.now());
        _syncCall(expand: false);
      }
    } else if (state == 'idle' && call != null && !call!.simulated) {
      endCall(fromSystem: true);
    }
  }

  void incomingCall({required String caller, String subtitle = 'Audio call', bool simulated = true}) {
    call = CallLive(caller: caller, subtitle: subtitle, simulated: simulated, ringing: true);
    _syncCall(expand: true);
    status = simulated ? 'Simulated call. Accept and decline use the same island as a live call.' : 'Incoming call.';
    notifyListeners();
  }

  Future<void> acceptCall({bool fromSystem = false}) async {
    final current = call;
    if (current == null) return;
    if (!current.simulated && !fromSystem) {
      final ok = await platform.acceptCall();
      if (!ok) status = 'Android did not let this app answer. The island still shows the answered call.';
    }
    call = current.copy(ringing: false, since: DateTime.now());
    _syncCall(expand: false);
    engine.collapse();
    notifyListeners();
  }

  Future<void> endCall({bool fromSystem = false}) async {
    final current = call;
    if (current == null) return;
    if (!current.simulated && !fromSystem) await platform.endCall();
    call = null;
    engine.end('call');
    notifyListeners();
  }

  void _syncCall({required bool expand}) {
    final current = call;
    if (current == null) return;
    engine.start(ActivitySpec(
      id: 'call',
      label: current.ringing ? 'Incoming call from ${current.caller}' : 'On a call with ${current.caller}',
      priority: 100,
      leading: current.ringing ? 28 : 66,
      trailing: current.ringing ? 22 : 66,
      minimal: 22,
      expandedBody: 50,
    ));
    if (expand) engine.expand('call');
  }

  void startTimer({Duration length = const Duration(minutes: 5), String label = 'Timer'}) {
    timer = TimerLive(label: label, length: length, endsAt: DateTime.now().add(length), remaining: length);
    _armTimer();
    _syncTimer();
    notifyListeners();
  }

  void toggleTimer() {
    final current = timer;
    if (current == null) return;
    if (current.paused) {
      timer = TimerLive(label: current.label, length: current.length, endsAt: DateTime.now().add(current.remaining), remaining: current.remaining);
    } else {
      final left = current.endsAt!.difference(DateTime.now());
      timer = TimerLive(label: current.label, length: current.length, endsAt: null, remaining: left.isNegative ? Duration.zero : left);
    }
    _armTimer();
    _syncTimer();
    notifyListeners();
  }

  void cancelTimer() {
    _timerEnd?.cancel();
    timer = null;
    engine.end('timer');
    notifyListeners();
  }

  void _armTimer() {
    _timerEnd?.cancel();
    final current = timer;
    if (current == null || current.paused) return;
    _timerEnd = Timer(current.endsAt!.difference(DateTime.now()), () {
      timer = null;
      engine.end('timer');
      engine.start(const ActivitySpec(id: 'timer-done', label: "Time's up", priority: 60, leading: 22, trailing: 48, minimal: 22, expandedBody: 48), alert: true);
      Timer(const Duration(milliseconds: 6400), () => engine.end('timer-done'));
      notifyListeners();
    });
  }

  void _syncTimer() {
    final current = timer;
    if (current == null) return;
    engine.start(ActivitySpec(
      id: 'timer',
      label: current.paused ? '${current.label} paused' : '${current.label} running',
      priority: 50,
      leading: 22,
      trailing: 46,
      minimal: 22,
      expandedBody: 56,
    ));
  }

  void playSample() {
    final track = tracks.first;
    player = PlayerLive(
      external: false,
      title: track.title,
      artist: track.artist,
      duration: track.duration,
      playing: true,
      startedAt: DateTime.now(),
      position: Duration.zero,
    );
    _syncPlayer();
    notifyListeners();
  }

  void _onMedia(Map<String, dynamic> event) {
    final title = event['title']?.toString();
    if (title == null || title.isEmpty) return;
    final playing = event['playing'] == true;
    final duration = Duration(milliseconds: (event['duration'] as num?)?.toInt() ?? 0);
    final position = Duration(milliseconds: (event['position'] as num?)?.toInt() ?? 0);
    player = PlayerLive(
      external: true,
      title: title,
      artist: event['artist']?.toString() ?? '',
      duration: duration.inMilliseconds > 0 ? duration : const Duration(minutes: 3),
      playing: playing,
      startedAt: playing ? DateTime.now().subtract(position) : null,
      position: position,
    );
    _syncPlayer();
    notifyListeners();
  }

  void togglePlayer() {
    final current = player;
    if (current == null) return;
    if (current.external) {
      platform.mediaAction(current.playing ? 'pause' : 'play');
      return;
    }
    if (current.playing) {
      final at = DateTime.now().difference(current.startedAt!);
      player = PlayerLive(external: false, title: current.title, artist: current.artist, duration: current.duration, playing: false, startedAt: null, position: at);
    } else {
      player = PlayerLive(
        external: false,
        title: current.title,
        artist: current.artist,
        duration: current.duration,
        playing: true,
        startedAt: DateTime.now().subtract(current.position),
        position: current.position,
      );
    }
    _syncPlayer();
    notifyListeners();
  }

  void skip(int delta) {
    final current = player;
    if (current == null) return;
    if (current.external) {
      platform.mediaAction(delta > 0 ? 'next' : 'previous');
      return;
    }
    final index = tracks.indexWhere((track) => track.title == current.title);
    final next = tracks[(index + delta) % tracks.length];
    player = PlayerLive(external: false, title: next.title, artist: next.artist, duration: next.duration, playing: true, startedAt: DateTime.now(), position: Duration.zero);
    engine.updateSpec(ActivitySpec(id: 'now-playing', label: '${next.title}, ${next.artist}', priority: 40, leading: 22, trailing: 36, minimal: 22, expandedBody: 144), crossfade: true);
    notifyListeners();
  }

  Future<void> toggleOutput() async {
    final speaker = speakerRoute != 'Speaker';
    speakerRoute = speaker ? 'Speaker' : 'Phone';
    await platform.setSpeaker(speaker);
    status = 'Audio route: $speakerRoute. Carrier calls may ignore this unless the dialer owns the audio.';
    notifyListeners();
  }

  void stopPlayer() {
    player = null;
    engine.end('now-playing');
    notifyListeners();
  }

  void _syncPlayer() {
    final current = player;
    if (current == null) return;
    engine.start(ActivitySpec(
      id: 'now-playing',
      label: '${current.title}, ${current.artist}',
      priority: 40,
      leading: 22,
      trailing: 36,
      minimal: 22,
      expandedBody: 144,
    ));
  }

  void startUpload() {
    upload = const UploadLive('biscuit-in-the-sun.jpg', false);
    uploadProgress.value = 0;
    _uploadTimer?.cancel();
    _syncUpload();
    _uploadTimer = Timer.periodic(const Duration(milliseconds: 220), (timer) {
      final next = (uploadProgress.value + 0.035).clamp(0, 1).toDouble();
      uploadProgress.value = next;
      if (next >= 1) {
        timer.cancel();
        upload = const UploadLive('biscuit-in-the-sun.jpg', true);
        notifyListeners();
        Future<void>.delayed(const Duration(milliseconds: 1600), () {
          if (upload?.done == true) {
            upload = null;
            engine.end('upload');
            notifyListeners();
          }
        });
      }
    });
    notifyListeners();
  }

  void cancelUpload() {
    _uploadTimer?.cancel();
    upload = null;
    engine.end('upload');
    notifyListeners();
  }

  void _syncUpload() {
    engine.start(const ActivitySpec(id: 'upload', label: 'Uploading biscuit-in-the-sun.jpg', priority: 30, leading: 22, trailing: 22, minimal: 22, expandedBody: 64));
  }

  void startEta({String kind = 'ride'}) {
    eta = EtaLive(
      kind: kind,
      driver: kind == 'ride' ? 'Alex Kim' : 'Parcel',
      plate: kind == 'ride' ? '8YQ 214' : 'Door',
      vehicle: kind == 'ride' ? 'Blue hatchback' : 'Bike',
      rating: 4.9,
      arrives: DateTime.now().add(const Duration(minutes: 6)),
    );
    engine.start(ActivitySpec(
      id: 'eta',
      label: kind == 'ride' ? 'Ride arriving' : 'Delivery arriving',
      priority: 25,
      leading: kind == 'ride' ? 22 : 22,
      trailing: 52,
      minimal: 22,
      expandedBody: 112,
    ));
    notifyListeners();
  }

  void messageDriver() {
    final current = eta;
    if (current == null) return;
    status = 'Message sent to ${current.driver}.';
    notifyListeners();
  }

  void cancelEta() {
    eta = null;
    engine.end('eta');
    notifyListeners();
  }

  Future<void> startRecording({String kind = 'audio'}) async {
    var simulated = kind != 'audio';
    if (kind == 'audio') {
      final ok = await platform.startMic();
      simulated = !ok;
      if (simulated) {
        status = 'Microphone permission was not granted. The recording island is using a simulated level.';
        micLevel.value = 0.55;
      }
    } else {
      status = kind == 'screen'
          ? 'Screen capture needs a MediaProjection consent each time. This island shows the recording state; the meter is simulated.'
          : 'Camera capture stays in the island UI. The meter is simulated.';
    }
    recording = RecordingLive(kind: kind, title: kind == 'audio' ? 'Voice memo' : (kind == 'screen' ? 'Screen recording' : 'Camera'), paused: false, simulated: simulated, since: DateTime.now(), held: Duration.zero);
    _syncRecording();
    notifyListeners();
  }

  void toggleRecording() {
    final current = recording;
    if (current == null) return;
    if (current.paused) {
      recording = RecordingLive(kind: current.kind, title: current.title, paused: false, simulated: current.simulated, since: DateTime.now(), held: current.held);
    } else {
      final held = current.held + DateTime.now().difference(current.since!);
      recording = RecordingLive(kind: current.kind, title: current.title, paused: true, simulated: current.simulated, since: null, held: held);
    }
    _syncRecording();
    notifyListeners();
  }

  Future<void> stopRecording() async {
    await platform.stopMic();
    recording = null;
    engine.end('recording');
    notifyListeners();
  }

  void _syncRecording() {
    final current = recording;
    if (current == null) return;
    engine.start(ActivitySpec(
      id: 'recording',
      label: '${current.title} ${current.paused ? 'paused' : 'recording'}',
      priority: 70,
      leading: 22,
      trailing: 48,
      minimal: 22,
      expandedBody: 78,
    ));
  }

  void startTransfer({String direction = 'send'}) {
    transfer = TransferLive(direction: direction, device: "Maya's Pixel", item: 'biscuit-in-the-sun.jpg', done: false);
    transferProgress.value = 0;
    _transferTimer?.cancel();
    _syncTransfer();
    _transferTimer = Timer.periodic(const Duration(milliseconds: 240), (timer) {
      final next = (transferProgress.value + 0.04).clamp(0, 1).toDouble();
      transferProgress.value = next;
      if (next >= 1) {
        timer.cancel();
        final current = transfer;
        if (current == null) return;
        transfer = TransferLive(direction: current.direction, device: current.device, item: current.item, done: true);
        notifyListeners();
        Future<void>.delayed(const Duration(milliseconds: 1500), () {
          if (transfer?.done == true) {
            transfer = null;
            engine.end('transfer');
            notifyListeners();
          }
        });
      }
    });
    status = 'Quick Share is not available to a normal app. This is the island transfer, driven on a timer.';
    notifyListeners();
  }

  void cancelTransfer() {
    _transferTimer?.cancel();
    transfer = null;
    engine.end('transfer');
    notifyListeners();
  }

  void _syncTransfer() {
    final current = transfer;
    if (current == null) return;
    final sending = current.direction == 'send';
    engine.start(ActivitySpec(
      id: 'transfer',
      label: '${sending ? 'Sending' : 'Receiving'} ${current.item} ${sending ? 'to' : 'from'} ${current.device}',
      priority: 30,
      leading: 22,
      trailing: 22,
      minimal: 22,
      expandedBody: 72,
    ));
  }

  void showMessage({required String sender, required String body, String app = 'Messages', bool simulated = true}) {
    message = MessageLive(sender: sender, body: body, app: app, at: DateTime.now(), simulated: simulated);
    engine.start(
      ActivitySpec(id: 'message', label: 'Message from $sender', priority: 80, group: 'inbox', leading: 22, trailing: 64, minimal: 22, expandedBody: 72),
      alert: true,
    );
    _messageHide?.cancel();
    _messageHide = Timer(const Duration(seconds: 10), () {
      message = null;
      engine.end('message');
      notifyListeners();
    });
    notifyListeners();
  }

  Future<void> refreshCalendar() async {
    final rows = await platform.calendar();
    upcoming = [
      for (final row in rows)
        CalendarEvent(
          title: row['title']?.toString() ?? 'Event',
          start: DateTime.fromMillisecondsSinceEpoch((row['start'] as num?)?.toInt() ?? 0),
          end: DateTime.fromMillisecondsSinceEpoch((row['end'] as num?)?.toInt() ?? 0),
          location: row['location']?.toString() ?? '',
        ),
    ];
    final now = DateTime.now();
    CalendarEvent? next;
    for (final event in upcoming) {
      if (event.end.isAfter(now)) {
        next = event;
        break;
      }
    }
    if (next != null && next.start.difference(now) <= const Duration(minutes: 20)) {
      calendar = CalendarLive(title: next.title, start: next.start, end: next.end, location: next.location);
      final starting = !next.start.isAfter(now);
      _syncCalendar(alert: starting && next.start.difference(now).inMinutes >= -1);
    }
    notifyListeners();
  }

  void pinNextEvent() {
    if (upcoming.isEmpty) {
      calendar = CalendarLive(
        title: 'Design review',
        start: DateTime.now().add(const Duration(minutes: 15)),
        end: DateTime.now().add(const Duration(minutes: 45)),
        location: 'Studio',
      );
      status = 'No calendar events were readable. Showing a sample event on the same island path.';
    } else {
      final event = upcoming.first;
      calendar = CalendarLive(title: event.title, start: event.start, end: event.end, location: event.location);
    }
    _syncCalendar(alert: false);
    notifyListeners();
  }

  void _syncCalendar({required bool alert}) {
    final current = calendar;
    if (current == null) return;
    final spec = ActivitySpec(id: 'calendar', label: current.title, priority: 20, leading: 22, trailing: 48, minimal: 22, expandedBody: 78);
    if (alert) {
      engine.start(spec, alert: true);
    } else {
      engine.start(spec);
    }
  }

  void dismissCalendar() {
    calendar = null;
    engine.end('calendar');
    notifyListeners();
  }

  void startOrders() {
    orderWave++;
    engine.start(ActivitySpec(id: 'order-a', label: 'Order 1042, 12 minutes', priority: 22, group: 'orders', leading: 22, trailing: 48, minimal: 22, expandedBody: 64));
    engine.start(const ActivitySpec(id: 'order-b', label: 'Order 1043, 25 minutes', priority: 22, group: 'orders', leading: 22, trailing: 48, minimal: 22, expandedBody: 64));
    status = 'Two orders share a group. Only 1042 shows until the other alerts.';
    notifyListeners();
  }

  void handoffOrder() {
    engine.updateSpec(const ActivitySpec(id: 'order-b', label: 'Order 1043 is out for delivery', priority: 22, group: 'orders', leading: 22, trailing: 48, minimal: 22, expandedBody: 64), alert: true);
    status = 'The island dismisses 1042, waits, then expands 1043.';
    notifyListeners();
  }

  void clearOrders() {
    engine.end('order-a');
    engine.end('order-b');
    notifyListeners();
  }

  void _onPressed(String id) {
    status = switch (id) {
      'call' => 'Call tapped. Long-press the pill to expand it.',
      'timer' => 'Timer tapped.',
      'now-playing' => 'Now playing tapped.',
      _ => 'Opened $id.',
    };
    notifyListeners();
  }

  void _onStructure() {
    final shown = engine.visible;
    if (shown.isEmpty) {
      platform.clearLive();
      return;
    }
    final primary = shown.first;
    platform.setLive(title: primary.label, body: shown.length > 1 ? shown[1].label : 'Live on the island');
    notifyListeners();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refreshDevice();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _uploadTimer?.cancel();
    _transferTimer?.cancel();
    _timerEnd?.cancel();
    _batteryHide?.cancel();
    _messageHide?.cancel();
    _calendarTimer?.cancel();
    engine.dispose();
    super.dispose();
  }
}

class IslandScope extends InheritedWidget {
  final IslandController controller;

  const IslandScope({super.key, required this.controller, required super.child});

  static IslandController of(BuildContext context) => context.dependOnInheritedWidgetOfExactType<IslandScope>()!.controller;

  @override
  bool updateShouldNotify(IslandScope oldWidget) => true;
}
