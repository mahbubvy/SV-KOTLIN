package com.secretvault.app.core.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Manages ephemeral, single-use sharing of encrypted media.
 * Temporarily decrypts media files into a private cache directory exposed via FileProvider,
 * launches the Android Share Chooser, and automatically wipes and deletes decrypted files
 * after an auto-destruction timeout or when the app is backgrounded/locked.
 */
class EphemeralShareManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeAutoDestructJobs = mutableListOf<Job>()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _shareProgress = MutableStateFlow(0f)
    val shareProgress: StateFlow<Float> = _shareProgress.asStateFlow()

    private val _shareStatusText = MutableStateFlow("")
    val shareStatusText: StateFlow<String> = _shareStatusText.asStateFlow()

    private val sharedDir: File
        get() = File(context.cacheDir, "vault_shared").apply { mkdirs() }

    private var currentShareJob: Job? = null

    /**
     * Cancels any in-progress decryption/share operation and purges temporary files.
     */
    fun cancelShare() {
        currentShareJob?.cancel()
        currentShareJob = null
        _isSharing.value = false
        _shareProgress.value = 0f
        _shareStatusText.value = ""
        purgeAllSharedFiles()
    }

    /**
     * Decrypts a single [MediaItem] ephemerally, launches the share intent,
     * and schedules secure destruction in [autoDestructDelayMs] (default 10 minutes).
     */
    fun shareItem(
        activity: Activity,
        item: MediaItem,
        autoDestructDelayMs: Long = 10 * 60_000L
    ) {
        currentShareJob?.cancel()
        currentShareJob = scope.launch {
            try {
                _isSharing.value = true
                _shareStatusText.value = if (item.mediaType == MediaType.VIDEO) "Preparing video..." else "Preparing photo..."
                _shareProgress.value = 0.05f

                val decryptedFile = decryptToSharedCache(item) { progress ->
                    _shareProgress.value = (0.05f + progress * 0.9f).coerceIn(0f, 1f)
                }

                if (decryptedFile == null) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    return@launch
                }

                val uri = getFileProviderUri(decryptedFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = item.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(decryptedFile.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share securely").apply {
                    clipData = ClipData.newRawUri(decryptedFile.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    try {
                        activity.startActivity(chooser)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                scheduleAutoDestruction(decryptedFile, autoDestructDelayMs)
            } finally {
                if (currentShareJob?.isCancelled == true) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    _shareStatusText.value = ""
                }
            }
        }
    }

    /**
     * Decrypts multiple [MediaItem]s ephemerally, launches the multi-share intent,
     * and schedules secure destruction.
     */
    fun shareMultiple(
        activity: Activity,
        items: List<MediaItem>,
        autoDestructDelayMs: Long = 10 * 60_000L
    ) {
        if (items.isEmpty()) return
        currentShareJob?.cancel()
        currentShareJob = scope.launch {
            try {
                _isSharing.value = true
                _shareProgress.value = 0.05f

                val decryptedFiles = mutableListOf<File>()
                val uris = ArrayList<Uri>()
                val total = items.size

                for ((index, item) in items.withIndex()) {
                    _shareStatusText.value = "Preparing ${index + 1} of $total..."
                    val decFile = decryptToSharedCache(item) { itemProg ->
                        val overall = (index + itemProg) / total.toFloat()
                        _shareProgress.value = overall.coerceIn(0f, 1f)
                    }
                    if (decFile != null) {
                        decryptedFiles.add(decFile)
                        uris.add(getFileProviderUri(decFile))
                    }
                }

                if (uris.isEmpty()) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    return@launch
                }

                val mimeType = if (items.all { it.mediaType == items.first().mediaType }) {
                    items.first().mimeType
                } else {
                    "*/*"
                }

                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    if (uris.isNotEmpty()) {
                        val clip = ClipData.newRawUri(decryptedFiles.first().name, uris.first())
                        for (i in 1 until uris.size) {
                            clip.addItem(ClipData.Item(uris[i]))
                        }
                        this.clipData = clip
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share ${uris.size} items securely").apply {
                    if (shareIntent.clipData != null) {
                        this.clipData = shareIntent.clipData
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    try {
                        activity.startActivity(chooser)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                for (file in decryptedFiles) {
                    scheduleAutoDestruction(file, autoDestructDelayMs)
                }
            } finally {
                if (currentShareJob?.isCancelled == true) {
                    _isSharing.value = false
                    _shareProgress.value = 0f
                    _shareStatusText.value = ""
                }
            }
        }
    }

    private suspend fun decryptToSharedCache(
        item: MediaItem,
        onProgress: ((Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val encFile = File(item.encryptedPath)
        if (!encFile.exists()) return@withContext null

        val extension = when {
            item.filename.contains(".") -> item.filename.substringAfterLast(".")
            item.mimeType.contains("png") -> "png"
            item.mimeType.contains("mp4") -> "mp4"
            else -> "jpg"
        }

        val baseName = if (item.filename.contains(".")) {
            item.filename.substringBeforeLast(".")
        } else {
            "SV_${item.id.take(8)}"
        }
        val tempName = "${baseName}.$extension"
        val destFile = File(sharedDir, tempName)

        try {
            cryptoEngine.decryptFile(encFile, destFile, onProgress = onProgress)
            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            if (destFile.exists()) destFile.delete()
            null
        }
    }

    private fun getFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun scheduleAutoDestruction(file: File, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            secureWipeAndDelete(file)
        }
        synchronized(activeAutoDestructJobs) {
            activeAutoDestructJobs.add(job)
        }
    }

    /**
     * Instantly and cryptographically purges all temporary shared files,
     * zeroing out their contents on disk before deleting.
     */
    fun purgeAllSharedFiles() {
        scope.launch {
            val files = sharedDir.listFiles() ?: return@launch
            for (file in files) {
                secureWipeAndDelete(file)
            }
            synchronized(activeAutoDestructJobs) {
                activeAutoDestructJobs.forEach { it.cancel() }
                activeAutoDestructJobs.clear()
            }
        }
    }

    /**
     * Overwrites file contents with zeros to prevent data recovery from flash memory, then deletes.
     */
    private fun secureWipeAndDelete(file: File) {
        try {
            if (file.exists() && file.isFile) {
                val length = file.length()
                if (length > 0) {
                    RandomAccessFile(file, "rws").use { raf ->
                        val zeros = ByteArray(4096)
                        var written = 0L
                        while (written < length) {
                            val toWrite = minOf(zeros.size.toLong(), length - written).toInt()
                            raf.write(zeros, 0, toWrite)
                            written += toWrite
                        }
                    }
                }
                file.delete()
            }
        } catch (e: Exception) {
            file.delete()
        }
    }
}
