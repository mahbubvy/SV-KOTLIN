# Gallery stability and camera zoom slider

Implemented October 3, 2026, on `codex/camera-stream-test`.

The camera and stream viewer now expose a vertical zoom slider on the right.
Both retain pinch zoom and use the existing bounded, conflated zoom queues.
The displayed ratio comes from the camera acknowledgement. Local gestures and
the slider wait for the selected lens's initial zoom to finish applying.

Home Settings occupies the previous rightmost Lock position. Lock remains
available in the former Settings slot. Folder toolbar behavior is retained.

## Gallery causes and changes

Opening an album cleared its list before the database query emitted. The screen
treated that temporary empty list as a confirmed empty album. A separate loading
state now shows `Loading media…` until the first result; a failed query offers
Retry. Sorting keeps already-visible media while the replacement query runs.

Thumbnail upgrades replace an encrypted thumbnail path. Previously that started
a fresh image request with crossfade and no retained placeholder. Media and album
cells now reuse the previous cached preview during the replacement request and
disable thumbnail crossfade. Grid requests specify 512 pixels, and the encrypted
fetcher respects the requested dimensions instead of decoding at display size.
Existing encrypted 512-pixel thumbnail generation and stable item IDs remain.

## Verification

- `testDebugUnitTest`: 120 tests, zero failures. Loading, confirmed empty results,
  sorting, query failure and Retry are covered directly.
- Mutation check: reversing the completed-query loading flag caused all three
  new loading regressions to fail. The original implementation was restored and
  the full unit suite passed.
- App and Android test APKs compile. `git diff --check` passes.
- Lint retains the existing 41 errors and 115 warnings; this is not a clean-lint
  release. The unrelated camera API-level findings were not expanded in scope.

Pixel 5 device checks:

1. An encrypted solid-red preview remains visible during a deliberately delayed
   solid-blue replacement, then switches to blue. Decoded grid bitmap dimensions
   remain at most 768 pixels for the 1200x1800 fixture.
2. Ninety-six generated items in the actual Camera album survive three folder
   reentries without a false empty message, repeated scrolling, live thumbnail
   upgrades and six selection updates without new fetches for unchanged visible
   images. Settings is to the right of Lock and both callbacks fire. The harness
   removes only its generated items afterward.
3. At 4K 60 FPS, the right slider is vertical and within the viewport; setting
   2x, dragging to higher zoom, and resetting to 1x changes CameraX hardware zoom.
   The reported Pixel range is 0.615x to 7x. The first test exposed a startup race:
   camera binding enabled the controls before the initial lens zoom completed.
   Waiting for `appliedCameraId` resolved the failure; the hardware test then passed
   twice without an artificial startup delay.
4. A generated slider fixture accepts the enabled state, then rejects a progress
   action while disabled and leaves its zoom unchanged.

Evidence: `scratch/gallery-slider-pixel-device.log` contains both passing gallery
cases and the initial slider failure. `scratch/camera-slider-readiness.log` contains
the first hardware pass; `scratch/camera-slider-final-device.log` contains two
passing slider cases. `scratch/gallery-loading-mutation.log` records the expected
mutation failure. `scratch/gallery-slider-final-tests.log` records the final unit
suite and test build. Scratch files are local, ignored artifacts.

The extra screenshot attempt did not provide a usable layout image, and a rerun
was blocked by Android relocking. No screenshot is offered as visual evidence.
Screenshot-only code was removed from the slider fixture; its previously passing
functional assertions remain. Gallery pixel sampling uses generated colors only
and restores screenshot protection in a finally block. No camera capture or video
recording was performed in this increment.

## Delivery and limits

Installed on Pixel `192.168.0.171:38193`. The installed APK and local build have
the same SHA256:
`132AF100EE78AF318F373FB73DB1F3B43D13BBFAED92EF6DB2158FC6061A3A6B`.

APK: `app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk`.
Only the Pixel was available for these checks. The viewer slider uses the same
component and existing tested `queueZoom` path; a fresh two-phone slider test,
CMF install, landscape, large fonts, physical keyboard and prolonged memory
pressure were not exercised in this increment. The 96-item check is a stress
regression, not a claim that every possible flicker source has been eliminated.

Review: state transitions distinguish loading, error and confirmed empty results;
image requests remain stable across selection changes and retain their cached
placeholder on upgrades. The shared slider adds no camera backend or protocol.
Zoom remains constrained by camera ranges and operation guards. Thumbnail work
stays off the UI thread and now decodes at the request's bounded dimensions.
No encryption, backup format, vault authentication or database schema changes.
