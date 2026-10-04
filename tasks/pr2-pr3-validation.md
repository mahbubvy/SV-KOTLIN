# Recording pause and direct file sharing review

Date: 2026-10-04. User authorized merging PR #2 and PR #3, confirmed testing
PR #2, and requested a two-phone check of PR #3.

## PR #2

Reviewed CameraX Pause/Resume events, native MediaRecorder paused-time accounting,
finalization resets, remote command eligibility and recording-state validation.
Both phones must run the new build because the recording-state wire message adds
a paused flag. The existing app identity and storage formats are retained.

The combined baseline runs 128 unit tests with zero failures in this workspace.
The PR author's hard-coded-path compatibility failure does not reproduce here.
Inverting the added paused-state validation makes
`recordingCommandsPreserveFramesAndRejectInvalidStates` fail; the correct source
is restored and the full suite passes. Evidence:
`scratch/pr2-pause-mutation.log`, `scratch/pr2-pr3-final-tests-build.log`.

PR #2 merged to GitHub as `05092c17c585e9567a60538478fd8f06fe8eb635`.
Local/remote physical pause recording was not repeated in this increment; the
owner's manual confirmation covers PR #2, with protocol checks providing the
automated evidence.

## PR #3 corrections before merge

- Preserve Require PIN when the saved PIN is missing/unreadable; show Set PIN
  recovery instead of silently switching receiving to open mode.
- Close in-progress sender connection sockets on Cancel, including TLS pairing.
- Cancel the receiver job and check cancellation after preview generation before
  publishing the current item. Delete its encrypted file/preview on failure.
- Keep database insertion and the completed-item counter together so cancellation
  or a lost acknowledgement does not delete files belonging to a committed row or
  under-report completed items.
- Validate total received bytes and require a positive final acknowledgement.
- Use full UUIDs for incoming file IDs rather than an eight-character prefix,
  reducing the risk of overwriting an existing vault file after many imports.
- Generate incoming/backup previews with the existing DecryptingMediaDataSource.
  Video previews no longer decrypt the entire video into a plaintext temp file;
  photo previews no longer collect the full file in memory. Legacy staging-file
  cleanup remains for artifacts from older code.

## Device test setup

Pixel 5 and CMF Phone 1 connect successfully over wireless ADB. The optimized
combined build updates `com.secretvault.app` on both with `adb install -r`; both
updates succeed with the existing signing certificate, without uninstalling or
clearing vault data. The spec's different-machine signing assumption does not
apply to these devices in this workspace.

`TransferDeviceTest` uses generated blue JPEGs and the existing video fixture with
32 MiB of appended padding. Two-phone success transfers 20 photos and one video.
The receiver inserts owned test rows into Imports, reads them back, validates
preview metadata, and decrypts each received file to verify readability. Tests
remove only their own received rows/files and generated source files. PIN tests
use uniquely named preferences and a synthetic PIN, preserving the user's PIN.
No personal media is displayed, captured, transferred or logged.

`tasks/test-file-transfer.ps1` runs the same cases in either direction. Sender and
receiver names/addresses are arguments, not hard-coded roles. Receiver callbacks
accept/decline the generated offer; this tests the production discovery, TLS,
transfer and persistence engines without requiring manual navigation through
private gallery content.

## Completed results

- App/test build: PASS.
- Combined unit suite: 128 tests, zero failures.
- Missing required PIN: PASS on Pixel; preference stays enabled and no item is
  received.
- Cancellation during preview: PASS on CMF; the current encrypted original and
  preview are removed and no row is published after cancellation.
- The initial fixture used Compose to replace the activity content, exposing a
  stripped Compose method in the instrumented R8 target. The fixture now uses a
  native TextView; the production UI and optimization rules are unchanged.
- CMF to Pixel, PIN off: PASS. 20 photos and one 32 MiB padded video are saved,
  read back from Imports, decrypted and cleaned up.
- Pixel to CMF, PIN on: PASS for the same 21 generated items.
- Decline, CMF to Pixel: PASS; zero items saved.
- Sender cancellation, Pixel to CMF: PASS; 20 completed photos are kept and the
  unfinished video is discarded. Completed-item count matches received rows.
- Receiver cancellation, CMF to Pixel: PASS with the same preservation/count
  checks.

Evidence: `scratch/transfer-*-sender.log`, `scratch/transfer-*-receiver.log`,
`scratch/transfer-preview-cancel-cmf.log`, `scratch/transfer-local-regressions.log`.
The final optimized app/test build passes in
`scratch/pr2-pr3-final-build.log`, including all 128 unit tests. The installed
production APK SHA-256 is
`30789BD77DB34B6896CF287217622611C2DB16B9E14615B444C57012B4ABD21B`.
After the final UUID and preview-buffer cleanup changes, success transfers were
rerun in both directions: CMF to Pixel without PIN and Pixel to CMF with PIN.
Both sender and receiver logs report PASS and all 21 received items are verified.
Decline and cancellation results above precede those two small final changes.
The first headless discovery attempts received no advertisement while the Pixel
display was asleep. Waking the displays without unlocking Android made discovery
and transfer succeed. The harness now wakes the displays; the production screens
already keep them awake. Fixture preparation time is reported separately.

Two-phone tests run directly against the production engines in the test process;
they unlock the test's app session for generated fixtures, leaving Android's
keyguard intact. No Android lock PIN is entered or bypassed. This is not a normal
screen/navigation certification.

## Scoped review

- Correctness: record-state unit/mutation checks, required-PIN regression, preview
  cancellation and real two-phone success/decline/cancellation cover the changes.
- Architecture: reuse existing TLS, pairing, encryption and random-access reader;
  no new dependency, media schema or backup wire format.
- Security: retain the accept gate and optional PIN intent, reject bad messages,
  bound preview memory and avoid new plaintext files on disk.
- Performance: transfer buffers stay bounded. No sustained-throughput percentage
  or frame-rate claim is based on these functional runs.
- UI: existing SV dark/mint components and 48 dp buttons remain; Set PIN opens
  the existing four-digit setup dialog. Error recovery is reviewed in source.
  The normal screen click-through remains an explicit manual verification limit.

## Limits

Full normal-screen click-through, a 1 GiB video, accept timeout, low-storage and
Wi-Fi-loss scenarios remain outside the checks completed so far. No sustained
throughput, full R8 regression coverage or optical/photo-quality claim is made.
Existing lint findings remain; no unrelated dependency or vault format change.
