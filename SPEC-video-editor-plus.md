# Spec: Multi-clip video editor

Status: **Planned.** Work happens on branch `video-editor-plus`. Tick the boxes in [Phases](#phases) as you go, and
add a dated note under [Progress log](#progress-log).

## Goal

A separate video editor screen, like a simplified CapCut, opened from a new **Video editor** button on the home screen.
Videos are added from the vault, arranged on a timeline, cut, split, muted per clip, overlaid with emoji or image
stickers, and exported as one new vault video. Everything stays inside the vault, uses only Media3 (already a
dependency) and never uploads anything.

The existing per-video **Edit** button (trim / remove section, `ui/editor/VideoEditorScreen.kt`) **stays exactly as it
is**.

## Decisions (agreed with the user, 2026-10-05)

| Topic | Decision |
|---|---|
| Entry | **Home → Video editor** button opens an empty editor. **+ Add** picks videos from the vault. The video viewer's Edit button is unchanged. |
| Import | **Vault videos only.** Phone-gallery videos are imported into the vault first, as today. |
| Clip types | **Videos only** in v1. No photos as still clips. |
| Clip tools | Trim (drag the selected clip's edges), **split** at the playhead, **mute** the clip, move left / right, delete. |
| Stickers | **Emoji** (a fixed list in the app) or a **vault photo**. PNG transparency is kept. Up to **10**, each with its own position, size and time range. |
| Output shape | Takes the **first clip's shape**. Other clips fit inside it with black bars. |
| Projects | **No drafts.** One session: edit, then Save. Leaving with changes asks "Discard changes?". |
| Save | Exports **one new video** into the album of the first clip, named `Edit_<date>`. Source clips are never changed. |
| Out of scope for v1 | Drafts, photos, text, transitions, music, speed, filters, volume levels. |

## Screen layout

```
┌─────────────────────────────┐
│  ←  Video editor       Save │
│        [ preview ]          │  stickers drawn on top; drag to move, pinch to resize
│     ▶  0:12 / 0:48          │
├─────────────────────────────┤
│        │ playhead (fixed)   │
│ 🎬 [clip1][clip2 🔇][clip3]  │  video track: scrolls, pinch to zoom, frames inside blocks
│ 😀    [emoji]   [logo]      │  sticker track: drag a bar or its ends
├─────────────────────────────┤
│ + Add  ✂ Split  🔇 Mute  🗑  │  tools for the selected clip or sticker
│ ◀ ▶ Move   😀 Sticker        │
└─────────────────────────────┘
```

## Existing code to reuse

All paths are under `app/src/main/java/com/secretvault/app/`.

| What | Where | Use |
|---|---|---|
| Encrypted playback and export input | `core/player/EncryptedMediaDataSource` | Both the ExoPlayer preview and the Transformer read vault files through it, as `VideoEditManager` does. |
| Export → encrypt → save pipeline | `core/worker/VideoEditManager.kt` | **Shared** by both editors. `start(item, segments)` is the old Edit button and is unchanged. `start(project)` is the multi-clip editor. Both go through `run()` → `export(clips, fitToFirst)`. |
| Timeline frames from an encrypted file | `loadTimelineFrames` in `ui/editor/VideoEditorScreen.kt` | Move it to a shared place and reuse it per clip. |
| Range maths and mute processor (**already on this branch**) | `core/processing/VideoEditPlan.toOutput`, `core/processing/MuteAudioProcessor.kt` and tests | `MuteAudioProcessor(listOf(whole clip))` silences a clip. `toOutput` maps ranges between timelines. |
| Vault media list | `app.mediaRepository.getMedia()` | The source for the Add picker, filtered to videos. |
| Home buttons | `ui/gallery/GalleryScreen.kt` (FAB column, `isAlbumsHome`) and `ui/navigation/VaultNavGraph.kt` | Add the Video editor button and a `Screen.VideoProject` route. |

## Design

### Model (pure Kotlin, unit-tested): `core/processing/VideoProject.kt`

```kotlin
data class Clip(val id: String, val media: MediaItem, val sourceDurationMs: Long,
                val startMs: Long, val endMs: Long, val muted: Boolean)   // startMs/endMs inside the source
data class Sticker(val id: String, val source: StickerSource, val startMs: Long, val endMs: Long, // output timeline
                   val centerX: Float, val centerY: Float, val widthFraction: Float)              // fractions of the frame
data class VideoProject(val clips: List<Clip>, val stickers: List<Sticker>)
```

- Operations return a new project: `add`, `split(at)`, `trim(clip, start, end)`, `move(clip, ±1)`, `delete`,
  `toggleMute`, `addSticker` and so on.
- Edge rules: a clip is at least 0.5 s long; split is ignored within 0.5 s of an edge; stickers are clamped to the
  project length when clips change.
- Helpers: `durationMs`, `clipAt(outputMs)` → (clip, offset), `outputStartOf(clip)`.

### Preview
- One ExoPlayer with one `MediaItem` per clip, each with a `ClippingConfiguration` from `startMs`/`endMs`, read
  through `EncryptedMediaDataSource`.
- Rebuild the playlist when the clips change, keeping the playhead.
- Volume is 0 while a muted clip is playing.
- Stickers are a Compose layer over the displayed video rectangle.

### Export (`MultiClipExportManager`)
- **Sequence:** one Composition with one `EditedMediaItemSequence`, one `EditedMediaItem` per clip, each with its
  clipping.
- **Per-clip effects:**
  - Video: a `Presentation` sized to the first clip's upright width × height, with `LAYOUT_SCALE_TO_FIT`, which adds
    black bars.
  - Audio: `MuteAudioProcessor` covering the whole clip when it is muted.
- **Composition-level:**
  - `experimentalSetForceAudioTrack(true)`, so clips without sound don't break the sequence.
  - An `OverlayEffect` with one `BitmapOverlay` per sticker. Alpha is 0 outside the sticker's time range, and position
    and scale come from the model.
- **Sticker bitmaps:** emoji are drawn with `Canvas`/`Paint`. Vault photos are decrypted in memory, decoded and capped
  at 1024 px, and never written to disk.
- **Risks to check on the phone:**
  - Whether composition-level overlay timestamps are the output timeline. If not, attach overlays per clip with mapped
    ranges.
  - Whether a per-item audio processor is allowed in a sequence on Media3 1.5.1.

## Security rules (same as the existing editor)
- Plaintext video exists only as the Transformer output temp file in `cacheDir/video_edit_*`. It is deleted in
  `finally` and on the next start.
- Sticker photos are decrypted in memory only.
- Don't log names or user content.

## Phases

Each phase must build (`./gradlew assembleDebug`) and keep the unit tests passing (1 known unrelated failure:
`CrossCompatibilityTest`), then be installed as the `.debug` test copy and tried on the phone. Commit after each phase.

### Phase 0: Plan
- [x] Agree this plan with the user. Mute processor and `toOutput` are kept from the earlier attempt; the Mute tab in the old editor was dropped.

### Phase 1: Model and export engine
- [x] `VideoProject` model and operations, with unit tests (split, trim limits, move, delete, mute, duration, `clipAt`).
- [x] `VideoEditManager.start(project)` (reuses the old editor's manager, so no second manager): sequence export with per-clip trim, mute and Presentation, forced audio, then save into the vault.

### Phase 2: Editor screen v1 (first version the user can try)
- [x] Home **Video editor** button and a `Screen.VideoProject` route.
- [x] Screen: preview, play/pause, time, a simple fit-to-width clip track (tap to select), the vault video picker for **+ Add**, Split, Mute, Move, Delete, Save with a progress dialog, and Discard changes.

### Phase 3: Timeline
- [x] A scrolling timeline with a fixed centre playhead, pinch zoom, frame thumbnails per clip, and dragging the selected clip's edges to trim.

### Phase 4: Stickers
- [x] Sticker track (bars, drag, edges), emoji grid and vault-photo picker, placing on the preview (drag and pinch), delete, export overlay.

### Phase 5: Device test and docs
- [ ] On the phone, test:
  - 3 clips of different shapes (portrait + landscape)
  - a clip with no sound
  - split, then mute one half
  - reorder
  - 3 stickers with different times
  - a PNG logo
  - cancelling a save
  - leaving with unsaved changes
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
- 2026-10-05: Plan changed from "add tabs to the old editor" to "separate multi-clip editor". Kept `MuteAudioProcessor` and `VideoEditPlan.toOutput` (with tests); the old editor is unchanged from `main`.
- 2026-10-05: Phase 1 done.
  - `VideoProject` / `Clip` with 5 tests.
  - `VideoEditManager.start(project)`: per-clip trim and mute (`MuteAudioProcessor`), every clip fitted into the first clip's upright frame with `Presentation` `LAYOUT_SCALE_TO_FIT`, forced audio track, saved as `Edit_<date>.mp4`.
  - Not run on a device yet; the Phase 2 screen will exercise it.
- 2026-10-05: Phase 2 done; installed, waiting for the user to try it.
  - `ui/editor/VideoProjectScreen.kt`: playlist preview with per-clip clipping and mute volume, fit-to-width `ClipTrack`, a RangeSlider to trim the selected clip, tools (Add, Split, Mute, Left, Right, Delete), `VaultVideoPicker`, and Discard changes.
  - Home FAB (MovieCreation icon), route `Screen.VideoProject`.
  - The old editor's `SavingDialog` and `formatTime` are now `internal` for reuse.
- 2026-10-05: Phase 3 done; installed, waiting for the user to try it.
  - `Timeline` in `VideoProjectScreen.kt` replaces the fit-to-width strip and the trim slider. The playhead is fixed in the centre and the timeline is drawn from `positionMs`, so playback scrolls it with no scroll state.
  - Swipe scrubs (seeks with `CLOSEST_SYNC` while the finger is down, `EXACT` on release), pinch zooms (10–400 dp per second), tap selects, dragging the selected clip's edge trims and parks that edge under the playhead.
  - Frames: `loadTimelineFrames` (old editor, now `internal` with a `count`) loads 4–30 frames per source video; square tiles pick the nearest one.
  - No fling after a swipe yet.
- 2026-10-05: Phase 4 done; installed, waiting for the user to try it (the export overlay is the part to check).
  - Model: `Sticker` / `StickerSource` (Emoji, Photo) in `VideoProject.kt`; `addSticker`, `updateSticker`, `deleteSticker`; every clip change re-fits stickers into the video (`withClips`). Test `stickersStayInsideTheVideo`.
  - Bitmaps: `core/image/StickerBitmap.kt` (emoji drawn with Paint; photos via the new `decodeEncryptedImage`, in memory, max 1024 px). Shared by preview and export.
  - Preview: the frame takes the first clip's shape (`VideoEditManager.uprightSize`, now public), stickers are Compose `Image`s on it. Tap picks a sticker, drag moves, pinch resizes.
  - Timeline: sticker lanes under the clips (first free lane), tap to select, drag the bar to move, edges to trim.
  - Export: composition-level `OverlayEffect` with one `StickerOverlay` (BitmapOverlay) per sticker; alpha 0 outside its range; time counted from the first frame the overlay sees. Scale = widthFraction × frame width ÷ bitmap width (Media3 starts overlays at their pixel size).
