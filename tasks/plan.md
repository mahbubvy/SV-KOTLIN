# Implementation plan: Discoverable SV camera streaming

Updated: 2026-10-02. Implementation authorized; capability probes and an initial
live UI checks passed in both CMF/Pixel directions, including disconnect cleanup.
The original failure was not reproduced. No dependency change yet.
Active tasks: R1–R14 in [todo.md](todo.md). This revises the same streaming work.
The original prototype plan is retained below for its decisions and evidence.

## Required experience

- Sender: normal SV camera → Stream → configure a separate persistent streaming
  PIN once → advertise availability → waiting/connected indicator → Stop.
- Roles are selectable on every supported device. CMF and Pixel name test
  hardware only; do not gate streaming roles by model or encode fixed peer addresses.
- Receiver: vault home → View stream directly above Import → available cameras
  → choose device → enter streaming PIN → fitted live camera image → Disconnect.
- Both phones use the same Wi-Fi. Bluetooth assists discovery/connection setup;
  video still travels over Wi-Fi. No hotspot, internet account or cloud relay.
- One viewer initially. Foreground only. No audio, remote shutter, media saving,
  simultaneous recording, automatic broadcast or automatic reconnect.
- The receiver sees the active lens, zoom, preview crop, rotation and mirroring.
  Streaming resolution is independent of the saved recording quality setting.
- Proposed PIN policy: six numeric digits, configurable on the sending device,
  remembered until changed. Viewer PIN is not saved. This is a planning default,
  not a confirmed user requirement.

## What exists and what has not passed

Existing `core/stream/` implements bounded H.264 messages, a decoder, native TLS,
a random-secret invitation, a separate Camera2 encoder and session cleanup.
Generated 640 × 360 video and an initial 720p live camera UI check over
CMF → Pixel Wi-Fi passed; 90 unit tests passed. The originally reported failure
was not reproduced in the successful live checks. Native UI-test cache and
timing failures were corrected; both role directions pass the same check.

`CameraManager` owns CameraX Preview plus photo/video use cases; CMF's selected
1080p60 rear-main mode instead uses `CmfHighFpsCameraView` and a vendor Camera2
session with preview/MediaRecorder surfaces. `CameraStreamEncoder` currently
opens another fixed rear camera independently. Sharing the active preview is
a new integration, not merely relocating its debug button.

`GalleryScreen` has a bottom-right action column: Import above Camera. Add
View stream above Import at the root only; hide it during media selection.
Navigation already checks unlocked state. `MainActivity` supplies screen-awake
and screenshot protection. Reuse those policies.

The existing vault `PinManager` authenticates local unlock and has retry rules;
it is not a network pairing protocol. Do not reuse its PIN or digest for streaming.

## Architecture choices and research

### Keep the working media/transport pieces

Reuse StreamProtocol, StreamDecoder, bounded queues, native TLS and the existing
error/lifecycle flow. Retain 720p30 as a measured target, approximately 3 Mbps,
first live frame within five seconds after pairing, five-minute viewing and
median visual delay below 500 ms. These targets are still unverified.

Split camera acquisition from encoding: the normal camera remains the only
owner; the encoder accepts frames through its input Surface. A renderer draws
one camera texture to the local preview and encoder, throttling encoder input
to 30 FPS before encoding. Do not drop dependent encoded H.264 frames.

For CameraX, prefer its Preview CameraEffect/SurfaceProcessor hook with a
concrete EGL/OpenGL renderer. CameraX provides SurfaceRequest/SurfaceOutput
ownership and transforms; honor its release callbacks and source generations.
This hook exists before the installed CameraX 1.4.1, so no upgrade is planned.
This is a proposed integration, subject to device proof.
[CameraX SurfaceProcessor](https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor),
[CameraEffect targets](https://developer.android.com/reference/androidx/camera/core/CameraEffect).

For CMF's native path, use the same renderer on the existing preview input and
keep the existing camera owner/session. Avoid adding an unverified third HAL
output to its vendor 60 FPS session. Probe the exact configuration on CMF before
promoting it; individual output-size support does not prove a surface combination.
[Camera2 output combinations](https://developer.android.com/media/camera/camera2/multiple-camera-streams-simultaneously).

Do not use recurring screenshot/Bitmap copies as the production source:
they add readback/copy cost and may fail the latency/FPS target. They may serve
only as a diagnostic comparison. UI controls must not be transmitted.
No WebRTC/RTSP dependency or transport rewrite until measurements justify it.

### Wi-Fi discovery first

Use native NsdManager DNS-SD to advertise the current stream and resolve it in
SV. Use a dynamically allocated port; support service-name conflicts and loss.
[Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd).

Advertise bounded public data only: generic camera name, protocol version,
fresh session ID, endpoint, certificate fingerprint and availability. Never
advertise the current StreamInvitation string: it contains a secret token.
Discovery data, names, endpoints and fingerprints are untrusted.

Keep target SDK 35. The current local-network guidance grants access through
INTERNET for targets ≤36; do not add SDK37 permission calls or upgrade the SDK
for this feature. Reassess when targeting 37, and inspect actual device versions
and compatibility overrides during the test.
[Local-network permissions](https://developer.android.com/privacy-and-security/local-network-permission).

### PIN authentication before public discovery

A low-entropy PIN plus a certificate fingerprint obtained from the same
unauthenticated discovery channel cannot establish identity by itself.
Do not send the PIN, a reusable digest, or a simple challenge/HMAC that permits
offline PIN guessing. Bluetooth discovery also does not authenticate a device.

Proposed choice: a reviewed password-authenticated key exchange (PAKE), with
mandatory mutual key confirmation, fresh per-attempt randomness and binding to
the actual TLS server certificate, protocol version, roles and current session.
Media starts only after both sides confirm. A replay or relayed exchange under
a different TLS certificate must fail. Do not implement PAKE mathematics.

Candidate: Bouncy Castle's lightweight J-PAKE API. Its documented exchange
includes validation and key confirmation; the participant is single-use and
not thread-safe. R7 must select/pin a current compatible release, check license/
advisories/package size, inspect official implementation/examples and benchmark
on both phones. The official download page currently lists 1.86. The linked LTS
API documents the algorithm, not a decision to depend on the LTS distribution.
No dependency has been added.
[J-PAKE API](https://downloads.bouncycastle.org/lts-java/docs/bccore-lts8on-2.73.11-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html),
[Java releases](https://www.bouncycastle.org/download/bouncy-castle-java/).

R7 is a security/design checkpoint, not permission to invent a custom handshake.
Write/review its exact transcript and certificate binding, verify wrong-PIN,
replay, reflection, altered-certificate and malformed-group-element cases,
then allow discovery. Use the library's lightweight classes without globally
replacing Android's crypto/TLS provider. A candidate that fails review or device
performance must be replaced with another reviewed PAKE; no insecure fallback.

Store the sender's pairing secret encrypted using a dedicated Android Keystore
AES-GCM key and fresh random IVs in private preferences. Reuse existing storage
patterns but never the vault master-key alias or vault PIN. Symmetric PAKE needs
a recoverable secret; a simple vault-PIN verification hash is not interchangeable.
The persistent PIN-key alias must also be outside the temporary TLS-identity
cleanup prefix; ending a stream must not delete the saved configuration key.
[Keystore AES-GCM example](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).

Apply a sender-wide persisted retry budget (proposed five failures/30-second
cooldown), bounded handshake input, one in-flight pairing attempt and a deadline.
Per-IP limits alone are bypassable. A cooldown can temporarily block a legitimate
viewer; show retry timing and do not silently reset it on Stop/Start.
Changing the PIN ends the active session and invalidates prior pairing.
Fresh stream/session keys are generated even though the configured PIN persists.
Keep the old invitation protocol debug-only; new discovery cannot downgrade to it.

Google Nearby Connections is an alternative with additional Play-services
transport. Its documentation still requires authenticating discovered peers;
automatic acceptance does not solve predefined-PIN authentication. Avoid
replacing the proved socket path just to add discovery.
[Nearby authentication](https://developers.google.com/nearby/connections/android/manage-connections).

### Optional Bluetooth assistance after Wi-Fi works

Use native BLE advertisements and a small read-only GATT metadata service.
BLE carries identification/connection setup data; it never carries video, the
PIN, proof secrets or media keys. Metadata uses the same validation and PAKE
path as Wi-Fi discovery.

Legacy advertisements have a 31-byte limit. Use one service-data field containing
the service UUID, version and short session handle, accounting for flags and AD
overhead; do not duplicate the UUID in a second field. Read the full session ID,
name and endpoint through bounded GATT. Deduplicate only after resolving that
full ID; a short handle is not an authenticated identity. Probe peripheral/
advertising support on both phones. Session IDs change on every new stream.
[BLE advertiser](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser),
[BLE roles and GATT](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview).

On Android 12+, request BLUETOOTH_SCAN/ADVERTISE/CONNECT as applicable when
enabling assistance. On API 26–30, declare legacy Bluetooth permissions and
handle the location permission/system requirements for BLE scanning. Assert
neverForLocation only for scan use that does not derive location, and verify
that filtering still discovers these advertisements. Do not require Bluetooth
or location just to use NSD. Denial, Bluetooth-off or unsupported hardware keeps
Wi-Fi discovery usable. Use filtered, time-bounded scans; stop
after selection or exit and provide Refresh instead of endless scanning.
[Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions),
[BLE scan guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices).

A nearby BLE device may use a different Wi-Fi network; finding it does not
establish a video route. Resolve/check reachability and explain that both phones
need the same Wi-Fi. Measure whether BLE improves discovery; promise no speedup.

## Ordered work

| Task | Working result | Depends on |
|---|---|---|
| R1 | Reported live failure reproduced, root cause fixed and tested | None |
| R2 | Surface-input encoder decoupled from camera ownership | R1 |
| R3 | CameraX preview feeds local view and encoder from one camera | R2 |
| R4 | CMF native 1080p60 preview feeds the same streaming path | R3 |
| R5 | Lens/zoom/crop/mirror/rotation and source rebinds stay correct | R3, R4 |
| R6 | Separate persistent streaming PIN can be configured safely | None |
| R7 | PIN pairing succeeds, impersonation/replay/guessing checks fail closed | R6 |
| R8 | Wi-Fi sender appears in a debug device list and pairs securely | R5, R7 |
| R9 | Stream/Stop works inside the normal camera | R8 |
| R10 | Home View stream above Import opens list → PIN → live view | R9 |
| R11 | Optional BLE advertisement/GATT reports public connection metadata | R10 |
| R12 | Optional BLE discovery merges into the same list and pairing path | R11 |
| R13 | All termination/restart/permission-loss paths release resources | R10, R12 |
| R14 | Two-phone quality/latency and regression evidence, reviewable APK | R13 |

Checkpoints: camera proof after R5; secure pairing after R7; complete Wi-Fi UX
after R10; BLE comparison after R12; final lifecycle/performance review after R14.
Do not promote a later UI slice as working when its device checkpoint is pending.
Build the Wi-Fi version before adding BLE, so a radio issue cannot obscure a
camera/codec failure. No parallel agents are required for this shared pipeline.

## Verification and delivery

Use the existing build/unit/instrumentation commands in the spec. Verify each
slice with a focused runnable check and the actual relevant phone; compile-only
results do not satisfy camera, BLE, pairing-performance or lifecycle acceptance.

Observe current ADB IDs; no old address is assumed valid. Install only with
adb install -r. Preserve vault data; no uninstall, data clear or PIN in chat.
Camera tests wait until the user positions the phones at a nonprivate test scene.
Use generated media for parser/decoder/transport checks.

Read scoped camera/codec/network logs without tokens/PINs/frames. Expose readable
failure stages: camera open/session, encoder config/output, discovery, pairing,
transport and decoder. No permanent logging framework is needed.

Maintain the previous 90-test baseline; assess lint against the documented
41 errors in unchanged camera/crypto/player files. This does not waive new errors.
Review any touched existing lint error before extending the affected code.
R14 must report actual coded size/profile/FPS, pairing/first-frame times,
20 visual-delay samples, five-minute stability and regression results.

UI follows existing SV styling and During-mode antislop: labeled actions, 48 dp
targets, 4.5:1 content text/3:1 functional borders, readable selected/loading/
empty/error states and keyboard/landscape reachability. Discovery never exposes
technical tokens. Viewing fits the image rather than stretching it.

Deliver a debug APK and an updated validation report. No GitHub push, release
publication or transport/quality downgrade is authorized by this planning request.

## Remaining decisions and risks

- Live failure: exact error/logs are still needed; no cause is asserted.
- Six-digit PIN/retry defaults are proposed, not a user-confirmed length policy.
- PAKE dependency, transcript, Android compatibility and handshake cost must pass
  R7 review before automatic discovery becomes user-facing.
- GPU renderer compatibility with CameraX/CMF vendor surfaces is a device proof.
  If it fails, report the limitation; do not silently open a different lens.
- Router client isolation can block Wi-Fi traffic even when BLE finds a device.
- BLE may improve convenience without improving measured connection time.
- Lens/source changes require ordered configuration plus a keyframe; stale
  callbacks must not deliver frames from an old camera after switching.
- No simultaneous recording in this increment. Disable Record while streaming
  with an explanation; stopping returns the previous recording mode/default.

## Historical prototype plan

The original plan below explains the implemented debug prototype. Its remaining
live/lifecycle/performance work is carried into R1–R5 and R13–R14. Its manual
pairing/Settings UI are diagnostic history, not the new user-facing requirement.

## Goal

CMF Phone 1 sends a live rear-main-camera image to SV on Pixel 5 over the
same Wi-Fi. First prove transport and decoding, then substitute the actual
camera, then measure usability. Keep the prototype separate from the existing
photo/video recording path.

Initial targets: 720p30, first frame within 5 seconds after pairing, median
visual delay below 500 ms, and five minutes of continuous viewing. These are
test targets; actual phone performance has not been verified.

## Proposed architecture

```text
CMF rear camera (one Camera2 session)
    +-- local preview Surface
    +-- MediaCodec H.264 encoder Surface
                 |
        bounded encoded-frame queue
                 |
        authenticated TLS socket over local Wi-Fi
                 |
        MediaCodec H.264 decoder on Pixel
                 |
        fitted, rotated viewer Surface inside SV
```

### Native media and transport

Use Camera2 and Android MediaCodec for the streaming-only mode. Camera2 supports
sessions with multiple output surfaces, subject to hardware combinations;
MediaCodec supplies encoder input surfaces and decoder surface output.
References: [camera output combinations](https://developer.android.com/media/camera/camera2/multiple-camera-streams-simultaneously),
[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).

This is a proposed use of those APIs, not a guarantee of CMF compatibility.
Check 1280 x 720 surface support, encoder capabilities, and exposure FPS ranges
before configuring the session. Observe actual encoded and rendered FPS.
Begin with approximately 3 Mbps H.264, a supported low-delay profile without
B-frames, and roughly one keyframe per second. Verify accepted settings; change
the bitrate/profile based on evidence if necessary.

Use platform SSLServerSocket/SSLSocket and a session-scoped SSLContext. Require
TLS 1.2 or newer. No HTTP server, cloud signaling, custom encryption, or additional
library is proposed. Android provides TLS sockets:
[secure network protocols](https://developer.android.com/privacy-and-security/security-ssl).

WebRTC remains an alternative for a later reviewed plan if measured packet-loss
behavior or delay makes this direct connection unsuitable. Do not build both
transports or a generic transport interface for this prototype.

### Pairing and network boundary

- Generate a temporary signing key/certificate in Android Keystore and a fresh
  128-bit random viewer token for each started session. Configure a valid
  self-signed certificate, including authorized signing and validity dates;
  generation can otherwise produce an invalid signature. Reference:
  [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).
- Show one manual connection string containing the Wi-Fi IPv4 address, ephemeral
  port, SHA-256 certificate fingerprint, and token. Treat this whole string as a
  secret invitation; never log it. Support deliberate paste in the viewer.
- The viewer's isolated TLS context must check certificate validity/signature
  and exactly the fingerprint transferred from the sender. Reject mismatches
  during the handshake, before sending the token. This authenticates the paired
  local identity instead of relying on a public DNS certificate. Do not install
  a global trust manager, trust-all callback, or blanket cleartext exception.
- Authenticate the token inside TLS before sending configuration or frames.
  Compare fixed-length tokens in constant time. Only one viewer may be active.
  Time out unauthenticated peers; a rejected attempt must not destroy the host.
- Bind the listener to the observed Wi-Fi address, use a dynamically assigned
  port, and route the viewer socket through that Wi-Fi network. Do not require
  internet validation on the network. Validate private IPv4 literals and ports;
  do not accept arbitrary URLs, DNS endpoints, or file paths in the invitation.
- Add INTERNET and ACCESS_NETWORK_STATE permissions. Check the connected phones'
  Android versions and applicable local-network restrictions before testing;
  do not upgrade the SDK as part of this prototype. Reference:
  [local-network permissions](https://developer.android.com/privacy-and-security/local-network-permission).
- Stop/restart invalidates the invitation, closes peers/listener, and deletes
  only this session's Keystore alias. Clean abandoned prototype aliases on startup.
  Vault keys, PINs, files, and database storage are outside this flow.

### Small, versioned media framing

Define one binary format shared by both roles. Use network byte order and
length-prefixed messages with fixed maxima, checked before allocation.

1. Authentication: magic, protocol version, fixed-size token; explicit success
   response before any media.
2. Configuration: H.264 only, encoded width/height, requested FPS, orientation,
   and codec-specific initialization data (SPS/PPS).
3. Video: sequence number, presentation timestamp in microseconds, relevant
   codec flags, payload length, and one complete encoded access unit.
4. End: close/EOF ends viewing; no file transfer or command protocol.

Initial bounds: 64 KiB total configuration data, 1 MiB per video access unit,
6 pending access units and 2 MiB total queued payload per side. Validate known
types, supported dimensions/FPS, orientation in 90-degree steps, timestamp and
sequence behavior, and truncated input. A protocol unit test covers these bounds.

Cache the codec configuration, release every codec buffer promptly, and send
configuration followed by a fresh keyframe when the viewer connects. Never
start the decoder in the middle of a dependent frame sequence. Treat codec
config buffers separately from normal frames; handle offsets and sizes correctly.

For the first test, queue overflow or a sustained write/decode stall closes the
session with a readable error. Do not silently drop dependent H.264 frames or
let latency grow without bounds. A cancellable watchdog closes stuck sockets;
read timeout alone does not bound a blocked write. Record queue/stall events.
This deliberate limit requires a fresh session on poor Wi-Fi. Adaptive bitrate
and keyframe recovery can be proposed later if this limit is actually reached.

### Integration and lifecycle

- A single StreamSession owns this prototype's sockets, jobs, and media objects.
  Expose a StateFlow with idle, waiting, connecting, live, disconnected, and error
  states. Use concrete helpers; no DI/service framework or global singleton.
- Add one debug-only "Camera stream test" entry in unlocked Settings. It opens
  a temporary route with Send and View roles. The route also checks unlocked
  state and BuildConfig.DEBUG; no externally exported streaming activity.
- The sender starts its own camera only on this dedicated screen, after the
  ordinary camera has released ownership. Reuse lifecycle/error-handling
  patterns from CmfHighFpsCameraView without refactoring the recording manager.
- The viewer never opens its camera or microphone. Fit its image with preserved
  aspect ratio; use sensor/display rotation metadata rather than stretching it.
- Stop on Back, Stop/Disconnect, disposal, ON_STOP, explicit vault lock, surface
  loss, network loss, or media errors. Cleanup must be idempotent and handle late
  callbacks. Reopening the app must not resume a broadcast automatically, even
  when keep-vault-open is enabled.
- Reuse the app's screen-awake flag and screenshot protection. Reuse existing
  Compose colors, button hierarchy, touch targets, and accessible state labels.
  Keep developer details out of the viewer except the temporary test information.

## Build order and checkpoints

| Step | Working result | Depends on |
|---|---|---|
| 1 | Bounded invitation/media format with runnable checks | None |
| 2 | Paired TLS round trip on the phones, with rejection checks | 1 |
| 3 | Generated fixture video plays on the Pixel through TLS | 2 |
| 4 | CMF live camera replaces the fixture source | 3 |
| 5 | Temporary unlocked SV test screen exposes both roles | 4 |
| 6 | Stop, lock, background, network-loss and overload behavior verified | 5 |
| 7 | Five-minute device validation and regression report | 6 |

Checkpoint after 2: pairing works, wrong credentials/certificate fail, input
checks pass, APK builds. Review evidence before attaching media.

Checkpoint after 4: fixture and live images decode correctly on the actual Pixel.
Review codec support and measured startup/FPS before UI work proceeds.

Checkpoint after 6: complete user flow and all termination paths work; unit tests,
APK build, relevant device tests and lint assessment complete.

Final checkpoint: measured targets and regression checks recorded, remaining
limitations stated, test APK ready for review. Permanent camera options and
remote control remain a separate user decision.

The dependency chain is sequential. No parallel agent work is needed; transport,
codec configuration, camera ownership, and UI cleanup share the same contract.

## Verification and delivery

Use the executable build/test/install commands in the spec. Add focused JUnit
protocol checks and Android tests for TLS, codecs, and cleanup; reuse the existing
generated video-edit fixture for the first media test. That fixture is 640 x 360
and validates wiring only; it does not satisfy the final 720p30 target.

Install both test APKs with `adb install -r` after observing the device IDs.
Use no uninstall, app-data clear, vault PIN in chat, or private media fixture.
ADB is for install/diagnostics; final video traffic must travel over real Wi-Fi,
without adb forward/reverse or a desktop relay.

Measure first-frame time on the viewer with a monotonic clock. Count encoded,
received, and rendered frames separately in steady-state windows. Report the
actual coded size, profile, bitrate, frame rate, stalls, and restart behavior.
PTS comparisons across unsynchronized phones do not establish network delay.

For visual end-to-end delay, film a common timer/test scene and the receiving
display together using an external device, then compare at least 20 samples.
Report median and worst sampled delay. If measurement hardware is unavailable,
mark latency unverified instead of guessing or declaring the target passed.

Check five continuous minutes, viewer rotation, wrong invitation, second viewer,
Wi-Fi interruption, app backgrounding, screen lock, Back, Stop, reconnect with
fresh details, and rejection of stale details. Show an overlay when disconnected
so a retained image cannot be mistaken for a live feed.

After cleanup, open the normal camera and make short nonprivate recordings on
CMF (1080p60) and Pixel (its existing supported default). Inspect dimensions/FPS
and confirm that playback, gallery navigation, and keep-open settings still work.

Document results in `tasks/camera-stream-validation.md`. Update task checkboxes
only with actual evidence. No release, GitHub push, or permanent UI promotion is
part of this plan.

## Risks and decisions to review

| Risk | Effect | Response |
|---|---|---|
| TLS identity generation/provider behavior | Connection cannot start | Test this before camera work; never bypass validation |
| Camera surface combination/codec quirks | Black view, configure failure, reduced FPS | Probe actual CMF support; verify a local decode and Pixel output |
| TCP retransmission/backpressure | Increasing delay on poor Wi-Fi | Bounded queues and stall termination; measure before accepting transport |
| Router client isolation/VPN routing | Phones cannot reach each other | Check reachability over the Wi-Fi interface; report the network issue |
| Surface recreation/late callbacks | Leaks or broadcast continues after exit | Idempotent close, session generation checks, instrument termination paths |
| Pairing string length | Manual entry is inconvenient | Accept paste for this prototype; QR/discovery only after stream proof |
| Pixel not connected to ADB | Cannot install/verify receiver | Continue host/unit checks, obtain current Pixel connection for two-phone tests |

Review the proposed native transport, temporary Settings entry, manual secret
invitation, and performance targets before implementation. Targets change only
after measured evidence and user review.
