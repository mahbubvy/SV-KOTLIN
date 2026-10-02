# Camera stream validation

Debug prototype built on `codex/camera-stream-test`. Live-camera capture is pending:
the user requested finishing the build before positioning the phones.

## Revised implementation: R1 diagnostics, 2026-10-02

- User authorized starting the revised plan. Wireless ADB connected to CMF A015
  on Android 36 and Pixel 5 on Android 34 after restarting the restricted ADB
  server outside the runner. No application data was cleared.
- New capture-free StreamCameraDeviceTest passed on CMF (0.292 seconds) and
  Pixel (0.230 seconds). Rear camera 0 advertises 1280 × 720 for preview and
  encoder, an exposure range including 30 FPS and a sufficient minimum frame
  duration. H.264 Surface-input configure/start/stop passed with Baseline:
  CMF c2.mtk.avc.encoder; Pixel c2.qti.avc.encoder.
- Test APK installed as an update on both; no camera or microphone opened.
  This is capability/configuration evidence, not a live capture/session result.
- Existing 90-test unit baseline and instrumentation APK build pass. The
  reported live failure is still undiagnosed. CMF was locked; camera positioning
  confirmation/local unlock remains needed before live reproduction.

## Protocol and secure connection

- Unit suite and debug APK build passed after protocol implementation.
- Five protocol tests cover pairing input, fragmented reads, round trips,
  oversized/truncated payloads, invalid fields, and authentication.
- TLS device checks passed on CMF (Android 36, 0.398 seconds) and Pixel 5
  (Android 34, 0.498 seconds): correct pairing, wrong fingerprint/token rejection,
  second-viewer rejection, listener recovery and blocked-accept cancellation.
- Actual Wi-Fi round trip passed: CMF sender and Pixel viewer, no video relay
  or adb port forwarding. Session invitation transferred privately for the test
  using ADB, absent from command arguments and logs, then deleted on both phones.
- TLS signing initially failed because the temporary EC key did not authorize
  signing a precomputed digest. Added DIGEST_NONE alongside SHA-256 as required
  by [Android's TLS Keystore documentation](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...)).
  Normal TLS 1.2/1.3 negotiation remains enabled; certificate validation is intact.

## Generated media and build evidence

- Generated 640 × 360 H.264 fixture decoded without opening a camera on CMF
  (0.267 seconds) and Pixel (0.333 seconds). Frame-render callbacks were observed.
- CMF sent that fixture over actual Wi-Fi/TLS to Pixel through `StreamSession`:
  both instrumentation roles passed (sender 18.198 seconds including waiting for
  ADB setup; viewer 1.296 seconds). Viewer reached the live state, reported codec
  dimensions/startup, and released the connection/decoder after explicit stop.
  These test durations are not latency measurements.
- An earlier fixture run closed the viewer before the sender completed writing.
  Corrected the test to wait for the short fixture transmission before stopping.
- H.264 presentation timestamps can be reordered by B-frames. Decoder preserves
  sequence/decode order and accepts nonnegative reordered presentation timestamps.
- Pending access units are bounded to six entries and 2 MiB. Queue count/memory
  limit and capacity recovery checks pass; frames are not silently dropped.
- Full unit suite: 90 tests, zero failures/errors. Debug app and instrumentation
  APK builds pass. Prototype installed as an update on both phones; cameras
  were not opened. Vault data was not cleared.
- Lint fails with 41 errors and 113 warnings. All error locations are in unchanged
  camera, crypto or player source (API-level/Media3 opt-in issues). Streaming adds
  warnings for a scoped custom trust manager and Surface ownership. Reviewed:
  trust verifies certificate validity, exact SHA-256 pin and self-signature;
  Surface ownership ends in the TextureView destruction callback. No lint error
  is located in the new streaming source or changed UI/navigation/build files.

## Review and deferred device checks

- Session cleanup runs off the UI thread, closes listener/socket to unblock IO,
  and disables restart until encoder/decoder cleanup finishes. Network callback
  registration is synchronized with termination; late updates cannot restore a
  stopped session's live state or invitation.
- Native camera encoding uses one Camera2 session with preview/encoder surfaces,
  capability checks, H.264 Baseline and a requested 1280 × 720 at 30 FPS.
  Initial live output was subsequently verified below; sustained performance is pending.
- Settings entry and destination require debug/unlocked state. Start is explicit;
  Back, background, Surface destruction and network-loss handlers end a stream.
  Ordinary camera/recording code was not changed.
- Static UI review: role controls expose selection, Back is labeled, status uses
  a polite live region, controls have 48 dp targets, content scrolls with keyboard
  or limited height, and image fitting runs after configuration and size changes.
  Sensitive copied pairing details are cleared when the session/screen ends.
- UI click-through, screen rotation/aspect ratio, live camera, background/lock/
  network-loss behavior, sustained performance, visual delay and camera/vault
  regression checks remain pending. This is a test build, with no performance
  target or completed on-device accessibility gate claimed.

APK: `app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk`.
SHA-256: `0FD9E906E2220D8CF1FCF088222784A58AC49BEF95E6D0C34E1048F5F92F3D30`.

When the phones are positioned: unlock SV on both, open Settings → Camera stream
test, select Send camera on CMF and View camera on Pixel. Start CMF, transfer its
fresh connection details privately, and Connect on Pixel. Stop/Disconnect ends
that session; a new sender session requires new details.

## Actual CMF → Pixel camera UI check — 2026-10-02

- User authorized opening both vaults and testing the live camera. No vault
  media was opened, no recording was saved, and no vault PIN was stored.
- Installed application version was unchanged: 1.1.1-camera-stream-test. Only
  the instrumentation APK changed. No application behavior was patched.
- CMF sender UI started normally, published AVC configuration and encoded
  frames. The initial roughly 13 FPS display was sampled during startup, not a
  steady-state result. The reported failure was not reproduced.
- StreamLiveUiDeviceTest exercised the real CameraStreamScreen on each phone
  with a consuming TextureView. It transferred the fresh invitation through
  app-private cache/stdin without printing credentials, then connected Pixel.
- Both roles passed 15 consecutive one-second live/FPS checks at 1280 × 720.
  Minimum sampled UI counters: CMF encoded 29 FPS, Pixel rendered 32 FPS.
  These counters can include burst delivery; they do not establish exact
  sensor FPS or visual latency. No image-quality/rotation judgment is claimed.
- Stop/Disconnect completed on both. After instrumentation, opened each vault
  and its ordinary camera; camera service showed an active SV camera-0 client
  on each phone.
- The test intentionally mounts only the diagnostic stream screen in a debug
  instrumentation Activity; it does not test vault authentication/navigation.
- Original failure stage remains unknown. Background, Wi-Fi loss, rotation,
  long runs and battery/performance checks remain pending.
- A repeat passed the Pixel's 15-second playback check but revealed a test
  shutdown race: viewer disconnected before the sender's last assertion.
  Changed sender sampling to 10 seconds followed by waiting for peer-disconnect
  cleanup; viewer still samples 15 seconds and initiates Disconnect. This
  corrected test awaits another unlocked-device run. A subsequent attempt was
  blocked by the phones' lock screens, before camera startup.
- After another local unlock, a repeat passed the keyguard precondition but
  could not locate Pixel's View camera control. No connection was attempted by
  that viewer. This UI-test startup failure is unresolved; it is not evidence
  that the camera/transport failed. Both devices relocked after failed-run
  cleanup. Added nonsecret foreground-package information to future timeout
  errors. The modified UI check is not yet marked verified or committed.

## Device-neutral roles and repeatable UI check — 2026-10-02

- Stream/transport source contains no CMF/Pixel model checks or fixed peer IPs.
  Both devices expose Send camera and View camera. Changed the debug sender
  prompt to refer to this camera and another SV device. The user requires
  device-neutral roles; updated the spec and active plan accordingly.
- Built and installed the debug update and instrumentation on both phones.
  The media, encryption and ordinary-camera implementation were unchanged.
- Resolved UI-test failures using native visible-window retrieval, fresh
  accessibility nodes on SDK 34+, enabled button actions and scroll-to-control.
  Also wait for the FPS counter's initial update before sampling and let the
  viewer initiate shutdown after a longer check than the sender's sampling.
  No app workaround, test dependency or security bypass was added.
  References: [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation)
  and [interactive windows](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS).
- Final checks passed on both roles in both directions:

| Sender → viewer | Sender's minimum sampled encoded FPS (10s) | Viewer's minimum sampled rendered FPS (15s) | Disconnect/peer cleanup |
| --- | ---: | ---: | --- |
| CMF → Pixel | 29 | 28 | Passed |
| Pixel → CMF | 28 | 28 | Passed |

- These short runs establish a working live baseline, not five-minute stability,
  exact sensor FPS, measured visual latency or support on untested hardware.
  Capability checks determine availability; device names do not determine roles.
- Final APK SHA-256 after the prompt change:
  `9F55BF9306F85D20843F3886EC81FF2DA7CF27D644A80CA6AB3891B7A47C4729`.
- Review: tests exercise actual camera/codec/TLS/TextureView rendering and both
  roles, bound waits and private invitation cleanup, preserve vault data, and
  add no production library. Product copy is concrete and has no device-specific
  role instruction. Layout, controls, contrast and visual styling were unchanged.
  Full camera-stream feature/accessibility/performance gates remain pending.

Repeat after installing the debug and instrumentation APKs, with both phones
unlocked and positioned for the camera test:

```powershell
# Keep the ADB server socket configured for the connected phones.
& .\tasks\test-live-stream.ps1 -Sender <sender-serial> -Viewer <viewer-serial>
```

The runner rejects failed instrumentation results and removes temporary
invitation files. Logs in ignored scratch contain status and counters only.
The runner reopens SV after finishing so an unlocked phone remains awake in the app.

## Surface encoder ownership split — 2026-10-02

- Extracted StreamEncoder from the diagnostic Camera2 encoder. It owns only
  native codec resources and exposes an input Surface; it never requests camera
  permission or opens a camera. The diagnostic CameraStreamEncoder owns its
  Camera2 device/session and feeds that Surface. Configuration, Baseline AVC,
  frame copying, keyframe request, callbacks and transport bounds are preserved.
- Constructor exceptions close codec resources. Callback guards ignore work
  after close. Surface release finishes callback-thread shutdown even if release
  throws. These error paths were reviewed; unsupported-codec failure was not
  forced on the supported test phones.
- StreamCameraDeviceTest#surfaceEncoderStartsAndReleasesWithoutOpeningCamera
  passed on CMF (0.214s) and Pixel (0.090s): native start/keyframe request, valid
  input Surface, release invalidation and harmless repeated close. Durations
  are test runtimes, not streaming latency.
- Repeated live UI tests after extraction passed in both directions, including
  viewer Disconnect and sender peer-disconnect cleanup:

| Sender → viewer | Minimum sampled encoded FPS (10s) | Minimum sampled rendered FPS (15s) |
| --- | ---: | ---: |
| CMF → Pixel | 29 | 28 |
| Pixel → CMF | 28 | 28 |

- All 90 unit tests pass; debug and instrumentation APK builds pass. Updated
  both installed debug apps without clearing vault data or changing recording
  defaults. No production dependency or device-role restriction was added.
- APK SHA-256:
  `64FE2CEE7E0B689432D8FACF69245EDD234AAA14163F33C96748AA0A22EC75BC`.
- This prepares normal-camera integration. The normal CameraX/native preview
  is not connected to the encoder yet, and the requested camera/home buttons,
  discovery, persistent streaming PIN and BLE assistance remain pending.

## Sender preview orientation — 2026-10-02

- User confirmed the sideways portrait image was on the camera phone only.
  Both roles had used the decoder transform: it applied sensor rotation again
  to the Camera2 TextureView, which already receives that rotation from Android.
  Sender now corrects display rotation and fits the sensor-oriented buffer;
  decoder rotation and the transmitted stream configuration remain unchanged.
  Platform behavior: https://developer.android.com/media/camera/camera2/camera-preview#textureview
- StreamPreviewDeviceTest#cameraAndDecoderShowTheSameUprightUnstretchedImage
  passed on CMF and Pixel. It checks corner positions, aspect ratio and centered
  fitting for all four sensor/display rotations, portrait/landscape view sizes
  and both producers. This verifies transform geometry, not real-image appearance.
- Updated debug and instrumentation APKs on both phones; build passed.
  Live UI streaming and disconnect cleanup passed again in both directions:
  CMF → Pixel minimum sampled sender/viewer FPS 28/27;
  Pixel → CMF 28/28 (10s sender and 15s viewer sampling).
  Physical portrait preview appearance still needs user confirmation; no camera
  images or vault media were captured for visual verification.
- Review: both transform call sites pass the current role; stream configuration,
  encoder, decoder, authentication, capture defaults, layout and controls are
  unchanged. No dependency or device-specific role rule was added.
- APK SHA-256:
  `23989B17729897509641D4089AEF4555D2C91C91B856F83153781D519ACC58C3`.

## Shared active camera source — 2026-10-02

- StreamPreviewRenderer uses native EGL/GLES and CameraX's SurfaceProcessor /
  CameraEffect hook. The existing CameraManager remains the camera owner.
  CameraX output transforms are applied to the local preview and stream; the
  stream applies the local view's centered crop and fits that content without
  stretching. No screenshot copying, second camera or dependency was introduced.
- CMF native 1080p60 uses a GPU input in place of its direct preview Surface,
  retaining the vendor preview + MediaRecorder output combination. Native
  source callbacks now reject stale camera generations after Stop/restart.
- Shared-camera tests passed with local CameraX preview STREAMING, live H.264
  rendering and disconnect cleanup:
  Pixel CameraX → CMF and CMF CameraX → Pixel minimum sampled FPS 28/28;
  CMF native 60 FPS → Pixel minimum sampled FPS 28/28 after the rotation fix.
  Sender samples 10 seconds; viewer samples 15 seconds.
- The first Pixel photo-mode source supplied 13 FPS. Requesting Preview's fixed
  30 FPS target during photo-mode streaming brought both sampled counters to 28.
  Normal nonstreamed capture settings remain unchanged.
- A generated four-colour chart caught incorrect pre-rotation in the GPU encoder
  path. Corrected the GL rotation direction. Both phones pass the same generated
  Surface → GPU → H.264 → decoder → rotation-metadata colour-order check.
  Bitmap reads in this test inspect only its generated pixels, never live camera
  frames or private media; no screenshot protection was disabled.
- 90 unit tests and APK builds pass. Lint before the CMF adapter change reported
  the existing 41-error/113-warning baseline, with no renderer or session finding.
  CMF source-loss callbacks/rebinds, real-image lens/crop comparison and ordinary
  recording after Stop remain acceptance work. No production controls are exposed
  yet; PIN pairing/discovery/home entry are still pending.
- APK SHA-256:
  `CAE968B829F3750CC2F7BB9DB9E8B03F8A0D5C7229B3CD895FAD1217B60BE2C3`.
