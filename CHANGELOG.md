# Changelog

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
- 85 unit tests passed. CMF Phone 1 device tests passed for trim, one/multiple removed sections, cancellation, output playback decoding, original preservation, and incremented output names.
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
