import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'ui/settings_ui.dart';
import 'ui/setup.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.dark,
    statusBarBrightness: Brightness.light,
    systemNavigationBarColor: SettingsColors.background,
    systemNavigationBarIconBrightness: Brightness.dark,
  ));
  runApp(const SetupApp());
}

class SetupApp extends StatelessWidget {
  const SetupApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Island',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        scaffoldBackgroundColor: SettingsColors.background,
        colorScheme: const ColorScheme.light(
          primary: SettingsColors.label,
          surface: SettingsColors.background,
        ),
        fontFamily: 'Roboto',
      ),
      home: const SetupPage(),
    );
  }
}
