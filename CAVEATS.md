# MoodBoard — Caveats, Known Limits & Fixes

Read this before you test. These are the realistic edge cases for MoodBoard v3
(relevance/South Indian sourcing/anti-repeat, meme pre-cache, floating overlay
bubble, and the Material 3 UI overhaul), with the workaround for each, so nothing
surprises you on the phone.

## 1. Build / CI

| Issue | Why | Fix |
|---|---|---|
| `.github` folder didn't upload | GitHub's web drag-drop sometimes skips hidden folders, so no build runs | Use GitHub Desktop or `git push`, or create the file manually via **Add file → Create new file** named `.github/workflows/build-apk.yml` and paste the contents |
| Release step fails with 403 | Repo Actions lack write permission | Repo **Settings → Actions → General → Workflow permissions → Read and write**. The APK is still available under **Artifacts** regardless |
| SDK license / package errors | New SDK component needed | Already handled by the `sdkmanager` step; if a build-tools version errors, bump it in `build-apk.yml` |
| First build slow | Downloads Gradle + SDK | Normal (~3–5 min). Later builds are cached |

## 2. Sending stickers into chats (the big one)

Android only lets a keyboard insert an image if the **target app's text field
declares it accepts images** (the Commit Content API).

- **Works**: WhatsApp, Telegram, Gmail, Messages, Signal, Discord (GIF/image fields).
- **May not work**: Instagram DMs and some apps disable rich content in certain
  fields. When that happens MoodBoard shows *"This app doesn't accept stickers
  here"* instead of failing silently.
- GIPHY/Tenor results are **GIFs** (`image/gif`). A few apps accept `image/png`
  but not `image/gif`; your imported PNG stickers cover those cases.
- This is an Android platform rule, not a bug — no keyboard can bypass it.

## 3. Camera inside a keyboard

- An IME is a `Service`, so it can't pop a permission dialog. The first Mood tap
  opens a tiny transparent screen to ask for **Camera**. Grant it, then tap Mood
  again.
- Some heavily-customised Android skins restrict camera use from a background
  service. If the preview stays black, open the camera once from the MoodBoard
  app first, or grant Camera in **Settings → Apps → MoodBoard → Permissions**.
- Front camera only. Devices without a front camera fall back gracefully (you'll
  see a "Camera failed" toast and stay on the keyboard).

## 4. Emotion detection accuracy

- Emotion is inferred on-device from facial blendshapes (MediaPipe Face
  Landmarker); expect rough accuracy, not clinical precision. Good lighting and a
  clear, centered face help a lot.
- The expression is scored across all emotions and the strongest is chosen, so it
  won't get stuck on "Neutral"; the "Use mood" button forces a reading.
- If a mood is hard to trigger, its scoring weights can be tuned in
  `EmotionAnalyzer` (sensitivity via `NEUTRAL_FLOOR` / `STABLE_FRAMES`).
- Everything runs frame-by-frame on the phone — no network latency, no per-request
  limits, and no photo is saved to your gallery.

## 5. Sticker providers

- The built-in **GIPHY demo key is shared and rate-limited** — add your own key
  for dependable results.
- Switch to **Tenor** in Setup if you prefer (needs a Google Tenor key).
- No network → your **imported stickers** and the **meme pre-cache** (§5d) still
  show; only the live online query pool is unavailable.
- Online queries default to the **South Indian** culture pack (`MemeQueryBank`) —
  switchable to a generic pack in Setup. Relevance scoring and a 40-item
  per-emotion anti-repeat buffer mean results vary scan-to-scan instead of always
  returning the same popular head of the search; if a mood's results still feel
  off-topic, its query list and keyword set live in `MemeQueryBank.kt` and are
  easy to extend.

## 5b. Getting your existing WhatsApp stickers in — what's really possible

- **Not possible, by design:** reading WhatsApp's sticker/pack storage directly.
  It lives under `/data/data/com.whatsapp/...`, which Android's app sandbox makes
  unreadable to any other app without root. There is also no public WhatsApp API
  to *enumerate* a user's stickers — WhatsApp's sticker API only lets a
  third-party app *offer* a pack *to* WhatsApp, not read one back. MoodBoard does
  not attempt a workaround for this, and no legitimate app can.
- **What does work, and is implemented:**
  1. **Share → MoodBoard** on a sticker already in a WhatsApp chat (or any app).
  2. **Import a folder → Import WhatsApp stickers**, which opens Android's
     Storage Access Framework folder picker pre-pointed at
     `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` — the
     *received-stickers* folder WhatsApp itself writes to, which (unlike its
     private app data) sits under `Android/media` and is SAF-readable. If that
     folder doesn't exist on a device the picker just opens at its default
     location — it never crashes.
  3. **Import from gallery**, for stickers already saved to photos.
- Sticker retrieval order at scan time (SPEC_V2 B.5, extended by a client bug
  fix): your own stickers for the detected mood (favourites first) → your own
  stickers for the runner-up mood if you have fewer than 12 → your own stickers
  from **any** mood if the grid is still thin, ranked below the first two → the
  meme pre-cache → GIPHY/Tenor, only if online and enabled in Settings. The
  "any mood" step exists specifically so a populated personal library can never
  disappear from the grid just because every sticker you've saved happens to be
  filed under one mood that isn't today's detected (or runner-up) mood.
- **Long-press a sticker in the keyboard's result grid** (any tier — yours,
  cached, or online) to save it into your own library on the spot, with the
  mood picker defaulting to the mood you just scanned. This is how you keep a
  meme you found while scanning, without leaving the keyboard.

## 5c. Floating meme button (overlay bubble) — what it can and can't do

- **It cannot type or paste into another app's text field.** This is the honest,
  unavoidable limit. An overlay window (`TYPE_APPLICATION_OVERLAY`) has no
  `InputConnection` and no `EditorInfo` for whatever app is in front, so the
  Commit Content API the keyboard uses simply does not exist for it. Android has
  no API that lets a floating window inject content into another app's editor —
  by design, since that is precisely a keylogger/injection primitive.
- **So there are two routes, and both are implemented:**
  1. **Clipboard (primary)** — tap a meme and it goes on the clipboard as an
     image content URI. Then long-press the chat box and tap **Paste**. WhatsApp,
     Telegram, Signal, Gmail and Messages all accept an image paste.
  2. **Share sheet (secondary)** — long-press a meme (or use the **Share…**
     button) to open `ACTION_SEND` and pick the target chat.
  A few apps accept neither; there is no third route.
- **Permission**: needs *Display over other apps* (`SYSTEM_ALERT_WINDOW`), granted
  from Setup → *Floating meme button* → Start. If you revoke it while the bubble
  is running, the service notices and stops itself instead of crashing.
- **Notification**: the bubble is kept alive by a foreground service, so Android
  requires a permanent low-priority notification. It carries a **Stop** action.
  Dismissing the notification is not possible while the bubble runs — that is an
  OS rule for foreground services, not a MoodBoard choice.
- **The bubble and the keyboard share one front camera.** The overlay opens the
  camera *only* while the expanded panel is actively scanning, and releases it
  (and the MediaPipe model) the instant the mood locks, the panel collapses, the
  panel loses window focus, or the service stops. Practically: if you tap a text
  box while the panel is open, the panel closes first and the keyboard gets a
  clean camera. Do not expect both scans to run at once — the second one to start
  would silently steal the camera from the first (CameraX's provider is a
  process-wide singleton).
- **Android 14+ camera-in-a-service note**: the service is declared
  `foregroundServiceType="specialUse"` exactly as SPEC_V3 C.4 requires. Camera
  access works because the process has a visible overlay window while scanning.
  If a particular OEM build denies camera frames to the bubble, the fix is one
  line in `AndroidManifest.xml`: `specialUse|camera` plus the
  `FOREGROUND_SERVICE_CAMERA` permission (documented in a comment there).
- **The app is fully functional with the overlay never enabled.** Nothing
  auto-starts it, there is no boot receiver, and the service is `START_NOT_STICKY`
  so Android will not resurrect it in the background.

## 5d. Meme pre-cache — what to expect

- The pre-cache targets your **top 10 most-used moods** (usage is tracked
  locally in `Prefs.moodUsageCounts`), falling back to a fixed default list
  (Happy, Laughing, Sad, Angry, Annoyed, Surprised, Excited, Sleepy, Kiss,
  Skeptical) until you have scan history.
- It is capped at **300 files / 150 MB** (whichever hits first, LRU-evicted) with
  a **7-day TTL**. Expired entries still serve instantly (stale-but-usable) while
  a refresh happens in the background — you should never see a spinner for a
  cached mood.
- Refresh runs automatically every 12 hours (Wi-Fi-only and battery-not-low by
  default, both toggleable in Setup), or on demand via **Refresh now**. A failed
  emotion during a refresh is skipped, not fatal, so one bad network blip does
  not blank the whole cache.
- Cached items are plain files under `filesDir/meme_cache/`; sending one reuses
  the same `FileProvider`/Commit-Content path as any other sticker, so it works
  identically whether the source was your library, the cache, or a live fetch.

## 6. Keyboard scope (intentionally minimal for v1)

- QWERTY + a `?123` symbols page + shift/caps. No long-press accents, no
  swipe-typing, no autocorrect, no number row — kept lean for a light APK.
- No emoji panel (the Mood/sticker flow is the headline feature). Easy to add later.
- Languages: English (`en_US`) only.

## 7. Signing / distribution

- CI produces a **debug-signed** APK — perfect for sideloading to your own phone,
  **not** for the Play Store. Publishing there needs a release keystore + signing
  config (documented as a future step).

## 8. UI and theming (v3)

- The app is themed with Material 3 (`Theme.Material3.DayNight.NoActionBar`),
  dark-first, since the keyboard and overlay are almost always used dark. A
  `values-night/colors.xml` override exists to satisfy the DayNight contract
  explicitly, but the default palette is already the dark one, so light-mode
  devices see the same look.
- Control affordances (keyboard shift/backspace/enter, settings/mood/camera
  buttons, sticker actions, the floating bubble) use hand-authored vector icons,
  not emoji — emoji are kept only as **content** (mood-bucket covers, the emoji
  fallback grid, emotion labels).
- This was a presentation-only pass: no scan/search/cache/overlay behaviour
  changed. The one exception is `EmotionLabActivity`'s Action Unit bars, which
  were switched from a plain `ProgressBar` to Material's
  `LinearProgressIndicator` — same values, different widget.

## 9. Privacy

- Camera frames are analysed in memory on the device and are never saved to the
  gallery or uploaded. Emotion recognition is fully on-device; only the emotion
  keyword leaves the phone, to fetch stickers.
- The web `.env` GIPHY key in `../moodboard-keyboard-prototype` is for the browser
  prototype only and is **not** bundled into the APK.

---
If something behaves unexpectedly during testing, note which app + which step,
and it's almost always one of the cases above.
