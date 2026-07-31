# MoodBoard — Caveats, Known Limits & Fixes

Read this before you test. These are the realistic edge cases for a v1 emotion
keyboard, with the workaround for each, so nothing surprises you on the phone.

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
- No network → only your **imported** stickers show (still usable, retrieval order
  below).

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
- Sticker retrieval order at scan time (SPEC_V2 B.5): your own stickers for the
  detected mood (favourites first) → your own stickers for the runner-up mood if
  you have fewer than 12 → GIPHY/Tenor, only if online and enabled in Settings.

## 6. Keyboard scope (intentionally minimal for v1)

- QWERTY + a `?123` symbols page + shift/caps. No long-press accents, no
  swipe-typing, no autocorrect, no number row — kept lean for a light APK.
- No emoji panel (the Mood/sticker flow is the headline feature). Easy to add later.
- Languages: English (`en_US`) only.

## 7. Signing / distribution

- CI produces a **debug-signed** APK — perfect for sideloading to your own phone,
  **not** for the Play Store. Publishing there needs a release keystore + signing
  config (documented as a future step).

## 8. Privacy

- Camera frames are analysed in memory on the device and are never saved to the
  gallery or uploaded. Emotion recognition is fully on-device; only the emotion
  keyword leaves the phone, to fetch stickers.
- The web `.env` GIPHY key in `../moodboard-keyboard-prototype` is for the browser
  prototype only and is **not** bundled into the APK.

---
If something behaves unexpectedly during testing, note which app + which step,
and it's almost always one of the cases above.
