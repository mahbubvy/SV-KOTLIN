# Spec: Discoverable SV camera streaming

Status: Normal-camera streaming, persistent encrypted PIN, Home viewer, native
Wi-Fi discovery and optional BLE assistance are implemented in the debug build.
Both role directions pass pairing/live-view checks. A five-minute CMF → Pixel
PIN-authenticated stream passed; the user confirmed upright, matching previews.
Remaining acceptance and unperformed measurements are tracked in R1–R14 and
tasks/camera-stream-validation.md; implementation does not mark them passed.

## Objective

Send the active SV camera view from one supported SV device to another over shared
Wi-Fi, with native discovery and separate PIN pairing. Stream starts inside the
normal camera; View stream sits on vault home directly above Import. Optional
BLE helps find nearby senders and obtain setup metadata; video remains on Wi-Fi.

Either device can send or view. Roles, discovery, pairing and streaming must not
depend on phone model. Check native camera/codec capabilities at runtime and
report unsupported configurations. CMF and Pixel are test devices, not fixed
product roles; verify both directions.

Assumptions for review:
- One sender and one viewer, both running a test build of SV.
- Live video only. No microphone, media saving, remote shutter, or simultaneous recording.
- Both apps stay in the foreground during the test.
- The requested camera Stream and home View stream entries replace manual
  connection-string entry in the user-facing flow. The existing debug route may
  remain diagnostic while implementation is verified.
- QR pairing, hotspot/Wi-Fi Direct and cloud services remain out of scope.
- Proposed defaults: six-digit sender streaming PIN, five failed attempts then
  a 30-second sender-wide cooldown. PIN length/retry policy need review.

## Scope and behavior

1. The unlocked sender taps Stream inside the normal camera. It configures a
   separate persistent streaming PIN if missing, advertises its availability,
   and shows waiting/connected state plus Stop.
2. The unlocked viewer taps View stream above Import on root home, chooses an
   available camera, enters its streaming PIN and pairs. Discovery alone must
   not grant access; no pairing secret is included in advertisements.
3. The viewer displays the live image with correct orientation and aspect ratio,
   fits the available screen, supports display rotation, and offers Disconnect.
4. The connection carries encrypted video and mutually confirmed PIN-based
   authentication. Use a reviewed PAKE, fresh attempts/session secrets and actual
   TLS identity binding; never send the PIN or a reusable PIN proof. A configured
   sender PIN persists separately, encrypted by its own Keystore key. Viewer PIN
   is not saved. Vault PIN/key/storage are outside this flow.
5. Leaving the test screen, explicitly locking the vault, backgrounding the app,
   or ending the session releases the camera/network resources. Existing
   keep-vault-open behavior must not silently restart broadcasting.
6. Network loss shows a readable disconnected/error state. Restart is explicit;
   do not keep displaying an old image as though it were live.
7. No internet service or cloud upload is required. Test with internet unavailable
   while the local Wi-Fi link remains available.
8. A single camera owner supplies preview and streaming. Selected lens, zoom,
   crop, orientation and mirroring match; camera UI controls are not streamed.
   Stream quality is independent of saved recording defaults. Recording is
   unavailable while streaming in this increment; Stop restores normal controls.
9. Wi-Fi/BLE sightings share a fresh session ID and appear once. Bluetooth is
   optional; denied/off/unsupported states preserve Wi-Fi discovery. A BLE sighting
   on another Wi-Fi cannot itself establish a video route or bypass authentication.
10. PIN change ends the active session. Restart rejects stale authorization and
    never silently downgrades to the debug random-invitation protocol.

## Performance targets

- Initial target: 1280 x 720 at 30 FPS, rear main camera only.
- Target first live frame within 5 seconds after successful pairing.
- Target five continuous minutes of viewing without crashes or growing delay.
- Target median visual delay below 500 ms on the actual shared Wi-Fi network.
- Report requested and actual resolution/FPS, delay, freezes, and device heat
  observations. These are acceptance targets, not verified performance claims.
- If the phones or selected transport cannot meet a target, document the measured
  limitation and review a smaller target before declaring success.

## Existing stack and relevant structure

- Kotlin 2.1.0, Android SDK 26 minimum / 35 target, Compose, coroutines 1.10.1.
- CameraX 1.4.1 handles normal camera sessions.
- CMF 1080p60 uses a custom Camera2/MediaRecorder path.
- Native TLS/H.264, CameraEffect/EGL, NSD and BLE/GATT are implemented. Bouncy
  Castle 1.86's lightweight J-PAKE supplies certificate-bound mutual PIN confirmation;
  it does not replace Android's TLS or encryption providers.
- Preserve generated-fixture evidence in tasks/camera-stream-validation.md;
  diagnose live camera before promoting the revised UX.

Relevant source:
- `app/src/main/java/com/secretvault/app/core/camera/`: camera ownership and configuration.
- `app/src/main/java/com/secretvault/app/ui/camera/`: existing camera UI and preview host.
- `app/src/main/java/com/secretvault/app/ui/navigation/`: unlocked vault routes.
- `app/src/main/AndroidManifest.xml`: permission declarations.
- `app/src/test/`: JUnit tests for protocol/session behavior where needed.
- `app/src/androidTest/`: Android integration tests.
- `SPEC-camera-stream-test.md`: this specification.

The revised implementation plan proposes camera integration, discovery and
PIN-authentication details for review.
Prefer existing/native facilities when they meet these criteria. Any new
dependency must have a stated need, authoritative documentation, and a pinned
version. The sender must have one camera owner; do not open competing sessions.

## Commands

Run from `K:\gemini` in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
& .\gradlew.bat :app:lintDebug
git diff --check
& 'C:\Users\MAHBUB\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

Install/update and instrumentation commands after device IDs are confirmed:

```powershell
$adbPath = 'C:\Users\MAHBUB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$deviceId = '<confirmed sender or viewer serial>'
$apkPath = '<absolute path of the built test APK>'
& $adbPath -s $deviceId install -r $apkPath
& $adbPath -s $deviceId shell am start -n com.secretvault.app/.MainActivity
```

The placeholders must be replaced with observed values, never guessed addresses.
No uninstall or data-clear commands are part of this test.

## Code style

Follow existing Kotlin/Compose conventions: four-space indentation, descriptive
camelCase functions/properties, PascalCase types, StateFlow for observable state,
and lifecycle cleanup. Example already used by `CameraManager`:

```kotlin
private val _isRecording = MutableStateFlow(false)
val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
```

Reuse existing colors, controls, navigation, and screen-awake behavior. Interactive
controls need at least 44 dp touch targets and readable waiting/error states.

## Testing strategy

- Run existing unit tests and build the APK; run Android lint and assess findings
  against the baseline rather than attributing existing warnings to this work.
- Add focused runnable checks for pairing rejection, malformed/oversized input,
  bounded frame buffering, and session cleanup according to the chosen transport.
- Use the actual CMF and Pixel in both directions for camera/network/rendering checks. A desktop
  receiver or emulator alone cannot satisfy the two-phone acceptance criteria.
- Use a blank/test surface for captures; do not inspect existing vault media.
- Measure delay with a common visible time reference observed in both the sender
  scene and viewer output; do not compare unsynchronized phone timestamps.
- Verify wrong pairing details, five-minute playback, viewer rotation, Wi-Fi loss,
  Stop/Disconnect, backgrounding, lock, and starting a fresh session.
- After ending the test, verify the ordinary camera still opens and retains its
  existing recording quality defaults.
- Record commands, outcomes, measurements, and unresolved limits in a validation
  document. Do not mark unperformed checks as passed.

## Boundaries

Always: protect pairing/session secrets, bound network input and buffering, report
errors, release resources, preserve vault data, and verify on both phones.

Ask first: changes to this scope/performance targets, external services,
simultaneous recording, or release publication. The camera Stream and root-home
View stream placement are requested in this conversation and need no repeat approval.
Transport/dependency choices are presented in the technical plan for review.

Never: broadcast automatically, expose media files/keys/PINs, accept an unpaired
viewer, disable certificate validation globally, record audio, clear app data,
uninstall the vault, or claim hardware support without a device test.

## Success criteria

- Live camera images are received inside SV over Wi-Fi in both tested directions,
  with either supported device selectable as sender or viewer.
- The measured quality, startup, sustained-viewing, and delay targets above pass,
  or revised targets have been reviewed explicitly.
- Device discovery leads to PIN-authenticated viewing; wrong PIN, impersonation,
  replay and stale sessions fail before any media is sent.
- Orientation, disconnect, error display, and lifecycle cleanup checks pass.
- Existing vault contents and normal camera behavior remain intact.
- A test APK and a short evidence-backed validation report are available.

## Open questions

- Exact stage/error of the reported live-camera failure; device logs still needed.
- Proposed PIN length/retry policy and a reviewed PAKE dependency/transcript.
- Current ADB connections, nonprivate camera positioning and BLE capability
  must be observed again when implementing; earlier addresses are not assumed valid.
- Active plan and dependencies: `tasks/plan.md` and `tasks/todo.md`, R1–R14.
