package com.secretvault.app.core.player

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.secretvault.app.core.crypto.VaultCryptoEngine
import java.io.File

/**
 * Random-access view of an encrypted vault video for [android.media.MediaMetadataRetriever],
 * so timeline frames can be read without writing plaintext to disk.
 */
@OptIn(UnstableApi::class)
class DecryptingMediaDataSource(cryptoEngine: VaultCryptoEngine, file: File) : MediaDataSource() {
    private val uri = Uri.fromFile(file)
    private val source = EncryptedMediaDataSource(cryptoEngine)
    private val totalSize = source.open(DataSpec(uri))
    private var position = 0L

    override fun getSize(): Long = totalSize

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= totalSize) return -1
        if (position != this.position) {
            source.close()
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            this.position = position
        }
        val read = source.read(buffer, offset, size)
        if (read == C.RESULT_END_OF_INPUT) return -1
        this.position += read
        return read
    }

    @Synchronized
    override fun close() = source.close()
}
