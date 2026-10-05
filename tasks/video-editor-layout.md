# Video editor layout

Approved scope: use SV's existing editing tools and colours, rename Save to Export without changing its vault destination, and add undo/redo. The supplied timeline reference requires a time ruler, a fixed centre playhead, horizontal scrubbing and pinch zoom, a fixed Add column on the left, a Mute control travelling with its selected clip, and sticker lanes below the video.

Design read for final polishing: this is a private media workspace. The video is the focal point; the ruler and selected track provide precise editing context. Keep the existing dark surfaces and native Material controls. Mint identifies Export and the active selection. ENERGY 1 / RHYTHM 1 / MOTION 1. Motion follows scrubbing and playback, with no added entrance animation. The timeline and moving playhead position are the identity motif, grounded in the user's reference.

Reuse `VaultDarkBg`, `VaultSurfaceVariant`, `TextPrimary`, `TextSecondary`, `TextMuted`, and `VaultAccent`. Use 4/8/12/16/24/32/48/64 dp spacing and 12/14/18 sp text. Use 48 dp icon targets and 64 dp tool targets. Keep the preview flexible in portrait; use a vertically scrollable workspace with a bounded preview for short heights and large text. Bound tall sticker stacks independently so they cannot consume the preview.

Undo history holds up to 100 project snapshots in memory, including the restored draft. Each trim, sticker-placement gesture, or slider drag makes one history entry. Scrubbing, playback and timeline zoom do not enter history. New edits clear redo. Source media stays encrypted and is unchanged by history operations.

Validation: model tests, complete unit suite, optimized APK build, and a real-device test using only generated video and emoji stickers. Screenshots are enabled only while that generated project is visible, with the privacy flag restored afterward. Device checks cover stationary Add, travelling Mute, scrubbing, pinch zoom, history, existing tool callbacks, and discard recovery. Actual results are recorded in the delivery gate after testing.

Completed: 143 unit tests passed; optimized build passed; full generated-media checks passed on CMF A015 and Pixel 5. Fixed one real picker accessibility gap found during the Pixel check: video/photo tiles now retain their filename description even before a thumbnail exists. Both phones have the final APK installed. Export retains the existing new-video save destination inside the vault.
