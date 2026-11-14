import 'package:flutter/material.dart';

/// Accents measured for the island presets.
class IslandColors {
  static const lagoon = Color(0xFF46F0C6);
  static const green = Color(0xFF32D74B);
  static const talkGreen = Color(0xFF76FB73);
  static const accept = Color(0xFF31D259);
  static const decline = Color(0xFFFE4345);
  static const red = Color(0xFFFF453A);
  static const orange = Color(0xFFFF9F0A);
  static const yellow = Color(0xFFFFD60A);
  static const blue = Color(0xFF0A84FF);
  static const charging = Color(0xFF53FA6D);
  static const ink = Colors.white;
  static const secondary = Color(0x9EEBEBF5);
  static const tertiary = Color(0x5CEBEBF5);
  static const fill = Color(0x24FFFFFF);
  static const track = Color(0x29FFFFFF);
  static const page = Color(0xFF0B0B0F);
  static const card = Color(0xFF17171C);
  static const cardEdge = Color(0x14FFFFFF);
}

class IslandType {
  static const compact = TextStyle(
    color: IslandColors.ink,
    fontSize: 15,
    fontWeight: FontWeight.w600,
    height: 1.15,
    letterSpacing: -0.2,
    fontFeatures: [FontFeature.tabularFigures()],
  );

  static const title = TextStyle(
    color: IslandColors.ink,
    fontSize: 17,
    fontWeight: FontWeight.w600,
    height: 1.2,
    letterSpacing: -0.3,
  );

  static const subtitle = TextStyle(
    color: IslandColors.secondary,
    fontSize: 15,
    fontWeight: FontWeight.w400,
    height: 1.2,
  );

  static const caption = TextStyle(
    color: IslandColors.secondary,
    fontSize: 13,
    fontWeight: FontWeight.w500,
    height: 1.2,
  );

  static const value = TextStyle(
    color: IslandColors.ink,
    fontSize: 34,
    fontWeight: FontWeight.w700,
    height: 1.0,
    letterSpacing: -0.8,
    fontFeatures: [FontFeature.tabularFigures()],
  );
}
