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
import javax.net.SocketFactory
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

object StreamTls {
    private const val PREFIX = "sv_stream_test_"
    private val cleaned = AtomicBoolean(false)

    class Host internal constructor(private val alias: String, private val store: KeyStore,
        private val listener: SSLServerSocket, val invitation: StreamInvitation) : Closeable {
        private val closed = AtomicBoolean(false)
        private val pending = AtomicReference<SSLSocket?>()
        private val peer = AtomicReference<SSLSocket?>()

        fun accept(): SSLSocket {
            val socket = listener.accept() as SSLSocket
            pending.set(socket)
            try {
                if (closed.get() || peer.get() != null) throw IOException("A viewer is already connected")
                socket.useClientMode = false
                socket.soTimeout = 5000
                socket.startHandshake()
                StreamProtocol.readAuth(DataInputStream(socket.inputStream), invitation.token)
                if (closed.get() || !peer.compareAndSet(null, socket)) throw IOException("Session ended")
                socket.outputStream.write(1); socket.outputStream.flush()
                socket.soTimeout = 3000
                return socket
            } catch (error: Exception) {
                socket.close()
                peer.compareAndSet(socket, null)
                throw IOException("Pairing rejected", error)
            } finally { pending.compareAndSet(socket, null) }
        }

        fun releasePeer() { peer.getAndSet(null)?.close() }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { listener.close() }
            runCatching { pending.getAndSet(null)?.close() }
            runCatching { releasePeer() }
            store.deleteEntry(alias)
        }
    }

    fun listen(address: InetAddress): Host {
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
            return Host(alias, store, listener, StreamInvitation(address.hostAddress!!, listener.localPort, pin, token))
        } catch (error: Exception) { store.deleteEntry(alias); throw error }
    }

    fun connect(invitation: StreamInvitation, socketFactory: SocketFactory = SocketFactory.getDefault(),
        onSocket: (Socket) -> Unit = {}): SSLSocket {
        val pin = invitation.fingerprint.copyOf()
        require(pin.size == 32 && invitation.token.size == 16)
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
        var tls: SSLSocket? = null
        try {
            onSocket(raw)
            raw.connect(InetSocketAddress(invitation.host, invitation.port), 5000)
            tls = context.socketFactory.createSocket(raw, invitation.host, invitation.port, true) as SSLSocket
            onSocket(tls)
            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            tls.useClientMode = true
            tls.soTimeout = 5000
            tls.startHandshake()
            StreamProtocol.writeAuth(DataOutputStream(tls.outputStream), invitation.token)
            if (tls.inputStream.read() != 1) throw IOException("Pairing rejected")
            tls.soTimeout = 3000
            return tls
        } catch (error: Exception) {
            runCatching { tls?.close() }; runCatching { raw.close() }
            throw IOException("Could not pair with camera", error)
        }
    }
}
