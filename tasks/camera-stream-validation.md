# Camera stream validation

Build in progress on `codex/camera-stream-test`. Live-camera capture is pending:
the user requested finishing the build before positioning the phones.

## Protocol and secure connection

- Unit suite and debug APK build passed after protocol implementation.
- Four protocol tests cover pairing input, fragmented reads, round trips,
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

Pending: generated media decoding, live media, sustained performance, visual
latency, UI click-through and lifecycle/regression checks. No performance target
is claimed as verified yet.
