# Island

Android Dynamic Island. The island UI and motion are a Flutter port of the open-source [islandcn](https://github.com/opencoredev/islandcn) component (MIT). This is not a port of the islandcn.dev marketing site.

The island is a real screen overlay (`TYPE_APPLICATION_OVERLAY`). It sits in the status-bar band, around the camera punch-hole (`DisplayCutout` / `WindowInsets`), and only the island itself takes touches. Taps outside it go to whatever app is underneath. On a device with no top cutout it uses a centered 28dp hole so the layout still matches a Pixel-class front camera.

The activity you open from the launcher is the permission and activity screen. The island stays up after you leave that screen. Android will not let an app draw the system status-bar island, so this overlay is the island.

## Install

The release APK is arm64-v8a, signed with the debug keystore so it installs without a private release key.

```bash
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

A copy is also at `media/island.apk` in the project store when that directory is writable.

Build it again:

```bash
flutter build apk --release --target-platform android-arm64
```

Flutter 3.50 (stable) and Android SDK 36. `minSdk` is 30. `targetSdk` / `compileSdk` follow the Flutter Android template (36).

## Using it

The lock-screen page is the island. Tap an activity to swell it, tap again before 0.1s for the activity's press action, or hold about 0.22s to expand. Tap the expanded page or the system back gesture to collapse. Two live activities split: the primary stays on the leading side of the camera and the secondary becomes the bubble on the trailing side. An alert holds expanded for about 6 seconds. A second order in the same group drops to idle, waits about 1.4 seconds, then expands.

Settings on the page:

- Reduced motion: follow the system, always, or never. Reduced motion is a 0.2s ease with no overshoot.
- Keyline: thin rim once the pill grows on a dark page.
- Show over other apps is required. The first launch requests runtime permissions, then opens the overlay setting, then notification access. Each can be turned on again from the setup screen.

## Modes

Every islandcn preset is here, plus messages and calendar.

| Mode | What it does | Live data |
| --- | --- | --- |
| Battery | Charging pill holds 3.3s, then dismisses. Below 20% and not charging, it stays. | `ACTION_BATTERY_CHANGED` and charge-time remaining. No permission. |
| Phone | Incoming call expands with accept and decline. Active call is compact: timer and level meter. | `TelephonyCallback` (`READ_PHONE_STATE`). Accept uses `TelecomManager.acceptRingingCall` (`ANSWER_PHONE_CALLS`). End uses the Telecom reflection Android still exposes. The in-app "Call" chip (caller "Mochi") uses the same widgets when no live call is present. Caller ID is often empty for apps that are not the default dialer. |
| Messages | Compact sender and body, then an expanded alert. | Notification listener for message notifications, and `SMS_RECEIVED` when SMS permission is granted. The "Message" chip uses that same path. |
| Calendar | Upcoming event, compact and expanded. | `CalendarContract.Instances` for the next 18 hours (`READ_CALENDAR`). An event inside 20 minutes is shown on its own. If nothing is readable, a sample event is available from the Event chip. |
| Timer | Countdown, pause, cancel, and a "time's up" alert. | In-app. |
| Now playing | Artwork, transport, scrubber, output route. | A media session from the notification listener when one is posted. Otherwise the sample playlist (Low Tide Radio, Saltwater Static, Glass Pier) with generated artwork. Output toggles speakerphone (`MODIFY_AUDIO_SETTINGS`); a carrier call may ignore it. |
| Upload | Progress ring, stop, then a check. | Simulated file `biscuit-in-the-sun.jpg`. |
| ETA | Ride or delivery, minutes, and a message button. | Simulated. islandcn has no turn-by-turn navigation mode. |
| Recording | Audio, screen, or camera. Stop returns to idle. | Audio uses `AudioRecord` plus a microphone foreground service (`RECORD_AUDIO`). If the mic is denied, the same meter runs on a simulated level. Screen and camera are the island UI with a simulated meter. `MediaProjection` is not requested. |
| Nearby transfer | Send or receive, progress ring, stop. | Simulated. Quick Share is not a public API for a normal app. |
| Split | Timer plus music, the two-activity layout. | In-app. |
| Orders | Two deliveries in one group. Handoff plays the idle gap, then the next order. | In-app demo of the islandcn group handoff. |

Priorities, highest first: call, message, recording, timer-done, timer, now playing, upload and transfer, ETA, orders, calendar, battery.

## Permissions

Denial leaves the rest of the app usable. Each live row explains the fallback.

| Permission | Why |
| --- | --- |
| `READ_PHONE_STATE` | Ringing, off-hook, and idle. |
| `ANSWER_PHONE_CALLS` | Answer the ringing call. |
| `READ_SMS`, `RECEIVE_SMS` | SMS into the message island. |
| Notification access | Message notifications and media sessions. This is a system setting, not a runtime dialog. |
| `READ_CALENDAR` | Upcoming events. |
| `RECORD_AUDIO` | Live microphone meter. |
| `MODIFY_AUDIO_SETTINGS` | Speaker route for the player. |
| `POST_NOTIFICATIONS` | Ongoing activity notification. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MICROPHONE` | Keep the activity posted, and the mic type while recording. |
| `SYSTEM_ALERT_WINDOW` | The island overlay. Android opens a settings screen for this. It cannot be granted from a dialog. |

## Motion

Springs use the islandcn response and damping values (SwiftUI closed form): appear, expand, press, collapse, alert collapse, split, unsplit, swap, and the via-idle waist on those retargets. Press swell, the 0.1s tap decision, long-press expand, alert hold, group handoff, bubble pinch, number turnover, and the progress spring match those timings. Corners are continuous (smoothing 0.72). The expanded island is the only one with a shadow.

## What a phone test still needs

This environment has no Pixel, so the cutout layout is tested against a 448×998 dp screen with a centered punch-hole, not on hardware. On a device, allow display over other apps, then confirm the island sits on the camera and that taps outside it reach the app underneath. System status icons can still draw beside the pill. Android does not let a third-party app replace the status-bar island itself.

## License

The Dart and Kotlin in this project are MIT. Geometry and motion constants are derived from [opencoredev/islandcn](https://github.com/opencoredev/islandcn), which is also MIT. See `LICENSE`.
