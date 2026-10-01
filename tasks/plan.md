# Implementation plan: Local camera streaming test

Status: Proposed build plan. No application code or dependencies changed.
Specification: [SPEC-camera-stream-test.md](../SPEC-camera-stream-test.md).
Task list: [todo.md](todo.md). Execute sequentially after review.

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
