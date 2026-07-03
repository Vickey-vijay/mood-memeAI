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
| On-device emotion (MediaPipe) | `app/.../emotion/EmotionAnalyzer.kt` |
| Emotions + sticker queries | `app/.../emotion/Emotion.kt` |
| Sticker search (GIPHY / Tenor + your own) | `app/.../stickers/StickerRepository.kt` |
| Send sticker into chat (Commit Content) | `app/.../util/RichContentSender.kt` |
| Enable / switch keyboard | `app/.../ui/SetupActivity.kt` |
| Add / remove your own stickers | `app/.../ui/StickerManagerActivity.kt` |
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

Detected emotions: Happy, Laughing, Excited, Surprised, Shocked, Sad, Angry,
Annoyed, Disgust, Skeptical, Sleepy, Kiss, Neutral.

### Your own stickers
Settings (the wrench on the keyboard) → **Manage my stickers** → **Add sticker
from gallery**. Long-press a sticker to delete it. Imported stickers appear first
in every mood and work with no internet.

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
