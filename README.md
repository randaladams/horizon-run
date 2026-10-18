# Horizon Run

A first-person running game for Android, played in landscape. You run across a small planet whose horizon curves away from the centre of the screen. Trees, rocks, animals and low-hanging branches rise up over that curve and grow as you approach — weave between them.

## Controls

- **Tilt the phone left/right** to steer.
- **Hold a finger on the screen** to sprint. The longer you hold, the faster you go.
- **Lift your finger** and you ease back to your base speed, which keeps climbing the longer the run lasts.

**Flowers** add to your score: glowing flowers come in curving trails of five, +20 each and a **Bouquet +100** bonus for collecting the whole trail; rare **golden flowers** are worth +150. Close calls earn +25 bonus metres. Run through a **glowing snail** to knock your base speed back down (about 27 km/h); snails show up every 5–9 seconds, and more often the faster you're going. The **Reset best score** button (bottom-left of the start and crash screens, tap twice) clears your record for testing. Your best distance is saved on the phone.

New to the game? Tap **How to play** on the start screen for a 10-step practice run covering steering, sprinting, each kind of obstacle, snails and the options.

## Install on your phone

1. On your Android phone, open **https://github.com/randaladams/horizon-run/releases/tag/latest**
2. Tap **HorizonRun.apk** to download it.
3. Open the download. If Android asks, allow your browser to *install unknown apps*, then tap **Install**.

Every push to `main` rebuilds the APK automatically (GitHub Actions) and replaces the file on that release page. New builds install over the old one, keeping your best score.

## Building locally

Requires the Android SDK (API 35) and JDK 17:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
