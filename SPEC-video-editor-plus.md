# Spec: Video editor additions — mute sections, stickers, join clips

Status: **In progress** on branch `video-editor-plus`. Tick the boxes in [Phases](#phases) as you go, and add a
dated note under [Progress log](#progress-log).

## Goal

Extend SV's existing video editor (trim / remove section, PR #1) with three features. Everything stays inside the
vault, uses only Media3 (already a dependency) and never uploads anything.

1. **Mute sections:** silence up to 10 parts of the video while the picture keeps playing.
2. **Stickers:** up to 5 emoji or vault-photo stickers, each with its own position, size and time range.
3. **Join clips:** pick 2 or more vault videos in the gallery and join them into one new video.

## Decisions (agreed with the user, 2026-10-05)

| Topic | Decision |
|---|---|
| One save | Trim *or* remove section (as today), plus mute sections and stickers, all go out in **one export**. The original is kept and the result is a new `_edited` video in the same album. |
| Mute | Up to 10 sections. It mutes only; there are no volume levels. |
| Sticker source | An **emoji**, from a fixed list in the app, or a **photo from the vault**. PNG transparency is kept. |
| Sticker count | Up to **5**. Each has its own position, size and time range (default: the whole video). |
| Join input | **Videos only**, no photos. 2 to 10 clips. |
| Join shape | The output takes the **first clip's shape**. Other clips fit inside it with black bars. |
| Join flow | Gallery → select videos → **Join** → reorder screen → Save. The joined video can then be opened in the editor like any other. |

## How the existing editor works (read first)

All paths are under `app/src/main/java/com/secretvault/app/`.

| What | Where |
|---|---|
| **Editor screen:** ExoPlayer preview, frame strip, range slider, Trim / Remove section tabs | `ui/editor/VideoEditorScreen.kt` |
| **Cut maths** (pure, unit-tested): `VideoSegment`, `trimmed()`, `withSectionsRemoved()`, `editedFileName()` | `core/processing/VideoEditPlan.kt` |
| **Export pipeline:** Media3 `Transformer` reads the **encrypted** file through `EncryptedMediaDataSource`, writes a plaintext MP4 to `cacheDir/video_edit_*`, then `MediaSaveQueue.saveVideo` encrypts it into the vault and deletes the temp file. Leftover temp files are deleted on start. | `core/worker/VideoEditManager.kt` |

## Design

### Timelines
Mute sections and sticker time ranges are picked on the **original** video's timeline, which is what the user sees on the
frame strip. At export they are mapped onto the **output** timeline (after trim/remove) by a pure function
`VideoEditPlan.toOutput(ranges, segments)`. Each kept segment contributes its overlap with a range, shifted by the
output time of all kept segments before it.

### Mute
- **Export:** a composition-level audio processor, `MuteAudioProcessor(rangesUs)`, a `BaseAudioProcessor` in
  `core/processing/`. It counts frames from 0, so it works on the output timeline, and writes zeros for samples inside
  a range. It accepts 16-bit and float PCM; zero is silence for both.
- **Preview:** the player volume is set to 0 while the play position is inside a mute section.

### Stickers
- **Model:** `Sticker(source, centerX, centerY, widthFraction, range)`. Positions are fractions of the video frame
  (0..1), so they don't depend on screen size.
- **Export:**
  - A composition-level `OverlayEffect` with one `BitmapOverlay` per sticker.
  - The overlay settings (position, scale) come from the model.
  - A sticker outside its output time range gets alpha 0.
- **Emoji** are drawn to a bitmap with `Canvas` + `Paint`.
- **Vault photos** are decrypted in memory (`cryptoEngine.decryptStream` into a byte array, then `BitmapFactory`) and
  scaled down to at most 1024 px. They are never written to disk.
- **Preview:** a Compose overlay on top of the player, mapped through the displayed video rectangle. Drag to move,
  pinch to resize.

### Join
- A Media3 `Composition` with **one sequence** holding one `EditedMediaItem` per vault video, each read through
  `EncryptedMediaDataSource`.
- Every item gets a `Presentation` effect sized to the first clip's displayed width × height, using
  `LAYOUT_SCALE_TO_FIT`, which adds black bars.
- Clips with no audio: set `Composition.Builder.experimentalSetForceAudioTrack(true)`, so the output always has a
  (silent where needed) audio track.
- Save through the same `MediaSaveQueue.saveVideo` path. The name is `Joined_<first clip name>` and the album is the
  first clip's album.

## Security rules (unchanged from the existing editor)
- Plaintext video exists only as the Transformer output temp file in `cacheDir/video_edit_*`. It is deleted in
  `finally` and on the next start.
- Sticker photos are decrypted in memory only.
- Don't log file names or user content.

## Phases

Each phase must build (`./gradlew assembleDebug`) and keep the unit tests passing (1 known unrelated failure:
`CrossCompatibilityTest`), then be installed as the `.debug` test copy and tried on the phone. Commit after each phase.

### Phase 0: Guide
- [x] Write this spec and agree the decisions with the user.

### Phase 1: Mute sections
- [ ] `VideoEditPlan.toOutput(ranges, segments)`, with unit tests (trim, remove, ranges spanning a cut, empty).
- [ ] `MuteAudioProcessor`, with a JVM unit test: silence inside the range, untouched outside, works across buffer boundaries.
- [ ] `VideoEditManager.start(item, segments, edits)` applies mutes as composition audio effects.
- [ ] Editor: a **Mute** tab. Sections are added and edited like Remove section and shown in a separate colour. The preview goes silent inside them.
- [ ] Save is allowed when only mutes or stickers changed, with no cut.

### Phase 2: Stickers
- [ ] `Sticker` model, plus emoji and vault-photo bitmap loading (in memory).
- [ ] Composition `OverlayEffect` with time-ranged alpha.
- [ ] Editor: a **Sticker** tab with an emoji grid, Pick from vault, drag and pinch on the preview, a time range slider and delete. Up to 5 stickers.

### Phase 3: Join clips
- [ ] Gallery multi-select: **Join** action, enabled for 2 to 10 videos with no photos.
- [ ] Join screen: list in selection order with move up/down, total duration, and Save.
- [ ] Export: one sequence, a `Presentation` sized to the first clip, forced audio track, saved as `Joined_…`.

### Phase 4: Device test and docs
- [ ] On the phone, test:
  - mute 2 sections
  - mute combined with trim
  - mute combined with remove
  - an emoji sticker for part of the video
  - a PNG logo sticker
  - 3 stickers at once
  - joining portrait + landscape clips
  - joining a clip with no sound
  - cancelling while saving
- [ ] Update `SecretVault-Features.md` and `CHANGELOG.md`.

## Test installs
Install only as the `.debug` test copy. **Never** reinstall or uninstall `com.secretvault.app` on the user's phone,
because that wipes their vault.

```bash
sed -i 's/^\(\s*\)versionNameSuffix = "-camera-stream-test"/&\n\1applicationIdSuffix = ".debug"/' app/build.gradle.kts
./gradlew -q assembleDebug
git checkout -- app/build.gradle.kts
adb install -r app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk
```

## Progress log
- 2026-10-05: Phase 0 done. Branch `video-editor-plus` created from `main` (438fd16).
