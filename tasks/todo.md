# Tasks: Discoverable SV camera streaming

Updated: 2026-10-02. R1–R14 are the active revised backlog. The user authorized
implementation; R1 capability and live UI streaming checks passed on both phones. The prior checklist is retained below as
prototype evidence. Unfinished prototype acceptance is carried into these tasks.

See [plan.md](plan.md) and [spec](../SPEC-camera-stream-test.md).
Optional streaming PIN (2026-10-03): Settings now has a persistent Require
streaming PIN switch, on by default. Off allows direct encrypted Wi-Fi viewing
without initial PIN setup; existing PINs are retained for re-enabling. Protected
listeners reject the distinct open handshake. All 95 unit tests and both APK
builds pass. PIN storage/TLS checks and the Settings switch pass on both phones;
no-PIN live viewing passes in both directions. Lint remains at 41 errors and
116 warnings. See camera-stream-validation.md for evidence and limitations.

Remote controls, increment 1 (2026-10-03): remote photo capture is implemented.
The viewer has an accessible shutter and pending/saved/error feedback; captures
are saved in the camera phone's Camera album through the existing encrypted queue.
Commands use the authenticated stream, one pending request, sequential IDs, bounded
payloads and timeouts. Locked/background camera screens refuse capture.
Debug app and instrumentation APKs build; all 94 unit tests pass. Lint remains
at 41 recorded errors and 116 warnings. Both phones received the APK update without
clearing vault data. Paired capture/persistence and continued-preview verification
passed in both directions: one photo in each camera phone's Camera album, saved
confirmation on the viewer, fifteen seconds of continued preview, Disconnect and
explicit restart/Stop, with recording defaults retained. An initial CMF-to-Pixel
attempt failed before Live; retry passed, with no confirmed cause for that initial
connection failure. Remote lens switching and video recording remain
separate future increments.
Latest APK build: Home action sizes now use the same regular FAB for Import and
Camera, with an icon-only Cast action above Import for View stream. The accessibility
label is retained.
Settings now offers persistent four-digit streaming PIN setup and Bluetooth
discovery preferences; the test-stream entry is removed. Camera streaming uses
one accessible start/stop icon above Flash, with connection/error status shown
only when needed. The device UI check now covers Settings PIN persistence and
icon placement. On 2026-10-02, debug app and instrumentation APK builds passed,
with 93 unit tests passing (zero failures/errors/skips). The app update was installed
with `adb install -r` on `192.168.0.173:44225` (model 23073RPBFG) and opened successfully.
CMF and Pixel connection attempts were refused; UI/device verification of the new
controls is pending. APK: `app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk`;
SHA-256: `114B877729573D53F2D1E2E69AE8FA827B16DCC0642FDBCE7F74C7B81CEB7ED9`.
All streaming roles are device-neutral. CMF and Pixel below identify test
hardware or an existing native camera adapter, not sender/viewer restrictions.
Verify both directions; discover actual peers rather than storing their IPs.
Source paths are relative to app/src/main/java/com/secretvault/app/ unless
prefixed by another root. New file/class names are proposals: reuse equivalents.

## Verification commands

Run from K:\gemini with Android Studio's JBR configured as in the spec.

- U: `& .\gradlew.bat :app:testDebugUnitTest` (use a focused --tests filter during a slice).
- B: `& .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`.
- L: `& .\gradlew.bat :app:lintDebug`; compare changed-code findings with the recorded baseline.
- D: install with `adb -s <observed serial> install -r <built APK>`, then
  `adb -s <observed serial> shell am instrument -w -r -e class <implemented class> com.secretvault.app.test/androidx.test.runner.AndroidJUnitRunner`.

Every device verification below uses D when its test class exists, plus the
specified manual check. Observe serials first. No vault-data clear, uninstall,
private-media fixture, PIN in chat or release/push. Physical camera checks wait
until the user positions the phones; build/fixture results are not a substitute.

## R1: Reproduce and fix the current live-camera failure

- [ ] Capture the failing stage on CMF and repair the cause before changing discovery or UI.

Progress: capability probes and the actual CameraStreamScreen flow passed in
both CMF/Pixel directions. Each viewer displayed 720p continuously for 15 seconds;
Disconnect and sender peer-disconnect cleanup passed. Both ordinary camera
screens reopened and acquired camera 0 during the initial check.
The previously reported failure was not reproduced, so no application fix or
confirmed root cause is claimed. A repeatable two-phone UI check now guards the
live baseline after correcting stale accessibility nodes and startup/shutdown
timing in the test. Remaining original-failure investigation needs failing conditions.

Acceptance:
- [ ] Record camera/codec capability results and the exact failure stage; no speculative cause is marked confirmed.
- [x] CMF rear-camera frames encode and render on Pixel through the existing diagnostic flow.
- [x] A focused live UI check guards playback and disconnect; the ordinary camera reopened after Stop. Original reported cause remains unconfirmed.

Verification: U/B; reproduce and rerun on the positioned phones with scoped logcat and a nonprivate scene.
Dependencies: None. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/CameraStreamEncoder.kt; core/stream/StreamSession.kt; androidTest/.../StreamCodecDeviceTest.kt; tasks/camera-stream-validation.md.

## R2: Separate encoding from camera ownership

- [x] Make the encoder consume an input Surface without independently opening a camera.

Progress: StreamEncoder owns only MediaCodec, its callback thread and input
Surface. CameraStreamEncoder is now the diagnostic Camera2 owner feeding that
Surface. Native start/keyframe/release/double-close checks passed on both phones;
both directions of the 720p live UI/disconnect check passed after the split.
Normal-camera shared-source integration remains R3/R4, not part of this proof.

Acceptance:
- [x] Encoder exposes its input Surface/configuration/frames and owns only codec resources.
- [ ] Existing debug capture remains usable for comparison; the production source has one camera owner.
- [x] Stop/release verified on both phones; constructor failure and late-callback cleanup reviewed. Queue limits unchanged.

Verification: U/B; generated Surface-input encoding/decoding check and the R1 live diagnostic.
Dependencies: R1. Estimated scope: Small/medium (at most five listed files).
Files: core/stream/StreamEncoder.kt; core/stream/CameraStreamEncoder.kt; androidTest/.../StreamCameraDeviceTest.kt.

## R3: Share the CameraX preview

- [x] Prove GPU rendering of one CameraX camera texture to the local preview and stream encoder.

Progress: the native EGL SurfaceProcessor is attached to the existing CameraManager
Preview via CameraEffect. Shared-source 720p live encode/decode and local PreviewView
STREAMING checks pass in both phone directions (minimum sampled 28 FPS).
Photo-mode streaming requests 30 FPS; normal unstreamed capture is unchanged.
Source release callbacks and current-input checks protect CameraX ownership.
Lens/rebind geometry and real-image comparison remain R5 device checks.

Acceptance:
- [x] Native CameraEffect/SurfaceProcessor feeds preview and encoder with no second camera acquisition.
- [x] Initial 720p30 output renders on Pixel while the local preview remains usable.
- [ ] SurfaceRequest/SurfaceOutput release and rebind callbacks cannot leak or render a stale source.

Verification: U/B; proposed StreamPreviewDeviceTest with a checkerboard/timer; inspect actual output size/FPS.
Dependencies: R2. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamPreviewRenderer.kt (proposed); core/camera/CameraManager.kt; core/stream/CameraStreamEncoder.kt; ui/camera/components/CameraPreviewView.kt; androidTest/.../StreamPreviewDeviceTest.kt (proposed).

## R4: Share the CMF native preview

- [x] Connect the existing CMF 1080p60 vendor session to the renderer without opening a second camera.

Progress: the existing CMF session now feeds a GPU SurfaceTexture in place of
its direct preview target, retaining the preview + MediaRecorder HAL outputs.
The renderer supplies local preview and 720p30 encoder. CMF → Pixel shared native
stream and disconnect pass (minimum sampled sender/viewer 28/28 FPS). Generated
colour-chart codec/rotation checks pass on both phones. After five minutes of
streaming, ordinary CMF recording measured 59.4 FPS at 1920 × 1080. The temporary
test recording was deleted. Broader lifecycle interruption cases remain R13.

Acceptance:
- [x] CMF native local preview and 720p stream derive from the same sensor frames.
- [x] The verified session/output combination is preserved or any necessary change is explicitly device-tested.
- [x] Stream Stop restores the prior ordinary 1080p60 recording path and releases renderer outputs.

Verification: U/B; CMF native-path instrumentation, Pixel receive check and short nonprivate recording regression.
Dependencies: R3. Estimated scope: Medium (at most five listed files).
Likely files: core/camera/CmfHighFpsCameraView.kt; core/stream/StreamPreviewRenderer.kt; core/camera/CameraManager.kt; androidTest/.../StreamPreviewDeviceTest.kt.

## R5: Preserve the actual camera view through changes

- [ ] Match camera content and handle configuration changes in an ordered, bounded stream.

Progress: portrait images were confirmed upright and matching by the user.
Pixel front/rear/alternate rear lens rebinds kept the same authenticated stream
and local preview alive. Viewer FPS dipped to 2 during transitions; this is not
a continuous-30-FPS claim. Asymmetric real-image lens/mirroring and landscape
appearance still need visual verification. Fixed codec dimensions are retained
through source changes; CameraX output transforms and fresh keyframes update content.

Acceptance:
- [ ] Selected rear/front lens, live zoom, crop, mirroring and orientation match local camera content; controls are not sent.
- [ ] Lens/rebind/orientation changes deliver valid ordered configuration and a fresh keyframe before dependent frames; stale camera callbacks are ignored.
- [ ] Receiver preserves aspect ratio through rotation; errors are visible instead of displaying a different lens or an old frame.

Verification: U/B; transform boundary checks plus two-phone asymmetric test chart, lens/zoom and portrait/landscape rebinds.
Dependencies: R3, R4. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamPreviewRenderer.kt; core/stream/StreamProtocol.kt; core/stream/StreamSession.kt; core/stream/StreamDecoder.kt; androidTest/.../StreamPreviewDeviceTest.kt.

### Checkpoint after R5: Active camera proof

- [ ] Relevant build/checks and actual-device acceptance pass; findings are recorded.
- [ ] Review the evidence before promoting the next slice; unresolved hardware/security results remain pending.

## R6: Store a separate streaming PIN

- [x] Add persistent sender configuration without touching the vault unlock PIN or media key.

Progress: StreamPinManager stores four ASCII digits with Keystore AES-256-GCM,
fresh IVs and dedicated sv_stream_pin_key alias. Persistence, IV uniqueness,
corruption/invalid-input rejection and persisted five-attempt retry cooldown
checks pass on CMF and Pixel. PIN set/change UI and active-session invalidation
are connected in R9 and pass on both phones. Tests restore the original streaming
preferences; they do not leave the fixture PIN configured.

Acceptance:
- [x] Streaming PIN is set/changeable while unlocked and persists across manager recreation; user-requested policy is four numeric digits. Process-restart UI remains untested.
- [x] PIN/secret is encrypted with a dedicated Keystore AES-GCM key and random IV; values are absent from logs/backups/discovery.
- [ ] Malformed/missing/corrupt configuration fails explicitly; changing the PIN ends an active stream and invalidates old pairing.

Verification: U/B; proposed StreamPinDeviceTest covering restart, change, invalid input and storage corruption.
Dependencies: None; camera work does not depend on PIN storage. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamPinManager.kt (proposed); ui/stream/StreamPinDialog.kt (proposed); androidTest/.../StreamPinDeviceTest.kt (proposed).

## R7: Prove secure PIN pairing

- [x] Select a reviewed PAKE implementation and integrate certificate-bound mutual confirmation into native TLS before exposing public discovery.

Acceptance:
- [x] Chosen library/version/license/advisories/size and exact transcript are documented; measured pairing succeeds on both phones with the same PIN.
- [x] Wrong PIN, altered TLS identity/transcript, replay/reflection, malformed input and protocol downgrade fail before media; the PIN/reusable proof is never sent.
- [x] Pairing input/attempts/time are bounded, sender-wide retry cooldown survives restart, only one viewer is authorized, and session restart requires fresh proofs.

Verification: B; expanded StreamTlsDeviceTest plus PAKE negative cases, two-phone handshake timings and explicit security review before R8.
Dependencies: R6. Estimated scope: Medium (at most five listed files).
Likely files: gradle/libs.versions.toml; app/build.gradle.kts; core/stream/StreamPairing.kt (proposed); core/stream/StreamTls.kt; androidTest/.../StreamTlsDeviceTest.kt.

### Checkpoint after R7: Authenticated pairing

- [x] Relevant build/checks and actual-device acceptance pass; findings are recorded.
- [x] Reviewed the certificate-bound transcript, bounded parsing, attempts and cleanup before discovery integration.

## R8: Discover and pair over Wi-Fi

- [x] Build a debug end-to-end device list using NSD and the verified PIN handshake.

Acceptance:
- [x] CMF appears on Pixel over the shared Wi-Fi with conflict-safe names and a fresh session identifier; reverse roles and duplicate names also pass.
- [x] Only bounded public metadata is advertised; endpoints/fingerprints are treated as untrusted and authenticated by R7 before video.
- [ ] Service loss, stale details, second-viewer/busy, client isolation and registration/resolve failure produce usable states; Stop removes the service.

Verification: U/B; proposed StreamDiscoveryDeviceTest over actual Wi-Fi, duplicate names, restart and no internet; protocol validation tests.
Dependencies: R5, R7. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamDiscovery.kt (proposed); core/stream/StreamSession.kt; ui/stream/CameraStreamScreen.kt; test/.../StreamProtocolTest.kt; androidTest/.../StreamDiscoveryDeviceTest.kt (proposed).

## R9: Add Stream inside the normal camera

- [x] Expose the sender through the existing camera UI and active owner.

Acceptance:
- [x] A labeled Stream action configures the PIN if missing, starts intentionally and shows Waiting/Viewer connected/Stop without navigating to a separate camera.
- [x] Record is disabled while streaming with a reason; camera settings/defaults are restored after Stop.
- [ ] Close, failure or PIN change stops broadcast; reopening with keep-vault-open never automatically starts it.

Verification: U/B; actual camera UI walkthrough on CMF and Pixel, permission/error states and ordinary camera reopen.
Dependencies: R8. Estimated scope: Medium (at most five listed files).
Likely files: ui/camera/CameraScreen.kt; ui/camera/components/CameraTopBar.kt; ui/camera/CameraViewModel.kt; core/stream/StreamSession.kt; core/camera/CameraManager.kt.

## R10: Add Home View stream above Import

- [x] Expose the receiver with the placement specified by the user and a device-list/PIN/view flow.

Acceptance:
- [x] Root home shows labeled View stream directly above Import; it is absent in folders/selection mode and inaccessible when the vault is locked.
- [x] Tap opens available cameras; select → masked PIN dialog → encrypted live view. Receiver never opens its camera/microphone.
- [ ] Search/empty/busy/PIN failure/live/disconnected states, cancel/back/Disconnect and rotated fit are reachable with keyboard and large text.

Verification: U/B; click through both roles, locked navigation, folder/selection state, keyboard/landscape and PIN retry using actual phones.
Dependencies: R9. Estimated scope: Medium (at most five listed files).
Likely files: ui/gallery/GalleryScreen.kt; ui/navigation/Screen.kt; ui/navigation/VaultNavGraph.kt; ui/stream/CameraStreamScreen.kt; ui/stream/StreamPinDialog.kt.

### Checkpoint after R10: Complete Wi-Fi UX

- [ ] Relevant build/checks and actual-device acceptance pass; findings are recorded.
- [ ] Review the evidence before promoting the next slice; unresolved hardware/security results remain pending.

## R11: Advertise optional Bluetooth assistance

- [x] Provide public setup metadata using native BLE advertisement and bounded read-only GATT.

Acceptance:
- [ ] Advertising/peripheral capability is checked on both phones; permission denial or unsupported hardware leaves Wi-Fi working.
- [x] Legacy advertising payload fits its limit; longer public metadata is read through bounded GATT and contains no secret/PIN.
- [x] Bluetooth advertising/GATT exist only during an explicit stream, use the same rotating session ID as NSD and stop on exit.

Verification: U/B; proposed StreamBleDeviceTest on CMF and Pixel with Bluetooth off, permissions denied/revoked and oversized metadata.
Dependencies: R10. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamBleDiscovery.kt (proposed); core/stream/StreamSession.kt; ui/camera/CameraScreen.kt; app/src/main/AndroidManifest.xml; androidTest/.../StreamBleDeviceTest.kt (proposed).

## R12: Merge Bluetooth discoveries into the viewer list

- [x] Add a filtered short BLE scan to the existing list and retain the same PIN-authenticated Wi-Fi transport.

Acceptance:
- [x] Wi-Fi/BLE sightings for the same session produce one list entry and share the R7 pairing path.
- [ ] Scan is bounded, stops after selection/exit, and supports Refresh; Bluetooth denial/off falls back to NSD.
- [ ] Different-network/unreachable sightings explain the Wi-Fi requirement; no video or PIN travels in BLE advertisements/GATT.

Verification: U/B; BLE-only discovery, dual sightings, repeated Refresh/rotation, denial and phones on different Wi-Fi; compare discovery timing.
Dependencies: R11. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamBleDiscovery.kt; core/stream/StreamDiscovery.kt; ui/stream/CameraStreamScreen.kt; core/stream/StreamSession.kt; androidTest/.../StreamBleDeviceTest.kt.

### Checkpoint after R12: Bluetooth comparison

- [ ] Relevant build/checks and actual-device acceptance pass; findings are recorded.
- [ ] Review the evidence before promoting the next slice; unresolved hardware/security results remain pending.

## R13: Verify termination and restart

- [ ] Exercise resource ownership across camera, viewer, discovery, pairing and app lifecycle.

Acceptance:
- [ ] Stop/Disconnect/Back/lock/background/Surface loss/Wi-Fi loss/permission revocation close all media/network/NSD/BLE resources despite late callbacks.
- [ ] Queue overflow and stalled peers end visibly; no indefinite delay/memory growth or automatic resume/reconnect occurs.
- [ ] Fresh session IDs/proofs reject stale/replayed authorization; repeated start/stop and a second viewer do not leak resources or bypass cooldown.

Verification: U/B/L; proposed StreamLifecycleDeviceTest, slow-peer/network interruption tests, real lock/background and repeated-cycle checks.
Dependencies: R10, R12. Estimated scope: Medium (at most five listed files).
Likely files: core/stream/StreamSession.kt; core/stream/StreamDiscovery.kt; core/stream/StreamBleDiscovery.kt; ui/stream/CameraStreamScreen.kt; androidTest/.../StreamLifecycleDeviceTest.kt (proposed).

## R14: Measure performance and regressions

- [ ] Produce evidence and a reviewable debug APK after the complete flow works.

Acceptance:
- [ ] Report actual coded size/profile, encoded/received/rendered FPS, discovery/PIN/first-frame timings, 20 visual-delay samples and five-minute stability; no unperformed target is marked passed.
- [ ] After streaming, CMF 1080p60/Pixel supported defaults, photo/video capture, playback, sharing, backup navigation and keep-open behavior pass checks without touching private media.
- [ ] Build/unit/relevant device checks and changed-code lint review pass; scoped accessibility/UI and security review results plus limitations are recorded.

Verification: U/B/L and git diff --check; two-phone Wi-Fi test without internet/ADB relay, external timer measurement and installed APK hash.
Dependencies: R13. Estimated scope: Medium (at most five listed files).
Likely files: tasks/camera-stream-validation.md; tasks/todo.md; SPEC-camera-stream-test.md.

### Checkpoint after R14: Complete stream validation

- [ ] Relevant build/checks and actual-device acceptance pass; findings are recorded.
- [ ] Review the evidence before promoting the next slice; unresolved hardware/security results remain pending.

## Historical prototype checklist

The original Task 1–7 status is retained below. Do not execute its old manual
pairing/Settings UI plan as the active UX; use R1–R14 above. Existing unchecked
live/lifecycle/performance items have not been marked completed.

Status: Debug prototype built and installed as an update on both phones. Live
camera, UI and sustained-performance checks remain pending at the user's request. See
[plan.md](plan.md) and [spec](../SPEC-camera-stream-test.md).

Paths below are relative to `app/src/main/java/com/secretvault/app/` unless
explicitly marked. Filenames are proposed; reuse an existing equivalent if found.

## Task 1: Define a bounded stream format

- [x] Implement invitation parsing and authenticated media framing.

Acceptance:
- [x] Valid invitation/configuration/access-unit messages round-trip.
- [x] Wrong versions, invalid endpoints, oversized/truncated data and invalid
  codec/orientation fields fail before unbounded allocation.
- [x] Token comparisons use fixed-length checks;
  secrets are absent from errors/logs.

Verify: `& .\gradlew.bat :app:testDebugUnitTest --tests '*StreamProtocolTest' :app:assembleDebug`.
Dependencies: None. Scope: Small, 2 files.
Files: `core/stream/StreamProtocol.kt`;
`app/src/test/java/com/secretvault/app/stream/StreamProtocolTest.kt`.

## Task 2: Prove authenticated TLS on Wi-Fi

- [x] Establish one paired connection and exchange a generated test payload.

Acceptance:
- [x] A session-scoped identity/token permits the paired peer over actual Wi-Fi.
- [x] Wrong fingerprint/token and second active peer are
  rejected; rejection leaves the listener usable.
- [x] Close interrupts blocked accept, deletes only the temporary identity, and
  restart invalidates prior details.

Verify: build with `& .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`;
run `StreamTlsDeviceTest` via AndroidJUnitRunner on an observed ADB serial.
Use instrumentation fixture roles on both phones to verify real Wi-Fi reachability;
the local round-trip alone is insufficient.
Dependencies: 1. Scope: Medium, 4 files.
Files: `core/stream/StreamTls.kt`; `core/stream/StreamSession.kt`;
`app/src/main/AndroidManifest.xml`;
`app/src/androidTest/java/com/secretvault/app/stream/StreamTlsDeviceTest.kt`.

### Checkpoint: Secure connection

- [x] Focused checks and APK build pass; real peer connection/rejections observed.
- [x] Transport evidence reviewed: CMF/Pixel TLS checks and Wi-Fi round trip pass.

## Task 3: Play a generated video on the receiving phone

- [x] Stream the existing nonprivate fixture through TLS into a decoder Surface.

Acceptance:
- [ ] Pixel displays the fixture in correct order with supplied codec initialization.
- [x] First decode starts from configuration plus a keyframe; access-unit offsets,
  sizes and presentation timestamps remain correct.
- [ ] Buffering is bounded and decoder/surface/EOF errors close cleanly.

Verify: build instrumentation APK; run `StreamCodecDeviceTest` locally, then its
fixture sender/viewer roles on CMF and Pixel over Wi-Fi. Existing fixture size is
only a transport/decoder check, not the final quality target.
Dependencies: 2. Scope: Medium, 3 source/test files plus an existing reused asset.
Files: `core/stream/StreamDecoder.kt`; `core/stream/StreamSession.kt`;
`app/src/androidTest/java/com/secretvault/app/stream/StreamCodecDeviceTest.kt`.
Reuse: `app/src/androidTest/assets/video-edit-fixture.mp4`.

## Task 4: Substitute the live CMF camera

- [x] Implement preview and an H.264 encoder in one rear-main-camera session.

Acceptance:
- [ ] CMF exposes a local preview and the Pixel receives the actual live camera.
- [ ] Requested 720p30 configuration is capability-checked; actual size/profile/FPS
  is recorded and failures are explicit.
- [ ] Pairing begins with cached configuration and a new keyframe; camera/codec
  buffers are released without changing the ordinary recording implementation.

Verify: `& .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`;
run live-camera instrumentation on CMF with a nonprivate test scene, receive on
Pixel, then verify the normal camera can reopen.
Dependencies: 3. Scope: Medium, 3 files.
Files: `core/stream/CameraStreamEncoder.kt`; `core/stream/StreamSession.kt`;
`app/src/androidTest/java/com/secretvault/app/stream/StreamCodecDeviceTest.kt`.

### Checkpoint: Live media proof

- [ ] Fixture and live camera decode on Pixel; size, FPS and startup evidence exist.
- [ ] Review observed codec support and latency issues before UI integration.

## Task 5: Expose the temporary SV test screen

- [x] Add a debug-only entry in unlocked Settings with Send/View roles.

Acceptance:
- [ ] Entry and destination both enforce debug/unlocked state; no broadcast starts
  automatically and normal camera ownership is released before sending.
- [ ] Sender can Start/Stop; viewer can paste details, Connect/Disconnect and rotate
  a fitted image with preserved aspect ratio.
- [ ] Waiting, connecting, live, disconnected and error states are readable;
  controls use existing styling, accessible labels and at least 44 dp targets.

Verify: build APK; install as an update on both phones; exercise all controls and
rotation. Confirm locked/direct navigation cannot enter the test route.
Dependencies: 4. Scope: Medium, 5 files.
Files: `ui/stream/CameraStreamScreen.kt`; `core/stream/StreamSession.kt`;
`ui/navigation/Screen.kt`; `ui/navigation/VaultNavGraph.kt`;
`ui/settings/SettingsScreen.kt`.

## Task 6: Verify termination and bounded delay behavior

- [x] Connect lifecycle cleanup; fixture-viewer disconnect and bounded queues verified.
- [ ] Exercise live interrupted/slow sessions on both phones.

Acceptance:
- [ ] Back, Stop, Disconnect, background, screen lock, explicit vault lock,
  surface loss and network loss release resources despite late callbacks.
- [ ] Queue overflow or a stalled peer terminates visibly instead of accumulating
  memory/delay or dropping undecodable dependent frames.
- [ ] Keep-vault-open never resumes a broadcast; fresh sessions reject stale details.

Verify: focused cleanup/protocol checks, `& .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`;
run `StreamLifecycleDeviceTest` and manually test both phones, including a slow
receiver and Wi-Fi loss. Compare lint findings with baseline.
Dependencies: 5. Scope: Medium, up to 5 files.
Files: `core/stream/StreamSession.kt`; `core/stream/StreamTls.kt`;
`ui/stream/CameraStreamScreen.kt`;
`app/src/test/java/com/secretvault/app/stream/StreamProtocolTest.kt`;
`app/src/androidTest/java/com/secretvault/app/stream/StreamLifecycleDeviceTest.kt`.

### Checkpoint: Complete test flow

- [ ] Unit checks/build pass, lint assessed, device termination checks pass.
- [ ] Click through all controls and record the scoped antislop/accessibility gate.
- [ ] Review evidence before the sustained performance test.

## Task 7: Measure the stream and check regressions

- [ ] Run the five-minute CMF-to-Pixel test and produce a validation report.

Acceptance:
- [ ] Report actual resolution, encoded/received/rendered FPS, startup, at least
  20 visual delay samples, freezes, queue/stall events and heat observations.
- [ ] Check ordinary recording defaults, playback/gallery navigation and keep-open
  behavior after streaming; preserve vault data throughout.
- [ ] Test APK and report clearly distinguish passed, failed and unperformed checks;
  target changes require review and no unverified claim is marked passed.

Verify: full build/unit suite, relevant instrumentation, `git diff --check`;
actual two-phone Wi-Fi test without a relay, including local Wi-Fi with internet
unavailable. Use an external measurement device for visual delay when available.
Dependencies: 6. Scope: Small, 2 files.
Files: `tasks/camera-stream-validation.md`; `tasks/todo.md`.

### Final checkpoint

- [ ] Required targets and security/lifecycle checks verified, or limitations reviewed.
- [ ] Existing camera/vault regressions assessed and test APK ready for review.

The live-media checkpoint and device UI gate are deferred per the user's
"Build first; I'll position them later" instruction. Implementation checkboxes
above do not mark their unchecked hardware acceptance criteria as passed.
- [ ] Decide separately whether to add a permanent option, pairing QR/discovery,
  remote capture controls, or simultaneous recording.

## Device-test command template

Replace placeholders with observed APK paths, device serials, and test class.
Run in PowerShell from `K:\gemini` after the build:

```powershell
$adbPath = 'C:\Users\MAHBUB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$deviceId = '<observed serial>'
$appApk = '<absolute built app APK path>'
$testApk = '<absolute built instrumentation APK path>'
$testClass = 'com.secretvault.app.stream.<test class>'
& $adbPath -s $deviceId install -r $appApk
& $adbPath -s $deviceId install -r $testApk
& $adbPath -s $deviceId shell am instrument -w -r -e class $testClass com.secretvault.app.test/androidx.test.runner.AndroidJUnitRunner
```

If a device test needs paired sender/viewer roles, document its concrete role
arguments and session-detail transfer commands when implemented. Never place
tokens in committed logs, report content, or process command-line arguments.
