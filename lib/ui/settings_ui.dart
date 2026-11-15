import 'package:flutter/material.dart';

/// Pixel System settings: lavender page, blue section labels, white rounded cards.
class SettingsColors {
  static const background = Color(0xFFEDEFF6);
  static const card = Color(0xFFFFFFFF);
  static const label = Color(0xFF0B57D0);
  static const title = Color(0xFF1B1B1F);
  static const subtitle = Color(0xFF44474E);
  static const back = Color(0xFFE4E6EC);
}

class SettingsPage extends StatelessWidget {
  final String title;
  final bool showBack;
  final Widget? trailing;
  final List<Widget> children;
  final Future<void> Function()? onRefresh;

  const SettingsPage({
    super.key,
    required this.title,
    required this.children,
    this.showBack = false,
    this.trailing,
    this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    final list = ListView(
      physics: onRefresh == null ? null : const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                if (showBack) ...[
                  Material(
                    color: SettingsColors.back,
                    shape: const CircleBorder(),
                    child: IconButton(
                      onPressed: () => Navigator.of(context).maybePop(),
                      icon: const Icon(Icons.arrow_back, color: SettingsColors.title),
                    ),
                  ),
                  const SizedBox(width: 12),
                ],
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(color: SettingsColors.title, fontSize: 32, fontWeight: FontWeight.w500, height: 1.15),
                  ),
                ),
                if (trailing != null) trailing!,
              ],
            ),
            const SizedBox(height: 8),
            ...children,
      ],
    );
    return Scaffold(
      backgroundColor: SettingsColors.background,
      body: SafeArea(
        child: onRefresh == null
            ? list
            : RefreshIndicator(
                color: SettingsColors.label,
                onRefresh: onRefresh!,
                child: list,
              ),
      ),
    );
  }
}

class SettingsSection extends StatelessWidget {
  final String label;
  final List<Widget> children;

  const SettingsSection({super.key, required this.label, required this.children});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
            child: Text(label, style: const TextStyle(color: SettingsColors.label, fontSize: 14, fontWeight: FontWeight.w500)),
          ),
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) const SizedBox(height: 4),
            children[i],
          ],
        ],
      ),
    );
  }
}

class SettingsCard extends StatelessWidget {
  final Widget child;
  const SettingsCard({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: SettingsColors.card,
      borderRadius: BorderRadius.circular(28),
      clipBehavior: Clip.antiAlias,
      child: child,
    );
  }
}

class SettingsRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const SettingsRow({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return SettingsCard(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 12, 14),
          child: Row(
            children: [
              Icon(icon, color: SettingsColors.subtitle, size: 24),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(color: SettingsColors.title, fontSize: 16, fontWeight: FontWeight.w500, height: 1.25)),
                    if (subtitle != null && subtitle!.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(subtitle!, style: const TextStyle(color: SettingsColors.subtitle, fontSize: 14, height: 1.3)),
                    ],
                  ],
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 8),
                trailing!,
              ],
            ],
          ),
        ),
      ),
    );
  }
}
