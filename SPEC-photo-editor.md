# Spec: Photo editor

Status: **Built, on-device testing pending**. Work happens on branch `photo-editor` (from `main`). Tick the boxes in [Phases](#phases) as you
go, and add a dated note under [Progress log](#progress-log).

## Goal

Edit a vault photo from the viewer: colour adjustments, crop and straighten, filters and drawing, then save a new
encrypted copy. Everything happens on the phone, inside the vault; the photo is decrypted in memory only.

## Decisions (agreed with the user, 2026-10-06)

| Topic | Decision |
|---|---|
| Entry | The photo viewer's **Edit** button (today it shows for videos only) opens a full-screen editor. |
| Tabs | **Adjust**, **Crop**, **Filters**, **Draw** along the bottom; each opens its panel above the tabs. |
| Adjust | The video editor's 10 adjustments (brightness, exposure, contrast, saturation, vibrance, shadows, highlights, warmth, tint, fade), same chips + one slider, plus **Grain**. |
| Crop | Crop box with handles; shapes **Free, Original, 1:1, 4:5, 3:4, 16:9, 9:16**. **Straighten** slider −30°..+30°, zooming in just enough to avoid empty corners. **Rotate 90°**, **Flip horizontal**, **Flip vertical**. |
| Filters | Common presets (Vivid, Warm, Cool, Mono, Noir, Sepia, Vintage, Fade, Dramatic), shown as thumbnails of the photo, each with a **strength slider** (0–100%). Manual adjustments apply on top. |
| Draw | Brush with **thickness**, **softness** (feathered edge) and **colour**; a **Blur brush** mode that blurs what you paint over; **Undo**. |
| Save | A **new copy** named `<name>_edited` (then `_edited_1`, …) in the original's album, like the video editor. The original is never changed. |
| Lock | Like the video editor: a vault lock keeps the edit in memory and unlocking returns to it. Leaving with changes asks "Discard changes?". |
| Out of scope | Text, stickers, face blur, healing/erase, curves, selective (masked) adjustments, editing a saved copy's steps later. |

## Rendering

Edits are kept as data and applied in a fixed order, so every step stays changeable until Save:

1. **Geometry**: rotate 90° steps, flip, straighten, crop.
2. **Filter**, scaled by its strength.
3. **Adjustments** (one colour table from filter + adjustments).
4. **Grain** (noise, seeded so it doesn't flicker or change between preview and save).
5. **Drawing**: strokes recorded in photo coordinates, so crop and rotation changes keep them in place.

- **Preview**: a copy of the photo about 1600 px on its long side (900 px while a slider or handle moves), drawn by
  the same renderer as the save, so it matches exactly on every Android version.
- **Save**: once, at full resolution, on a background thread; JPEG quality 95, encrypted through the existing save
  path. No plaintext file is written.

## Phases

- [x] 1. Editor screen, Edit button for photos, Adjust tab (with Grain), save as a new copy.
- [x] 2. Crop tab: crop box, shapes, straighten, rotate 90°, flips.
- [x] 3. Filters tab: presets, thumbnails, strength.
- [x] 4. Draw tab: brush, softness, colour, blur brush, undo.
- [ ] 5. Lock handling, docs, tests (done); PR (waiting for on-device testing).

## Progress log

- 2026-10-06: Plan agreed.
- 2026-10-06: Phases 1–4 built, plus lock handling, docs and unit tests (`PhotoEditTest`). Rendering ended up on the
  CPU for both preview and save rather than GPU shaders: one renderer keeps the preview exactly like the saved photo
  on every Android version, and drawing in the preview at 900 px while a slider moves keeps it responsive. Photos
  larger than 6000 px on the long side are saved at 6000 px. Not yet tried on a phone.
