# MoodBoard — Emotion-Aware Sticker Keyboard (Android)

A lightweight Android keyboard that lets you type normally **and**, with one tap,
scan your face with the front camera, detect your mood, and instantly show
matching stickers/GIFs you can send into any chat — without leaving the
conversation.

Built to the design in `../docs/` (InputMethodService + CameraX in-place capture
+ NVIDIA NIM emotion API + GIPHY/Tenor stickers + Android Commit Content API).

---

## What's inside

| Piece | File |
|---|---|
| Keyboard service (IME) | `app/.../ime/MoodBoardService.kt` |
| QWERTY view (typing, shift, symbols) | `app/.../ime/QwertyKeyboardView.kt` |
| In-IME front camera (CameraX) | `app/.../camera/KeyboardCameraManager.kt` |
| Emotion detection (NVIDIA NIM) | `app/.../emotion/NvidiaEmotionClassifier.kt` |
| Sticker search (GIPHY / Tenor + your own) | `app/.../stickers/StickerRepository.kt` |
| Send sticker into chat (Commit Content) | `app/.../util/RichContentSender.kt` |
| Setup / enable keyboard / API keys | `app/.../ui/SetupActivity.kt` |
| Import your own stickers | `app/.../ui/StickerManagerActivity.kt` |
| Cloud build → APK | `.github/workflows/build-apk.yml` |

The app bundles **no** machine-learning model, so the APK stays small. Emotion
detection happens in the cloud (NVIDIA's free vision API).

---

## How to get the APK (no Android Studio needed)

The project builds itself on GitHub's servers and hands you a ready `.apk`.

### One-time: put the project on GitHub
1. Create a free account at https://github.com (if you don't have one).
2. Click **New repository** → name it e.g. `moodboard-keyboard` → **Create**.
3. On the new repo page click **uploading an existing file**.
4. Drag the **entire `MoodBoardKeyboard` folder contents** into the upload box
   (keep the folder structure — including the `.github` folder).
   - Tip: if drag-and-drop drops the hidden `.github` folder, use
     **GitHub Desktop** or `git push` instead so the workflow file is included.
5. Click **Commit changes**.

### The build runs automatically
6. Open the **Actions** tab. A run called **Build MoodBoard APK** starts.
7. Wait ~3–5 minutes for the green check.

### Download your APK
8. Go to the **Releases** section (right sidebar) → open the latest
   `MoodBoard build N` → download **`MoodBoard-debug.apk`**.
   - (Or: Actions → the finished run → **Artifacts** → `MoodBoard-APK`.)

### Install on your phone
9. Copy `MoodBoard-debug.apk` to your Android phone.
10. Tap it → allow **Install from unknown sources** when prompted → **Install**.

> Prefer Android Studio? Just open this folder in Android Studio and press
> **Run** / **Build → Build APK(s)**. The `gradle` setup is standard.

---

## First-time setup on the phone

1. Open the **MoodBoard** app.
2. Tap **Enable keyboard** → turn on *MoodBoard Keyboard* in system settings.
3. Tap **Choose keyboard** → pick *MoodBoard Keyboard* as the active one.
4. (Recommended) Paste your free API keys (see below) and tap **Save keys**.

You can switch back to your old keyboard any time from the same picker.

### Free API keys

| Key | Where to get it (free) | Used for |
|---|---|---|
| NVIDIA NIM | https://build.nvidia.com → sign in → any vision model → **Get API Key** | Detecting your emotion |
| GIPHY | https://developers.giphy.com → Create an App → API key | Stickers/GIFs |
| Tenor (alt) | https://developers.google.com/tenor/guides/quickstart | Stickers/GIFs |

- A public GIPHY demo key is built in, so stickers work immediately — but it is
  rate-limited. Add your own GIPHY (or Tenor) key for reliable results.
- Without an NVIDIA key, mood detection falls back to "Neutral" and still shows
  fun stickers — so the keyboard is always usable.

---

## How to use it

1. In any chat, type normally with the QWERTY keys.
2. Tap **🙂 Mood** → the front camera preview opens.
3. Make your expression, tap **📸 Capture**.
4. MoodBoard reads your mood (e.g. *Happy*) and shows matching stickers.
5. Tap a sticker → it's sent straight into the chat.
6. Tap the ↩ icon (top-left) to go back to typing.

### Your own stickers
Setup → **Manage my stickers** → **Add sticker from gallery**. Imported stickers
always appear first in the grid and work even with no internet.

---

## Notes
- `minSdk 24` (Android 7.0) → runs on virtually all phones in use.
- Debug APK is unsigned for the Play Store but installs fine by sideloading.
- See **`CAVEATS.md`** for the full list of known limitations and fixes.
- The empty `themes.xml.tmp` / `mipmap-anydpi-v26` folder (if present) are
  harmless build leftovers and can be deleted.
