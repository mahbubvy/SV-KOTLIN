package com.secretvault.app.core.transfer

import android.content.Context
import android.os.Build
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.stream.PairingPurpose
import com.secretvault.app.core.stream.StreamDiscovery
import com.secretvault.app.core.stream.StreamEndpoint
import com.secretvault.app.core.stream.StreamTls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/** Sends vault photos and videos to one SV receiver after PIN pairing. See SPEC-file-share.md. */
class TransferSender(private val context: Context, private val cryptoEngine: VaultCryptoEngine) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    val state = mutableState.asStateFlow()
    /** Receivers on this Wi-Fi; call [StreamDiscovery.search] to refresh. */
    val discovery = StreamDiscovery(context, share = true)
    private val recents = context.getSharedPreferences("sv_share_recent", Context.MODE_PRIVATE)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var cancelled = false
    private var job: Job? = null

    /** [pin] is needed only when [receiver] requires one. */
    @Synchronized fun send(receiver: StreamEndpoint, pin: CharArray?, items: List<MediaItem>) {
        if (job?.isActive == true) { pin?.fill('\u0000'); return }
        cancelled = false
        job = scope.launch {
            var sent = 0
            try {
                val files = items.map { File(it.encryptedPath) }
                val headers = items.mapIndexed { index, item ->
                    val size = cryptoEngine.getPlaintextSize(files[index])
                    if (size !in 1..TransferProtocol.MAX_ITEM_BYTES) throw IOException("${item.originalName} is empty or too large to send")
                    TransferProtocol.ItemHeader(item.mediaType, TransferProtocol.safeMime(item.mediaType, item.mimeType),
                        TransferProtocol.safeName(item.originalName.ifBlank { item.filename }), size, item.createdAt.coerceAtLeast(0),
                        if (item.mediaType == MediaType.VIDEO) item.durationMs.coerceAtLeast(0) else 0L,
                        item.width.coerceIn(0, 20_000), item.height.coerceIn(0, 20_000))
                }
                if (headers.size !in 1..TransferProtocol.MAX_ITEMS) throw IOException("Send 1 to ${TransferProtocol.MAX_ITEMS} items at a time")
                val total = headers.sumOf { it.size }
                mutableState.value = TransferState.Waiting(receiver.name)
                val connected = try {
                    val factory = shareWifi(context).first.socketFactory
                    val onSocket: (java.net.Socket) -> Unit = { if (cancelled) it.close() }
                    if (receiver.requiresPin) StreamTls.connect(receiver, requireNotNull(pin) { "Enter the PIN" }, factory, onSocket, PairingPurpose.SHARE)
                    else StreamTls.connect(receiver, factory, onSocket)
                } catch (error: IOException) {
                    throw IOException(if (receiver.requiresPin) "Could not connect. Check the PIN and that the other phone is still on Receive files."
                        else "Could not connect. Check that the other phone is still on Receive files.", error)
                }
                socket = connected
                if (cancelled) connected.close()
                connected.use { tls ->
                    val input = DataInputStream(BufferedInputStream(tls.inputStream))
                    val output = DataOutputStream(BufferedOutputStream(tls.outputStream, 64 * 1024))
                    tls.soTimeout = 70_000 // the receiver has 60 s to accept
                    TransferProtocol.writeOffer(output, TransferProtocol.Offer(headers.size, total, "SV ${Build.MODEL.take(48)}"))
                    if (!TransferProtocol.readFlag(input)) throw IOException("The other phone declined, or it doesn't have enough space")
                    tls.soTimeout = 20_000
                    var done = 0L
                    headers.forEachIndexed { index, header ->
                        mutableState.value = TransferState.Transferring(index + 1, headers.size, done, total)
                        TransferProtocol.writeItemHeader(output, header)
                        val start = done
                        val hash = sendPlaintext(files[index], output, header.size) { bytes ->
                            mutableState.value = TransferState.Transferring(index + 1, headers.size, start + bytes, total)
                        }
                        TransferProtocol.writeHash(output, hash)
                        if (!TransferProtocol.readFlag(input)) throw IOException("The other phone couldn't save ${header.name}")
                        done += header.size
                        sent++
                    }
                    TransferProtocol.writeEnd(output)
                    TransferProtocol.readFlag(input)
                }
                remember(receiver.name)
                mutableState.value = TransferState.Done(sent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = TransferState.Failed(if (cancelled) "Cancelled" else error.message ?: "Transfer failed", sent)
            } finally {
                pin?.fill('\u0000')
                socket = null
            }
        }
    }

    fun cancel() { cancelled = true; socket?.close() }

    /** Names of phones this vault has sent to, most recent first. */
    fun recentReceivers(): List<String> = recents.getString(RECENT_KEY, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    // ponytail: phones are remembered by advertised name ("SV <model>"), so two phones of the same model look alike.
    private fun remember(name: String) {
        val names = (listOf(name) + recentReceivers().filter { it != name }).take(10)
        recents.edit().putString(RECENT_KEY, names.joinToString("\n")).apply()
    }

    /** Back to [TransferState.Idle] after a finished or failed send. */
    fun reset() { if (job?.isActive != true) mutableState.value = TransferState.Idle }

    override fun close() { cancel(); discovery.close(); scope.cancel() }

    /** Decrypts one vault file straight into the socket and returns the SHA-256 of what was sent. */
    private fun sendPlaintext(file: File, output: DataOutputStream, size: Long, onProgress: (Long) -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var reported = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (written + length > size) throw IOException("File changed while sending")
                output.write(bytes, offset, length); digest.update(bytes, offset, length)
                written += length
                if (written - reported >= 512 * 1024) { onProgress(written); reported = written }
            }
            override fun flush() = output.flush()
            override fun close() = Unit
        }
        BufferedInputStream(FileInputStream(file)).use { cryptoEngine.decryptStream(it, sink) }
        if (written != size) throw IOException("File changed while sending")
        onProgress(written)
        return digest.digest()
    }

    private companion object { const val RECENT_KEY = "names" }
}
