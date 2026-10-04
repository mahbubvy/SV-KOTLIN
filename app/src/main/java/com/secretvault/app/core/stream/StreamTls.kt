package com.secretvault.app.core.stream

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

object StreamTls {
    private const val PREFIX = "sv_stream_test_"
    private val cleaned = AtomicBoolean(false)

    class Host internal constructor(private val alias: String, private val store: KeyStore,
        private val listener: SSLServerSocket, val invitation: StreamInvitation,
        private val pinManager: StreamPinManager?, cameraControls: Boolean, recordingControls: Boolean, settingsControls: Boolean, interactionControls: Boolean,
        private val purpose: PairingPurpose = PairingPurpose.STREAM) : Closeable {
        private val closed = AtomicBoolean(false)
        private val pending = AtomicReference<SSLSocket?>()
        private val peer = AtomicReference<SSLSocket?>()
        private val deadlines = Executors.newSingleThreadScheduledExecutor()
        private val requiresPin = if (purpose == PairingPurpose.SHARE) pinManager?.isSharePinRequired() == true else pinManager?.isPinRequired() != false
        val endpoint = if (pinManager != null) StreamEndpoint(invitation.host, invitation.port,
            invitation.fingerprint.joinToString("") { "%02x".format(it.toInt() and 255) },
            UUID.randomUUID().toString().replace("-", ""), "SV ${android.os.Build.MODEL.take(48)}", requiresPin, cameraControls, recordingControls, settingsControls, interactionControls) else null

        fun accept(): SSLSocket {
            val socket = listener.accept() as SSLSocket
            pending.set(socket)
            var deadline: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                deadline = deadlines.schedule({ runCatching { socket.close() } }, 15, TimeUnit.SECONDS)
                if (closed.get() || peer.get() != null) throw IOException("A viewer is already connected")
                socket.useClientMode = false
                socket.soTimeout = 5000
                socket.startHandshake()
                if (pinManager == null) StreamProtocol.readAuth(DataInputStream(socket.inputStream), invitation.token)
                else if (!requiresPin) StreamProtocol.readOpenAuth(DataInputStream(socket.inputStream), requireNotNull(endpoint).sessionId)
                else {
                    pinManager.beginPairingAttempt()
                    val pin = pinManager.readPin() ?: throw IOException("Set a streaming PIN first")
                    try { StreamPairing.authenticate(DataInputStream(socket.inputStream), DataOutputStream(socket.outputStream),
                        pin, invitation.fingerprint, requireNotNull(endpoint).sessionId, true, purpose) }
                    finally { pin.fill('\u0000') }
                }
                if (closed.get() || !peer.compareAndSet(null, socket)) throw IOException("Session ended")
                if (requiresPin) pinManager?.pairingSucceeded()
                socket.outputStream.write(1); socket.outputStream.flush()
                socket.soTimeout = 3000
                return socket
            } catch (error: Exception) {
                socket.close()
                peer.compareAndSet(socket, null)
                throw IOException("Pairing rejected", error)
            } finally { deadline?.cancel(false); pending.compareAndSet(socket, null) }
        }

        fun releasePeer() { peer.getAndSet(null)?.close() }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { listener.close() }
            runCatching { pending.getAndSet(null)?.close() }
            runCatching { releasePeer() }
            deadlines.shutdownNow()
            store.deleteEntry(alias)
        }
    }

    fun listen(address: InetAddress, pinManager: StreamPinManager? = null, cameraControls: Boolean = false, recordingControls: Boolean = false, settingsControls: Boolean = false, interactionControls: Boolean = false,
               purpose: PairingPurpose = PairingPurpose.STREAM): Host {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (cleaned.compareAndSet(false, true)) {
            store.aliases().toList().filter { it.startsWith(PREFIX) }.forEach(store::deleteEntry)
        }
        val alias = PREFIX + UUID.randomUUID()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setCertificateSubject(X500Principal("CN=SV local camera"))
                    .setCertificateSerialNumber(BigInteger(96, SecureRandom()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 86_400_000)).build())
                generateKeyPair()
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
            val certificate = store.getCertificate(alias) as X509Certificate
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
            val listener = context.serverSocketFactory.createServerSocket(0, 2, address) as SSLServerSocket
            listener.enabledProtocols = listener.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded)
            val token = ByteArray(16).also(SecureRandom()::nextBytes)
            return Host(alias, store, listener, StreamInvitation(address.hostAddress!!, listener.localPort, pin, token), pinManager, cameraControls, recordingControls, settingsControls, interactionControls, purpose)
        } catch (error: Exception) { store.deleteEntry(alias); throw error }
    }

    fun connect(invitation: StreamInvitation, socketFactory: SocketFactory = SocketFactory.getDefault(),
        onSocket: (Socket) -> Unit = {}): SSLSocket = connectTls(invitation.host, invitation.port, invitation.fingerprint,
        socketFactory, onSocket) { socket -> StreamProtocol.writeAuth(DataOutputStream(socket.outputStream), invitation.token) }

    fun connect(endpoint: StreamEndpoint, pin: CharArray, socketFactory: SocketFactory = SocketFactory.getDefault(),
        onSocket: (Socket) -> Unit = {}, purpose: PairingPurpose = PairingPurpose.STREAM): SSLSocket = try {
        require(endpoint.requiresPin) { "This camera does not require a PIN" }
        connectTls(endpoint.host, endpoint.port, endpoint.fingerprintBytes(), socketFactory, onSocket) { socket ->
            StreamPairing.authenticate(DataInputStream(socket.inputStream), DataOutputStream(socket.outputStream),
                pin, endpoint.fingerprintBytes(), endpoint.sessionId, false, purpose)
        }
    } finally { pin.fill('\u0000') }

    fun connect(endpoint: StreamEndpoint, socketFactory: SocketFactory = SocketFactory.getDefault(),
        onSocket: (Socket) -> Unit = {}): SSLSocket {
        require(!endpoint.requiresPin) { "This camera requires a streaming PIN" }
        return connectTls(endpoint.host, endpoint.port, endpoint.fingerprintBytes(), socketFactory, onSocket) { socket ->
            StreamProtocol.writeOpenAuth(DataOutputStream(socket.outputStream), endpoint.sessionId)
        }
    }

    private fun connectTls(host: String, port: Int, fingerprint: ByteArray, socketFactory: SocketFactory,
                           onSocket: (Socket) -> Unit, authenticate: (SSLSocket) -> Unit): SSLSocket {
        val pin = fingerprint.copyOf()
        require(pin.size == 32)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificate not supported")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.singleOrNull() ?: throw CertificateException("Invalid session identity")
                cert.checkValidity()
                if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(cert.encoded)))
                    throw CertificateException("Session identity mismatch")
                cert.verify(cert.publicKey)
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val raw = socketFactory.createSocket()
        val deadlines = Executors.newSingleThreadScheduledExecutor()
        val deadline = deadlines.schedule({ runCatching { raw.close() } }, 15, TimeUnit.SECONDS)
        var tls: SSLSocket? = null
        try {
            onSocket(raw)
            raw.connect(InetSocketAddress(host, port), 5000)
            tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            onSocket(tls)
            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            tls.useClientMode = true
            tls.soTimeout = 5000
            tls.startHandshake()
            authenticate(tls)
            if (tls.inputStream.read() != 1) throw IOException("Pairing rejected")
            tls.soTimeout = 3000
            return tls
        } catch (error: Exception) {
            runCatching { tls?.close() }; runCatching { raw.close() }
            throw IOException("Could not pair with camera", error)
        } finally { deadline.cancel(false); deadlines.shutdownNow() }
    }
}
