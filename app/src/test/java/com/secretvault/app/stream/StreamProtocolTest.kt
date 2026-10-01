package com.secretvault.app.stream

import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class StreamProtocolTest {
    @Test fun invitationRejectsPublicHostsAndNeverPrintsCredentials() {
        val invitation = StreamInvitation("192.168.0.143", 40000, ByteArray(32) { 7 }, ByteArray(16) { 9 })
        val encoded = invitation.encode()
        assertEquals(invitation.host, StreamInvitation.parse(encoded).host)
        assertArrayEquals(invitation.token, StreamInvitation.parse(encoded).token)
        assertFalse(invitation.toString().contains("090909"))
        listOf(encoded.replace("192.168.0.143", "example.com"),
            encoded.replace("192.168.0.143", "8.8.8.8"), encoded.replace(":40000", ":0"),
            encoded + "00", "x".repeat(400)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { StreamInvitation.parse(bad) }
        }
    }

    @Test fun configurationAndFramesRoundTripAcrossFragmentedReads() {
        val config = StreamConfig(1280, 720, 30, 90, byteArrayOf(0, 0, 1, 103), byteArrayOf(0, 0, 1, 104))
        val frame = StreamFrame(0, 123456, 1, byteArrayOf(0, 0, 1, 101))
        val bytes = ByteArrayOutputStream().also {
            StreamProtocol.writeConfig(DataOutputStream(it), config)
            StreamProtocol.writeFrame(DataOutputStream(it), frame)
        }.toByteArray()
        val fragmented = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val input = DataInputStream(fragmented)
        val restored = StreamProtocol.readConfig(input)
        assertEquals(90, restored.rotation)
        assertArrayEquals(config.csd0, restored.csd0)
        val result = StreamProtocol.readFrame(input)
        assertEquals(frame.ptsUs, result.ptsUs)
        assertArrayEquals(frame.bytes, result.bytes)
    }

    @Test fun rejectsLengthsBeforeReadingPayloadAndRejectsInvalidFrames() {
        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).apply {
                writeByte(2); writeLong(0); writeLong(1); writeInt(1); writeInt(Int.MAX_VALUE)
            }
        }.toByteArray()
        assertThrows(IOException::class.java) { StreamProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes))) }
        assertThrows(IOException::class.java) {
            StreamProtocol.writeFrame(DataOutputStream(ByteArrayOutputStream()), StreamFrame(-1, 0, 0, byteArrayOf(1)))
        }
        assertThrows(IOException::class.java) {
            StreamProtocol.writeConfig(DataOutputStream(ByteArrayOutputStream()), StreamConfig(1280, 720, 30, 17, byteArrayOf(1), byteArrayOf(2)))
        }
        assertThrows(EOFException::class.java) {
            StreamProtocol.readConfig(DataInputStream(ByteArrayInputStream(byteArrayOf(1))))
        }
    }

    @Test fun authenticationChecksMagicVersionAndExactToken() {
        val token = ByteArray(16) { 3 }
        val bytes = ByteArrayOutputStream().also { StreamProtocol.writeAuth(DataOutputStream(it), token) }.toByteArray()
        StreamProtocol.readAuth(DataInputStream(ByteArrayInputStream(bytes)), token)
        assertThrows(IOException::class.java) { StreamProtocol.readAuth(DataInputStream(ByteArrayInputStream(bytes)), ByteArray(16)) }
        bytes[0] = 0
        assertThrows(IOException::class.java) { StreamProtocol.readAuth(DataInputStream(ByteArrayInputStream(bytes)), token) }
    }
}
