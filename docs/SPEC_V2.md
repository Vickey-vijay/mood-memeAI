# MoodBoard v2 — Engineering Specification

This is the authoritative spec for the final-semester rebuild. Two independent
workstreams: **(A) Emotion Engine v2** and **(B) Mood-Categorised Sticker Library**.

---

# Part A — Emotion Engine v2 (FACS-EBS)

## A.0 Why v1 fails

`EmotionAnalyzer.classify()` v1 takes 15 hand-picked blendshapes, multiplies them by
hand-tuned constants, and takes the argmax. Four defects:

| # | Defect | Consequence |
|---|---|---|
| D1 | No per-user neutral baseline | A resting face with naturally down-turned corners scores permanently SAD; a user with heavy brows scores permanently ANGRY. Every user's zero point is different. |
| D2 | Unnormalised mixed-scale features | `jawOpen` saturates near 1.0 while `browDownLeft` rarely exceeds 0.45. Any emotion whose formula contains `jaw` dominates the argmax. This is why only 4 emotions are reachable. |
| D3 | Single-frame decision | Blendshape output is noisy frame-to-frame; classification flickers and the `STABLE_FRAMES` counter resets, so the scan hangs. |
| D4 | L/R averaging destroys asymmetry, and 20 of the 52 blendshapes are unused | Wink, smirk/contempt, single-brow-raise, eye-roll, cheek-puff are all *physically* distinguishable but *structurally* invisible to v1. |

v2 fixes all four. It stays fully on-device and deterministic — no training data,
no network, and every decision is explainable, which is what the viva question
("how do you interpret an annoyed face?") actually demands.

## A.1 Pipeline

```
CameraX front stream
  -> MediaPipe FaceLandmarker (VIDEO, numFaces=1, outputFaceBlendshapes=true)
  -> 52 raw blendshape weights, per frame
  -> [1] Baseline correction   (per-user neutral, calibrated once)
  -> [2] AU projection          (52 blendshapes -> 26-dim Action Unit vector)
  -> [3] Temporal buffer + EMA  (24 frames, alpha = 0.35)
  -> [4] Apex selection         (frame with max ||a||, not the latest frame)
  -> [5] Prototype matching     (cosine x coverage against 18 FACS prototypes)
  -> [6] Softmax -> distribution, blend arbitration, intensity gate, hysteresis
  -> EmotionResult { emotion, confidence, distribution[3], contributors[4] }
```

## A.2 [1] Per-user neutral calibration

**New class `NeutralBaseline`.**

- Captured from a Setup screen: *"Calibrate my neutral face"* — 3 s, ~60 frames,
  user holds a relaxed expression.
- For each of the 52 blendshapes store the **median** across accepted frames
  (median, not mean — robust to a stray blink or smile).
- **Accept/reject:** require a face in >= 70 % of frames, and require the median
  absolute deviation of `mouthSmile*`, `browDown*`, `jawOpen` to stay below 0.15
  (i.e. the user actually held still). On failure show *"Hold a relaxed face and
  try again"* and do not overwrite the stored baseline.
- Persist as JSON in `Prefs` under key `neutral_baseline_v1`, plus
  `neutral_baseline_at` (epoch ms) for the UI to show *"Calibrated on <date>"*.
- **Uncalibrated fallback:** all-zeros baseline (v1 behaviour) **and** the keyboard
  shows a one-line nudge *"Calibrate your neutral face for better accuracy"*
  linking to Setup. The app must work uncalibrated — just less well.

**Correction formula**, applied to every raw weight `r_i`:

```
delta_i = clamp01( (r_i - b_i) / max(0.15, 1 - b_i) )
```

The `max(0.15, ...)` denominator prevents a blendshape whose baseline is already
near 1.0 from exploding to infinite sensitivity.

## A.3 [2] Action Unit projection

Project the 52 corrected blendshapes onto a **26-dimensional FACS-derived feature
vector**. This is the fix for D2 and D4: one named, comparable axis per facial
muscle action, plus explicit asymmetry and gaze axes that v1 threw away.

MediaPipe emits ARKit-compatible names. Full list for reference:
`_neutral, browDownLeft/Right, browInnerUp, browOuterUpLeft/Right, cheekPuff,
cheekSquintLeft/Right, eyeBlinkLeft/Right, eyeLookDownLeft/Right,
eyeLookInLeft/Right, eyeLookOutLeft/Right, eyeLookUpLeft/Right,
eyeSquintLeft/Right, eyeWideLeft/Right, jawForward, jawLeft, jawOpen, jawRight,
mouthClose, mouthDimpleLeft/Right, mouthFrownLeft/Right, mouthFunnel, mouthLeft,
mouthLowerDownLeft/Right, mouthPressLeft/Right, mouthPucker, mouthRight,
mouthRollLower, mouthRollUpper, mouthShrugLower, mouthShrugUpper,
mouthSmileLeft/Right, mouthStretchLeft/Right, mouthUpperUpLeft/Right,
noseSneerLeft/Right, tongueOut`

| Index | AU | Name | Source (on baseline-corrected deltas) |
|---|---|---|---|
| 0 | AU1 | Inner brow raiser | `browInnerUp` |
| 1 | AU2 | Outer brow raiser | mean(`browOuterUpLeft`, `browOuterUpRight`) |
| 2 | AU4 | Brow lowerer | mean(`browDownLeft`, `browDownRight`) |
| 3 | AU5 | Upper lid raiser | mean(`eyeWideLeft`, `eyeWideRight`) |
| 4 | AU6 | Cheek raiser (Duchenne) | mean(`cheekSquintLeft`, `cheekSquintRight`) |
| 5 | AU7 | Lid tightener | mean(`eyeSquintLeft`, `eyeSquintRight`) |
| 6 | AU9 | Nose wrinkler | mean(`noseSneerLeft`, `noseSneerRight`) |
| 7 | AU10 | Upper lip raiser | mean(`mouthUpperUpLeft`, `mouthUpperUpRight`) |
| 8 | AU12 | Lip corner puller (smile) | mean(`mouthSmileLeft`, `mouthSmileRight`) |
| 9 | AU14 | Dimpler | mean(`mouthDimpleLeft`, `mouthDimpleRight`) |
| 10 | AU15 | Lip corner depressor (frown) | mean(`mouthFrownLeft`, `mouthFrownRight`) |
| 11 | AU16 | Lower lip depressor | mean(`mouthLowerDownLeft`, `mouthLowerDownRight`) |
| 12 | AU17 | Chin raiser | max(`mouthShrugLower`, 0.6 * `mouthShrugUpper`) |
| 13 | AU18 | Lip pucker | `mouthPucker` |
| 14 | AU20 | Lip stretcher | mean(`mouthStretchLeft`, `mouthStretchRight`) |
| 15 | AU22 | Lip funneler | `mouthFunnel` |
| 16 | AU24 | Lip pressor | mean(`mouthPressLeft`, `mouthPressRight`) |
| 17 | AU26 | Jaw drop | `jawOpen` |
| 18 | AU28 | Lip suck | mean(`mouthRollLower`, `mouthRollUpper`) |
| 19 | AU33 | Cheek blow | `cheekPuff` |
| 20 | AU43 | Eyes closed | mean(`eyeBlinkLeft`, `eyeBlinkRight`) |
| 21 | ASYM_SMILE | Asymmetric smile | abs(`mouthSmileLeft` - `mouthSmileRight`) |
| 22 | ASYM_BROW | Asymmetric brow | max(abs(`browOuterUpL`-`browOuterUpR`), abs(`browDownL`-`browDownR`)) |
| 23 | ASYM_BLINK | One eye closed | abs(`eyeBlinkLeft` - `eyeBlinkRight`) |
| 24 | GAZE_UP | Eye roll | mean(`eyeLookUpLeft`, `eyeLookUpRight`) |
| 25 | TONGUE | Tongue out | `tongueOut` |

**Asymmetry axes (21–23) must be computed from the raw L/R values *before* the
L/R mean is taken.** They are the entire reason wink, smirk and single-brow-raise
become detectable.

> **Runtime capability probe.** `tongueOut` is present in the blendshape list but
> is not reliably predicted by the shipped `face_landmarker.task` model. On first
> run, if `tongueOut` never exceeds 0.15 across a whole calibration session, set
> `Prefs.tongueSupported = false` and **exclude `SILLY` from the prototype set**.
> Do not ship an emotion the model cannot see. `cheekPuff` is reliable — keep it.

## A.4 [3][4] Temporal buffer and apex selection

- Ring buffer of the last **24** AU vectors (~1.6 s at 15 fps).
- Exponential moving average on the AU vector: `a_t = 0.35 * a_raw + 0.65 * a_(t-1)`.
- An expression has an **onset → apex → offset** envelope. Classifying the newest
  frame catches onset or offset and is unstable. Instead classify the **apex**:
  `argmax_t ||a_t||_2` over the buffer, restricted to the last 1.5 s.
- Discard frames where MediaPipe reports no face; do not let them reset the buffer.

## A.5 [5] Prototype matching

Each emotion `e` has a **prototype vector** `p_e` over the 26 AU axes, with entries
in 0..1 expressing the *relative* expected activation of that action. Values are
grounded in Ekman & Friesen's EMFACS AU combinations, mapped to the ARKit
blendshape rig.

```
HAPPY        AU6 .60  AU12 1.0  AU14 .30
LAUGHING     AU6 .80  AU12 1.0  AU26 .80  AU7 .40
EXCITED      AU12 .80 AU5  .70  AU2  .60  AU26 .40
SURPRISED    AU1 .90  AU2  1.0  AU5  .80  AU26 .70
SHOCKED      AU1 .80  AU2  .80  AU5  1.0  AU26 1.0  AU20 .40
FEARFUL      AU1 .90  AU2  .60  AU4  .50  AU5 1.0  AU7 .40  AU20 .80  AU26 .50
SAD          AU1 .90  AU4  .50  AU15 1.0  AU17 .50
ANGRY        AU4 1.0  AU5  .50  AU7  .70  AU24 .80  AU9  .30
ANNOYED      AU4 .60  AU7  .80  AU24 .70  GAZE_UP .60  AU14 .20
FRUSTRATED   AU4 .80  AU15 .60  AU17 .60  AU24 .50  AU7  .40
DISGUST      AU9 1.0  AU10 .80  AU15 .50  AU16 .40  AU4  .30
CONTEMPT     ASYM_SMILE 1.0  AU14 .60  AU12 .40
SKEPTICAL    ASYM_BROW  1.0  AU2  .50  AU7  .40  AU24 .30
SLEEPY       AU43 .80  AU7  .50  AU26 .40  AU1  .30
KISS         AU18 1.0  AU22 .50
WINK         ASYM_BLINK 1.0  AU12 .50  AU6  .40
PUFFED       AU33 1.0  AU24 .40
SILLY        TONGUE 1.0  AU26 .50  AU12 .40      (gated on tongueSupported)
```

`NEUTRAL` has no prototype — it is the outcome of the intensity gate.

**Score.** Let `â = a / (||a||_2 + eps)` and `p̂_e = p_e / (||p_e||_2 + eps)`.

```
similarity_e = dot(â, p̂_e)                                   // cosine, in [0,1]
coverage_e   = sum_i min(â_i, p̂_e_i) / sum_i p̂_e_i           // is the prototype actually present?
score_e      = sqrt(similarity_e * coverage_e)                // geometric mean
```

Coverage matters: a sparse observation can score a high cosine against a sparse
prototype it only partially matches. The geometric mean requires the expression
to both *point in the right direction* and *contain the required actions*.

## A.6 [6] Decision, blends, hysteresis

1. **Intensity gate.** `I = ||a||_2`. If `I < 0.12`, return `NEUTRAL` with
   confidence `1 - I/0.12` — do not run the classifier.
2. **Distribution.** `P = softmax(8.0 * score)` over all active prototypes.
   Keep the top 3 for display.
3. **Blend arbitration.** If `P[top1] - P[top2] < 0.12` and the unordered pair is
   in the table below, emit the composite label instead of `top1`:

   | Pair | Composite |
   |---|---|
   | HAPPY + ANGRY | ANNOYED |
   | HAPPY + SKEPTICAL | CONTEMPT |
   | SAD + ANGRY | FRUSTRATED |
   | SURPRISED + HAPPY | EXCITED |
   | SURPRISED + SAD | SHOCKED |
   | SURPRISED + ANGRY | FEARFUL |
   | DISGUST + ANGRY | CONTEMPT |
   | SLEEPY + ANNOYED | SLEEPY |

   This is the direct, defensible answer to *"how do you interpret an annoyed
   face?"* — a mouth reading ~50 % smile against eyes reading angry resolves to
   ANNOYED, both by its own prototype (AU4+AU7+AU24) **and** by blend arbitration
   when the two parents tie.
4. **Hysteresis.** Emit a locked result only after the same label wins **3**
   consecutive apex evaluations. The `✓ Use mood` button bypasses hysteresis and
   locks the current best immediately.

## A.7 Result type and explainability

```kotlin
data class EmotionResult(
    val hasFace: Boolean,
    val emotion: Emotion,
    val confidence: Float,                       // P[winner]
    val distribution: List<Pair<Emotion, Float>>,// top 3, descending
    val contributors: List<Contribution>,        // top 4 AUs by â_i * p̂_i
    val intensity: Float,                        // ||a||
    val calibrated: Boolean
)
data class Contribution(val auLabel: String, val value: Float) // e.g. "Brow lowered" to 0.42
```

`contributors` is mandatory, not optional. It is what turns the classifier from a
black box into something defensible in a viva, and it is the source of the
"Why this mood?" figure in the report.

Human-readable AU labels: AU1 "Inner brows raised", AU2 "Brows raised",
AU4 "Brows lowered", AU5 "Eyes widened", AU6 "Cheeks raised", AU7 "Lids tightened",
AU9 "Nose wrinkled", AU10 "Upper lip raised", AU12 "Smiling", AU14 "Dimpled",
AU15 "Mouth corners down", AU16 "Lower lip down", AU17 "Chin raised",
AU18 "Lips puckered", AU20 "Lips stretched", AU22 "Lips funnelled",
AU24 "Lips pressed", AU26 "Jaw open", AU28 "Lips sucked in", AU33 "Cheeks puffed",
AU43 "Eyes closed", ASYM_SMILE "One-sided smile", ASYM_BROW "One brow raised",
ASYM_BLINK "One eye closed", GAZE_UP "Eyes rolled up", TONGUE "Tongue out".

## A.8 UI changes

- **Scan overlay:** replace the single `"😀 Happy 62%"` line with the label plus a
  compact three-row bar breakdown (top 3 with percentages) and up to three
  "why" chips from `contributors`.
- **SetupActivity:** add a *Calibrate neutral face* card showing calibration state
  and date, with a Re-calibrate action.
- **Emotion Lab** (new, reachable from Setup): live view of all 26 AU bars, the
  full distribution, intensity and calibration state. Primary purpose is
  producing report/viva screenshots and letting the user verify a mood is
  reachable. Keep it simple — a RecyclerView of labelled progress bars.

## A.9 Files

| File | Change |
|---|---|
| `emotion/Emotion.kt` | Expand enum to the 18 + NEUTRAL above; each entry keeps `label`, `query`, `emojis`; add `key` (stable storage string, e.g. `"angry"`) used by the sticker library. |
| `emotion/ActionUnits.kt` | **new** — AU index constants, labels, blendshape→AU projection. |
| `emotion/NeutralBaseline.kt` | **new** — capture, validate, JSON serialise, apply correction. |
| `emotion/EmotionPrototypes.kt` | **new** — the prototype table above. |
| `emotion/ExpressionClassifier.kt` | **new** — buffer, apex, cosine+coverage, softmax, blends, hysteresis. |
| `emotion/EmotionAnalyzer.kt` | Rewritten as a thin adapter: MediaPipe → deltas → AUs → classifier. Keep `close()`. |
| `ime/MoodBoardService.kt` | Use `EmotionResult`; new scan overlay; calibration nudge. |
| `ui/SetupActivity.kt` | Calibration card + Emotion Lab entry. |
| `ui/CalibrationActivity.kt`, `ui/EmotionLabActivity.kt` | **new** |
| `util/Prefs.kt` | `neutralBaseline`, `neutralBaselineAt`, `tongueSupported`. |

---

# Part B — Mood-Categorised Sticker Library

## B.0 Why v1 fails

`CustomStickerStore` is a flat directory of PNGs with no metadata. There is no
category, so a detected mood cannot map to the user's own stickers, and the
Manage Stickers screen is a single undifferentiated grid. Every import is
re-encoded to PNG, which destroys animated WhatsApp `.webp` stickers.

## B.1 Storage

```
filesDir/stickers/
  index.json
  happy/      <uuid>.webp  <uuid>.png ...
  angry/      ...
  annoyed/    ...
  uncategorised/
```

`index.json`:
```json
{ "version": 1,
  "items": [
    { "id":"...", "mood":"happy", "file":"happy/9f2.webp", "mime":"image/webp",
      "addedAt": 1750000000000, "source":"whatsapp_folder", "favorite": true }
  ] }
```

- **Preserve original bytes and mime.** Copy the stream as-is for `image/webp`
  and `image/gif` (animation must survive). Only re-encode when the source is a
  large JPEG/PNG — then clamp the long edge to 512 px and write PNG.
- Write `index.json` atomically (temp file + rename); tolerate a missing or
  corrupt index by rebuilding it from the directory tree.

## B.2 `StickerLibrary` (replaces `CustomStickerStore`)

```kotlin
fun moods(): List<MoodBucket>              // MoodBucket(mood, count, coverFile)
fun list(mood: Emotion): List<StickerItem> // favourites first, then newest first
fun add(uri: Uri, mood: Emotion): StickerItem?
fun addAll(uris: List<Uri>, mood: Emotion): Int
fun importTree(treeUri: Uri, mood: Emotion): Int   // SAF folder, recursive, images only
fun move(id: String, mood: Emotion)
fun setFavorite(id: String, fav: Boolean)
fun delete(id: String): Boolean
fun totalCount(): Int
```

Keep a thin `CustomStickerStore` shim delegating to `StickerLibrary` if that
avoids churn, or delete it and update call sites — implementer's choice, but no
dead code left behind.

## B.3 UI — two levels

**Level 1 — `StickerManagerActivity`** becomes a grid of **mood cards**, one per
`Emotion` (excluding NEUTRAL only if it has no stickers, otherwise include it):
emoji + label + count + cover thumbnail. Moods with zero stickers still show,
greyed, so the user knows the slot exists. Header shows total count and two
actions: **Import from gallery** and **Import a folder**.

**Level 2 — `MoodStickersActivity`** (extra: `mood_key`): grid of that mood's
stickers, with
- FAB **Add** → `GetMultipleContents("image/*")`, all imports land in this mood.
- Overflow **Import folder** → `OpenDocumentTree`, recursive image import into this mood.
- Long-press an item → bottom sheet: **Set as cover** / **Move to another mood**
  (mood picker dialog) / **Favourite** / **Delete**.
- Empty state explains the three ways to add stickers.

## B.4 Getting existing WhatsApp stickers in

**Be honest in the UI and the docs about what is and isn't possible.**

*Not possible:* reading WhatsApp's own sticker store directly. Received stickers
and pack metadata live under `/data/data/com.whatsapp/...`, which the Android
application sandbox makes unreadable to any other app without root. There is no
public WhatsApp API to enumerate a user's stickers — WhatsApp's published sticker
API is one-way (third-party app *offers a pack to* WhatsApp, via
`com.whatsapp.intent.action.ENABLE_STICKER_PACK`). Do not attempt a workaround.

*Three legitimate paths, all of which must be implemented:*

1. **Share into MoodBoard.** Register in `AndroidManifest.xml` an activity
   `ReceiveStickerActivity` with intent filters for `ACTION_SEND` and
   `ACTION_SEND_MULTIPLE` on `image/*` (webp/png/gif/jpeg). The user opens a
   sticker in WhatsApp → Share → **MoodBoard** → a mood picker appears → saved.
   This works for any app, not just WhatsApp, and needs no permissions.
2. **Folder import via SAF.** WhatsApp writes received stickers to
   `…/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/`. Unlike
   `Android/data`, the `Android/media` tree is readable through the Storage Access
   Framework. Provide an **Import WhatsApp stickers** button that launches
   `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI` pre-pointed at
   `content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Stickers`,
   then bulk-import every image the user grants. If that folder is absent the
   picker simply opens at the default location — handle it gracefully, never crash.
   Persist the granted permission with `takePersistableUriPermission`.
3. **Multi-select gallery import**, for stickers already saved to the gallery.

Show the honest explanation inline in the import sheet, one short paragraph — the
grader will ask, and "we cannot read another app's private storage; here are the
three supported routes" is a stronger answer than a broken feature.

## B.5 Retrieval order at scan time

`StickerRepository.search(result: EmotionResult)`:

1. User stickers for `result.emotion` — favourites first.
2. User stickers for `result.distribution[1].first` (the runner-up mood), tagged
   as related, only if step 1 returned fewer than 12.
3. Online GIPHY/Tenor for `emotion.query`, only if the network is up **and**
   `Prefs.onlineStickers` is on.

New `Prefs.onlineStickers` (default true) and `Prefs.preferOwnStickers` (default
true). Offline with a populated library must produce a full grid.

## B.6 Files

| File | Change |
|---|---|
| `stickers/StickerLibrary.kt` | **new** — storage, index, import, mutation. |
| `stickers/StickerItem.kt` | add `id`, `mood`, `favorite`; keep `previewUrl`/`sendUrl`/`mime`/`isLocal`. |
| `stickers/MoodBucketAdapter.kt` | **new** — level-1 mood cards. |
| `stickers/StickerRepository.kt` | new ordering; accept `EmotionResult`. |
| `ui/StickerManagerActivity.kt` | rewritten as the mood grid. |
| `ui/MoodStickersActivity.kt` | **new**. |
| `ui/ReceiveStickerActivity.kt` | **new** — share target. |
| `AndroidManifest.xml` | register the two new activities + share intent filters. |
| layouts | `activity_mood_stickers.xml`, `item_mood_bucket.xml`, `dialog_mood_picker.xml`, rework `activity_sticker_manager.xml`. |

---

# Constraints for both workstreams

- Kotlin, minSdk 24, targetSdk 34, view binding, no new heavyweight dependencies.
  Glide is already present and handles webp/gif.
- Everything on-device. No new network calls beyond the existing sticker search.
- The keyboard is an `InputMethodService`: it cannot request permissions or show
  dialogs. Anything needing either goes through an Activity.
- No blocking work on the main thread; camera analysis stays on its own executor.
- The app must remain fully functional **uncalibrated** and **offline**.
- Update `README.md` and `CAVEATS.md` to match reality when done.
