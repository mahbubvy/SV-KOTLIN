# Spec: Direct file sharing between two SV apps

Status: **Planned.** Phase 0 is done (this guide). Work happens on branch `file-share`.
Tick the boxes in [Phases](#phases) as you finish each step, and add a dated note under
[Progress log](#progress-log) so the next agent knows where things stand.

## Goal

Send photos and videos from one SV vault straight into another SV vault on a nearby phone.
Don't use Quick Share, the Android share sheet or any cloud service. Files never exist
unencrypted on disk on either phone. Each vault keeps its own encryption key.

## Decisions for version 1 (agreed with the user, 2026-10-04)

| Topic | Decision |
|---|---|
| Connection | Both phones on the **same Wi-Fi** network. A phone hotspot with the other phone joined counts as the same network. No Wi-Fi Direct, no Bluetooth data transfer. |
| What can be sent | **Photos and videos only** (`MediaType.PHOTO`, `MediaType.VIDEO`). |
| Pairing | **PIN every time.** The receiver's existing streaming PIN (`StreamPinManager`) is reused. There's no "trusted devices" list. |
| Direction | **One direction per session.** One phone receives and the other sends. To send back, swap roles. |
| Interrupted transfer | **Starts over.** Items fully received before the drop are kept. The partly received item is deleted. No resume. |
| Compatibility | Both phones must run a build containing this feature. There's no protocol negotiation beyond a version byte. |

Anything outside this table is **out of scope** for version 1. Don't add it without asking the user.

## User flow

1. **Receiver:** Home → **Receive files**. If no streaming PIN is set, the receiver is asked to set one, using the same dialog streaming uses. The screen shows "Waiting for a sender… Name: SV <model>" and a Stop button. The phone advertises itself on Wi-Fi.
2. **Sender:** Gallery → select items → **Send to SV device**. A single-item **Send to SV device** in the media viewer is optional. A list of receiving phones appears. Pick one, then enter the receiver's PIN.
3. **Receiver:** a dialog appears: "Accept 5 items (1.2 GB) from SV <model>?" with Accept and Decline. With no answer in 60 s, the transfer is declined.
4. **Both** phones show progress: "Item 2 of 5 · 340 MB of 1.2 GB" and Cancel.
5. **Done:** the receiver shows "5 items saved to Imports". The sender shows "Sent 5 items". Received items appear in the **Imports** album (`AlbumEntity.ALBUM_IMPORTS_ID`).
6. Leaving the screen, locking the vault or sending the app to the background stops advertising and cancels any transfer.

## Existing code to reuse (read these first)

All paths are under `app/src/main/java/com/secretvault/app/`.

| What | Where | How file sharing uses it |
|---|---|---|
| Wi-Fi discovery (NSD / DNS-SD) | `core/stream/StreamDiscovery.kt` | Service type is hard-coded to `_svcamera._tcp.` and it parses camera version numbers 2–11. Generalise it in Phase 1; don't copy it. |
| Endpoint data | `core/stream/StreamEndpoint.kt` | Holds host, port, TLS fingerprint, session ID and name. Its capability flags are camera-only, so keep them `false` for share endpoints. |
| TLS server and client with certificate pinning | `core/stream/StreamTls.kt` | The receiver calls `StreamTls.listen(address, pinManager)`. The sender calls `StreamTls.connect(endpoint, pin)`. One peer per `Host`, which suits us. |
| PIN pairing (J-PAKE, never sends the PIN) | `core/stream/StreamPairing.kt` | Used automatically by `StreamTls`. Add a purpose string in Phase 1 so a share pairing can't be mistaken for a camera pairing. |
| Streaming PIN storage and retry cooldown | `core/stream/StreamPinManager.kt` | Reused as is. It allows 5 wrong tries, then a 30 s cooldown. |
| How a host picks its Wi-Fi address | `core/stream/StreamSession.kt` (search for `StreamTls.listen`) | Reuse the same address selection. |
| Vault encryption | `core/crypto/VaultCryptoEngine.kt` | Sender: `decryptStream(input, output)` and `getPlaintextSize(file)`. Receiver: `createEncryptingOutputStream(FileOutputStream(encFile))`. |
| Building thumbnails and metadata from received files | `core/backup/BackupImportManager.kt` (stage 2, around lines 190–290) | This is the same job. Move that code into a shared helper and call it from both places. Don't duplicate it. |
| Saving the database row | `mediaRepository.insertMedia(item)`; see `core/worker/MediaSaveQueue.saveVideo` | Same file layout: `filesDir/vault_media/<id>.enc` and `filesDir/vault_thumbs/<id><GALLERY_THUMBNAIL_SUFFIX>`. |
| Gallery multi-select Share | `ui/gallery/GalleryScreen.kt` (around line 370, `shareMultiple`) | Add **Send to SV device** next to it. |
| Home entry for streaming | `ui/navigation/VaultNavGraph.kt` (`onViewStreamClick`, `Screen.ViewStream`) | Add **Receive files** the same way. |
| Stream screens, for UI patterns | `ui/stream/StreamViewerScreen.kt`, `ui/camera/CameraScreen.kt` | Device list, PIN entry dialog, lifecycle handling. |

## Architecture

New package `core/transfer/`:

- `TransferProtocol.kt` contains only reading, writing and validating messages. It has no Android types, so it can be unit tested.
- `TransferReceiver.kt` advertises, accepts one sender, asks the UI to accept, writes encrypted files and inserts rows. It exposes a `StateFlow<TransferState>`.
- `TransferSender.kt` connects with a PIN and streams decrypted vault items into the socket. It exposes a `StateFlow<TransferState>`.
- `TransferState` covers Idle, Waiting, AwaitingAccept(count, bytes, peerName), Transferring(index, count, bytesDone, bytesTotal, name), Done(count), Failed(message) and Cancelled.

New UI, in `ui/transfer/`: `ReceiveScreen.kt` and `SendScreen.kt`. The send screen holds the device picker, PIN entry and progress.

Run network and crypto work on `Dispatchers.IO`. Hold a partial wake lock while transferring, the same way `MediaSaveQueue.acquireWakeLock` does. Keep the screen on while either screen is open.

## Wire protocol (version 1)

This runs inside the TLS socket returned by `StreamTls` after PIN pairing succeeds. It uses big-endian `DataInputStream` / `DataOutputStream`. S is the sender and R is the receiver.

```
S → R  Offer
        int    magic       = 0x53564631  ("SVF1")
        byte   version     = 1
        int    itemCount   1..500
        long   totalBytes  sum of item sizes, 0..(1 TiB)
        UTF    senderName  1..64 chars, no control chars (shown in the receiver's accept dialog)
R → S  byte   decision     1 = accept, 0 = decline (user said no, timeout, or not enough free space)

repeat itemCount times:
S → R  ItemHeader
        byte   type        0 = photo, 1 = video
        UTF    mimeType    lowercase `image/...` or `video/...` (max 54 chars), matching the type byte. Imported items can be any image or video type, so there is no fixed list.
        UTF    name        1..128 chars, no '/', '\\', control chars or leading '.'
        long   size        1..(16 GiB) plaintext bytes
        long   createdAt   epoch ms, ≥ 0
        long   durationMs  ≥ 0, 0 for photos
        int    width, int height   0..20000
S → R  exactly `size` bytes of plaintext
S → R  32 bytes: SHA-256 of that plaintext
R → S  byte   result       1 = saved, 0 = failed (hash or size mismatch, disk full); on 0 both sides end the session

S → R  int    magic       = 0x53564645  ("SVFE"), meaning end of transfer
R → S  byte   1           final acknowledgement, then both close
```

Rules:
- **Validate before reading:** check every field before acting on it. Throw `IOException("Invalid transfer message")` for anything out of range, like the stream code does in `StreamProtocol`.
- **Never trust the sender's name:** the receiver makes its own file ID and on-disk name, the same way `MediaSaveQueue` does. `name` is only used as `originalName`.
- **Free space:** the receiver declines the offer if `totalBytes × 1.1` is more than `filesDir.usableSpace`.
- **Socket timeouts:**
  - `StreamTls` leaves `soTimeout = 3000` in place after pairing.
  - The sender sets 70 s while waiting for the accept decision, then 20 s during data transfer.
  - The receiver uses 20 s throughout.
- **Cancel:** either side just closes the socket. The receiver deletes the partly written `.enc` and thumbnail of the current item. Completed items stay.

## Security rules (must not be weakened)

- **Plaintext in memory only:** it may exist only in memory buffers and inside the TLS socket. The one exception is the temporary video file the import code already uses for `MediaMetadataRetriever`. Wipe it right after, the way `BackupImportManager` does.
- **No secrets in discovery:** the NSD advertisement carries only what streaming already carries (version, session ID, TLS fingerprint, name, "PIN required" flag). Never the PIN or vault data.
- **Unlocked vault on both sides:** both phones must be unlocked (`sessionManager.isUnlocked`). Locking cancels the transfer.
- **PIN required:** the receiver always requires a PIN. If `StreamPinManager.isPinRequired()` is off, still require it for receiving, because open receiving isn't allowed.
- **One sender at a time:** a second sender trying to connect is rejected. `StreamTls.Host` already enforces one peer.
- **No sensitive logs:** don't log file names, sizes per item, hashes or the PIN.

## Phases

Each phase should build (`./gradlew assembleDebug`) and keep the unit tests passing before you move on. There is 1 known, unrelated unit-test failure: `CrossCompatibilityTest` uses a hard-coded path. Commit after each phase.

### Phase 0: Guide
- [x] Write this spec and agree the version 1 decisions with the user.

### Phase 1: Shared plumbing
- [x] Give `StreamDiscovery` a service-type parameter. The default stays `_svcamera._tcp.`. Use `_svshare._tcp.` for receivers and advertise `v = "s1"`. Camera discovery must behave exactly as before.
- [x] Give `StreamPairing.authenticate` a `purpose` parameter.
  - The default is the current `"SV-stream-v2"` binding, so camera streaming stays byte-for-byte compatible with older builds.
  - Use `"SV-share-v1"` for sharing, and roles `"receiver"` / `"sender"` instead of `"camera"` / `"viewer"`.
  - Pass `purpose` through `StreamTls.listen` / `connect`.
- [x] Unit tests: pairing succeeds with matching purpose and fails with mismatched purpose. The existing stream pairing tests still pass.

### Phase 2: Transfer protocol
- [x] `core/transfer/TransferProtocol.kt`: data classes `Offer` and `ItemHeader`, plus read and write functions for each message in the wire protocol.
- [x] Validation exactly as specified: limits, mime pattern, name rules.
- [x] Unit tests (`app/src/test/java/com/secretvault/app/transfer/TransferProtocolTest.kt`): round trip of every message; every out-of-range field rejected; truncated input throws; a name with `/` or control characters is rejected.

### Phase 3: Receiver engine
- [x] Extract the thumbnail and metadata code from `BackupImportManager` stage 2 into a shared helper. Done as `writeEncryptedPreview()` and `secureWipeFile()` in `core/image/EncryptedMediaPreview.kt`. The backup import must behave the same after the change.
- [x] `TransferReceiver`:
  - `start()`: pick the Wi-Fi address like `StreamSession` does, call `StreamTls.listen(addr, pinManager, purpose = share)`, then advertise `_svshare._tcp.`.
  - Accept loop: take one sender, read the Offer, then expose `AwaitingAccept` and wait for the UI's `accept()` or `decline()`, or 60 s.
  - Per item: wrap the socket input so it reads exactly `size` bytes, then feed it through a SHA-256 digest into `cryptoEngine.createEncryptingOutputStream` writing `vault_media/<newId>.enc`. Verify the hash, run the finalizer to make the thumbnail and dimensions, then `insertMedia` into `ALBUM_IMPORTS_ID`.
  - Failure or cancel: delete the current item's `.enc` and thumbnail files, close everything, and stop advertising.
- [ ] *(Deferred to Phase 7.)* Instrumentation or unit test with a local socket pair, if practical: 2 items in, 2 rows out, and a bad hash leaves no files behind. The receiver needs a real Keystore and Wi-Fi, so this is covered by the two-phone test.

### Phase 4: Sender engine
- [x] `TransferSender`:
  - `search()` uses `StreamDiscovery` with the share type.
  - `send(endpoint, pin, items)` calls `StreamTls.connect(endpoint, pin, purpose = share)`, then sends the Offer and waits for the decision.
  - Per item: get the size from `getPlaintextSize`, then run `decryptStream` into a non-closing wrapper around the socket output that also updates the SHA-256 digest. Send the hash and read the result.
- [x] Progress is reported in bytes. `cancel()` closes the socket.
- [x] Wrong PIN gives the readable message "Wrong PIN". The cooldown gives "Too many attempts, try again in 30 s". Reuse the messages streaming shows.

### Phase 5: UI
- [x] Home: **Receive files** entry next to View stream, with a new `Screen.ReceiveFiles` route in `VaultNavGraph`.
- [x] `ReceiveScreen`: set the PIN if missing, show the waiting state and phone name, the Accept/Decline dialog, progress, the result, and Stop.
- [x] Gallery multi-select: **Send to SV device** action that opens `SendScreen` with the selected IDs.
- [x] `SendScreen`: device list with refresh, PIN entry, progress, cancel, and the result.
- [x] Strings are short and plain. Buttons are at least 48 dp. Content descriptions are set.

### Phase 6: Lifecycle and safety
- [x] Backgrounding the app, locking the vault, or leaving either screen cancels the transfer and stops advertising. This uses `StopWhenHidden` in `TransferScreens.kt`. On lock, the nav graph swaps the screen out and `onDispose` closes the engine.
- [x] Keep-screen-on while a transfer screen is open. No partial wake lock: backgrounding cancels the transfer, so the screen is always on while a transfer runs. Add one only if transfers ever continue in the background.
- [x] No plaintext files remain after a cancelled video transfer. The video thumbnail temp file is wiped in `finally`, and the receiver wipes `vault_staging/transfer` on every start in case the process was killed. *Still to confirm on device in Phase 7.*

### Phase 7: Device testing and docs
- [ ] Install as the `.debug` test copy only. **Never** reinstall or uninstall `com.secretvault.app` on the user's phone, because that wipes their vault. See "Test installs" below.
- [ ] Test on two phones, in both directions:
  - 1 photo
  - 20 photos
  - a video over 1 GB
  - cancel from each side mid-transfer
  - wrong PIN, then 5 wrong PINs to trigger the cooldown
  - decline
  - let the accept dialog time out
  - receiver low on storage
  - Wi-Fi turned off mid-transfer
- [ ] Measure and record the transfer speed in MB/s.
- [ ] Update `SecretVault-Features.md` and `CHANGELOG.md`.

## Test installs (important)

The user's phone runs the real vault `com.secretvault.app`, signed with a key from another machine. To install a test build alongside it:

```bash
sed -i 's/^\(\s*\)versionNameSuffix = "-camera-stream-test"/&\n\1applicationIdSuffix = ".debug"/' app/build.gradle.kts
./gradlew -q assembleDebug
git checkout -- app/build.gradle.kts
adb install -r app/build/outputs/apk/debug/SecretVault-v1.1.1-camera-stream-test-debug.apk
```

Never commit `applicationIdSuffix`. Testing a transfer needs two phones with this build installed.

## Progress log

- 2026-10-04: Phase 0 done. Spec written; branch `file-share` created from `main`.
- 2026-10-04: Phase 1 done. `StreamDiscovery(context, share = true)` uses `_svshare._tcp.` and `v=s1`. `PairingPurpose.SHARE` is passed via `StreamTls.listen(..., purpose)` and `StreamTls.connect(endpoint, pin, ..., purpose = ...)`, and SHARE hosts always require the PIN. `StreamPairingTest.purposeMustMatchOnBothSides` covers it.
- 2026-10-04: Phase 2 done. `core/transfer/TransferProtocol.kt` (messages, validation and the `exactly(input, size)` reader) with 6 tests in `TransferProtocolTest`.
- 2026-10-04: Phases 3 and 4 done (builds; not yet run on devices).
  - `TransferReceiver(context, cryptoEngine, mediaRepository, StreamPinManager)` exposes `start()`, `accept()`, `decline()`, `stop()` and `state`. It receives one sender per `start()`.
  - `TransferSender(context, cryptoEngine)` exposes `discovery`, `send(endpoint, pin, items)`, `cancel()`, `reset()` and `state`.
  - Both report `TransferState`.
  - The Offer now carries `senderName`.
  - `TransferProtocol.safeName` and `safeMime` clean sender data.
  - `shareWifi(context)` gives the Wi-Fi network and address.
- 2026-10-04: Phases 5 and 6 done (builds; 128 unit tests, only the known `CrossCompatibilityTest` fails).
  - The home screen has a **Receive files** button (inbox icon), below View stream.
  - Gallery multi-select has **Send to SV**. Selected IDs pass through `savedStateHandle` (`SEND_IDS_KEY` in `VaultNavGraph`).
  - Screens live in `ui/transfer/TransferScreens.kt`.
  - `StreamPinDialog` gained an optional `message`.
  - Next: Phase 7, the two-phone test.
