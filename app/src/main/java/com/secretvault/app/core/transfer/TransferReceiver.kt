package com.secretvault.app.core.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.GALLERY_THUMBNAIL_SUFFIX
import com.secretvault.app.core.image.writeEncryptedPreview
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.stream.PairingPurpose
import com.secretvault.app.core.stream.StreamDiscovery
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.core.stream.StreamTls
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLSocket

/** Receives photos and videos from one PIN-paired SV sender into the Imports album. See SPEC-file-share.md. */
class TransferReceiver(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val mediaRepository: MediaRepository,
    private val pins: StreamPinManager
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    val state = mutableState.asStateFlow()
    private val discovery = StreamDiscovery(context, share = true)
    @Volatile private var host: StreamTls.Host? = null
    @Volatile private var decision: CompletableDeferred<Boolean>? = null
    @Volatile private var stopped = false
    private var job: Job? = null

    /** Advertises on Wi-Fi and receives from the first sender that pairs. Ends after one transfer. */
    @Synchronized fun start() {
        if (job?.isActive == true) return
        stopped = false
        job = scope.launch {
            var saved = 0
            var listener: StreamTls.Host? = null
            try {
                pins.readPin()?.fill('\u0000') ?: throw IOException("Set a streaming PIN first")
                listener = StreamTls.listen(shareWifi(context).second, pins, purpose = PairingPurpose.SHARE)
                host = listener
                if (stopped) return@launch
                val endpoint = requireNotNull(listener.endpoint)
                discovery.advertise(endpoint)
                mutableState.value = TransferState.Waiting(endpoint.name)
                val socket = acceptSender(listener) ?: return@launch
                discovery.stopAdvertising()
                socket.use { saved = receive(it) { count -> saved = count } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = TransferState.Failed(if (stopped) "Cancelled" else error.message ?: "Transfer failed", saved)
            } finally {
                discovery.stopAdvertising()
                listener?.close()
                host = null
            }
        }
    }

    fun accept() { decision?.complete(true) }
    fun decline() { decision?.complete(false) }

    /** Stops advertising and cancels any transfer. Items already saved are kept. */
    fun stop() {
        stopped = true
        decision?.complete(false)
        host?.close()
    }

    override fun close() { stop(); discovery.close(); scope.cancel() }

    /** Wrong PINs end in an IOException from accept(); keep waiting until someone pairs or [stop] is called. */
    private fun acceptSender(listener: StreamTls.Host): SSLSocket? {
        while (!stopped) {
            try { return listener.accept() } catch (_: IOException) { }
        }
        return null
    }

    private suspend fun receive(socket: SSLSocket, onSaved: (Int) -> Unit): Int {
        socket.soTimeout = 20_000
        val input = DataInputStream(BufferedInputStream(socket.inputStream))
        val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
        val offer = TransferProtocol.readOffer(input)
        val enoughSpace = offer.totalBytes + offer.totalBytes / 10 <= context.filesDir.usableSpace
        val accepted = enoughSpace && askUser(offer)
        TransferProtocol.writeFlag(output, accepted)
        if (!accepted) {
            mutableState.value = TransferState.Failed(when {
                stopped -> "Cancelled"
                !enoughSpace -> "Not enough storage for these files"
                else -> "Declined"
            })
            return 0
        }
        var saved = 0
        var received = 0L
        repeat(offer.itemCount) { index ->
            val header = TransferProtocol.readItemHeader(input)
            if (header.size > offer.totalBytes - received) throw IOException("Invalid transfer message")
            mutableState.value = TransferState.Transferring(index + 1, offer.itemCount, received, offer.totalBytes)
            val start = received
            receiveItem(input, output, header) { bytes ->
                mutableState.value = TransferState.Transferring(index + 1, offer.itemCount, start + bytes, offer.totalBytes)
            }
            received += header.size
            saved++
            onSaved(saved)
        }
        TransferProtocol.readEnd(input)
        TransferProtocol.writeFlag(output, true)
        mutableState.value = TransferState.Done(saved)
        return saved
    }

    private suspend fun askUser(offer: TransferProtocol.Offer): Boolean {
        val answer = CompletableDeferred<Boolean>()
        decision = answer
        if (stopped) return false
        mutableState.value = TransferState.AwaitingAccept(offer.senderName, offer.itemCount, offer.totalBytes)
        return try { withTimeoutOrNull(60_000) { answer.await() } ?: false } finally { decision = null }
    }

    /** Streams one item straight into a new encrypted vault file; on any failure its files are removed. */
    private suspend fun receiveItem(input: DataInputStream, output: DataOutputStream, header: TransferProtocol.ItemHeader, onProgress: (Long) -> Unit) {
        val video = header.type == MediaType.VIDEO
        val id = (if (video) "video_" else "photo_") + UUID.randomUUID().toString().take(8)
        val encFile = File(context.filesDir, "vault_media/$id.enc").apply { parentFile?.mkdirs() }
        val thumbFile = File(context.filesDir, "vault_thumbs/$id$GALLERY_THUMBNAIL_SUFFIX")
        var inserted = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = DigestInputStream(TransferProtocol.exactly(input, header.size), digest)
            cryptoEngine.createEncryptingOutputStream(FileOutputStream(encFile)).use { vault ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    vault.write(buffer, 0, count)
                    done += count
                    if (done - reported >= 512 * 1024) { onProgress(done); reported = done }
                }
                buffer.fill(0)
                onProgress(done)
            }
            if (!MessageDigest.isEqual(TransferProtocol.readHash(input), digest.digest())) {
                TransferProtocol.writeFlag(output, false)
                throw IOException("A file arrived damaged. Transfer stopped.")
            }
            val preview = writeEncryptedPreview(cryptoEngine, encFile, video, thumbFile, File(context.filesDir, "vault_staging/transfer"))
            mediaRepository.insertMedia(MediaItem(
                id = id,
                filename = header.name,
                originalName = header.name,
                mediaType = header.type,
                mimeType = header.mimeType,
                encryptedPath = encFile.absolutePath,
                thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null,
                sizeBytes = encFile.length(),
                durationMs = if (video) preview.durationMs.takeIf { it > 0 } ?: header.durationMs else 0L,
                width = preview.width,
                height = preview.height,
                createdAt = header.createdAt,
                albumId = AlbumEntity.ALBUM_IMPORTS_ID
            ))
            inserted = true
            TransferProtocol.writeFlag(output, true)
        } catch (error: Exception) {
            if (!inserted) { encFile.delete(); thumbFile.delete() }
            throw error
        }
    }
}

/** The phone's Wi-Fi network and its IPv4 address; file sharing only runs over Wi-Fi. */
internal fun shareWifi(context: Context): Pair<Network, InetAddress> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    return cm.allNetworks.firstNotNullOfOrNull { network ->
        if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
        else cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { network to it.address }
    } ?: throw IOException("Connect both phones to the same Wi-Fi")
}
