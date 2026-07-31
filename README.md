# MoodBoard — Emotion-Aware Sticker Keyboard (Android)

A lightweight Android keyboard that types normally and, with one tap, reads your
facial expression from the front camera, works out your mood on the device, and
shows matching stickers you can send into any chat — without leaving the
conversation.

Emotion recognition runs entirely on the phone using MediaPipe Face Landmarker
(facial blendshapes). Stickers come from the GIPHY Stickers API, plus any you
import yourself.

## What's inside

| Piece | File |
|---|---|
| Keyboard service (IME) | `app/.../ime/MoodBoardService.kt` |
| QWERTY view (typing, shift, symbols) | `app/.../ime/QwertyKeyboardView.kt` |
| In-keyboard front camera (CameraX) | `app/.../camera/KeyboardCameraManager.kt` |
| Camera permission helper | `app/.../camera/PermissionActivity.kt` |
| On-device emotion (MediaPipe, FACS-EBS v2) | `app/.../emotion/EmotionAnalyzer.kt`, `ExpressionClassifier.kt` |
| Emotions + sticker queries | `app/.../emotion/Emotion.kt` |
| Mood-bucketed sticker storage | `app/.../stickers/StickerLibrary.kt` |
| Sticker search (your own, then GIPHY / Tenor) | `app/.../stickers/StickerRepository.kt` |
| Send sticker into chat (Commit Content) | `app/.../util/RichContentSender.kt` |
| Enable / switch keyboard, calibration, Emotion Lab | `app/.../ui/SetupActivity.kt` |
| Manage stickers - level 1 (mood grid) | `app/.../ui/StickerManagerActivity.kt` |
| Manage stickers - level 2 (one mood's grid) | `app/.../ui/MoodStickersActivity.kt` |
| "Share into MoodBoard" target | `app/.../ui/ReceiveStickerActivity.kt` |
| On-device model | `app/src/main/assets/face_landmarker.task` |
| Cloud build → APK | `.github/workflows/build-apk.yml` |

The MediaPipe model is bundled in the app (~3.6 MB) so emotion detection works
offline and privately — camera frames are analysed in memory and never uploaded.

## How it works

1. Type normally with the QWERTY keys in any app.
2. Tap the **Mood** button — the front camera opens inside the keyboard and shows
   a live emotion read-out (e.g. "Happy 78%").
3. Hold a clear expression; once it is stable the keyboard locks the mood and loads
   matching stickers. (Or tap **Use mood** to lock the current reading immediately.)
4. Tap a sticker and it is sent straight into the chat.

Detected emotions: Happy, Laughing, Excited, Surprised, Shocked, Fearful, Sad,
Angry, Annoyed, Frustrated, Disgust, Contempt, Skeptical, Sleepy, Kiss, Wink,
Puffed, Silly, Neutral. (Silly/tongue-out is only offered if the on-device model
reliably sees `tongueOut` on this phone - see `docs/SPEC_V2.md` A.3.)

### Your own stickers - mood-categorised library
Settings (the wrench on the keyboard) → **Manage my stickers** opens a grid of
mood cards (one per emotion, greyed out until you add something). Tap a card to
see that mood's stickers, with:
- **+ (FAB)** - add from the gallery, straight into this mood.
- **Import folder** - pick any folder via Android's file picker; every image
  inside (recursively) is imported into this mood.
- **Long-press a sticker** - set as the mood's cover, move it to another mood,
  favourite it (favourites sort first), or delete it.

Three ways to get stickers in, including ones you already have in WhatsApp:
1. **Share → MoodBoard** from WhatsApp (or any app) on an existing sticker - a
   mood picker appears and it's saved.
2. **Import a folder**, then **Import WhatsApp stickers** - opens Android's
   folder picker pre-pointed at WhatsApp's *readable* received-stickers folder
   (`Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers`). WhatsApp's
   own private sticker/pack storage under `/data/data/com.whatsapp` cannot be
   read by any other app - this is an Android sandboxing rule, not a MoodBoard
   limitation. See `docs/SPEC_V2.md` B.4 for the full explanation, which is also
   shown inline in the import sheet.
3. **Import from gallery** - for stickers already saved to your photos.

Animated `.webp`/`.gif` stickers are copied byte-for-byte so they stay animated;
large JPEG/PNG imports are downsized (long edge clamped to 512px). At mood-scan
time your own stickers for the detected mood show first (favourites first), then
stickers from the runner-up mood if you don't have many, then GIPHY/Tenor -
so a populated library works fully offline.

## Getting the APK (no Android Studio needed)

The project builds itself on GitHub's servers.

1. Push this project to a GitHub repository (keep the `.github` folder).
2. Open the **Actions** tab — a run called **Build MoodBoard APK** starts and
   finishes in ~3–5 minutes.
3. Download **`MoodBoard-debug.apk`** from **Releases** (right sidebar), or from
   the finished Actions run under **Artifacts**.
4. Copy it to your phone and install (allow "install from unknown sources").

> If the release step reports a permission error, set
> **Settings → Actions → General → Workflow permissions → Read and write** and
> re-run. The APK is still available under Artifacts either way.

Prefer Android Studio? Open the folder and use **Build → Build APK(s)**.

## First-time setup on the phone

1. Open the **MoodBoard** app.
2. Tap **Enable keyboard** and turn on *MoodBoard Keyboard* in system settings.
3. Tap **Choose keyboard** and select *MoodBoard Keyboard*.

No keys to enter — emotion runs on-device, and a built-in GIPHY demo key powers
stickers out of the box. To use your own GIPHY or Tenor key, replace
`DEFAULT_GIPHY_KEY` in `app/.../util/Prefs.kt`.

## Notes
- `minSdk 24` (Android 7.0) — runs on virtually all phones in use.
- APK is ~35 MB (MediaPipe native libraries, limited to ARM CPUs).
- Debug-signed for sideloading; release signing is needed for the Play Store.
- See **`CAVEATS.md`** for known limitations and fixes.
