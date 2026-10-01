# Tasks: Local camera streaming test

Status: Build authorized by the user. Implementation in progress. See
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

- [ ] Establish one paired connection and exchange a generated test payload.

Acceptance:
- [ ] A session-scoped identity/token permits the paired peer over actual Wi-Fi.
- [ ] Wrong fingerprint/token, stalled handshake and second active peer are
  rejected; rejection leaves the listener usable.
- [ ] Close interrupts blocked work, deletes only the temporary identity, and
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

- [ ] Focused checks and APK build pass; real peer connection/rejections observed.
- [ ] Review transport evidence and limitations before attaching video.

## Task 3: Play a generated video on the receiving phone

- [ ] Stream the existing nonprivate fixture through TLS into a decoder Surface.

Acceptance:
- [ ] Pixel displays the fixture in correct order with supplied codec initialization.
- [ ] First decode starts from configuration plus a keyframe; access-unit offsets,
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

- [ ] Feed preview and an H.264 encoder from one rear-main-camera session.

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

- [ ] Add a debug-only entry in unlocked Settings with Send/View roles.

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

- [ ] Connect lifecycle cleanup and exercise interrupted/slow sessions.

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
