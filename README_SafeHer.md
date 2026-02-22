# SafeHer — Personal Safety Android App

> A discreet, always-on personal safety app that lets users trigger SOS alerts through multiple hidden gestures — even from a locked screen — automatically notifying emergency contacts with live GPS location, voice recordings, and photo evidence.

---

## Features

### 🚨 SOS Trigger Methods
SafeHer offers multiple ways to fire an emergency alert so the user is never left without options:

- **One-Tap SOS Button** — Large panic button inside the app and on the home screen widget
- **Pinch-Out Gesture (Lock Screen)** — Spread two fingers apart on the lock screen via an Accessibility Service; no unlock needed
- **Hand Gesture Detection** — Background camera service recognises a raised open palm and triggers SOS automatically
- **Scream Detection** — Always-on microphone listener detects a high-amplitude scream and fires SOS hands-free
- **Safe Walk Timer** — Set a countdown timer; if the user doesn't check in before it expires, SOS fires automatically

### 📍 Emergency Response (on SOS trigger)
- Sends SMS to all saved emergency contacts with a Google Maps link to the user's live GPS coordinates
- Starts a foreground voice recording that captures audio evidence during the emergency
- Launches `CameraEvidenceService` which silently photographs the surroundings every 5 seconds
- Plays the built-in SOS alarm sound at maximum volume, bypassing silent/do-not-disturb mode
- Vibrates the device with a distinct SOS pulse
- Opens the SOS screen over the lock screen so the user can see status and stop the alarm

### 🔒 Lock Screen & Background Operation
- SOS activity and Hand Gesture activity are configured to show over the lock screen (`showWhenLocked`, `turnScreenOn`)
- `SafeHerService` runs as a persistent foreground service (location + microphone) that survives app closure
- `BootReceiver` restarts protection automatically after the device reboots
- Scream detection and Hand Gesture service re-arm automatically on reboot if they were enabled

### 👥 People Radar
- Scans nearby Bluetooth LE devices and Classic Bluetooth to estimate how many people are around
- Displays a live radar view showing surrounding device count and signal strength
- Can broadcast the user's location to saved contacts directly from the radar screen

### 🛡️ Safety Utilities
- **Fake Call** — Triggers a realistic incoming call screen with preset caller names (Mom, Sister, Doctor…) to help the user exit an uncomfortable situation discreetly
- **Safe Walk Mode** — Shares live location with contacts every 2 minutes during a timed walk; auto-SOS on missed check-in
- **Evidence Recorder** — Manual audio recorder for capturing evidence
- **Photo Viewer** — Review evidence photos captured during an SOS event
- **Emergency Helplines** — Quick-dial screen for local emergency numbers
- **Contacts Manager** — Add, edit, and remove trusted emergency contacts who receive SMS alerts
- **AI Safety Chat** — In-app chat assistant for safety guidance

### 📱 Home Screen Widget
- One-tap SOS trigger and stop button directly from the home screen, no need to open the app

---

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS coordinates for SMS alerts and Safe Walk |
| `SEND_SMS` | Send emergency SMS to contacts |
| `CALL_PHONE` | Quick-dial helplines |
| `RECORD_AUDIO` | Scream detection and voice evidence recording |
| `CAMERA` | Silent photo evidence capture during SOS |
| `BLUETOOTH_SCAN / CONNECT` | People Radar — detect nearby devices |
| `RECEIVE_BOOT_COMPLETED` | Re-arm protection after device reboot |
| `FOREGROUND_SERVICE_*` | Keep location, microphone, and camera services alive |
| `VIBRATE` | SOS haptic alerts |
| `WAKE_LOCK` | Keep CPU alive during alarm and recording |
| `POST_NOTIFICATIONS` | Show persistent protection and alarm notifications |

---

## Project Structure

```
app/src/main/java/com/safeher/app/
│
├── MainActivity.java               # Home dashboard — wires all feature cards
├── SosActivity.java                # SOS screen (shows over lock screen)
│
├── SafeHerService.java             # Core background service: SOS trigger, scream detect, alarm, location SMS
├── SosService.java                 # SOS alarm and recording service
├── CameraEvidenceService.java      # Takes photos every 5s during SOS
├── HandGestureService.java         # Background camera gesture recognition
├── PinchAccessibilityService.java  # Lock screen pinch-out gesture via AccessibilityService
│
├── ContactsActivity.java           # Emergency contacts management
├── SafeWalkActivity.java           # Safe Walk timer + live location sharing
├── RadarMapActivity.java           # Bluetooth people radar + location broadcast
├── FakeCallActivity.java           # Fake incoming call screen
├── ScreamDetectActivity.java       # Scream detection toggle + live amplitude meter
├── HandGestureActivity.java        # Hand gesture SOS screen (shows over lock screen)
├── HandGestureSetupActivity.java   # Hand gesture setup and calibration
├── RecorderActivity.java           # Manual audio recorder
├── PhotoViewerActivity.java        # Evidence photo gallery
├── ChatActivity.java               # AI safety chat
│
├── SosWidget.java                  # Home screen widget receiver
├── BootReceiver.java               # Auto-restart on device boot
│
├── RadarView.java                  # Custom radar canvas view
├── CircleTrailView.java            # Animated circle gesture trail overlay
├── PinchOverlayView.java           # Pinch gesture visual feedback
├── CircleGestureDetector.java      # Circle gesture math/detection
└── EvidencePhotoAdapter.java       # RecyclerView adapter for photo evidence

app/src/main/res/raw/
└── sos_alarm.wav                   # Built-in SOS alarm sound (no ringtone access needed)
```

---

## Building

1. Open the project in **Android Studio Hedgehog** or later
2. Sync Gradle dependencies (`google()` + `mavenCentral()`)
3. Add your **Google Maps API key** to `local.properties`:
   ```
   MAPS_API_KEY=your_key_here
   ```
4. Run on a physical device (API 26+) — emulators lack Bluetooth, camera, and accurate microphone

**Min SDK:** 26 (Android 8.0)  
**Target SDK:** 34 (Android 14)  
**Language:** Java  
**Dependencies:** Google Play Services Location, Material Components, Google Maps

---

## Setup (First Launch)

1. Grant all requested permissions — each is required for a specific safety feature
2. Enable the **SafeHer Pinch-Out SOS** Accessibility Service in Android Settings → Accessibility
3. Add at least one emergency contact in the **Contacts** section
4. Optionally enable Scream Detection and Hand Gesture detection from their respective screens
5. The app's background protection service starts automatically and persists across reboots

---

## Privacy & Data

- All data (contacts, recordings, photos) is stored **locally on device only**
- No data is uploaded to any server
- Emergency SMS messages are sent directly from the device's SIM
- The microphone is only accessed when Scream Detection is enabled by the user, or actively recording evidence during an SOS

---

## License

This project is private and proprietary. All rights reserved.
