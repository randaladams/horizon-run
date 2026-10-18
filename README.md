# Horizon Run

A first-person running game for Android, played in landscape. You run across a small planet whose horizon curves away from the centre of the screen. Trees, rocks, animals and low-hanging branches rise up over that curve and grow as you approach — weave between them.

## Controls

- **Tilt the phone left/right** to steer.
- **Hold the right half of the screen** to sprint. The longer you hold, the faster you go.
- **Tap the left half of the screen** to jump (about 1.5 m). You can clear rocks and foxes; trees, deer, bears and low branches still have to be steered around. Some rows of rocks are solid walls with no gap, so jumping is the only way through. Each jump over an obstacle is +10.
- **Lift your finger** and you ease back to your base speed, which keeps climbing the longer the run lasts.

**Flowers** add to your score: glowing flowers come in curving trails of five, +20 each and a **Bouquet +100** bonus for collecting the whole trail; rare **golden flowers** are worth +150. Close calls earn +25 bonus metres. Run through a **glowing snail** to knock your base speed back down (about 27 km/h); snails show up every 5–9 seconds, and more often the faster you're going. A **gold line** across the ground marks your best score; crossing it plays a chime. **Scores** (bottom-left of the start and crash screens) shows your last 10 runs; **Reset scores** is inside (tap twice).

**Holiday themes:** from 10 days before to 3 days after a US holiday the flowers change: New Year's (party poppers / champagne), Valentine's (hearts / golden heart), St. Patrick's (shamrocks / pot of gold), Easter (painted eggs / golden egg), 4th of July (stars / firework), Halloween (jack-o'-lanterns / glowing skull), Thanksgiving (autumn leaves / golden turkey) and Christmas (presents / Christmas tree), with a greeting on the start screen, a tinted sky and snow, leaves or confetti. To preview any theme, open Scores and hold the title for a second.

After a crash you can **watch an ad to keep running** (once per run, with a short shield). An ad also plays between games after every 4 games.

New to the game? Tap **How to play** on the start screen for a 12-step practice run covering steering, sprinting, jumping (including a rock wall), each kind of obstacle, snails and the options.

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

## Publishing to Google Play

The build also produces **HorizonRun.aab** (the file Play wants) once these repository secrets exist under *Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | contents of `UPLOAD_KEYSTORE_BASE64.txt` |
| `UPLOAD_KEYSTORE_PASSWORD` | the password in `horizonrun-upload-key-INFO.txt` |
| `UPLOAD_KEY_ALIAS` | `upload` |

Ads currently use Google's **test** ids. Before release, create an AdMob app and replace the ids in `Ads.java` (rewarded + interstitial) and the `APPLICATION_ID` in `AndroidManifest.xml`.
