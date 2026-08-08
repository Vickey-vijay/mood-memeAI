# MoodBoard v3 — Engineering Specification

Response to the examiner's review comments plus the requested product changes.
Four independent workstreams, implemented **strictly in this order** (each touches the
previous one's files):

- **A** — Meme relevance, South Indian film sourcing, anti-repeat randomness
- **B** — Pre-cache background worker
- **C** — Floating overlay bubble (keyboard-independent)
- **D** — Theme, UI and icon overhaul

## Examiner comments — disposition

| # | Comment | Disposition |
|---|---|---|
| 1 | Run a model on-device instead of calling the VLM; A/B it | **Already satisfied.** Detection has been fully on-device (MediaPipe Face Landmarker) since v1 of the Android build; the VLM only ever existed in the earlier `MoodMeme AI` concept abstract. There is no network call in the detection path. The A/B comparison against a VLM is explicitly **out of scope** by the student's decision. |
| 2 | Pre-cache trending memes for common emotions to avoid an API round trip | **Workstream B.** |
| 3 | Floating window so the feature is not tied to a keyboard | **Workstream C.** |

Additional product requirements from the student:

| Req | Description | Workstream |
|---|---|---|
| R1 | Returned memes are often irrelevant to the detected expression | A |
| R2 | Memes must be specifically South Indian film memes | A |
| R3 | The same expression must not return the same memes every time | A |
| R4 | Better theme/UI with familiar icons instead of emoji-as-buttons | D |

---

# Workstream A — Relevance, South Indian sourcing, anti-repeat

## A.1 Why results are currently irrelevant

`Emotion.query` is a single fixed string per emotion (`"happy smile reaction sticker"`).
It is passed verbatim to GIPHY sticker search, whose ranking is popularity-based, not
semantic. Three consequences: results drift off-emotion; results are culturally generic;
and because the query never changes, **the same top-N comes back every single scan**.

## A.2 `MemeQueryBank` (new)

Replace the single query with a **pool of queries per emotion**, in two culture packs.

```kotlin
enum class MemeCulture { SOUTH_INDIAN, GENERIC }

object MemeQueryBank {
    fun queries(emotion: Emotion, culture: MemeCulture): List<String>
    fun keywords(emotion: Emotion): List<String>   // for relevance scoring, A.4
}
```

**SOUTH_INDIAN pack** — 6–8 queries per emotion, built from well-known South Indian film
comedy and reaction sources across all four industries. Draw on names such as:

- *Tamil* — Vadivelu, Goundamani, Senthil, Santhanam, Yogi Babu, Soori, Vivek,
  Rajinikanth, Ajith, Vijay, Sivakarthikeyan
- *Telugu* — Brahmanandam, Ali, Sunil, Venu Madhav, Allu Arjun, Prabhas
- *Malayalam* — Jagathy Sreekumar, Salim Kumar, Suraj Venjaramoodu, Mohanlal, Mammootty
- *Kannada* — Sadhu Kokila, Sharan, Yash

Compose each query as `<source> <emotion-word>` or `<industry> comedy <emotion-word>`,
e.g. for `ANNOYED`: `"vadivelu annoyed"`, `"goundamani irritated"`,
`"tamil comedy irritated reaction"`, `"brahmanandam annoyed"`, `"yogi babu irritated"`,
`"telugu comedy annoyed"`, `"mohanlal irritated"`. Cover all 19 emotions.

**GENERIC pack** — keep the existing `Emotion.query` strings, used as the fallback tier.

`Emotion.query` stays as a field (the generic string) so nothing else breaks, but
`StickerRepository` must stop using it directly.

`Prefs.memeCulture` — default **SOUTH_INDIAN**, switchable from Setup.

## A.3 Randomness (R3)

Three independent sources of variation, all applied per scan:

1. **Random query** — pick uniformly from the emotion's pool.
2. **Random API offset** — GIPHY and Tenor both accept an offset/`pos`. Pick
   `offset = random(0..40)`. This is what actually reaches past the same popular head.
3. **Shuffle** the surviving results before display.

Use a single `java.util.Random` seeded from `System.nanoTime()`; do not use a fixed seed.

## A.4 Relevance filtering (R1)

GIPHY returns `title` and `slug` per item; Tenor returns `content_description` and `tags`.
Score every candidate:

```
score = 2 * (emotion keyword hits in title/tags)
      + 1 * (culture keyword hits, e.g. actor or industry name)
      - 3 * (blocklist hits)
```

- `MemeQueryBank.keywords(emotion)` supplies emotion synonyms
  (e.g. ANNOYED → `annoy, irritat, frustrat, eye roll, fed up, cringe`).
- Blocklist: obvious off-topic noise (`birthday, logo, brand, advertisement, promo`).
- Keep items with `score > 0`. **If fewer than 8 survive, re-admit the highest-scoring
  rejects until 8** — never return an empty grid because the filter was too strict.
- Preserve `rating=pg-13` on GIPHY and `contentfilter=medium` on Tenor.

## A.5 Anti-repeat store (R3)

```kotlin
class RecentlyShownStore(prefs: Prefs) {
    fun filterUnseen(emotion: Emotion, items: List<StickerItem>): List<StickerItem>
    fun markShown(emotion: Emotion, items: List<StickerItem>)
}
```

- Per emotion, keep a ring buffer of the **last 40 identities**. Identity = a stable
  string: GIPHY/Tenor item id if available, otherwise `sendUrl`.
- `filterUnseen` drops seen items; if that leaves fewer than 6, re-admit the
  **oldest-seen** first (so repeats are at least the least-recently-seen ones).
- Persist as JSON in `Prefs` under `recently_shown_v1`. Cap the whole structure so it
  cannot grow unbounded across 19 emotions.
- `markShown` is called with whatever was actually rendered into the grid.

## A.6 Retrieval order (supersedes SPEC_V2 §B.5)

1. The user's own stickers for the detected mood (favourites first) — unchanged.
2. **Pre-cache** for that mood (workstream B) — instant, no network.
3. Online: South Indian query pool → if that yields nothing usable, the GENERIC pool.
4. Emoji fallback — unchanged.

Apply A.4 filtering and A.5 anti-repeat to tiers 2 and 3. Do **not** filter the user's
own stickers — they chose those deliberately.

## A.7 Attribution

GIPHY's API terms require visible attribution. Add a small "Powered by GIPHY" / "via
Tenor" label under the results grid, shown only when online results are present. Also
mention it in the Setup screen's About line.

---

# Workstream B — Pre-cache (examiner comment 2)

## B.1 What to prefetch

The examiner asked for "top 10 trending memes of top 10 common emotions". Make the
emotion list **data-driven** rather than hardcoded, which is both a better answer and a
better story:

- `Prefs.moodUsageCounts` — a `Map<emotionKey, Int>`, incremented every time a scan
  resolves to that emotion.
- Prefetch targets = the **top 10 by usage count**, falling back to a sensible default
  list (`HAPPY, LAUGHING, SAD, ANGRY, ANNOYED, SURPRISED, EXCITED, SLEEPY, KISS,
  SKEPTICAL`) until usage data exists.
- 10 items per emotion → 100 cached items steady-state.

## B.2 Storage

```
filesDir/meme_cache/
  cache_index.json
  happy/<sha1>.gif ...
```

`cache_index.json`: `{ "version":1, "items":[ {"id","mood","file","mime","query",
"fetchedAt","lastUsedAt","bytes"} ] }`

- Atomic write (temp + rename); rebuild from disk if corrupt — same discipline as
  `StickerLibrary`.
- **Caps:** 300 files or 150 MB, whichever first. Evict by `lastUsedAt` (LRU).
- **TTL:** 7 days. Expired entries are still served (stale-but-instant) but trigger a
  background refresh.

## B.3 Worker

- Add `androidx.work:work-runtime-ktx:2.9.1` — the only new dependency permitted.
- `MemePrefetchWorker : CoroutineWorker`, registered as **periodic, every 12 hours**,
  with `NetworkType.CONNECTED` (or `UNMETERED` when `Prefs.prefetchWifiOnly`) and
  `setRequiresBatteryNotLow(true)`.
- Enqueue with `ExistingPeriodicWorkPolicy.KEEP` from `SetupActivity.onCreate` — the app
  has no `Application` subclass and adding one is unnecessary.
- Also expose a **"Refresh meme cache now"** one-shot from Setup, and show
  cache size + item count + last refresh time.
- `Prefs.prefetchEnabled` (default true), `Prefs.prefetchWifiOnly` (default true).
- The worker must be resilient: a failed emotion is skipped, not fatal; return
  `Result.retry()` only on wholesale network failure.

## B.4 Read path

`StickerRepository` tier 2 reads the cache synchronously off `Dispatchers.IO` and returns
immediately. Update `lastUsedAt` on the items served. Cached items are local files, so
they must be marked so `RichContentSender` grants a `FileProvider` URI correctly — extend
`res/xml/file_paths.xml` to cover `meme_cache/`.

---

# Workstream C — Floating overlay bubble (examiner comment 3)

## C.1 Behaviour

A draggable bubble that floats over other apps. Tap it → a compact panel opens → front
camera scans the expression → meme grid appears → tap a meme to insert it. Works in any
app, with no keyboard involved.

## C.2 Components

| File | Responsibility |
|---|---|
| `overlay/FloatingBubbleService.kt` | Foreground service owning the `WindowManager` views: collapsed bubble and expanded panel |
| `overlay/OverlayPanelController.kt` | Camera + `EmotionAnalyzer` + `StickerRepository` wiring for the panel; reuses the existing engine unchanged |
| `overlay/OverlayInsertion.kt` | Clipboard-image and share-intent insertion |
| `ui/OverlayPermissionActivity.kt` | Requests `SYSTEM_ALERT_WINDOW`, then starts the service |
| `res/layout/overlay_bubble.xml`, `overlay_panel.xml` | Views |

## C.3 Windowing

- `WindowManager.LayoutParams` with `TYPE_APPLICATION_OVERLAY`,
  `FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS`, `format = TRANSLUCENT`.
- Collapsed bubble: ~56 dp, dragged via `OnTouchListener` (track down/move/up, treat
  movement under ~10 dp as a tap), snapped to the nearer screen edge on release.
- Expanded panel: `MATCH_PARENT` width, ~60 % height, gravity bottom. While expanded the
  panel needs focus for scrolling, so **clear `FLAG_NOT_FOCUSABLE` on expand and restore
  it on collapse** — otherwise the grid will not scroll.
- Persist the bubble's last position in `Prefs`.

## C.4 Permissions and foreground-service compliance

This is the part most likely to break on the Android 16 test device. Get it exactly right:

- `AndroidManifest.xml` additions:
  - `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>`
  - `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
  - service declared with `android:foregroundServiceType="specialUse"` and a nested
    `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="floating_meme_launcher"/>`
- Check `Settings.canDrawOverlays(context)`; if false, launch
  `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` URI from
  `OverlayPermissionActivity`. Never assume it was granted.
- On API 33+, request `POST_NOTIFICATIONS` before starting the service.
- Call `startForeground()` **within 5 seconds** of service start, with a low-importance
  notification on its own channel, carrying a **Stop** action.
- The service must only ever be started from a user tap in an Activity — never from
  background — to satisfy background-FGS-start restrictions.
- Camera use inside the overlay still requires `CAMERA`; if it is missing, route through
  the existing `PermissionActivity` rather than failing silently.

## C.5 Insertion — and its honest limit

An overlay has **no `InputConnection`**, so it cannot insert rich content into another
app's text field the way the IME does. Do not pretend otherwise. Two real routes, both
implemented:

1. **Clipboard image** (primary) — copy the file via `FileProvider` into
   `ClipData.newUri(resolver, "meme", uri)`, grant read permission, then show a one-line
   hint: *"Copied — long-press the chat box and tap Paste."* Most modern chat apps accept
   an image paste.
2. **Share sheet** (secondary) — `ACTION_SEND` with the image URI, letting the user pick
   the target chat.

Both must set the correct mime (`image/gif`, `image/webp`, `image/png`) and use
`FLAG_GRANT_READ_URI_PERMISSION`. Document the limitation in `CAVEATS.md`.

## C.6 Entry point

A card in `SetupActivity`: *Floating meme button* — explains what it does, shows whether
the overlay permission is granted, and offers Start/Stop. Reflect the running state
accurately when returning to the screen.

---

# Workstream D — Theme, UI and icons (R4)

## D.1 Icons

Replace emoji-as-buttons with a hand-authored vector drawable set in
`res/drawable/`, Material-Symbols-style, 24 dp viewport, `?attr/colorControlNormal`
tint so they theme automatically. Minimum set:

`ic_mood`, `ic_camera`, `ic_settings`, `ic_stickers`, `ic_add`, `ic_folder`,
`ic_delete`, `ic_favorite`, `ic_favorite_border`, `ic_move`, `ic_backspace`,
`ic_enter`, `ic_shift`, `ic_space`, `ic_keyboard`, `ic_check`, `ic_close`,
`ic_refresh`, `ic_share`, `ic_copy`, `ic_calibrate`, `ic_lab`, `ic_bubble`.

Emoji remain legitimate **as content** — mood-bucket covers, emoji fallback grid — just
not as control affordances.

## D.2 Palette and theming

- Keep the violet brand hue but define a proper token set in `colors.xml`:
  primary / primaryContainer / onPrimary / surface / surfaceVariant / outline /
  error, with a **dark-first** scheme, since the keyboard is overwhelmingly seen dark.
- `themes.xml`: base on `Theme.Material3.DayNight.NoActionBar`, and add
  `values-night/` overrides. Material 3 is available — `material:1.11.0` is already a
  dependency.
- Consistent shape scale: 12 dp cards, 20 dp buttons, 8 dp keys.
- Type scale via Material 3 text appearances rather than hardcoded `textSize`.

## D.3 Surfaces to restyle

`keyboard_view.xml` (keys, mood button, status bar, scan overlay with its top-3 bars and
why-chips), `activity_setup.xml` (cards with leading icons), `activity_sticker_manager.xml`
and `item_mood_bucket.xml` (count badges, cover framing), `activity_mood_stickers.xml`,
`activity_calibration.xml`, `activity_emotion_lab.xml` (AU bars — use
`LinearProgressIndicator`), and the new overlay layouts.

Preserve every existing view **id** unless the owning Kotlin file is updated in the same
change — view binding will fail the build otherwise.

---

# Constraints for all workstreams

- Kotlin, minSdk 24, targetSdk 34, view binding. AGP 8.5.2 / Gradle 8.9 / JDK 21.
- **Only one new dependency permitted in total:** `androidx.work:work-runtime-ktx:2.9.1`
  (workstream B). Everything else uses what is already there.
- Do not regress the v2 emotion engine or sticker library. `emotion/` is off-limits
  except where a workstream is explicitly told to touch it.
- No blocking I/O on the main thread; no network on the main thread.
- The app must remain fully functional **offline**, **uncalibrated**, and with the
  **overlay disabled**.
- Every user-facing string in `res/values/strings.xml`.
- Verify by building: `./gradlew assembleDebug` must succeed before reporting done.
- Update `README.md` and `CAVEATS.md` at the end of workstream D.
