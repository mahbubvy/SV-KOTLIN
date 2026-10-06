# SecretVault — Current Features

SecretVault (shown as **Weather Today**) is an offline Android photo and video vault. The following list describes features currently implemented in the app.

## Access and privacy

- Weather-themed decoy home screen.
- PIN-protected vault with four-digit PIN entry.
- Optional fingerprint/biometric unlock, with PIN fallback.
- Keep-unlocked mode for multi-step work.
- One-tap camera shortcut from the decoy home while Keep unlocked is enabled.
- App-private storage for captured and imported media.
- Chunked AES-256-GCM encryption for vault-managed media.
- New media uses a random AES-256 file key wrapped by Android Keystore; bulk encryption runs in the app for fast saving and playback. File keys are present in app memory while in use.
- Existing version-1 media remains readable with its original hardware key. It retains the old performance until converted; no automatic migration is performed.
- No account, cloud sync, backend, analytics, advertising, or live weather service.

## Camera and media capture

- Primary camera and optional experimental/native camera.
- Photo capture and video recording.
- Photo flash controls, including Auto where supported.
- Video microphone on/off control.
- Tap-to-focus.
- Volume-button shutter support.
- Camera flip/front and back camera support.
- Video mode selector: 1080p at 30 FPS, 1080p at 60 FPS, or 4K at 30 FPS. Starts at 4K/30; unsupported choices are disabled for the selected lens. Switching to a lens without the selected mode uses its first supported choice and updates the displayed selection.
- Resolution and FPS changes are blocked during recording. CameraX handles the standard modes; CMF Phone 1 1080p/60 uses its device-specific Camera2 high-FPS mode. Actual frame rate can still vary by device.
- The screen stays awake while the app is visible, including camera preview and recording.
- Automatic head/face blur after photo capture using Gaussian blur. Video blur is not enabled.
- Long recordings use the queued/background save flow after capture.
- Fixed local-time filenames:

  ```text
  SV_DDMMYYYY_HHMM.jpg
  SV_DDMMYYYY_HHMM.mp4
  ```

## Albums and gallery

- Camera and Imports system albums.
- Conditional Unsorted area and custom albums.
- Custom album creation, renaming, and deletion.
- Custom album cover selection from the media viewer menu.
- Remove-cover action; removing a cover restores the default album artwork without deleting media.
- Create a new album directly while moving media.
- Sort albums/media by newest, oldest, name A–Z, or name Z–A.
- Full-screen photo viewer.
- Private video player with local playback controls and device media-volume startup.
- Video thumbnails.
- Swipe between media items in the viewer.
- Long-press actions for albums and individual media.
- Multi-select media for move, delete, and share.

## Video editing

- Per-video Edit in the viewer: trim, or remove a section, saved as a new video.
- Home **Video editor**: join vault videos into one new video, saved in the first clip's album as `Edit_<date>`. Originals are never changed.
- Scrolling timeline with a fixed centre playhead: swipe to preview, pinch to zoom, frame thumbnails per clip.
- Per-clip tools: trim by dragging the clip's edges, split at the playhead, mute, move left or right, delete, and turn, zoom and move the clip with two fingers on the video (snaps to quarter turns), or with the Size panel's zoom and rotate sliders and Fit, Fill and Rotate 90°.
- Per-clip colour adjustments: brightness, exposure, contrast, saturation, vibrance, shadows, highlights, warmth, tint and fade, previewed live and copyable to every clip.
- Output takes the first clip's shape; other clips fit inside it with black bars, and the preview shows the same frame.
- Up to 10 stickers: emoji, a bundled pack (including animated GIFs), or vault photos and GIFs. Each has its own position, size and time range, moved and resized on the preview and timed on the timeline. A selected sticker shows resize handles, a rotate button, and Duplicate and Delete. GIFs loop in the preview and the saved video. Keyframes make stickers glide smoothly between positions, sizes and angles over time.
- Blur regions on each clip: pixelate or blur, oval or rectangle, with a strength slider and handles that also squash the shape. They stay on what they cover when the clip is trimmed, split, turned or zoomed, and move with keyframes. Up to 16 regions per clip.
- Faces: scan a clip to find faces with the on-device detector (frames read from the encrypted file, in memory only). Each face becomes a region that follows it, leaning with the head, with as few keyframes as needed; a face missed for a moment is carried across the gap. Untick faces to keep them visible, and cover the rest with a blur or a sticker. Scanning again skips faces that already have a region.
- Editing reads the encrypted files directly; the only plaintext is the export's temporary file in app cache, deleted when the save finishes. Sticker photos are decoded in memory only.
- One editing session, no saved drafts; leaving with changes asks before discarding. Locking the vault mid-edit keeps the edit in memory and unlocking returns to it, paused.

## Import, move, delete, and share

- Import photos and videos into the vault from the Android picker.
- Import destination selection, including create-and-move into a new album.
- Move media between albums without duplicating the encrypted file.
- Delete single or multiple selected items.
- Android share sheet support for single and multiple items, including Quick Share.
- Sharing uses temporary decrypted copies and cleans them up where possible.
- SecretVault-to-SecretVault transfer using an encrypted `.svtransfer` package and one-time QR-derived key.

## Decoy weather screen

- Weather-style visual home screen with fake/static weather data.
- Calendar strip with date entries and fake weather conditions.
- Visibility entry used to reach the vault PIN screen.
- Visual weather effects such as rain/sunny presentation are display-only.

## Settings and diagnostics

- Settings screen for fingerprint preference, PIN change, camera preferences, and experimental-camera access.
- Camera flash, microphone, FPS, and related controls where supported.
- Developer storage test and copy-debug-text tools remain available for debugging builds.

## Current platform scope

- Android is the active target.
- The app currently focuses on photos and videos; general documents/files are not implemented.
- 4K/30 recording is verified on CMF Phone 1. Pixel 5 verification of the selectable modes is pending.
- CMF Phone 1 exposes 1080p/60 through its device-specific Camera2 path; the native implementation still needs an on-device recording check. Pixel 5 verification is pending.
- iOS support is paused.
