# Video editor: what was tested

Date: 2026-10-06. Covers the multi-clip video editor: PR #4 (merged), PR #5 (sticker pack, GIFs, handles) and PR #6
(blur regions, face tracking, face stickers). Device testing was done by the owner on the CMF Phone 1, using the
`com.secretvault.app.debug` test build; the installed vault app was never replaced.

## Unit tests

149 tests; 148 pass. The one failure is the existing `CrossCompatibilityTest`, unrelated to the editor.
Editor checks live in `VideoProjectTest` and `FaceTracksTest`:

- clip operations, trimming, splitting, framing and colour adjustments
- sticker times, keyframes (including the automatic first key), duplicate
- keyframe curves don't overshoot; a sticker held between two keys stays still
- blur regions stay in their clip's source time through trims and splits
- blur shader values (region position, size, shape, time range)
- face tracking across missed samples, still faces, head tilt, faces already covered
- a face sticker covers its region without being squashed, and converts back to a blur

## Confirmed on the phone

| Feature | PR |
|---|---|
| Joining clips of different shapes (a wide video in a 9:16 project) | #4 |
| Timeline: swipe to preview, pinch to zoom, trim by dragging edges | #4 |
| Emoji stickers, in the preview and the saved video | #4 |
| The edit survives a vault lock; playback pauses when hidden | #4 |
| Two-finger turn and zoom of a clip; the Size panel | #4 |
| Colour adjustments in the preview (after the shader fix) and the saved video | #4 |
| Transparent PNG/WebP stickers | #5 |
| Manual blur regions | #6 |
| Face tracking on 1080p video | #6 |

## Not confirmed yet

| Feature | PR | What to check |
|---|---|---|
| Keep-unlocked camera shortcut skipped while an edit is open | #4 | Lock mid-edit with Keep unlocked on; unlocking returns to the editor, not the camera |
| Sticker keyframes (moving stickers) | #4 | Used during development; not separately confirmed |
| Animated GIF stickers | #5 | GIFs loop in the preview and in the saved video |
| Sticker handles, rotate button, Duplicate | #5 | Resize from corners and sides, turn, duplicate, delete |
| Face stickers | #6 | Sticker from the faces dialog and by swapping a region; it follows the face in the saved video |
| Head tilt | #6 | Regions lean with a tilted head |
| Smoother keyframe curves | #6 | Movement glides; nothing swings past a key |
| Re-scan skips covered faces | #6 | Scanning a clip twice offers no duplicates |
| Faster scan (straight-through decode) | #6 | Scan time compared with before; same faces found |
| Portrait clips with the faster scan | #6 | Regions line up on a video shot upright |
| 4K scans | #6 | Time taken and memory on a 4K clip |
| Blur and face stickers on a turned or zoomed clip | #6 | Regions stay on the face in the saved video |

## Not covered at all

- Android 12 and older: the colour and blur previews need Android 13; the saved video should still have them.
- A release (R8) build of the editor.
- The Pixel 5.
