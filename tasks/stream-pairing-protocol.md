# Local camera PIN pairing, protocol v2

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
  listener never accepts its v1 auth magic, and discovery will advertise only
  PIN-protected endpoints. Changing the configured PIN must close the current
  session through the camera UI before saving it.

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
