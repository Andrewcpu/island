import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../platform.dart';
import '../theme.dart';

/// Every notification the listener can see, refreshed as they change.
class NotificationsPage extends StatefulWidget {
  const NotificationsPage({super.key});

  @override
  State<NotificationsPage> createState() => _NotificationsPageState();
}

class _NotificationsPageState extends State<NotificationsPage> with WidgetsBindingObserver {
  final IslandPlatform platform = IslandPlatform();
  StreamSubscription<dynamic>? _events;
  Timer? _debounce;
  List<Map<String, dynamic>> notes = const [];
  String? reason;
  bool loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _events = platform.listen((event) {
      if (event['type'] == 'notifications') _schedule();
    });
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _events?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  void _schedule() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 200), _load);
  }

  Future<void> _load() async {
    final raw = await platform.activeNotifications();
    if (!mounted) return;
    final list = raw['notifications'];
    setState(() {
      loading = false;
      reason = raw['ok'] == true ? null : (raw['reason'] ?? raw['message'] ?? 'Could not read notifications').toString();
      notes = [
        for (final item in list is List ? list : const [])
          if (item is Map) item.map((key, value) => MapEntry('$key', value)),
      ];
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: IslandColors.page,
      appBar: AppBar(
        backgroundColor: IslandColors.page,
        foregroundColor: Colors.white,
        title: Text(loading ? 'Notifications' : 'Notifications (${notes.length})'),
        actions: [
          IconButton(onPressed: _load, tooltip: 'Refresh', icon: const Icon(Icons.refresh)),
        ],
      ),
      body: RefreshIndicator(
        color: IslandColors.lagoon,
        onRefresh: _load,
        child: loading && notes.isEmpty && reason == null
            ? ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: const [
                  SizedBox(height: 48),
                  Center(child: CircularProgressIndicator(color: IslandColors.lagoon)),
                ],
              )
            : reason != null
            ? ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
                children: [
                  Text(reason!, style: const TextStyle(color: Colors.white, fontSize: 16, height: 1.35)),
                  const SizedBox(height: 16),
                  FilledButton(onPressed: platform.openNotificationAccess, child: const Text('Allow notification access')),
                ],
              )
            : notes.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
                    children: const [
                      Text('No active notifications.', style: TextStyle(color: IslandColors.secondary, fontSize: 16)),
                    ],
                  )
                : ListView.separated(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                    itemCount: notes.length,
                    separatorBuilder: (context, index) => const SizedBox(height: 12),
                    itemBuilder: (context, index) => _NoteCard(note: notes[index]),
                  ),
      ),
    );
  }
}

class _NoteCard extends StatelessWidget {
  final Map<String, dynamic> note;
  const _NoteCard({required this.note});

  @override
  Widget build(BuildContext context) {
    final progress = _map(note['progress']);
    final chronometer = _map(note['chronometer']);
    final actions = [
      for (final action in note['actions'] is List ? note['actions'] as List : const []) action.toString(),
    ];
    final extras = [
      for (final row in note['extras'] is List ? note['extras'] as List : const [])
        if (row is Map) row.map((key, value) => MapEntry('$key', value)),
    ];
    final ongoing = note['ongoing'] == true;
    final live = note['live'] == true;
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
      decoration: BoxDecoration(
        color: IslandColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: IslandColors.cardEdge),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${_s(note['app'])}   ${_s(note['package'])}', style: const TextStyle(color: IslandColors.secondary, fontSize: 12)),
          const SizedBox(height: 6),
          _line('Title', _s(note['title'])),
          _line('Text', _s(note['text'])),
          _line('Subtext', _s(note['subtext'])),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              _Flag(ongoing ? 'Ongoing' : 'Not ongoing', ongoing),
              _Flag(live ? 'Live' : 'Not live', live),
            ],
          ),
          if (progress != null) ...[
            const SizedBox(height: 10),
            _progress(progress),
          ],
          const SizedBox(height: 8),
          _line('Chronometer', _chronometer(chronometer)),
          const SizedBox(height: 4),
          _line('Actions', actions.isEmpty ? 'None' : actions.join(', ')),
          if (_s(note['largeIcon']).isNotEmpty || _s(note['picture']).isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.end,
              children: [
                if (_s(note['largeIcon']).isNotEmpty) _image('Large icon', _s(note['largeIcon'])),
                if (_s(note['picture']).isNotEmpty) _image('Picture', _s(note['picture'])),
              ],
            ),
          ],
          const SizedBox(height: 10),
          Text(
            [
              if (_s(note['channel']).isNotEmpty) 'Channel ${_s(note['channel'])}',
              if (_s(note['category']).isNotEmpty) 'Category ${_s(note['category'])}',
              if (_s(note['posted']).isNotEmpty) 'Posted ${_s(note['posted'])}',
              'id ${_s(note['id'])}${_s(note['tag']).isEmpty ? '' : '  tag ${_s(note['tag'])}'}',
            ].join('  ·  '),
            style: const TextStyle(color: IslandColors.tertiary, fontSize: 12, height: 1.35),
          ),
          if (extras.isNotEmpty) ...[
            const SizedBox(height: 10),
            const Text('Extras', style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            for (final row in extras)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: SelectableText.rich(
                  TextSpan(
                    children: [
                      TextSpan(text: '${row['key']}  ', style: const TextStyle(color: IslandColors.lagoon, fontSize: 12)),
                      TextSpan(text: '${row['value']}', style: const TextStyle(color: Colors.white, fontSize: 12)),
                    ],
                  ),
                ),
              ),
          ],
        ],
      ),
    );
  }

  Widget _line(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 3),
      child: SelectableText.rich(
        TextSpan(
          children: [
            TextSpan(text: '$label  ', style: const TextStyle(color: IslandColors.secondary, fontSize: 13)),
            TextSpan(text: value.isEmpty ? '—' : value, style: const TextStyle(color: Colors.white, fontSize: 15, height: 1.3)),
          ],
        ),
      ),
    );
  }

  Widget _progress(Map<String, dynamic> progress) {
    final indeterminate = progress['indeterminate'] == true;
    final percent = progress['percent'];
    final current = progress['current'];
    final max = progress['max'];
    final label = indeterminate
        ? 'Progress  indeterminate${max is num && max > 0 ? '  $current / $max' : ''}'
        : 'Progress  ${percent ?? '—'}%   $current / $max';
    final fraction = !indeterminate && percent is num ? (percent / 100).clamp(0, 1).toDouble() : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(color: Colors.white, fontSize: 14)),
        if (fraction != null) ...[
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: fraction,
              minHeight: 6,
              color: IslandColors.lagoon,
              backgroundColor: IslandColors.track,
            ),
          ),
        ],
      ],
    );
  }

  String _chronometer(Map<String, dynamic>? chronometer) {
    if (chronometer == null || chronometer['on'] != true) return 'Off';
    final text = _s(chronometer['text']);
    final base = _s(chronometer['base']);
    final kind = chronometer['countdown'] == true ? 'countdown' : 'counting up';
    return [text, kind, if (base.isNotEmpty) 'base $base'].join('  ·  ');
  }

  Widget _image(String label, String data) {
    try {
      final bytes = base64Decode(data);
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(color: IslandColors.secondary, fontSize: 12)),
          const SizedBox(height: 4),
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.memory(bytes, height: 72, fit: BoxFit.contain),
          ),
        ],
      );
    } catch (_) {
      return const SizedBox.shrink();
    }
  }

  Map<String, dynamic>? _map(Object? raw) {
    if (raw is! Map) return null;
    return raw.map((key, value) => MapEntry('$key', value));
  }

  String _s(Object? value) => value?.toString() ?? '';
}

class _Flag extends StatelessWidget {
  final String label;
  final bool on;
  const _Flag(this.label, this.on);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: on ? const Color(0x3332D74B) : IslandColors.fill,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(label, style: TextStyle(color: on ? IslandColors.green : IslandColors.secondary, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}
