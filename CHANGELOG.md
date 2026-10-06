# Changelog

## Unreleased

### Added
- Pause and resume local or remotely controlled video recordings, excluding paused time from the saved recording duration.
- Direct photo/video sharing between SV devices on the same Wi-Fi, with per-transfer Accept/Decline, optional streaming-PIN pairing and delivery to Imports.
- Multi-clip video editor from a new Home button: join vault videos, trim, split, mute and reorder clips on a scrolling, zoomable timeline; rotate, zoom and colour-adjust each clip; and add up to 10 stickers (emoji, a bundled pack with animated GIFs, or vault photos and GIFs) that can be resized, rotated, duplicated and moved over time with keyframes; and blur or cover faces, by hand or by scanning a clip for faces that are then tracked. Saves one new video in the first clip's shape; the per-video Edit button is unchanged.

### Fixed
- Preserve required file-sharing PIN mode when its saved PIN is unavailable.
- Cancel connection and preview work safely while preserving already completed transfers.
- Read incoming/backup preview media through bounded encrypted buffers, avoiding full plaintext video staging and whole-photo memory copies.

### Validation
- Combined unit suite passes 148 of 149 tests; the remaining failure is the existing CrossCompatibilityTest. Generated-file transfers pass CMF to Pixel without PIN and Pixel to CMF with PIN; decline and cancellation from either side pass.
- Video editor confirmed by the owner on the CMF Phone 1: joining clips of different shapes, timeline swiping and trimming, emoji and transparent PNG stickers, manual blur regions, and face tracking on 1080p video. Not confirmed yet: animated GIF stickers, sticker handles, face stickers, head tilt, the faster scan, re-scan skipping and 4K scans.
- Recording pause/resume was confirmed by the owner. File-sharing engine checks, corrections and remaining manual/device limits are recorded in `tasks/pr2-pr3-validation.md`.

## 1.1.1-optimized-20261004 - 2026-10-04

### Added
- Discover and view a live camera from another SV device over the same Wi-Fi, with optional Bluetooth discovery assistance.
- Four-digit streaming PIN settings, including an option to disable the streaming PIN.
- Remote photo capture, video recording, camera/lens selection, supported video quality, flash, microphone selection, pinch zoom and tap-to-focus.
- Gallery zoom for photos and videos, plus a Favorites collection.
- Right-side zoom sliders on the camera and stream viewer.

### Fixed
- Preserve gallery previews during thumbnail upgrades and distinguish loading from an empty folder.
- Generate sharper, correctly proportioned previews for photos and videos.
- Move Home settings to the previous lock-icon position.

### Build and validation
- APK: `SecretVault-v1.1.1-Optimized-2026-10-04.apk`, the exact installed Pixel build, renamed for distribution.
- Internal Android version: `1.1.1-performance`, version code 3. Non-debuggable with R8 enabled, signed with the existing personal-use debug certificate; Android 8.0 or newer.
- 120 debug unit tests passed. Earlier generated-media gallery checks and two-phone streaming/remote-control checks are documented under `tasks/`.
- Owner reports smoother Pixel gallery scrolling. Optimized frame timings and a complete R8 device regression suite remain unverified; testing stopped at the owner's request. Existing lint findings remain.
- SHA-256: `E0789722C31E634A65C69C00D4C33F6F7B2C24C95A6AB2C7FD83D71B23BF12E7`.

## 1.1.1 - 2026-10-01

### Added
- Video editing from the viewer: trim a range or remove up to ten sections, saving a new encrypted copy in the original album.
- Edited copies use `_edited`, `_edited_1`, and subsequent suffixes; the original remains unchanged.

### Fixed
- Unreadable editor previews show an error with Retry and Back instead of loading indefinitely.
- Disable Media3 1.5.1's incompatible fast-trim metadata probe for encrypted input; exports use the decrypting reader.
- Preserve the existing application ID so the debug APK updates the current vault installation.

### Build and validation
- APK: `SecretVault-v1.1.1-debug.apk` (debug signed; Android 8.0 or newer).
- 85 unit tests passed. CMF Phone 1 device tests passed for trim, one/multiple removed sections, cancellation, output playback decoding, original preservation, incremented output names, sharing-provider reads, and preview error/Retry/Back recovery.
- Saves use a bounded wake lock; Android may still stop a background process during long edits. Edited plaintext output is temporary in the private cache and is deleted after saving or on the next app start.

## 1.1.0 - 2026-10-01

### Added
- Settings for changing the vault PIN and enabling or disabling fingerprint unlock.
- A home-screen “Keep vault open” option that reopens directly to the camera, survives screen lock, and disables itself after ten minutes outside SecretVault. Back from the camera returns home; manual locking disables the option immediately.
- Portrait or landscape recording orientation, and photo/video viewing rotation with rotated video controls.
- Rear lens selection where the device exposes compatible cameras or zoom ranges.

### Changed
- Default video quality is 1080p at 60 FPS on CMF Phone 1 and 4K at 60 FPS on Pixel 5, with fallback to a supported mode for the selected camera.
- Restore progress shows elapsed time.

### Fixed
- Quick Share keeps exported photos available for its temporary sharing window instead of deleting them when the vault locks in the background.
- Video playback stability, camera quality labels, backup picker navigation, and returning from the media viewer to its folder.
- EXIF orientation for restored/imported photos and thumbnails, including refreshing duplicate photo thumbnails during restore.

### Security
- Use a random, Keystore-protected SQLCipher database key with migration for existing databases.
- Fail closed when the vault Keystore is unavailable.
- Authenticate encrypted media completion and close media sources after failed opens.

### Build and validation
- APK: `SecretVault-v1.1.0-debug.apk` (debug signed; Android 8.0 or newer).
- 73 unit tests passed; the debug APK builds successfully.
- Quick Share and playback were confirmed on-device during development. The latest keep-open camera/back flow still needs manual confirmation; Pixel 5 ultrawide field of view remains unverified.
