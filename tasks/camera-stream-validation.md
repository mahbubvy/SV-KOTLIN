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

## Separate streaming PIN storage — 2026-10-02

- Added StreamPinManager with six-digit ASCII validation and dedicated Keystore
  key alias sv_stream_pin_key, outside the temporary TLS identity prefix.
  AES-GCM uses a fresh native IV and authenticated context for each write.
  The existing KeyStoreManager supplies the AES-256 key without changing vault
  aliases, unlock authentication, media encryption or backup contents.
- Device checks pass on CMF (0.062s) and Pixel (0.229s): reconstruct manager and
  read, different ciphertext/IV for repeated writes, no plaintext preference,
  reject corrupt/malformed storage, persist retry budget/cooldown after manager
  recreation, cooldown expiry and successful-pairing reset.
- Tests use a temporary preference file and generated PIN, remove that file and
  do not set the real sender PIN. PIN UI and session invalidation remain R9.
  Build passes; authentication/PAKE is the next security checkpoint.

## Normal camera, PIN pairing and discoverable viewer — 2026-10-02

The normal camera now has Stream/Stop and separate streaming PIN setup/change.
Home exposes View stream above Import. Either device may send or receive; the
CMF-specific camera adapter remains only an existing recording hardware path.
One active camera owner feeds local preview and a 1280 × 720 Baseline AVC stream
targeting 30 FPS at 3 Mbps. Streaming does not change the saved recording quality.
No audio, simultaneous recording, remote capture or stream saving is provided.

### Authentication and discovery

- Uses Bouncy Castle lightweight J-PAKE 1.86 with NIST-3072/SHA-256 inside native
  TLS, mutual round-three confirmation bound to the actual certificate, role and
  fresh session ID. See [protocol and dependency review](stream-pairing-protocol.md).
  No Android crypto provider is replaced. No PIN/hash/reusable authorization is
  advertised or logged; the saved sender PIN uses its dedicated AES-GCM key.
- Local device PIN/TLS tests passed on CMF (529 ms) and Pixel (553 ms). Unit/device
  negative checks reject wrong PIN, changed certificate/session, replay/reflection,
  malformed/oversized/zero group input and prototype v1 downgrade before media.
  Five charged attempts cause a persisted thirty-second cooldown. Reads/connects
  and total pairing time are bounded; only one viewer is admitted.
- Production camera → discovered device → masked PIN → live view passed in both
  directions, including an incorrect PIN followed by correct PIN, Disconnect,
  sender error cleanup, explicit restart and Stop. NSD discovery measured 280/374 ms;
  PIN-to-first-frame measured 891/900 ms. Selected CMF FHD60 and Pixel UHD60 modes
  remained selected after Stop. These timings include the UI harness, not visual delay.
- BLE-only public metadata discovery and subsequent PIN-authenticated Wi-Fi TLS
  passed in both directions. Initial scans took 1379/1429 ms. A further duplicate-name
  test took 859/1022 ms and verified two same-name NSD senders have distinct labels,
  while NSD/BLE results for one session merge into one entry. Wi-Fi discovery was
  faster in these runs; Bluetooth is optional assistance, not a video transport.
- Wi-Fi pairing worked before Bluetooth permissions were granted. The permission
  denial/revocation paths are reviewed for Wi-Fi fallback; live revocation was not
  exercised. Metadata parsing accepts only bounded local IPv4 endpoints and v2
  fields, then verifies certificate-bound PIN proof before accepting media.

### Sustained streaming, UI and source changes

- CMF native camera → Pixel: sender sampled 300 seconds, minimum 25 encoded FPS;
  viewer sampled 305 seconds, minimum 26 rendered FPS. Continuous live/frame checks
  passed without a stall, reconnect or queue failure. The user confirmed both
  phones' portrait images were upright and matching during this run.
- After Stop, a 2.5-second temporary ordinary CMF clip measured 59.4 FPS from frame
  presentation timestamps at 1920 × 1080. It was deleted without importing to the vault.
- Pixel CameraX → CMF: front, rear alternate lens/zoom, then rear main rebinds
  passed without a second camera or stream reconnect. Sender minimum sampled FPS
  was 22; viewer minimum was 2 during transitions. This verifies resumed delivery,
  not uninterrupted 30 FPS through switching or optical ultrawide correctness.
  The encoder retains fixed dimensions; source transforms and requested keyframes
  update the feed. Real-image front mirroring/lens identity/landscape comparison
  still needs visual confirmation.
- An earlier switching run failed before the viewer reached its first-frame state;
  no source switch was reported. The repeated run after the Pixel was unlocked
  passed. The initial failure's precise cause is unconfirmed; it is not reported
  as a proven application fix or a lens failure.
- PIN setup/confirmation/change, masked input, deliberate start/Stop, background
  teardown and return without automatic broadcast passed on both phones. Tests
  snapshot and restore the real streaming preferences. Changing PIN is available
  after stopping the stream; Save alone does not start a broadcast.
- Home placement, absence in a synthetic folder/selection state, View stream/back,
  and removal of the viewer route on explicit vault lock passed on both phones.
  Initial checks found the Extended FAB's text absent from the accessibility tree;
  adding an explicit View stream description made the action discoverable/clickable.
  The corrected harness establishes authentication before creating the graph and
  checks OS keyguard separately. No private item was selected or changed.
- Stop/unpublish, exit/background cleanup, callback ownership, pending PIN wiping,
  surface release and bounded queue/stall behavior were reviewed. Camera creation
  failures now surface an error instead of escaping the Stream click handler.
  A late Bluetooth permission result cannot start receiver discovery in background.

### Limits of this evidence

No screenshots or camera/vault frames were read, and FLAG_SECURE remains enabled.
Only generated chart pixels were read in the geometry test. APKs were installed
as updates; no uninstall or vault-data clear occurred. Temporary test preferences,
invitation files and generated recording were removed; no stream remains active.

Not yet measured/exercised: twenty external visual-delay samples and a 500 ms
median target, heat observations, internet-disabled Wi-Fi, client isolation,
live Wi-Fi/permission loss, all surface/process-death races, large text/landscape
keyboard walkthrough, and unrelated sharing/backup/playback regression flows.
These remain unchecked acceptance items, not successful tests. The original
user-reported prototype failure was not reproduced under the tested conditions.

### Final build and review

- 93 unit tests passed with zero failures. Debug application and instrumentation
  APK builds passed. Final Home/PIN/background tests passed together on both phones
  (CMF 12.928 seconds / Pixel 14.133 seconds, two tests each).
- Lint still fails on the pre-existing 41-error baseline, with 116 warnings versus
  the earlier 113. No new error was introduced. Viewer Surface allocation has two
  Recycle warnings: ownership is intentionally held in Compose state and released
  on TextureView destruction, replacement and disposal. Those callbacks were
  reviewed rather than suppressing lint globally. Existing CameraManager API-level
  and Media3 opt-in findings remain outside this change. The overall lint task is
  not reported as passing.
- Reviewed correctness, resource/lifecycle ownership, input bounds, PIN/TLS trust
  binding, discovery limits, UI actions/contrast/touch targets and performance.
  Existing native/CameraX camera and encrypted-storage helpers were reused. The
  J-PAKE library is the only added dependency; no custom PAKE primitives or relay.
- Final debug APK: SecretVault-v1.1.1-camera-stream-test-debug.apk, 79,494,749 bytes.
  SHA-256: `B41CB4C3FAFC380775E1CDA31C9C9D5CE60C638EEB97EFF5749463C2F908B2A9`.
  Installed as an update on CMF and Pixel. No GitHub push/release was performed.

## Viewer portrait regression and four-digit PIN — 2026-10-02

- The user reported the production viewer remained sideways for an upright
  sender on both phones. A regression check of the actual viewer TextureView
  reproduced an identity matrix while Live camera was shown. The generated-chart
  test above manually rotates its captured surface bitmap; it did not establish
  the production viewer's on-screen rotation. Earlier visual/geometry evidence
  therefore was insufficient for this startup layout race.
- Stream configuration can arrive before AndroidView has measured the decoder
  TextureView. The old update skipped a zero-size view, and its initial surface
  callback had no configuration. Shared fitPreview now defers to doOnLayout,
  retaining resize fitting and the same rotation/aspect math. Both production and
  diagnostic viewers use this helper. The actual-view quarter-turn assertion
  fails before the fix and passes after it; no camera pixels are read.
  [Android's TextureView contract](https://developer.android.com/reference/android/view/TextureView#setTransform(android.graphics.Matrix))
  distinguishes transforming content from changing the view's layout size.
- PIN policy is now four ASCII digits throughout setup, encrypted storage and
  J-PAKE validation. Ciphertext size is 32 bytes including IV and authentication
  tag. An authenticated old six-digit record returns unconfigured, prompting
  explicit new setup; the old PIN is never truncated or used for authentication.
  The vault unlock PIN, media keys, retry budget and cooldown are unchanged.
- Four-digit persistence/fresh-IV/corruption/retry-budget and legacy replacement
  checks pass on CMF and Pixel. Four-digit masked setup/change and background
  teardown checks also pass on both (6.639 / 8.719 seconds for two tests each).
- Correct four-digit PIN, wrong PIN, changed session and v1 downgrade native TLS
  tests pass on both phones (406 ms CMF / 488 ms Pixel for matching pairing).
  All 93 unit tests and both APK builds pass. Lint remains at 41 existing errors /
  116 warnings; it has not been suppressed or reported as passing.
- Final APK SHA-256:
  `60B698A3445E6EF7465C03E5AB2634542AD84F5D9D42115EBCEBF9B9B6C45E91`.
  Both phones received the update without clearing vault data.
- Final production-flow regression passes in both directions, inspecting the
  actual viewer's portrait quarter-turn at first live frame, four-digit masked
  PIN/wrong-PIN retry, fifteen seconds of live view, Disconnect and explicit
  restart/Stop. CMF → Pixel PIN-to-first-frame 846 ms; Pixel → CMF 1050 ms.
  Installed APK hashes on both phones match the final artifact. No private frames
  were inspected, no test PIN was left configured and no broadcast remains active.

## Remote photo capture — 2026-10-03

- Remote photo capture is the first remote-control increment. The paired viewer
  has a shutter, pending/saved/error feedback and a single outstanding request.
  Saved confirmation follows encrypted photo/thumbnail creation and database
  insertion in the camera phone's Camera album. Request IDs, bounded payloads,
  foreground/unlocked guards and timeouts protect the new command path.
- Camera streaming binds CameraX Preview and ImageCapture in both camera modes.
  Stopping restores the selected recording mode, including CMF 1080p60 and Pixel
  4K60 defaults. Simultaneous video recording and remote lens selection remain
  separate increments.
- All 94 unit tests and debug app/instrumentation APK builds pass. Lint retains
  the recorded 41 errors / 116 warnings and is not reported as passing.
- The first CMF-to-Pixel attempt failed before Live, before a shutter was sent.
  Its precise cause was not established. Safe phase/error-class diagnostics were
  added without credentials, filenames or captured image data. Retry passed;
  this evidence does not establish that every connection attempt succeeds.
- CMF-to-Pixel: NSD discovery 534 ms, PIN to first frame 900 ms. Pixel-to-CMF:
  NSD discovery 395 ms, PIN to first frame 1038 ms. Both paired UI tests pass:
  wrong-PIN rejection, actual viewer portrait transform, remote shutter,
  exactly one newly saved photo with stored photo/thumbnail files, viewer saved
  confirmation, fifteen seconds of continued live view, Disconnect, retained
  recording defaults and explicit restart/Stop. Each phone retains one test
  photo in its Camera album; original media is preserved.
- Logs: `scratch/remote-photo-cmf-host.log`,
  `scratch/remote-photo-pixel-viewer.log`, `scratch/remote-photo-pixel-host.log`,
  `scratch/remote-photo-cmf-viewer.log`.
- Final APK: `app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk`.
  SHA-256: `C06ABCDD8F9B047AC86C29F95505AE2C0982CCA2D01637D56F431020F11AD701`.
  Installed on both phones without clearing app data. Hardware save-failure and
  the full capture-timeout paths have not been deliberately induced.

## Optional streaming PIN — 2026-10-03

- Added persistent Settings → Require streaming PIN, enabled by default. Off
  skips setup and viewer PIN entry; TLS encryption remains enabled. Protected
  v2 and open v3 endpoints are distinct, with the sender's selected mode fixed
  when starting. Existing saved PINs and protected retry limits are retained.
- All 95 unit tests and app/instrumentation APK builds pass. Open handshake
  bounds/session checks and public endpoint mode round trips are covered.
  On both phones, isolated native TLS/storage tests pass: open mode without a
  saved PIN, protected listener downgrade rejection, correct protected pairing,
  persisted switch and retained PIN. Settings switch/setup/background checks
  also pass on both devices.
- Two-phone no-PIN production-flow checks pass in both directions. CMF → Pixel:
  discovery 337 ms, connection to first frame 485 ms. Pixel → CMF: discovery
  329 ms, connection to first frame 499 ms. Both verify absence of the PIN dialog,
  the actual portrait transform, fifteen seconds of continued live viewing,
  Disconnect, retained recording defaults and explicit restart/Stop.
- The first live attempt stopped because Android still reported the Pixel's
  lock screen. After local unlock, both directions passed. The test harness now
  allows the sender's timeout/finally cleanup to finish before terminating it;
  no-PIN live tests no longer remove an existing saved PIN. These test-only
  cleanup edits compile but a failed-viewer cleanup path was not re-induced.
- No captures were taken for this increment. Logs: scratch/no-pin-cmf-host.log,
  scratch/no-pin-pixel-viewer.log, scratch/no-pin-pixel-host.log and
  scratch/no-pin-cmf-viewer.log. Lint still reports 41 existing errors and 116
  warnings; it is not passing or suppressed. Bluetooth open-mode discovery was
  not tested separately from the successful NSD flow.
- Installed on CMF and Pixel without clearing app data. APK SHA-256:
  `94D4529785930DD7D6A0B0C1F8314E76C675C152A2A086AA45F0C70D926C8B22`.

## Remote camera/lens controls, 2026-10-03

- Added a secondary camera menu to the viewer. Available facings come from
  CameraX, rear lens choices from the existing camera catalog, and selected IDs
  follow camera readiness plus successful zoom completion. Remote photo and
  selection share one pending command, sequential IDs and a bounded result queue.
  Local source controls disable during pending remote operations. Results and
  selected-state updates share the single video writer.
- All 96 unit tests pass, both APKs build, and diff whitespace checks pass. A
  deliberate mutation weakening the option limit from eight to nine fails the
  new protocol test; restoring the limit makes the full suite pass. Lint remains
  at 41 existing errors and 116 warnings, without suppression.
- The first UI attempt read the camera menu before its popup appeared. The
  test now waits for Front before reading options. The next test overlapped its
  local-flip phase with the viewer's last rear-selection assertion; these phases
  are now separated. These were test synchronization failures; no production
  source change was needed to obtain the subsequent switching passes.
- CMF camera, open mode: NSD discovery 346 ms, first frame 446 ms. Remote front
  and rear 1× switch with acknowledgments and matching camera model; local flips
  update the viewer without reconnecting. CMF advertises one rear choice.
- Pixel camera, protected mode: discovery 406 ms, PIN to first frame 970 ms.
  Wrong PIN rejects, correct PIN connects, remote front and both rear options
  acknowledge and match camera selections. Local flips synchronize. Both
  directions check portrait viewer transform, fifteen seconds of continued live
  preview, Disconnect, original video defaults and explicit restart/Stop.
- Logs: scratch/remote-lens-cmf-host.log, scratch/remote-lens-pixel-viewer.log,
  scratch/remote-lens-pixel-host.log and scratch/remote-lens-cmf-viewer.log.
  No photos/videos were saved. Physical field-of-view, front-camera mirroring,
  landscape and large-font visual checks are not claimed from these metadata
  assertions. The full camera-selection timeout was not deliberately induced.
- Final UI review uses the same eligibility for shutter disabled colors and
  click handling, hides a stale menu while camera readiness is pending, and caps
  the scrollable controls to half the window height to preserve preview space.
  Final app is installed on both phones without clearing app data. SHA-256:
  `727A3E5F178EA710F5FDA1BF5C422370C851E49496E082F2271E814DA237D957`.
  The final runtime repeat initially stopped at Android lock screens after
  installation. After local unlock, both directions pass again, including the
  selector's 48 dp minimum height and viewport bounds. Final CMF-camera startup:
  discovery 411 ms, first frame 689 ms; final Pixel-camera startup: discovery
  283 ms, first frame 1079 ms. Installed hashes match on both phones. No
  broadcasts remain active. The scoped UI gate is remote-camera-controls-ui-gate.md.

## Explicit CMF viewer → Pixel 0.6× check, 2026-10-03

- The user requested a specific Pixel ultrawide check from the CMF viewer. Added
  test-only readback of the bound rear CameraInfo zoom state after remote choices;
  production source and the installed app APK are unchanged.
- CMF menu advertises Rear 0.6×, Rear 1× and Front. Remote 1× → 0.6× → 1×
  selections report actual CameraX applied zoom 1.0 → 0.615 → 1.0. The existing
  one-decimal lens label rounds 0.615 to 0.6×. This verifies applied camera zoom,
  beyond the selected label/model. No camera frame or vault photo was inspected.
- Discovery 270 ms; PIN to first frame 986 ms. The paired test also checks local
  camera synchronization, continued live preview, Disconnect, retained video
  defaults and explicit restart/Stop. No captures are saved.
- Both device tests passed (Pixel host 46.025 s; CMF viewer 40.346 s). Evidence:
  `scratch/pixel-ultrawide-remote-host.log` and
  `scratch/pixel-ultrawide-remote-viewer.log`. The test APK build passed.

## Remote video start/stop, 2026-10-03

- Viewer Record video / Stop recording controls reuse the paired TLS channel.
  Both phones successfully bind Preview + ImageCapture + FHD30 VideoCapture.
  Start follows the CameraX Start event; stop success follows encrypted video,
  thumbnail and Camera-album database insertion. No recording defaults change.
- Protected Pixel camera → CMF viewer normal start/stop passes; front, rear and
  0.6× remote choices and local synchronization still pass. Applied zoom remains
  0.615 at 0.6×. Clip 1920×1080, 4555 ms, rotation metadata 90°, silent.
  Logs: `scratch/remote-video-final-pixel-host.log`,
  `scratch/remote-video-final-cmf-viewer.log`.
- Final Pixel camera disconnect test passes: stopping the viewer while recording
  finalizes/encrypts one clip. 1920×1080, 4651 ms, 140 video samples, average PTS
  rate 29.8846 FPS, rotation 90°, silent. Viewer texture timestamps advance for
  each of four recording-second checks; lens selection is disabled. Logs:
  `scratch/remote-video-disconnect-pixel-host.log`,
  `scratch/remote-video-disconnect-cmf-viewer.log`.
- CMF camera → Pixel viewer in PIN-off mode passes normal stop and remote photo
  regression with the new video output. One photo plus one video saved. Clip
  1920×1080, 4580 ms, 138 video samples, average PTS rate 29.9067 FPS, rotation 90°,
  silent; four recording-second viewer texture timestamp checks pass. Discovery
  334 ms; connection-to-first-frame 557 ms. Logs:
  `scratch/remote-video-final-cmf-host.log`,
  `scratch/remote-video-final-pixel-viewer.log`.
- Camera lifecycle background test passes on CMF: the recording stops and saves
  a 7021 ms 1920×1080 clip with 90° rotation. Viewer terminates, camera resumes
  ordinary capture, defaults are retained, and an explicit restart/Stop succeeds.
  Logs: `scratch/remote-video-background-cmf-host.log`,
  `scratch/remote-video-background-pixel-viewer.log`.
- Final app and instrumentation builds pass; 97 unit tests, zero failures/errors.
  Deliberately accepting duration -1 made the recording-boundary test fail; the
  mutation was restored and the full suite passes. Lint is the unchanged baseline:
  41 errors / 116 warnings; a newly introduced physical-camera API guard finding
  was fixed before final verification. No lint suppression/baseline changes.
- Reviewed startup exceptions, cancellation, save failure, shared command
  sequencing and lifecycle finalization. SOURCE_INACTIVE can contain valid frames
  according to the Android Finalize contract; retain/encrypt those for stream
  recordings. Recorder executor shutdown waits for Finalize. Save toasts now
  follow the actual per-video success/failure callback instead of queue-idle state.
- Latest app APK SHA-256:
  `7FE9A6364D06D9195278CE3E41F29168E24ADDB86EEEDD457CAF9CA2A0A0D69F`.
  Installed on both phones. Six short test videos and one photo remain in their
  Camera albums. Private decrypted test metadata files are deleted in finally.
  All tests end the stream and restore streaming settings; no active broadcast.
- Limits: microphone-on recordings, long-session thermals, landscape/large-font
  physical visual review and unsupported-device fallback are not live-tested.
  Tests inspect only metadata/timestamps from their own captures, never images or
  vault media from before the test. Nominal capture-FPS metadata is absent, so the
  measured FPS above comes from video sample PTS rather than that metadata key.
## Remote highest quality and flash, 2026-10-03

Final APK: `app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk`.
SHA-256: `D1D34C997C26C8C5DFD5FE9C77786E2AF0AE3B3EF66CCAE63DF9952994F473C7`.
Installed with `adb install -r` on CMF A015 and Pixel 5; package hashes match.
App/test APK builds and all 98 unit tests pass. Lint comparison retains the
pre-existing 41 errors / 116 warnings; no suppressions or baseline added.

Streaming quality is separate from ordinary recording preferences. Camera
capabilities rank resolution before FPS; the viewer defaults to the highest
available preset. Unsupported binding removes that candidate, reports the actual
fallback and does not acknowledge the failed quality as applied. The photo-only
fallback restores a 30 FPS preview. No rejected-binding hardware case occurred
on these two devices; that fallback received source/build review.

Two-phone checks use the normal CameraScreen and StreamViewerScreen, NSD
discovery, public controls and an unlocked vault. Cameras were aimed at the
user-authorized test scene. Only newly recorded silent test clips are decrypted
inside app cache for metadata/sample inspection; plaintext is deleted afterward.

| Camera → viewer | Selected saved mode | Dimensions | MP4 sample FPS | Duration |
| --- | --- | --- | --- | --- |
| Pixel → CMF | Highest default 4K60 | 3840×2160 | 58.9081 | 6111 ms |
| CMF → Pixel | Highest default 4K30 | 3840×2160 | 29.9087 | 6018 ms |
| CMF → Pixel | Selected native 1080p60 | 1920×1080 | 59.4412 | 5903 ms |
| CMF → Pixel | Native 1080p60, camera background | 1920×1080 | 59.4421 | 6945 ms |
| Pixel → CMF | Selected 1080p30, protected regression | 1920×1080 | 29.4688 | 6719 ms |
| Pixel → CMF | Selected 1080p60 | 1920×1080 | 59.7404 | 4887 ms |

All listed clips have portrait rotation metadata 90°, no audio, encrypted video
and thumbnail files, and exactly one inserted video record per capture. Reported
FPS comes from sample count and first/last MP4 timestamps, not the UI label.
Transport remains 720p30. TextureView timestamps increase during recording and,
for the stronger later checks, after normal stop. Short checks do not establish
sustained thermal performance, long-session battery behavior or performance on
other phones. Early highest-default checks preceded final UI/fallback refinements;
protected regression and Pixel 1080p60 use the final controls. The last one-line
photo-only fallback reset is source/build/unit verified, then installed.

Remote flash ON/OFF is acknowledged both before and during recording. CameraX
torch state is ON on the camera phone during the initial ON check; CMF native
acknowledgments wait for matching camera capture-result FLASH_MODE readback.
Auto keeps torch off and selects automatic still flash; its automatic firing in
dark scenes has not been measured. Front camera without a flash unit exposes
disabled No flash. 30 FPS retains remote photos; 60 FPS omits ImageCapture and
shows Select 30 FPS to take photos. Quality/lens/photo actions disable during
recording or save; flash remains available during recording.

Protected regression rejects a wrong PIN, reconnects with the fixture PIN,
changes front/back and rear lenses, confirms Pixel 0.6× CameraX zoom 0.615,
synchronizes local camera changes, saves a photo, and records/stops a video.
CMF native background test finalizes/encrypts without resuming broadcast. Ordinary
CMF 1080p60 and Pixel 4K60 defaults are retained after streaming.

Two UI failures were fixed and retested: controls could push Disconnect out of
the footer's visible range, and pre-pairing unavailable controls crowded the
wrong-PIN error. Disconnect now sits below the scrolling controls and quality/
unavailable-video controls appear only when live. Successful retests include
quality-button touch bounds, recovery, front-camera no-flash, lens and record
eligibility. Native stop now releases the old GL preview input before creating
its next session; background cleanup waits for recorder finalization before
surface release. FLAG_SECURE remains; no landscape/large-font visual audit or
keyboard test is claimed. See remote-camera-controls-ui-gate.md.

Protocol review checks bounded states/requests, strict flags, ordered frame and
result messages, protected/open v8/v9 discovery, one pending command and shared
sequential IDs. Mutation allowing flash mode 3 fails
qualityAndFlashMessagesAreBoundedAndPreserveFrames; source is restored and the
full 98-test suite passes. No new dependency or cryptography format change.

Local logs (ignored scratch files):
`remote-quality-pixel-4k60-{host,viewer}.log`,
`remote-quality-cmf-4k30-{host,viewer}.log`,
`remote-quality-cmf-1080p60-{host,viewer}.log`,
`remote-quality-cmf-background-{host,viewer}.log`,
`remote-quality-pixel-regression-{host,viewer}.log`, and
`remote-quality-pixel-1080p60-{host,viewer}.log`.
Additional failed-layout logs are retained locally. Test clips remain in the
Camera albums; no user media was removed. No push or release performed.

## Pixel viewer screenshot for layout review, 2026-10-03

Captured the actual Pixel StreamViewerScreen while CMF streams with 1080p60
selected. Image: `scratch/pixel-stream-viewer-1080p60.png` (1080×2340).
Opt-in `-ViewerScreenshot` instrumentation hides the TextureView image, clears
FLAG_SECURE only for the screenshot, and restores the flag and image in finally.
Restored FLAG_SECURE is asserted on-device. Both sender/viewer checks pass;
the production app APK and its screenshot protection are unchanged. Only the
test APK was rebuilt/installed. Screenshot retrieved from app cache for review.
The current 60 FPS photo-unavailable behavior remains visible in this capture.

## Compact viewer layout, 2026-10-03

User reference implemented with existing dark/mint colors: centered device title,
larger rounded preview, bottom quality/flash/lens row, full-width Record and
Disconnect. Routine confirmations are transient snackbars; errors, recording
time and save progress remain visible. The 30 FPS photo action remains above
the lens menu; 60 FPS photo guidance moves into the quality dropdown.

Final APK SHA256:
`61D098451A0B196F7A2C2ABBCAF508F903094EA61452C624E2616B914DE2D4B4`.
Built and installed on both CMF and Pixel. App/test builds pass, 98 unit tests
pass. Lint retains 41 errors / 116 warnings; no suppression or baseline added.
The width-condition mutation stays green under unit tests, identifying missing
Compose reflow coverage; source was restored before the final build. Portrait
device assertions check menu alignment/bounds and persistent recovery placement.

CMF camera → Pixel viewer passes quality selection to 1080p60, flash ON/OFF,
Record/Stop, disabled lens during recording, moving preview during/after stop,
encrypted clip/thumbnail/database save and Disconnect. New silent clip:
1920×1080, 59.4438 FPS from 288 sample timestamps, duration 4861 ms, rotation 90.
Logs: scratch/viewer-layout-cmf-pixel-{host,viewer}.log (flash), and
scratch/viewer-layout-cmf-pixel-record-{host,viewer}.log (final record check).

Final actual Pixel screenshot: scratch/pixel-stream-viewer-compact.png,
1080×2340. The camera image is hidden only by the opt-in instrumentation;
FLAG_SECURE and image visibility restore in finally and are asserted. An existing
device/system volume overlay appears at the right edge; it is not app UI.
Normal portrait visual review passes; physical large-font/landscape/narrow-width
and keyboard checks remain unperformed. See remote-camera-controls-ui-gate.md.

Reverse-role check passes: Pixel camera → CMF viewer at 1080p30, front/back,
Rear 0.6×/1× selection (camera-side zoom 0.615/1.0), local camera synchronization,
remote photo with encrypted save acknowledgment, continued preview and cleanup.
CMF photo-control screenshot: scratch/cmf-stream-viewer-compact-photo.png.
Logs: scratch/viewer-layout-pixel-cmf-photo-lens-{host,viewer}.log.
Both installed base.apk hashes match the final SHA256 above. Only temporary
debug screenshot cache files were removed; test media remains in the vault.
