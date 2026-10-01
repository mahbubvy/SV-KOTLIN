# Changelog

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
