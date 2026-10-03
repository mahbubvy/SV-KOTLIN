# Local camera pairing and controls

The existing TLS media connection remains in use. PIN authentication uses the
Bouncy Castle lightweight J-PAKE API, not a PIN hash or home-made PAKE arithmetic.
The configured PIN is four ASCII digits, as requested by the user. Previously
stored six-digit PINs require explicit setup of a new PIN; they are not truncated.

- Pinned artifact: org.bouncycastle:bcprov-jdk18on:1.86, downloaded by Gradle
  from Maven Central. JAR size 7,224,011 bytes; the complete streaming update adds
  approximately 2.5 MB to the debug APK (including discovery/UI, not just the library).
  [Official release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/)
  includes current fixes. [License](https://github.com/bcgit/bc-java/blob/main/LICENSE.html)
  is the permissive Bouncy Castle license. No Android TLS/crypto provider is registered
  or replaced. Only lightweight JPAKEParticipant is used, with NIST-3072 / SHA-256.
- [Library participant contract](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.java):
  a new participant and randomness per attempt, rounds validated in order, and
  mandatory round-three mutual key confirmation. The J-PAKE material serves
  confirmation only; native TLS supplies media encryption and fresh traffic keys.
- Public discovery carries version 2, fresh 128-bit session ID, local IPv4/port,
  certificate SHA-256 and bounded device label. None is trusted before pairing.
  The client checks the actual TLS certificate against the advertised fingerprint;
  PIN proofs then authenticate the discovered TLS channel. The sender uses its
  own certificate fingerprint. Relaying proofs under another certificate fails.
- Each participant ID is `sv2:<camera|viewer>:<binding>:<fresh-128-bit-nonce>`.
  Binding = SHA-256(ASCII `SV-stream-v2:<session-id>:` || actual TLS certificate hash).
  A received ID must have the opposite role, exact binding and a fresh-format nonce.
  IDs/proofs are cryptographically included by J-PAKE; later rounds must preserve IDs.
- Order: camera round 1 → viewer round 1 → camera round 2 → viewer round 2 →
  camera round 3 → viewer round 3 → camera ACK. Every round is validated before
  proceeding. Media starts after camera verifies viewer confirmation; viewer
  accepts only after verifying camera confirmation and receiving ACK.
- Wire messages use distinct SVP2 magic, round tag, bounded ASCII participant ID
  and canonical signed BigInteger encodings. Group integers < p; response scalars
  < q. Maximum integer encoding is 385 bytes, proof response/tag 33 bytes,
  participant ID 144 bytes. Array counts are fixed. Library validates group
  membership and zero-knowledge proofs. No unbounded JSON/deserialization is used.
- TLS reads/connects have five-second timeouts; a watchdog closes a handshake
  after fifteen seconds. One host pairing is processed at a time; one viewer is
  accepted. Five charged attempts produce a persisted thirty-second cooldown.
  An aborted attempt is charged. Verified pairing resets the budget. Stop/Start
  does not reset it. PIN and proof material are absent from logs/discovery.
- The random-token prototype remains a separate debug route. A PIN-protected
  listener never accepts its v1 auth magic or the open v3 handshake. Changing
  the configured PIN must close the current session through the camera UI
  before saving it.

## Optional streaming PIN

Settings has a persistent Require streaming PIN switch, enabled by default.
Disabling it requires no saved PIN and keeps any existing PIN for re-enabling.
The vault unlock PIN and protected pairing retry budget are unaffected.

Open streams advertise discovery version 3 and NSD auth flag 0; protected streams
retain version 2 and flag 1. BLE public endpoint records use the same versions.
The sender snapshots its mode when starting the listener. Open viewers skip the
PIN dialog and use the existing certificate-pinned TLS connection, followed by
SVO3 magic and the exact 32-byte ASCII session ID before the camera ACK. This
binds the connection to the selected session, without authenticating the viewer.
Anyone on the local Wi-Fi can view and use available remote controls while that
open stream is active. The Settings caption states this behavior.

An advertised open mode cannot downgrade a protected listener: it rejects the
distinct open handshake. Updated viewers still support older protected cameras;
older viewers do not discover open v3 cameras. Media and remote-photo message
formats remain unchanged.

Trust boundaries: nearby discovery metadata and network messages are hostile;
only authenticated vault UI may start/configure a camera stream. Assets are the
live camera feed and saved streaming PIN. Tampering/impersonation/replay must fail
before media, and malformed/slow peers must consume bounded memory/time/attempts.
No microphone, vault-file read, cloud endpoint or automatic broadcast is added.

## Remote photo capture

After authentication, the camera may announce photo support with message type 3
and one byte (0 or 1). The viewer sends type 5 followed by a positive 64-bit request
ID, starting at 1 and increasing by one. A single request may be pending. Invalid,
repeated or overlapping commands end the session. Command payload reads have a
three-second timeout; waiting for the next command tolerates idle read timeouts.

The camera dispatches the request on its main thread while the vault is unlocked
and the camera screen is active. CameraX ImageCapture supplies the photo to the
existing processing/encryption queue. Type 4 returns the request ID and a 0/1
result only after the encrypted photo, thumbnail and database record are saved,
or capture/save cannot be confirmed. Results share the video writer so messages
cannot interleave inside a frame. No filenames, media contents or vault paths are
sent to the viewer. Capture confirmation is bounded to 25 seconds; the viewer
ends the session after 30 seconds without a result and asks the user to check the
camera vault. Commands are never automatically retried.

Photo streaming uses CameraX Preview and ImageCapture, including when the camera
screen is set to Video. Stopping the stream restores ordinary camera mode and its
recording defaults. Both devices need the updated build to use remote capture;
updated viewers can still receive video from older cameras without photo controls.

Checkpoint evidence is recorded in camera-stream-validation.md. Matching PIN,
wrong PIN, changed certificate/session, replay/reflection, malformed group input
and v1 downgrade checks pass. On-phone PIN/TLS pairing took 529 ms on CMF and
  553 ms on Pixel; real two-phone PIN-to-first-frame UI checks took 891/900 ms.

## Remote camera and lens selection

Camera-control endpoints advertise protected discovery v4 or open discovery v5.
PIN and TLS handshakes remain unchanged. Updated viewers also understand v2/v3
cameras without lens controls; earlier viewers ignore v4/v5 endpoints rather
than receiving message types they cannot parse. Both phones need the new build
for remote lens selection. This version is carried identically in NSD and BLE.

After stream configuration, type 6 carries at most eight unique camera options
and the applied selection. IDs are 1–32 ASCII bytes matching [A-Za-z0-9_./:-];
labels are 1–48 UTF-8 bytes without control characters. Text lengths are one
byte and are bounded before allocation. No applied ID means camera readiness
or zoom is still pending. The camera supplies available front/back facings and
the existing rear lens catalog; options do not come from the viewer.

Type 7 carries a positive 64-bit request ID and an advertised target ID. Photo
and camera selections share sequential IDs and one outstanding command. Type 8
returns that ID and a 0/1 applied result after camera rebind and zoom completion.
The camera requires its unlocked foreground screen and accepts only advertised
targets. Selection confirmation is bounded to ten seconds; the viewer stops
after fifteen seconds without a result. A late hardware change can still apply
after timeout; the viewer must check the camera device and never retries it.

The same video writer sends configuration, option updates, acknowledgments and
frames. Local camera changes update the applied selection on the viewer. Camera
and lens controls are unavailable while a photo/selection command is pending.
Stopping restores ordinary recording configuration; simultaneous video recording
is still separate work.

## Remote video recording

Recording-control endpoints advertise protected v6 or open v7 in both NSD and
BLE. TLS/PIN authentication is unchanged; previous viewers ignore those versions.
Updated viewers retain support for v2 through v5 cameras.

Type 9 carries four strict 0/1 flags (available, recording, saving, audio) and a
nonnegative 32-bit elapsed-second count. Recording requires available=true and
cannot coexist with saving. Type 10 is a positive 64-bit sequential request ID
and a strict 0/1 start flag. Type 11 acknowledges that ID/action and a 0/1 success
flag. These commands share one pending slot and the same sequence as photos and
camera selection. Every outbound message uses the single frame writer.

Start is acknowledged after CameraX's Start event. Stop is acknowledged after
Finalize and successful encrypted save, thumbnail and database insertion. Host
start/stop limits are 10/60 seconds; viewer limits are 15/65 seconds. A timeout
does not retry. Saving can finish after timeout/disconnect; check the camera vault.
Photos and camera selection are blocked while recording/saving or a command is
pending. Foreground/unlocked ownership checks precede camera operations.

The camera attempts Preview + ImageCapture + FHD30 VideoCapture with the existing
preview effect; an unsupported binding retains preview/photos and reports video
unavailable. Ordinary saved-video quality defaults are retained. Microphone uses
the camera setting and permission. Disconnect/background/navigation stop active
recording. SOURCE_INACTIVE with recorded frames retains its valid output for
encryption, following the Android Finalize contract:
https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize
