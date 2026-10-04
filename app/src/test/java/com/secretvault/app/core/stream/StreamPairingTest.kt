package com.secretvault.app.core.stream

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamPairingTest {
    @Test fun rejectsRecordedExchangeReflectionAndOversizedGroupInput() {
        val executor = Executors.newSingleThreadExecutor()
        val cameraRecord = ByteArrayOutputStream()
        val viewerRecord = ByteArrayOutputStream()
        fun pin() = charArrayOf('4', '7', '2', '9')
        fun recorded(output: OutputStream, record: ByteArrayOutputStream) = DataOutputStream(object : OutputStream() {
            override fun write(value: Int) { output.write(value); record.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length); record.write(bytes, offset, length) }
            override fun flush() = output.flush()
        })
        try {
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                val camera = executor.submit<Unit> { listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    StreamPairing.authenticate(DataInputStream(socket.getInputStream()), recorded(socket.getOutputStream(), cameraRecord),
                        pin(), ByteArray(32) { 1 }, "0".repeat(32), true)
                } }
                Socket(java.net.InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    socket.soTimeout = 3000
                    StreamPairing.authenticate(DataInputStream(socket.getInputStream()), recorded(socket.getOutputStream(), viewerRecord),
                        pin(), ByteArray(32) { 1 }, "0".repeat(32), false)
                }
                camera.get(4, TimeUnit.SECONDS)
            }
            fun rejected(bytes: ByteArray): Boolean {
                ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                    val result = executor.submit<Boolean> { listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        try { StreamPairing.authenticate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()),
                            pin(), ByteArray(32) { 1 }, "0".repeat(32), true); false } catch (_: IOException) { true }
                    } }
                    Socket(java.net.InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        socket.getOutputStream().write(bytes); socket.getOutputStream().flush()
                        return result.get(4, TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue("Recorded viewer exchange was replayed", rejected(viewerRecord.toByteArray()))
            assertTrue("Camera exchange was reflected as a viewer", rejected(cameraRecord.toByteArray()))
            val oversized = viewerRecord.toByteArray()
            val idLength = ((oversized[5].toInt() and 255) shl 8) or (oversized[6].toInt() and 255)
            oversized[7 + idLength] = 1; oversized[8 + idLength] = 0x82.toByte()
            assertTrue("Oversized group element was accepted", rejected(oversized))
            val transcript = viewerRecord.toByteArray()
            val integerOffset = 7 + idLength
            val integerLength = ((transcript[integerOffset].toInt() and 255) shl 8) or (transcript[integerOffset + 1].toInt() and 255)
            val invalidGroup = transcript.copyOfRange(0, integerOffset) + byteArrayOf(0, 1, 0) +
                transcript.copyOfRange(integerOffset + 2 + integerLength, transcript.size)
            assertTrue("Zero group element was accepted", rejected(invalidGroup))
        } finally { executor.shutdownNow() }
    }

    @Test fun pairingConfirmsBothSidesAndRejectsWrongPinAndTlsBinding() {
        fun exchange(wrongPin: Boolean, wrongCertificate: Boolean): Boolean {
            val executor = Executors.newSingleThreadExecutor()
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                val sender = executor.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5000
                        try {
                            StreamPairing.authenticate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()),
                                charArrayOf('4', '7', '2', '9'), ByteArray(32) { 1 }, "0".repeat(32), true)
                            true
                        } catch (_: IOException) { false }
                    }
                }
                try {
                    val received = Socket(java.net.InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        socket.soTimeout = 5000
                        try {
                            StreamPairing.authenticate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()),
                                if (wrongPin) charArrayOf('4', '7', '2', '8') else charArrayOf('4', '7', '2', '9'),
                                ByteArray(32) { if (wrongCertificate) 2 else 1 }, "0".repeat(32), false)
                            true
                        } catch (_: IOException) { false }
                    }
                    return sender.get(6, TimeUnit.SECONDS) && received
                } finally { executor.shutdownNow() }
            }
        }
        assertTrue("Matching PIN failed mutual confirmation", exchange(false, false))
        assertFalse("Wrong PIN was accepted", exchange(true, false))
        assertFalse("Different TLS certificate was accepted", exchange(false, true))
    }

    @Test fun purposeMustMatchOnBothSides() {
        fun exchange(host: PairingPurpose, peer: PairingPurpose): Boolean {
            val executor = Executors.newSingleThreadExecutor()
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                val hosted = executor.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5000
                        try { StreamPairing.authenticate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()),
                            charArrayOf('4', '7', '2', '9'), ByteArray(32) { 1 }, "0".repeat(32), true, host); true } catch (_: IOException) { false }
                    }
                }
                try {
                    val joined = Socket(java.net.InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        socket.soTimeout = 5000
                        try { StreamPairing.authenticate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()),
                            charArrayOf('4', '7', '2', '9'), ByteArray(32) { 1 }, "0".repeat(32), false, peer); true } catch (_: IOException) { false }
                    }
                    return hosted.get(6, TimeUnit.SECONDS) && joined
                } finally { executor.shutdownNow() }
            }
        }
        assertTrue("Share pairing failed", exchange(PairingPurpose.SHARE, PairingPurpose.SHARE))
        assertFalse("Camera viewer paired with a file receiver", exchange(PairingPurpose.SHARE, PairingPurpose.STREAM))
        assertFalse("File sender paired with a camera", exchange(PairingPurpose.STREAM, PairingPurpose.SHARE))
    }
}
