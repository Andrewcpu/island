/// Sizes from islandcn's measured iPhone-class island, in logical pixels.
class IslandMetrics {
  final double designWidth;
  final double top;
  final double idleWidth;
  final double idleHeight;
  final double sensorWidth;
  final double compactHeight;
  final double edgePadding;
  final double innerLeading;
  final double innerTrailing;
  final double maxSlotWidth;
  final double maxWidth;
  final double balance;
  final double splitOuter;
  final double splitGap;
  final double splitInner;
  final double bubbleWidth;
  final double bubbleOuter;
  final double bubbleSlotGap;
  final double bubbleSlotEnd;
  final double splitMaxSpan;
  final double minimalGap;
  final double minimalOuter;
  final double minimalInner;
  final double detachedPad;
  final double detachedMin;
  final double expandedInset;
  final double expandedTop;
  final double expandedMin;
  final double expandedMax;
  final double expandedRadius;
  final double padTop;
  final double padSide;
  final double padTrailing;
  final double padBottom;
  final double cornerSmoothing;
  final double bridgeMaxGap;
  final double keylineWidth;
  final double keylineAlpha;

  const IslandMetrics({
    this.designWidth = 402,
    this.top = 13.91,
    this.idleWidth = 94.85,
    this.idleHeight = 36.84,
    this.sensorWidth = 94.85,
    this.compactHeight = 36.85,
    this.edgePadding = 7.18,
    this.innerLeading = -0.18,
    this.innerTrailing = 0.07,
    this.maxSlotWidth = 72,
    this.maxWidth = 260,
    this.balance = 0.7475,
    this.splitOuter = 6.59,
    this.splitGap = 6.15,
    this.splitInner = 8.18,
    this.bubbleWidth = 62.18,
    this.bubbleOuter = 6.42,
    this.bubbleSlotGap = 6.085,
    this.bubbleSlotEnd = 7.175,
    this.splitMaxSpan = 290,
    this.minimalGap = 7.82,
    this.minimalOuter = 7.09,
    this.minimalInner = 8.4,
    this.detachedPad = 6.42,
    this.detachedMin = 36.85,
    this.expandedInset = 13.83,
    this.expandedTop = 13.83,
    this.expandedMin = 84,
    this.expandedMax = 180,
    this.expandedRadius = 47.9,
    this.padTop = 18.18,
    this.padSide = 18.5,
    this.padTrailing = 17.81,
    this.padBottom = 18.35,
    this.cornerSmoothing = 0.72,
    this.bridgeMaxGap = 5,
    this.keylineWidth = 1.5,
    this.keylineAlpha = 0.16,
  });

  static const standard = IslandMetrics();

  IslandMetrics copyWith({
    double? top,
    double? idleWidth,
    double? idleHeight,
    double? sensorWidth,
    double? compactHeight,
    double? expandedTop,
  }) {
    return IslandMetrics(
      designWidth: designWidth,
      top: top ?? this.top,
      idleWidth: idleWidth ?? this.idleWidth,
      idleHeight: idleHeight ?? this.idleHeight,
      sensorWidth: sensorWidth ?? this.sensorWidth,
      compactHeight: compactHeight ?? this.compactHeight,
      edgePadding: edgePadding,
      innerLeading: innerLeading,
      innerTrailing: innerTrailing,
      maxSlotWidth: maxSlotWidth,
      maxWidth: maxWidth,
      balance: balance,
      splitOuter: splitOuter,
      splitGap: splitGap,
      splitInner: splitInner,
      bubbleWidth: bubbleWidth,
      bubbleOuter: bubbleOuter,
      bubbleSlotGap: bubbleSlotGap,
      bubbleSlotEnd: bubbleSlotEnd,
      splitMaxSpan: splitMaxSpan,
      minimalGap: minimalGap,
      minimalOuter: minimalOuter,
      minimalInner: minimalInner,
      detachedPad: detachedPad,
      detachedMin: detachedMin,
      expandedInset: expandedInset,
      expandedTop: expandedTop ?? this.expandedTop,
      expandedMin: expandedMin,
      expandedMax: expandedMax,
      expandedRadius: expandedRadius,
      padTop: padTop,
      padSide: padSide,
      padTrailing: padTrailing,
      padBottom: padBottom,
      cornerSmoothing: cornerSmoothing,
      bridgeMaxGap: bridgeMaxGap,
      keylineWidth: keylineWidth,
      keylineAlpha: keylineAlpha,
    );
  }
}

/// Springs fitted to the islandcn recordings (response / damping fraction).
class IslandSprings {
  static const appear = (response: 0.483, damping: 0.62);
  static const expand = (response: 0.517, damping: 0.824);
  static const expandBody = (response: 0.497, damping: 0.825);
  static const pressExpand = (response: 0.387, damping: 0.792);
  static const pressExpandBody = (response: 0.388, damping: 0.791);
  static const appearExpand = (response: 0.54, damping: 0.811);
  static const appearExpandBody = (response: 0.523, damping: 0.817);
  static const swap = (response: 0.498, damping: 0.829);
  static const collapse = (response: 0.479, damping: 0.894);
  static const collapseBody = (response: 0.453, damping: 0.928);
  static const collapseRetarget = (response: 0.447, damping: 1.0);
  static const alertCollapse = (response: 0.535, damping: 0.84);
  static const alertCollapseBody = (response: 0.481, damping: 0.908);
  static const alertCollapseRetarget = (response: 0.527, damping: 0.837);
  static const collapseSplit = (response: 0.4, damping: 0.9);
  static const split = (response: 0.505, damping: 0.815);
  static const unsplit = (response: 0.487, damping: 0.794);
  static const dismiss = (response: 0.432, damping: 1.019);
  static const resize = (response: 0.5, damping: 0.82);
  static const bubbleSplit = (response: 0.376, damping: 0.811);
  static const swell = (response: 0.803, damping: 0.641);
  static const bubbleSwell = (response: 0.509, damping: 0.05);
  static const release = (response: 0.3, damping: 1.5);
  static const idleRelease = (response: 0.53, damping: 0.77);
  static const progress = (response: 0.97, damping: 0.74);
  static const cornerExpand = (response: 0.448, damping: 0.87);

  static const decide = 0.1;
  static const longPress = 0.216;
  static const longPressBubble = 0.224;
  static const tapReturn = 0.22;
  static const idleTapReturn = 0.2;
  static const idleHoldReturn = 0.236;
  static const alertHold = 5.975;
  static const alertHoldIdle = 5.969;
  static const alertContentDelay = 0.12;
  static const groupHandoff = 1.445;
  static const groupWindow = 6.014;
  static const splitVia = 0.215;
  static const unsplitVia = 0.203;
  static const collapseVia = 0.311;
  static const alertCollapseVia = 0.29;
  static const swapVia = 0.223;
  static const bubbleSplitDelay = 0.345;
  static const swellTarget = 1.187;
  static const bubbleSwellTarget = 1.0622;
  static const reducedDuration = 0.2;
  static const chargingHold = 3.3;
}
