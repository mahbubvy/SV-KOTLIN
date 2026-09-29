# `.svbackup` (SVBACK01) Export Architecture & Interoperability Specification

## 1. Overview & Threat Model

The `.svbackup` file format (`SVBACK01`) is a streamable, authenticated, encrypted backup container designed for **Secret Vault**. It allows exporting vault content (albums, photos, videos, and metadata) into a single encrypted file protected by a user-supplied **Backup Password**.

### Security Guarantees
1. **Confidentiality:** All payloads (manifest metadata, file names, album structure, media bytes, and summaries) are encrypted with **AES-256-GCM**.
2. **Cryptographic Tag Chaining:** Records are cryptographically linked using Additional Authenticated Data (AAD). Modifying, reordering, skipping, injecting, or truncating any record instantly fails GCM authentication.
3. **Bounded Memory & Streaming:** Designed to handle multi-gigabyte vaults with fixed memory (~256 KiB buffer). Plaintext is never loaded in full into memory and never written unencrypted to disk.
4. **Separation of Secrets:** The backup encryption key is derived strictly from the **Backup Password** and a per-archive random salt. It is completely independent of the device's vault PIN, biometric master key, or local `.enc`/`.svenc` media keys.

---

## 2. High-Level Export Flow in SecretVault

The following diagram illustrates how SecretVault produces a `.svbackup` file from start to finish:

```
[User Selects Backup Scope & Password]
                   │
                   ▼
  1. Scope Filtering & Plaintext Calculation
     - Resolves albums, media items, and cover photos
     - Computes exact unencrypted plaintext size in O(1)
                   │
                   ▼
  2. Archive Header Generation
     - Generates 16-byte random salt
     - Writes 32-byte header: "SVBACK01" + 600,000 + 256 KiB + Salt
     - Derives 256-bit AES key via PBKDF2-HMAC-SHA256
                   │
                   ▼
  3. Write Record 0: Manifest (Type 1)
     - Serializes albums & items to UTF-8 JSON
     - Encrypts under AES-256-GCM (nonce = index 0, AAD with 16 zero-bytes tag)
     - Updates PreviousTag = Tag_0
                   │
                   ▼
  4. Stream Records 1..N: Media Chunks (Type 2)
     - Iterates through manifest.items in exact manifest order
     - Decrypts local .enc file in 64 KiB chunks
     - Gathers into 256 KiB plaintext buffer
     - Encrypts under AES-256-GCM (nonce = recordIndex, AAD with PreviousTag)
     - Writes [RecordHeader(9B)] + [Ciphertext + Tag(16B)]
     - Updates PreviousTag = Tag_N
                   │
                   ▼
  5. Write Record N+1: Final Summary (Type 3)
     - Serializes {"items": N, "bytes": TotalPlaintextBytes}
     - Encrypts under AES-256-GCM (nonce = recordIndex, AAD with PreviousTag)
                   │
                   ▼
  6. Final Flush & Memory Zeroization
     - Flushes output stream to Storage Access Framework URI
     - Securely wipes derived key and byte buffers from memory
```

---

## 3. Wire Format Specification

A `.svbackup` archive consists of a **32-byte File Header**, followed by a sequential series of **Framed Records**, terminating immediately with no outer envelope.

### 3.1. File Header (Exactly 32 Bytes)

| Offset | Length | Field | Type | Description |
|:---|:---|:---|:---|:---|
| `0` | 8 | Magic Bytes | ASCII | Literal `"SVBACK01"` |
| `8` | 4 | PBKDF2 Iterations | UInt32 BE | `600000` (600,000 iterations) |
| `12` | 4 | Chunk Size | UInt32 BE | `262144` bytes (256 KiB) |
| `16` | 16 | Salt | Binary | 16 cryptographically secure random bytes |

### 3.2. Record Framing

Every record in the archive (Manifest, Media Chunk, and Final Summary) follows this exact binary layout:

```
┌─────────────────┬──────────────────┬─────────────────────────────┬──────────────────────────┐
│ Type (1 byte)   │ Index (4 bytes)  │ Plaintext Length (4 bytes)  │ Sealed Bytes (Len + 16B) │
└─────────────────┴──────────────────┴─────────────────────────────┴──────────────────────────┘
  ◄───────────────── Record Header (9 bytes) ───────────────────►   ◄── Ciphertext + GCM Tag ─►
```

- **Type (1 byte):**
  - `0x01` = **Manifest** (always Record `0`)
  - `0x02` = **Media Chunk** (Records `1` through `N`)
  - `0x03` = **Final Summary** (Record `N + 1`)
- **Index (4 bytes UInt32 BE):** Zero-based sequential record counter (`0, 1, 2, ...`). Must strictly increment by 1. Gaps or duplicates must cause immediate rejection.
- **Plaintext Length (4 bytes UInt32 BE):** Exact unencrypted payload size in bytes (excludes the 16-byte GCM authentication tag).
- **Sealed Bytes (`Length + 16` bytes):** AES-GCM ciphertext followed by the 16-byte GCM authentication tag.

---

## 4. Cryptographic Wire Contract

### 4.1. Key Derivation (PBKDF2-HMAC-SHA256)
- **Input Password:** UTF-8 bytes of user password (without trimming or normalization).
- **Salt:** 16-byte random salt extracted from header offset `16..31`.
- **Iterations:** `600,000`.
- **Output Key:** 256 bits (32 bytes).
- Standard Java/Kotlin: `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.

### 4.2. Nonce Construction (12 Bytes)
For record with sequence index `i`:
```
Nonce = [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00] || UInt32BE(i)
```
- Exactly 8 zero-bytes followed by the 4-byte big-endian record index `i`.

### 4.3. Additional Authenticated Data (AAD) & Tag Chaining (57 Bytes)
To prevent record deletion, insertion, or reordering, every record's AES-GCM cipher binds the archive header, the current record header, and the previous record's authentication tag:

```
AAD = Header[32 bytes] || RecordHeader[9 bytes] || PreviousTag[16 bytes]
```
- **For Record 0:** `PreviousTag` is 16 zero-bytes (`0x00 * 16`).
- **For Record `i > 0`:** `PreviousTag` is the 16-byte GCM tag from Record `i - 1`.

---

## 5. Record Payloads & Schemas

### 5.1. Record 0: Manifest Payload (`type = 1`)
A single UTF-8 JSON object (maximum size: 16 MiB).

```json
{
  "version": 1,
  "createdAt": "2026-09-26T13:45:00.000Z",
  "albums": [
    {
      "id": "camera",
      "name": "Camera",
      "kind": "system",
      "createdAt": "2026-09-26T10:00:00.000Z",
      "coverItemId": "d3b07384-d113-469b-8218-12e964b4c73b"
    },
    {
      "id": "c1f7a149-16ec-4c6e-8ff5-827a3c79aef1",
      "name": "Vacation 2026",
      "kind": "custom",
      "createdAt": "2026-09-26T12:00:00.000Z",
      "coverItemId": null
    }
  ],
  "items": [
    {
      "id": "d3b07384-d113-469b-8218-12e964b4c73b",
      "albumId": "camera",
      "originalFilename": "IMG_20260926_1345.jpg",
      "mediaType": "photo",
      "mimeType": "image/jpeg",
      "size": 3145728,
      "createdAt": "2026-09-26T13:45:00.000Z"
    }
  ]
}
```

#### Field Specifications:
- `albums[].id`: Unique string identifier (e.g. `"camera"`, `"imports"`, or UUID).
- `albums[].kind`: `"system"` for built-in folders, `"custom"` for user folders.
- `albums[].coverItemId`: Optional UUID of an item in that album used as cover artwork. Must be a photo item in the same album.
- `items[].id`: UUID string.
- `items[].size`: **CRITICAL:** Must be the **exact unencrypted plaintext size** in bytes.
- `items[].mediaType`: `"photo"` or `"video"`.

### 5.2. Records 1..N: Media Chunks (`type = 2`)
- Media items are streamed in the **exact sequential order** defined in `manifest.items`.
- For an item with `size` bytes:
  - If `size == 0`: 0 chunks are written.
  - If `size > 0`: `ceil(size / 262144)` chunks are written.
- Every chunk except the last chunk for that item is exactly 262,144 bytes (256 KiB). The last chunk contains the remaining `size % 262144` bytes (or 256 KiB if divisible).

### 5.3. Record N+1: Final Summary Payload (`type = 3`)
A UTF-8 JSON object:
```json
{
  "items": 1,
  "bytes": 3145728
}
```
- `items`: Must match `manifest.items.size`.
- `bytes`: Must match the sum of all `item.size` fields in the manifest and the total media bytes written.
- **EOF:** After the final summary record, the stream ends immediately. Trailing bytes indicate a corrupted or tampered file and must be rejected.

---

## 6. SecretVault Specifics: Plaintext vs Ciphertext Size

In local SecretVault storage, media files on disk are stored encrypted using AES-GCM chunk framing (`ChunkedCipherStream`):
- 21-byte container header.
- 64 KiB chunks with a 20-byte framing overhead (4B length + 16B tag) per chunk.
- Local Room DB `MediaEntity.sizeBytes` records the **on-disk ciphertext size** (`encFile.length()`).

### Plaintext Size Calculation ($O(1)$)
When exporting, `BackupExportManager` calculates the exact plaintext size using `cryptoEngine.calculatePlaintextSize(encFile)`:
```kotlin
fun calculatePlaintextSize(encFile: File): Long {
    val totalEncryptedSize = encFile.length()
    val headerSize = 21L
    val chunkOverhead = 20L // 4-byte length + 16-byte tag
    val localChunkSize = 65536L // 64 KiB

    val payloadEncrypted = totalEncryptedSize - headerSize
    val fullChunkCiphertext = localChunkSize + chunkOverhead // 65556
    val fullChunks = payloadEncrypted / fullChunkCiphertext
    val remainder = payloadEncrypted % fullChunkCiphertext

    val remainderPlaintext = if (remainder > chunkOverhead) remainder - chunkOverhead else 0L
    return (fullChunks * localChunkSize) + remainderPlaintext
}
```
This ensures that the manifest `item.size` exactly equals the decrypted plaintext stream, completely preventing "export inconsistency" errors.

---

## 7. How to Support Exported Archives in Another Vault App

To support `.svbackup` files in your other SecretVault app, follow this step-by-step implementation guide:

### Step 1: Add Low-Level Crypto Primitives
Copy `SvBackupCrypto` into your other app:
```kotlin
object SvBackupCrypto {
    val MAGIC = "SVBACK01".toByteArray(Charsets.US_ASCII)
    const val HEADER_SIZE = 32
    const val RECORD_HEADER_SIZE = 9
    const val TAG_SIZE = 16
    const val NONCE_SIZE = 12
    const val AAD_SIZE = 57 // 32 + 9 + 16
    const val CHUNK_SIZE = 262144 // 256 KiB
    const val EXPECTED_ITERATIONS = 600000

    const val TYPE_MANIFEST = 1
    const val TYPE_MEDIA_CHUNK = 2
    const val TYPE_FINAL_SUMMARY = 3

    fun parseAndValidateHeader(header: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "Invalid header size" }
        require(header.copyOfRange(0, 8).contentEquals(MAGIC)) { "Not SVBACK01" }
        val buf = java.nio.ByteBuffer.wrap(header)
        require(buf.getInt(8) == EXPECTED_ITERATIONS) { "Unsupported iterations" }
        require(buf.getInt(12) == CHUNK_SIZE) { "Unsupported chunk size" }
        return header.copyOfRange(16, 32) // Returns 16-byte salt
    }

    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, EXPECTED_ITERATIONS, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun buildNonce(index: Int): ByteArray {
        return java.nio.ByteBuffer.allocate(12).putLong(0L).putInt(index).array()
    }

    fun buildAad(header: ByteArray, recordHeader: ByteArray, previousTag: ByteArray): ByteArray {
        val aad = ByteArray(AAD_SIZE)
        System.arraycopy(header, 0, aad, 0, 32)
        System.arraycopy(recordHeader, 0, aad, 32, 9)
        System.arraycopy(previousTag, 0, aad, 41, 16)
        return aad
    }

    fun decryptRecord(key: ByteArray, index: Int, aad: ByteArray, sealedBytes: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(128, buildNonce(index))
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(sealedBytes)
    }
}
```

### Step 2: Implement Pre-Restore Inspection
Before restoring files, your other app can inspect Record 0 in $O(1)$ to show the user what is inside the backup:
1. Read the 32-byte header.
2. Read the 9-byte record header for Record 0.
3. Decrypt Record 0 with `PreviousTag = ByteArray(16)`.
4. Parse the JSON manifest to display:
   - Creation Date
   - Number of albums
   - Number of photos & videos
   - Total payload size in MB

### Step 3: Implement Streaming Import
When the user confirms the restore:
1. Create a consumer callback that receives decrypted 256 KiB chunks:
   ```kotlin
   fun onMediaChunk(item: SvBackupItem, chunkBytes: ByteArray) {
       // Pipe chunkBytes directly into your other app's local encryption engine!
       // NEVER write chunkBytes as plaintext to disk.
   }
   ```
2. Verify Record `N+1` (Final Summary) matches the actual items and bytes consumed.
3. Verify `inputStream.read() == -1` (True EOF).
4. Atomically commit the new albums and media rows into your other app's database.

---

## 8. Summary Checklist for Full Interoperability

| Requirement | Specification |
|:---|:---|
| **Magic Header** | `SVBACK01` (8 bytes ASCII) |
| **KDF** | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt |
| **Cipher** | AES-256-GCM (`AES/GCM/NoPadding`, 128-bit authentication tag) |
| **Chunk Size** | 262,144 bytes (256 KiB) |
| **Nonce** | 8 zero bytes + 4-byte UInt32 BE record index |
| **AAD** | 32-byte Header + 9-byte RecordHeader + 16-byte PreviousTag |
| **Manifest Item Size** | Exact unencrypted plaintext byte count |
| **Media Order** | Strictly matches `manifest.items` sequence |
| **Final Record** | Authenticated summary JSON `{"items": N, "bytes": TOTAL}` |
| **EOF** | Zero trailing bytes allowed |

---

## 9. SecretVault Importer Behavior (Reference Semantics)

The Kotlin importer should preserve these user-visible restore semantics while using the Kotlin app's own database and media-encryption implementation. Match the `.svbackup` v1 format exactly; do not copy SecretVault's Expo-specific APIs.

### 9.1 Import and verification order

1. Let the user choose a `.svbackup` document through Android's Storage Access Framework. Treat the returned `content://` URI and every byte/field as untrusted. Do not request broad storage permission for this picker.
2. Ask for the backup password after selection. Use its exact entered value; do not trim or normalize it, and do not save it.
3. Check the selected archive is readable and plausibly complete, then ensure there is enough private storage for the encrypted archive copy and staged destination media. SecretVault currently copies the selected archive as ciphertext to app-private cache before parsing because some document providers have unreliable seek/read behavior. This is an implementation workaround, not a format requirement; a robust streaming SAF reader can avoid the extra archive copy.
4. Parse and authenticate records in order. Validate header, record type/index/length, GCM tag chain, manifest, media byte counts, final summary, and strict EOF. Never publish metadata or files before the final summary and EOF pass.
5. For each media item, stream authenticated plaintext into the destination app's own media-encryption writer. SecretVault does not save imported plaintext to disk. It stages newly encrypted media in app-private temporary storage, computes a content fingerprint while streaming, and reads/decrypts the staged encrypted file again to verify the fingerprint.
6. Only after the complete archive passes verification, merge albums and resolve duplicates, then publish new media and metadata. On failure or cancellation, remove only this attempt's staging files and keep the existing vault unchanged. Use a transaction or a durable publication journal so a process death during file moves can be recovered without deleting pre-existing content.

### 9.2 Merge and duplicate policy

- Restore is a **merge**, not a replace: existing albums and items remain.
- Album matching first uses equal source/destination `id` **and** equal `kind`. If that does not match, SecretVault matches album names after trimming and case-insensitive comparison. Existing matched album properties/covers are retained.
- An unmatched imported album is added with a newly generated destination ID and `kind = "custom"`; its display name and creation date are preserved. Empty albums are restored too.
- Media IDs from the archive are source references, not destination storage IDs. Generate fresh destination media IDs and app-owned storage filenames.
- Duplicate media is detected by full plaintext content fingerprint plus `mediaType` and exact plaintext `size`; archive ID and filename are not duplicate keys. Matching items are skipped, not overwritten. This is a byte-content duplicate policy, not a perceptual photo comparison.
- Resolve cover references through the source-item-to-destination-item mapping, including when the cover media was skipped as a duplicate. Apply an imported cover only when the matched/created destination album has no cover already and the resolved item is a photo belonging to that destination album. Never replace a user's existing cover during restore.
- `originalFilename` remains display metadata only. Generate safe destination filenames independently; never join, normalize, or otherwise interpret the archive value as a filesystem path.

### 9.3 Validation limits and destination-specific behavior

Apply the current SecretVault v1 manifest validation before trusting metadata: version exactly `1`; parseable dates; at most 10,000 albums and 100,000 items; album IDs 1–80 characters; album names nonblank and at most 80 characters; unique album IDs and trimmed case-insensitive names; system albums restricted to recognized ID/name pairs; UUID-shaped unique item IDs; every item references an album; media type is `photo` or `video`; filename is 1–512 characters; MIME string at most 255 characters; nonnegative safe-integer plaintext sizes and safe total; cover references a photo in the same source album. The Kotlin app may impose stricter destination limits, but must reject safely and explain the limitation rather than silently dropping or changing archive data.

SecretVault re-encrypts restored plaintext using its local vault key and local `.svenc` format. The Kotlin app must instead re-encrypt using its own at-rest encryption scheme. The backup password/key is never the destination vault key. Do not import PINs, biometrics, device keys, unlock settings, or other device-specific secrets.

### 9.4 Import acceptance tests

The Kotlin agent should test the following against the receiving app's real database/storage path, not just the parser:

1. Import a SecretVault-produced archive containing a photo, multi-chunk video, custom album, empty album, and album cover; compare plaintext hashes before destination re-encryption and verify playback after re-encryption.
2. Import the same archive again: identical media must be skipped without replacing files; album/cover behavior must follow the rules above.
3. Import an archive where a same-named album exists with a different source ID; confirm it merges by trimmed case-insensitive name and preserves the destination album's current cover.
4. Try wrong password, corrupted tag, malformed/oversized manifest, truncated record, missing final record, trailing bytes, revoked URI, low storage, cancellation, injected write failure, and process termination around publication. No partial item may become visible and no pre-existing item may be deleted.
5. Repeat with passwords containing leading/trailing spaces and non-ASCII characters. Compare PBKDF2 output and successful cross-app decryption with golden vectors; do not assume `PBEKeySpec` is compatible without this test.

Do not treat this section as a requirement to reproduce SecretVault's internal data model, UI, cache layout, or implementation bugs. The compatibility requirements are the exact archive contract, merge/duplicate semantics, safe failure behavior, and equivalent user-visible results.
