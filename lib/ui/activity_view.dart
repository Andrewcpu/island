import 'package:flutter/material.dart';

import '../controller.dart';
import '../island/engine.dart';
import '../theme.dart';
import 'bits.dart';
import 'glyphs.dart';
import 'island_view.dart';

class ActivityBody extends StatelessWidget {
  final ActivitySpec spec;
  final IslandSlot slot;

  const ActivityBody({super.key, required this.spec, required this.slot});

  @override
  Widget build(BuildContext context) {
    final island = IslandScope.of(context);
    final id = spec.id;
    if (id == 'battery') return _Battery(island: island, slot: slot);
    if (id == 'call') return _Call(island: island, slot: slot);
    if (id == 'timer' || id == 'timer-done') return _Timer(island: island, slot: slot, done: id == 'timer-done');
    if (id == 'now-playing') return _Player(island: island, slot: slot);
    if (id == 'upload') return _Upload(island: island, slot: slot);
    if (id == 'eta') return _Eta(island: island, slot: slot);
    if (id == 'recording') return _Recording(island: island, slot: slot);
    if (id == 'transfer') return _Transfer(island: island, slot: slot);
    if (id == 'message') return _Message(island: island, slot: slot);
    if (id == 'calendar') return _Calendar(island: island, slot: slot);
    if (id.startsWith('order-')) return _Order(slot: slot, id: id);
    return const SizedBox.shrink();
  }
}

class _Pad extends StatelessWidget {
  final Widget child;
  const _Pad({required this.child});

  @override
  Widget build(BuildContext context) {
    return Padding(padding: const EdgeInsets.fromLTRB(16, 14, 16, 14), child: child);
  }
}

class _Battery extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Battery({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final battery = island.battery;
    final low = battery.level < 0.2 && !battery.charging;
    final color = battery.charging ? IslandColors.charging : (low ? IslandColors.red : Colors.white);
    final percent = '${(battery.level * 100).round()}%';
    if (slot == IslandSlot.leading) {
      return Text(battery.charging ? 'Charging' : (low ? 'Low' : 'Battery'), style: IslandType.compact, maxLines: 1, overflow: TextOverflow.ellipsis);
    }
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          TurnoverText(percent, style: IslandType.compact.copyWith(color: color)),
          const SizedBox(width: 4),
          BatteryGlyph(level: battery.level, charging: battery.charging, color: color, width: slot == IslandSlot.bubble ? 22 : 28),
        ],
      );
    }
    if (slot == IslandSlot.minimal) return Glyph(GlyphId.bolt, color: color, size: 18);
    final detail = battery.charging
        ? (battery.fullInMinutes != null && battery.level < 1 ? 'Full in ${battery.fullInMinutes} min' : 'Charging')
        : (low ? 'Charge soon' : 'Battery');
    return _Pad(
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(battery.charging ? 'Charging' : 'Battery', style: IslandType.title),
                Text(detail, style: IslandType.caption),
              ],
            ),
          ),
          TurnoverText(percent, style: IslandType.value.copyWith(color: color, fontSize: 28)),
          const SizedBox(width: 8),
          BatteryGlyph(level: battery.level, charging: battery.charging, color: color, width: 36),
        ],
      ),
    );
  }
}

class _Call extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Call({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final call = island.call;
    if (call == null) return const SizedBox.shrink();
    if (slot == IslandSlot.minimal) {
      return Glyph(GlyphId.phone, color: call.ringing ? IslandColors.accept : IslandColors.talkGreen, size: 18);
    }
    if (slot == IslandSlot.leading) {
      if (call.ringing) return InitialAvatar(name: call.caller, size: 22);
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Glyph(GlyphId.phone, color: IslandColors.talkGreen, size: 16),
          const SizedBox(width: 4),
          TickingText(since: call.since, style: IslandType.compact.copyWith(color: IslandColors.talkGreen), reduced: island.reduced),
        ],
      );
    }
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) {
      if (call.ringing) return const Glyph(GlyphId.phone, color: IslandColors.accept, size: 18);
      return LevelMeter(color: IslandColors.talkGreen, level: 0.8, bars: slot == IslandSlot.bubble ? 4 : 8, height: 16);
    }
    return _Pad(
      child: Row(
        children: [
          InitialAvatar(name: call.caller, size: 50),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                call.ringing
                    ? Text(call.subtitle, style: IslandType.caption.copyWith(fontSize: 14))
                    : TickingText(since: call.since, style: IslandType.caption.copyWith(color: IslandColors.talkGreen, fontSize: 14), reduced: island.reduced),
                Text(call.caller, style: IslandType.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              ],
            ),
          ),
          if (call.ringing) ...[
            RoundButton(label: 'Decline call from ${call.caller}', color: IslandColors.decline, size: 50, onPressed: island.endCall, child: const Glyph(GlyphId.hangup, size: 26)),
            const SizedBox(width: 10),
            RoundButton(label: 'Accept call from ${call.caller}', color: IslandColors.accept, size: 50, onPressed: island.acceptCall, child: const Glyph(GlyphId.phone, size: 24)),
          ] else ...[
            const LevelMeter(color: IslandColors.talkGreen, bars: 8, height: 22, level: 0.85),
            const SizedBox(width: 10),
            RoundButton(label: 'End call with ${call.caller}', color: IslandColors.decline, size: 50, onPressed: island.endCall, child: const Glyph(GlyphId.hangup, size: 26)),
          ],
        ],
      ),
    );
  }
}

class _Timer extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  final bool done;
  const _Timer({required this.island, required this.slot, required this.done});

  @override
  Widget build(BuildContext context) {
    if (done) {
      if (slot == IslandSlot.expanded) {
        return const _Pad(child: Align(alignment: Alignment.centerLeft, child: Text("Time's up", style: IslandType.title)));
      }
      return const Glyph(GlyphId.timer, color: IslandColors.orange, size: 20);
    }
    final timer = island.timer;
    if (timer == null) return const SizedBox.shrink();
    final color = timer.paused ? IslandColors.secondary : IslandColors.orange;
    final time = TickingText(
      until: timer.endsAt,
      fixed: timer.paused ? timer.remaining : null,
      style: (slot == IslandSlot.expanded ? IslandType.value : IslandType.compact).copyWith(color: color),
      reduced: island.reduced,
    );
    if (slot == IslandSlot.leading) return Glyph(GlyphId.timer, color: color, size: 20);
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) return time;
    if (slot == IslandSlot.minimal) {
      final total = timer.length.inMilliseconds;
      final left = timer.paused ? timer.remaining.inMilliseconds : timer.endsAt!.difference(DateTime.now()).inMilliseconds;
      return ProgressRing(value: total == 0 ? 0 : (left / total).clamp(0, 1), color: IslandColors.orange, size: 22, child: const Glyph(GlyphId.timer, color: IslandColors.orange, size: 11));
    }
    return _Pad(
      child: Row(
        children: [
          RoundButton(
            label: timer.paused ? 'Resume ${timer.label}' : 'Pause ${timer.label}',
            color: IslandColors.orange.withValues(alpha: 0.3),
            iconColor: IslandColors.orange,
            onPressed: island.toggleTimer,
            child: Glyph(timer.paused ? GlyphId.play : GlyphId.pause, color: IslandColors.orange, size: 22),
          ),
          const SizedBox(width: 10),
          RoundButton(label: 'Cancel ${timer.label}', color: IslandColors.fill, onPressed: island.cancelTimer, child: const Glyph(GlyphId.close, size: 18)),
          const Spacer(),
          Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(timer.paused ? 'Paused' : timer.label, style: IslandType.caption.copyWith(color: color, fontWeight: FontWeight.w600)),
              time,
            ],
          ),
        ],
      ),
    );
  }
}

class _Player extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Player({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final player = island.player;
    if (player == null) return const SizedBox.shrink();
    final art = Artwork(seed: '${player.title}${player.artist}', size: slot == IslandSlot.expanded ? 60 : 22, radius: slot == IslandSlot.expanded ? 14 : 6);
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) return art;
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) {
      return LevelMeter(color: Colors.white, active: player.playing, bars: slot == IslandSlot.bubble ? 4 : 6, height: 16, level: player.playing ? 0.9 : 0.2);
    }
    final elapsed = player.playing && player.startedAt != null ? DateTime.now().difference(player.startedAt!) : player.position;
    final fraction = player.duration.inMilliseconds == 0 ? 0.0 : (elapsed.inMilliseconds / player.duration.inMilliseconds).clamp(0, 1).toDouble();
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 16, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              art,
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(player.title, style: IslandType.title.copyWith(fontSize: 16), maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(player.artist, style: const TextStyle(color: Color(0xFF898989), fontSize: 16), maxLines: 1, overflow: TextOverflow.ellipsis),
                  ],
                ),
              ),
              LevelMeter(color: Colors.white, active: player.playing, height: 20, level: player.playing ? 1 : 0.15),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              SizedBox(width: 36, child: Text(formatClock(elapsed), style: const TextStyle(color: Color(0xFF898989), fontSize: 11, fontFeatures: [FontFeature.tabularFigures()]))),
              Expanded(child: SpringFill(value: fraction, color: Colors.white, track: const Color(0x33FFFFFF), reduced: island.reduced)),
              const SizedBox(width: 8),
              Text('−${formatClock(player.duration - elapsed)}', style: const TextStyle(color: Color(0xFF898989), fontSize: 11, fontFeatures: [FontFeature.tabularFigures()])),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              RoundButton(label: 'Previous', color: Colors.transparent, size: 40, onPressed: () => island.skip(-1), child: const Glyph(GlyphId.back, size: 26)),
              RoundButton(label: player.playing ? 'Pause' : 'Play', color: Colors.transparent, size: 48, onPressed: island.togglePlayer, child: Glyph(player.playing ? GlyphId.pause : GlyphId.play, size: 28)),
              RoundButton(label: 'Next', color: Colors.transparent, size: 40, onPressed: () => island.skip(1), child: const Glyph(GlyphId.forward, size: 26)),
              const Spacer(),
              RoundButton(label: 'Audio output ${island.speakerRoute}', color: Colors.transparent, size: 40, onPressed: island.toggleOutput, child: const Glyph(GlyphId.speaker, size: 22)),
            ],
          ),
        ],
      ),
    );
  }
}

class _Upload extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Upload({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final upload = island.upload;
    if (upload == null) return const SizedBox.shrink();
    final art = Artwork(seed: upload.name, size: slot == IslandSlot.expanded ? 52 : 22, radius: slot == IslandSlot.expanded ? 12 : 5);
    Widget ring(double size) {
      return ValueListenableBuilder<double>(
        valueListenable: island.uploadProgress,
        builder: (context, value, _) {
          if (upload.done || value >= 1) {
            return ProgressRing(value: 1, color: IslandColors.blue, size: size, child: Glyph(GlyphId.check, color: IslandColors.blue, size: size * 0.45));
          }
          return ProgressRing(
            value: value,
            color: IslandColors.blue,
            size: size,
            child: slot == IslandSlot.expanded
                ? GestureDetector(onTap: island.cancelUpload, child: Glyph(GlyphId.stop, color: IslandColors.blue, size: size * 0.42))
                : Text('${(value * 100).round()}', style: TextStyle(color: Colors.white, fontSize: size * 0.32, fontWeight: FontWeight.w600)),
          );
        },
      );
    }

    if (slot == IslandSlot.leading) return art;
    if (slot == IslandSlot.minimal || slot == IslandSlot.trailing || slot == IslandSlot.bubble) return ring(22);
    return _Pad(
      child: Row(
        children: [
          art,
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(upload.name, style: IslandType.caption, maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(upload.done ? 'Uploaded' : 'Uploading', style: IslandType.title),
              ],
            ),
          ),
          ring(48),
        ],
      ),
    );
  }
}

class _Eta extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Eta({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final eta = island.eta;
    if (eta == null) return const SizedBox.shrink();
    final color = eta.kind == 'ride' ? IslandColors.green : IslandColors.yellow;
    final icon = eta.kind == 'ride' ? GlyphId.car : GlyphId.package;
    final minutes = TickingText(
      until: eta.arrives,
      style: (slot == IslandSlot.expanded ? IslandType.title : IslandType.compact).copyWith(color: color),
      reduced: island.reduced,
      format: (duration) {
        final mins = duration.isNegative ? 0 : (duration.inSeconds / 60).ceil();
        return slot == IslandSlot.expanded ? '$mins min' : '$mins min';
      },
    );
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) return Glyph(icon, color: color, size: slot == IslandSlot.expanded ? 28 : 20);
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) return minutes;
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Glyph(icon, color: color, size: 28),
              const Spacer(),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(eta.driver, style: IslandType.title),
                  Text('${eta.vehicle} · ${eta.plate}', style: IslandType.caption),
                ],
              ),
            ],
          ),
          const Spacer(),
          Row(
            children: [
              Glyph(icon, color: color, size: 18),
              const SizedBox(width: 8),
              Text('Arriving in ', style: IslandType.subtitle),
              minutes,
              const Spacer(),
              Text(eta.rating.toStringAsFixed(1), style: IslandType.caption),
            ],
          ),
          const SizedBox(height: 10),
          PillButton(label: 'Message ${eta.driver}', color: color.withValues(alpha: 0.18), textColor: color, onPressed: island.messageDriver),
        ],
      ),
    );
  }
}

class _Recording extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Recording({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final recording = island.recording;
    if (recording == null) return const SizedBox.shrink();
    final color = recording.paused ? IslandColors.secondary : IslandColors.red;
    final time = TickingText(
      since: recording.paused ? null : recording.since,
      fixed: recording.paused ? recording.held : null,
      style: IslandType.compact.copyWith(color: color, fontSize: slot == IslandSlot.expanded ? 22 : 15),
      reduced: island.reduced,
      format: (duration) => formatClock(recording.paused ? recording.held : duration + recording.held),
    );
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) {
      return Container(width: 12, height: 12, decoration: BoxDecoration(color: color, shape: BoxShape.circle));
    }
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) return time;
    return _Pad(
      child: Row(
        children: [
          Container(width: 14, height: 14, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(recording.title, style: IslandType.title),
                Text(recording.simulated ? 'Simulated level' : 'Microphone', style: IslandType.caption),
              ],
            ),
          ),
          ValueListenableBuilder<double>(
            valueListenable: island.micLevel,
            builder: (context, level, _) => LevelMeter(color: color, active: !recording.paused, level: recording.simulated ? 0.7 : level, bars: 10, height: 26),
          ),
          const SizedBox(width: 8),
          time,
          RoundButton(label: 'Stop ${recording.title}', color: Colors.transparent, onPressed: island.stopRecording, child: const Glyph(GlyphId.stop, color: IslandColors.red, size: 26)),
        ],
      ),
    );
  }
}

class _Transfer extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Transfer({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final transfer = island.transfer;
    if (transfer == null) return const SizedBox.shrink();
    final sending = transfer.direction == 'send';
    Widget ring(double size) {
      return ValueListenableBuilder<double>(
        valueListenable: island.transferProgress,
        builder: (context, value, _) {
          if (transfer.done || value >= 1) {
            return ProgressRing(value: 1, color: IslandColors.blue, size: size, child: Glyph(GlyphId.check, color: IslandColors.blue, size: size * 0.45));
          }
          return ProgressRing(
            value: value,
            color: IslandColors.blue,
            size: size,
            child: slot == IslandSlot.expanded
                ? GestureDetector(onTap: island.cancelTransfer, child: Glyph(GlyphId.stop, color: IslandColors.blue, size: size * 0.4))
                : Glyph(sending ? GlyphId.arrowUp : GlyphId.arrowDown, color: IslandColors.blue, size: size * 0.45),
          );
        },
      );
    }

    if (slot == IslandSlot.leading) return Artwork(seed: transfer.item, size: 22, radius: 5);
    if (slot == IslandSlot.minimal || slot == IslandSlot.trailing || slot == IslandSlot.bubble) return ring(22);
    return _Pad(
      child: Row(
        children: [
          Stack(
            clipBehavior: Clip.none,
            children: [
              InitialAvatar(name: transfer.device, size: 48),
              Positioned(right: -4, bottom: -4, child: Artwork(seed: transfer.item, size: 22, radius: 5)),
            ],
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${transfer.done ? (sending ? 'Sent' : 'Received') : (sending ? 'Sending' : 'Receiving')} ${transfer.item}', style: IslandType.caption, maxLines: 1, overflow: TextOverflow.ellipsis),
                Text('${sending ? 'To' : 'From'} ${transfer.device}', style: IslandType.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              ],
            ),
          ),
          ring(48),
        ],
      ),
    );
  }
}

class _Message extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Message({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final message = island.message;
    if (message == null) return const SizedBox.shrink();
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) return const Glyph(GlyphId.message, size: 20);
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) {
      return Text(message.sender, style: IslandType.compact, maxLines: 1, overflow: TextOverflow.ellipsis);
    }
    return _Pad(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(message.sender, style: IslandType.title, maxLines: 1, overflow: TextOverflow.ellipsis),
          Text(message.body, style: IslandType.subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
        ],
      ),
    );
  }
}

class _Calendar extends StatelessWidget {
  final IslandController island;
  final IslandSlot slot;
  const _Calendar({required this.island, required this.slot});

  @override
  Widget build(BuildContext context) {
    final event = island.calendar;
    if (event == null) return const SizedBox.shrink();
    final mins = event.start.difference(DateTime.now()).inMinutes;
    final short = mins <= 0 ? 'Now' : '${mins}m';
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) return const Glyph(GlyphId.calendar, color: IslandColors.lagoon, size: 20);
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) return Text(short, style: IslandType.compact.copyWith(color: IslandColors.lagoon));
    final start = TimeOfDay.fromDateTime(event.start).format(context);
    final end = TimeOfDay.fromDateTime(event.end).format(context);
    return _Pad(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(event.title, style: IslandType.title, maxLines: 1, overflow: TextOverflow.ellipsis),
          Text('$start – $end', style: IslandType.subtitle),
          if (event.location.isNotEmpty) Text(event.location, style: IslandType.caption, maxLines: 1, overflow: TextOverflow.ellipsis),
        ],
      ),
    );
  }
}

class _Order extends StatelessWidget {
  final IslandSlot slot;
  final String id;
  const _Order({required this.slot, required this.id});

  @override
  Widget build(BuildContext context) {
    final second = id == 'order-b';
    final label = second ? '1043' : '1042';
    final minutes = second ? '25m' : '12m';
    if (slot == IslandSlot.leading || slot == IslandSlot.minimal) return const Glyph(GlyphId.package, color: IslandColors.green, size: 20);
    if (slot == IslandSlot.trailing || slot == IslandSlot.bubble) return Text(minutes, style: IslandType.compact.copyWith(color: IslandColors.green));
    return _Pad(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('Order $label', style: IslandType.title),
          Text(second ? 'Out for delivery' : 'Preparing · $minutes', style: IslandType.subtitle),
        ],
      ),
    );
  }
}
