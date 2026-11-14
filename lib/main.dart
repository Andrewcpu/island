import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'controller.dart';
import 'theme.dart';
import 'ui/home.dart';

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
  final controller = IslandController();
  await controller.init();
  runApp(IslandApp(controller: controller));
}

class IslandApp extends StatelessWidget {
  final IslandController controller;

  const IslandApp({super.key, required this.controller});

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
      home: IslandHome(controller: controller),
    );
  }
}
