# Camera stream validation

Debug prototype built on `codex/camera-stream-test`. Live-camera capture is pending:
the user requested finishing the build before positioning the phones.

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
  Actual camera output/FPS remain unverified.
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
