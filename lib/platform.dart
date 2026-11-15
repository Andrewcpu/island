import 'dart:async';

import 'package:flutter/services.dart';

/// Android platform channel. Every call fails soft when the host has no implementation,
/// which is what widget tests and a denied permission look like to the UI.
class IslandPlatform {
  static const _methods = MethodChannel('dev.islandcn/platform');
  static const _events = EventChannel('dev.islandcn/events');

  StreamSubscription<dynamic> listen(void Function(Map<String, dynamic> event) onEvent) {
    return _events.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        onEvent(event.map((key, value) => MapEntry('$key', value)));
      }
    }, onError: (Object error, StackTrace stack) {});
  }

  Future<Map<String, dynamic>> _call(String method, [Object? args]) async {
    try {
      final result = await _methods.invokeMethod<Object?>(method, args);
      if (result is Map) return result.map((key, value) => MapEntry('$key', value));
      return {'ok': result ?? true};
    } on MissingPluginException {
      return {'unsupported': true};
    } on PlatformException catch (error) {
      return {'error': error.code, 'message': error.message ?? ''};
    }
  }

  Future<({Size size, List<Rect> cutouts})> display() async {
    final raw = await _call('display');
    return (
      size: Size((raw['width'] as num?)?.toDouble() ?? 0, (raw['height'] as num?)?.toDouble() ?? 0),
      cutouts: _rects(raw['cutouts']),
    );
  }

  List<Rect> _rects(Object? list) {
    if (list is! List) return const [];
    return [
      for (final item in list)
        if (item is Map)
          Rect.fromLTRB(
            (item['left'] as num?)?.toDouble() ?? 0,
            (item['top'] as num?)?.toDouble() ?? 0,
            (item['right'] as num?)?.toDouble() ?? 0,
            (item['bottom'] as num?)?.toDouble() ?? 0,
          ),
    ];
  }

  Future<List<Rect>> cutouts() async => _rects((await _call('cutout'))['cutouts']);

  Future<Rect?> overlayFrame() async {
    final raw = await _call('overlayFrame');
    final width = (raw['width'] as num?)?.toDouble() ?? 0;
    final height = (raw['height'] as num?)?.toDouble() ?? 0;
    if (width < 1 || height < 1) return null;
    final left = (raw['left'] as num?)?.toDouble() ?? 0;
    final top = (raw['top'] as num?)?.toDouble() ?? 0;
    return Rect.fromLTWH(left, top, width, height);
  }

  Future<void> setFrame(Rect rect, {List<Rect> touches = const []}) => _call('setFrame', {
        'left': rect.left,
        'top': rect.top,
        'width': rect.width,
        'height': rect.height,
        'touches': [
          for (final touch in touches) {'left': touch.left, 'top': touch.top, 'right': touch.right, 'bottom': touch.bottom},
        ],
      });

  Future<void> command(String name, [Map<String, dynamic> args = const {}]) => _call('command', {'name': name, 'args': args});

  Future<void> ensureOverlay() => _call('ensureOverlay');

  Future<void> overlayReady() => _call('ready');

  Future<Map<String, dynamic>> battery() => _call('battery');

  Future<List<Map<String, dynamic>>> calendar() async {
    final raw = await _call('calendar');
    final list = raw['events'];
    if (list is! List) return const [];
    return [
      for (final item in list)
        if (item is Map) item.map((key, value) => MapEntry('$key', value)),
    ];
  }

  Future<Map<String, dynamic>> permissions() => _call('permissionStatus');

  Future<Map<String, dynamic>> activeNotifications() => _call('activeNotifications');

  Future<Map<String, dynamic>> request(List<String> permissions) => _call('requestPermissions', {'permissions': permissions});

  Future<void> openNotificationAccess() => _call('openNotificationAccess');

  Future<void> openOverlaySettings() => _call('openOverlaySettings');

  Future<void> openAccessibilitySettings() => _call('openAccessibilitySettings');

  Future<void> openAppSettings() => _call('openAppSettings');

  Future<bool> acceptCall() async => (await _call('acceptCall'))['ok'] == true;

  Future<bool> endCall() async => (await _call('endCall'))['ok'] == true;

  Future<bool> startMic() async => (await _call('startMic'))['ok'] == true;

  Future<void> stopMic() => _call('stopMic');

  Future<void> setSpeaker(bool speaker) => _call('setSpeaker', {'speaker': speaker});

  Future<void> mediaAction(String action) => _call('mediaAction', {'action': action});

  Future<void> setLive({required String title, required String body}) => _call('setLive', {'title': title, 'body': body});

  Future<void> clearLive() => _call('clearLive');
}
