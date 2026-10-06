# PRs 5, 6 and 7 integration review

Reviewed 2026-10-06. Scope: merge the three feature PRs while preserving the owner's approved video-editor timeline and history.

## Reviewed heads

- #5 sticker pack: `6568e0a4f0a62da133389069c115217ea59597dd`.
- #6 face blur: `5d026dba924c913842011fa0530cc86a966ce7d5`, stacked on #5.
- #7 photo editor: `e9e61e667d9699ff3bd9180032ef89779e99334e`.

## Required findings addressed

- Photo quarter turns after a flip could crop different content than the displayed selection. Swap the flip axes on a turn and invert straighten on a single-axis flip. Android Matrix regressions cover every quarter-turn/flip combination.
- Fixed-ratio crop resizing at an edge could pass an inverted range to `coerceIn`. Use ratio-aware minimum dimensions bounded by available space; a JVM regression exercises the edge.
- Paint followed by blur/mosaic was previewed in a different order than export. Use the complete CPU renderer whenever hiding strokes exist, retaining GPU preview for ordinary paint and colour changes. Generated pixel tests cover both stroke orders.
- A missing face-sticker image was silently omitted from video export. Fail export before saving, preserving the original and draft. Device regression verifies no new vault item or plaintext export temp remains.
- A tiny or briefly overlapping region could cause a rescan to omit an uncovered face. Require every detected box to fit inside the rotated rectangle/oval, with the frame aspect applied; image transparency is not assumed. Regression cases cover size, duration, aspect, shape and rotation.
- Face scans created an unlimited number of preview bitmaps before the 16-region cap. Limit previews to available region slots and report omitted results.
- Face stickers received colour adjustments only during export. Apply the same colour effect to their preview layer.

## Integration and UI

The old inline timeline was removed during conflict resolution; the extracted timeline keeps its ruler, fixed centre playhead, left Add column, scrolling Mute, pinch zoom and sticker lanes. Region tracks now join those lanes, clipped to their owning clip, with correctly positioned keys. All new video edits route through the existing history, including continuous Strength changes.

Shared photo controls retain the video editor's slider semantics and 64 dp tool targets. Sticker duplicate/delete/rotate targets are 48 dp; pack images have accessibility labels. No dependencies or cryptographic formats changed. Photo saving reuses the encrypted media queue and retains the source.

Design direction remains the owner's SV dark/mint palette and media-first editing layout. ENERGY 2 / RHYTHM 2 / MOTION 1. Neutral panels group editing controls; mint marks selections and primary actions. Preview media and author-supplied sticker assets provide the content; no assets were invented for this merge.

## Verification

- 158 JVM tests passed. Logs: `scratch/pr5-pr7-final-build.log`.
- An intentionally inverted face-coverage timing guard caused two regression failures; restored before the final suite. Log: `scratch/pr5-pr7-mutation.log`.
- Optimized performance app and instrumentation APK builds passed, including R8 and vital lint.
- CMF photo transform and stroke-order pixel tests: two passed (`scratch/pr5-pr7-photo-cmf.log`).
- CMF GIF animation/loop/poster and missing face-image export tests: two passed (`scratch/pr5-pr7-mask-cmf.log`).
- CMF generated-media timeline integration: passed (`scratch/pr5-pr7-timeline-cmf.log`), including pack selection, handle duplication, grouped blur Strength undo and existing timeline/history/export-cancellation controls. Initial runs were interrupted by another foreground app and a phone lock; a toolbar-scroll test correction was required. Final result was `OK (1 test)`; elapsed time includes waiting for unlock and is not a performance measurement.

## Delivery

Integration checks PASS: the approved dark/mint hierarchy and fixed-playhead layout are preserved, newly exposed pack controls are labeled, native shared controls retain their focus/slider semantics and touched icon targets are 48 dp. No new colour system, decorative assets, empty links, marketing claims or dependency scaffolding were introduced. Primary/secondary text on SV dark has previously measured contrast 17.91:1/7.31:1; icon ink on white handles remains high contrast. The scoped UI check is the recorded generated-media timeline run. Photo tab click-through is the owner's PR-reported validation, independently supplemented here by transform and pixel regressions; hardware keyboard and extreme font-scale checks were not repeated.

APK: `app/build/outputs/apk/performance/SecretVault-v1.1.1-Photo-Sticker-FaceEditor-2026-10-06.apk`, SHA-256 `C67E0ED02129E2990BE7E9DC8CB39693A93A8F96351B3BCB554E1089EA87F303`. Installed on CMF with `install -r`; vault data was preserved. No release upload was requested.

Pixel hardware is unavailable. Android 12 CPU UI, very large photos, portrait/4K face scanning and animated GIFs in a successfully exported video remain hardware verification limits. Shared camera/photo-save exceptional cleanup is pre-existing and outside this integration change. Same-source GIF stickers still share animation timing in the preview, as documented in PR #5; export instances are independent.
