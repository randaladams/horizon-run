# Horizon Run

A first-person running game for Android, played in landscape. You run across a small planet whose horizon curves away from the centre of the screen. Trees, rocks, animals and low-hanging branches rise up over that curve and grow as you approach — weave between them.

## Controls

- **Tilt the phone left/right** to steer.
- **Hold a finger on the screen** to sprint. The longer you hold, the faster you go.
- **Lift your finger** and you ease back to your base speed, which keeps climbing the longer the run lasts.

Close calls earn +25 bonus metres. Your best distance is saved on the phone.

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
