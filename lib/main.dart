import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'theme.dart';
import 'ui/setup.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    statusBarBrightness: Brightness.dark,
    systemNavigationBarColor: IslandColors.page,
    systemNavigationBarIconBrightness: Brightness.light,
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
        brightness: Brightness.dark,
        scaffoldBackgroundColor: IslandColors.page,
        colorScheme: const ColorScheme.dark(primary: IslandColors.lagoon, surface: IslandColors.page),
        fontFamily: 'Roboto',
      ),
      home: const SetupPage(),
    );
  }
}
