# MoodBoard — Emotion-Aware Sticker Keyboard (Android)

A lightweight Android keyboard that types normally and, with one tap, reads your
facial expression from the front camera, works out your mood on the device, and
shows matching stickers you can send into any chat — without leaving the
conversation. A floating meme button (v3) offers the same scan-and-send flow
outside the keyboard, in any app.

Emotion recognition runs entirely on the phone using MediaPipe Face Landmarker
(facial blendshapes). Stickers come from the GIPHY Stickers API and Tenor, tuned
towards South Indian film comedy/reaction sources by default, plus any stickers
you import yourself.

## What's inside

| Piece | File |
|---|---|
| Keyboard service (IME) | `app/.../ime/MoodBoardService.kt` |
| QWERTY view (typing, shift, symbols, icon keys) | `app/.../ime/QwertyKeyboardView.kt` |
| In-keyboard front camera (CameraX) | `app/.../camera/KeyboardCameraManager.kt` |
| Camera permission helper | `app/.../camera/PermissionActivity.kt` |
| On-device emotion (MediaPipe, FACS-EBS v2) | `app/.../emotion/EmotionAnalyzer.kt`, `ExpressionClassifier.kt` |
| Emotions + generic sticker queries | `app/.../emotion/Emotion.kt` |
| South Indian / generic query pools + relevance keywords | `app/.../stickers/MemeQueryBank.kt` |
| Anti-repeat ring buffer (per emotion, last 40 shown) | `app/.../stickers/RecentlyShownStore.kt` |
| Mood-bucketed sticker storage | `app/.../stickers/StickerLibrary.kt` |
| Sticker search (yours → pre-cache → South Indian/GIPHY/Tenor → emoji) | `app/.../stickers/StickerRepository.kt` |
| Meme pre-cache store (`filesDir/meme_cache/`) | `app/.../stickers/MemeCacheStore.kt` |
| Periodic + on-demand pre-cache worker (WorkManager) | `app/.../stickers/MemePrefetchWorker.kt` |
| Send sticker into chat (Commit Content) | `app/.../util/RichContentSender.kt` |
| Floating overlay bubble service (foreground service) | `app/.../overlay/FloatingBubbleService.kt` |
| Overlay panel camera + sticker wiring | `app/.../overlay/OverlayPanelController.kt` |
| Overlay clipboard/share insertion | `app/.../overlay/OverlayInsertion.kt` |
| Overlay permission request flow | `app/.../ui/OverlayPermissionActivity.kt` |
| Enable / switch keyboard, calibration, Emotion Lab, meme cache, floating button | `app/.../ui/SetupActivity.kt` |
| Manage stickers - level 1 (mood grid) | `app/.../ui/StickerManagerActivity.kt` |
| Manage stickers - level 2 (one mood's grid) | `app/.../ui/MoodStickersActivity.kt` |
| "Share into MoodBoard" target | `app/.../ui/ReceiveStickerActivity.kt` |
| Icon set (Material-Symbols-style, tinted to theme) | `app/src/main/res/drawable/ic_*.xml` |
| Material 3 palette + dark-first theme | `app/src/main/res/values/colors.xml`, `themes.xml`, `values-night/colors.xml` |
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
4. Tap a sticker and it is sent straight into the chat. **Long-press a sticker**
   instead to keep it — a mood picker (defaulting to the mood you just scanned)
   appears, and it's saved into your own library for next time.

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
your stickers for the runner-up mood if that's thin, then — if the grid is
*still* thin — your stickers from any mood, so a populated library is never
invisible just because everything landed in one bucket; then the meme pre-cache
for that mood (instant, no network), then online results, then an emoji
fallback — so a populated library, or a warm cache, works fully offline.

A sticker's mood only ever comes from an actual `Emotion` (the mood picker only
offers real moods), and on every load `StickerLibrary` heals any entry that
somehow ended up filed under something else by folding it onto Neutral, so
nothing you've saved can become permanently unreachable.

### Online results — relevance, South Indian sourcing, anti-repeat (v3)
Online sticker results are pulled from a per-emotion pool of queries rather than
one fixed string, so the same expression doesn't return the same stickers twice:
- **Culture pack** (`MemeQueryBank`, default **South Indian**, switchable in
  Setup): queries built from well-known Tamil/Telugu/Malayalam/Kannada comedy and
  reaction sources (Vadivelu, Brahmanandam, Mohanlal, and more), covering all 19
  emotions. A generic pack is the fallback tier.
- Each scan picks a **random query** from the pool and a **random API offset**,
  then **shuffles** the results — three independent sources of variation.
- Candidates are **scored for relevance** (emotion-keyword hits, culture-keyword
  hits, an off-topic blocklist) before display, with a floor that re-admits the
  best rejects rather than ever showing an empty grid.
- A **`RecentlyShownStore`** ring buffer (last 40 per emotion) filters out stuff
  you were just shown, re-admitting the oldest-seen first if too few remain.
- Attribution ("Powered by GIPHY" / "via Tenor") appears under the grid whenever
  online results are shown, and in Setup's about text — required by GIPHY's API
  terms.

### Meme pre-cache (v3)
The 10 most-used moods (falling back to a sensible default list until you have
usage history) are pre-fetched in the background — 10 items each, ~100 items
steady-state — so common scans are instant even offline:
- Stored under `filesDir/meme_cache/` with a JSON index; capped at 300 files /
  150 MB (LRU eviction) with a 7-day TTL (stale entries still serve instantly and
  trigger a background refresh).
- A `WorkManager` periodic job runs every 12 hours (Wi-Fi-only and
  battery-not-low by default, both configurable in Setup), plus a **"Refresh meme
  cache now"** one-shot button that shows cache size, item count, and last
  refresh time.

### Floating meme button (v3)
Setup → **Floating meme button** starts a draggable bubble that floats over any
app (needs the *Display over other apps* permission). Tap it to open a compact
panel, scan your expression with the front camera, and pick a meme — no keyboard
required. Because an overlay window cannot type into another app's text field
(see `CAVEATS.md` §5c for why), the meme is copied to the clipboard for pasting,
or sent through the share sheet.

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

## UI and theming (v3)
The whole app was restyled onto Material 3 (`Theme.Material3.DayNight.NoActionBar`)
with a dark-first violet palette (`values/colors.xml`, `values-night/colors.xml`),
a consistent shape scale (12dp cards, 20dp buttons, 8dp keys), and Material 3 text
appearances in place of hardcoded text sizes. Every control that used to be an
emoji-as-button (the keyboard's shift/backspace/enter keys, settings/mood/camera
buttons, sticker manager actions, the floating bubble icon, etc.) now uses a
hand-authored vector icon from `res/drawable/ic_*.xml` (24dp, tinted via
`?attr/colorControlNormal` so it follows the theme). Emoji remain as **content** —
mood-bucket covers, the emoji fallback grid, and the emotion labels themselves —
just not as control affordances.

## Notes
- `minSdk 24` (Android 7.0) — runs on virtually all phones in use.
- APK is ~35 MB (MediaPipe native libraries, limited to ARM CPUs).
- Debug-signed for sideloading; release signing is needed for the Play Store.
- Fully functional offline, uncalibrated, and with the floating overlay disabled.
- See **`CAVEATS.md`** for known limitations and fixes.
