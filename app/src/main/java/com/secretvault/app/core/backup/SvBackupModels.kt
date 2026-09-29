package com.secretvault.app.core.backup

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Data structures and strict validator for .svbackup v1 manifest.
 * Conforms to the wire contract in vault-backup-manifest.ts.
 */
data class SvBackupAlbum(
    val id: String,
    val name: String,
    val kind: String, // "system" or "custom"
    val createdAt: String,
    val coverItemId: String?
)

data class SvBackupItem(
    val id: String,
    val albumId: String,
    val originalFilename: String,
    val mediaType: String, // "photo" or "video"
    val mimeType: String,
    val size: Long,
    val createdAt: String
)

data class SvBackupManifest(
    val version: Int,
    val createdAt: String,
    val albums: List<SvBackupAlbum>,
    val items: List<SvBackupItem>
) {
    companion object {
        const val SYSTEM_ALBUM_CAMERA = "system-camera"
        const val SYSTEM_ALBUM_IMPORTS = "system-imports"
        const val SYSTEM_ALBUM_UNSORTED = "system-unsorted"

        val SYSTEM_ALBUMS = listOf(
            SYSTEM_ALBUM_CAMERA to "Camera",
            SYSTEM_ALBUM_IMPORTS to "Imports",
            SYSTEM_ALBUM_UNSORTED to "Unsorted"
        )
        val UUID_REGEX = Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)
        private const val MAX_ALBUMS = 10_000
        private const val MAX_ITEMS = 100_000

        fun fromJson(jsonStr: String): SvBackupManifest {
            val root = JSONObject(jsonStr)
            val version = root.optInt("version", -1)
            require(version == 1) { "Unsupported backup manifest version: $version (expected 1)" }

            val createdAt = root.optString("createdAt", "")
            require(isValidDate(createdAt)) { "Invalid manifest createdAt date: $createdAt" }

            val albumsJson = root.optJSONArray("albums") ?: throw IllegalArgumentException("Missing albums array")
            require(albumsJson.length() <= MAX_ALBUMS) { "Too many albums in backup: ${albumsJson.length()}" }

            val albumList = ArrayList<SvBackupAlbum>(albumsJson.length())
            val albumIds = HashSet<String>()
            val albumNames = HashSet<String>()

            for (i in 0 until albumsJson.length()) {
                val a = albumsJson.getJSONObject(i)
                val id = a.optString("id", "")
                require(id.length in 1..80) { "Invalid album id length: ${id.length}" }
                require(albumIds.add(id)) { "Duplicate album id: $id" }

                val name = a.optString("name", "")
                val trimmedName = name.trim()
                require(trimmedName.isNotEmpty() && name.length <= 80) { "Invalid album name: $name" }
                val lowerTrimmed = trimmedName.lowercase()
                require(albumNames.add(lowerTrimmed)) { "Duplicate album name: $name" }

                val kind = a.optString("kind", "")
                require(kind == "system" || kind == "custom") { "Invalid album kind: $kind" }
                if (kind == "system") {
                    require(SYSTEM_ALBUMS.any { it.first == id && it.second == name }) {
                        "System album $id with name $name is not supported in SVBACK01 specification"
                    }
                }

                val albumCreatedAt = a.optString("createdAt", "")
                require(isValidDate(albumCreatedAt)) { "Invalid album createdAt: $albumCreatedAt" }

                val coverItemId = if (a.has("coverItemId") && !a.isNull("coverItemId")) {
                    a.optString("coverItemId")
                } else null

                albumList.add(SvBackupAlbum(id, name, kind, albumCreatedAt, coverItemId))
            }

            val itemsJson = root.optJSONArray("items") ?: throw IllegalArgumentException("Missing items array")
            require(itemsJson.length() <= MAX_ITEMS) { "Too many items in backup: ${itemsJson.length()}" }

            val itemList = ArrayList<SvBackupItem>(itemsJson.length())
            val itemIds = HashSet<String>()
            var totalBytes = 0L

            for (i in 0 until itemsJson.length()) {
                val it = itemsJson.getJSONObject(i)
                val id = it.optString("id", "")
                require(id.isNotEmpty() && id.length <= 80) { "Invalid item id: $id" }
                require(itemIds.add(id)) { "Duplicate item id: $id" }

                val albumId = it.optString("albumId", "")
                require(albumIds.contains(albumId)) { "Item references non-existent album: $albumId" }

                val mediaType = it.optString("mediaType", "")
                require(mediaType == "photo" || mediaType == "video") { "Invalid mediaType: $mediaType" }

                val originalFilename = it.optString("originalFilename", "")
                require(originalFilename.isNotEmpty() && originalFilename.length <= 512) { "Invalid filename length" }
                require(!originalFilename.contains('/') && !originalFilename.contains('\\') && !originalFilename.contains('\u0000')) {
                    "Invalid filename path characters: $originalFilename"
                }

                val mimeType = it.optString("mimeType", "")
                require(mimeType.isNotEmpty() && mimeType.length <= 255) { "Invalid mimeType: $mimeType" }

                val size = it.optLong("size", -1L)
                require(size >= 0L) { "Invalid item size: $size" }
                totalBytes += size
                require(totalBytes >= 0L) { "Total backup size integer overflow" }

                val itemCreatedAt = it.optString("createdAt", "")
                require(isValidDate(itemCreatedAt)) { "Invalid item createdAt: $itemCreatedAt" }

                itemList.add(SvBackupItem(id, albumId, originalFilename, mediaType, mimeType, size, itemCreatedAt))
            }

            // Validate cover references
            val itemMap = itemList.associateBy { it.id }
            for (album in albumList) {
                if (album.coverItemId != null) {
                    val cover = itemMap[album.coverItemId]
                    require(cover != null && cover.mediaType == "photo" && cover.albumId == album.id) {
                        "Invalid album cover reference: ${album.coverItemId} in album ${album.id}"
                    }
                }
            }

            return SvBackupManifest(version, createdAt, albumList, itemList)
        }

        private fun isValidDate(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            return try {
                Instant.parse(candidate)
                true
            } catch (e: DateTimeParseException) {
                false
            }
        }
    }

    /**
     * Calculates the total media bytes declared across all items in the manifest.
     */
    fun totalBytes(): Long {
        return items.fold(0L) { acc, item -> acc + item.size }
    }

    /**
     * Serializes manifest to valid JSON matching the v1 wire specification.
     */
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("createdAt", createdAt)

        val albumsArr = JSONArray()
        for (a in albums) {
            val aObj = JSONObject()
            aObj.put("id", a.id)
            aObj.put("name", a.name)
            aObj.put("kind", a.kind)
            aObj.put("createdAt", a.createdAt)
            if (a.coverItemId != null) {
                aObj.put("coverItemId", a.coverItemId)
            } else {
                aObj.put("coverItemId", JSONObject.NULL)
            }
            albumsArr.put(aObj)
        }
        root.put("albums", albumsArr)

        val itemsArr = JSONArray()
        for (it in items) {
            val itObj = JSONObject()
            itObj.put("id", it.id)
            itObj.put("albumId", it.albumId)
            itObj.put("originalFilename", it.originalFilename)
            itObj.put("mediaType", it.mediaType)
            itObj.put("mimeType", it.mimeType)
            itObj.put("size", it.size)
            itObj.put("createdAt", it.createdAt)
            itemsArr.put(itObj)
        }
        root.put("items", itemsArr)

        return root.toString()
    }
}

data class SvBackupSummary(
    val items: Int,
    val bytes: Long
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("items", items)
        root.put("bytes", bytes)
        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SvBackupSummary {
            val root = JSONObject(jsonStr)
            val items = root.optInt("items", -1)
            val bytes = root.optLong("bytes", -1L)
            require(items >= 0) { "Invalid items count in summary: $items" }
            require(bytes >= 0L) { "Invalid bytes count in summary: $bytes" }
            return SvBackupSummary(items, bytes)
        }
    }
}
