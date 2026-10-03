package com.secretvault.app.stream

import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class StreamProtocolTest {
    @Test fun recordingCommandsPreserveFramesAndRejectInvalidStates() {
        val state = StreamRecordingState(true, true, false, false, 4)
        val bytes = ByteArrayOutputStream().also { raw ->
            val output = DataOutputStream(raw)
            StreamProtocol.writeRecordingState(output, state)
            StreamProtocol.writeFrame(output, StreamFrame(0, 1, 1, byteArrayOf(7)))
            StreamProtocol.writeRecordingResult(output, StreamRecordingResult(2, false, true))
        }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(state, StreamProtocol.readMessage(input))
        assertArrayEquals(byteArrayOf(7), StreamProtocol.readFrame(input).bytes)
        assertEquals(StreamRecordingResult(2, false, true), StreamProtocol.readMessage(input))
        for (start in listOf(true, false)) {
            val request = ByteArrayOutputStream().also { StreamProtocol.writeRecordingRequest(DataOutputStream(it), 1, start) }.toByteArray()
            val command = DataInputStream(ByteArrayInputStream(request))
            assertEquals(StreamRecordingRequest(1, start), StreamProtocol.readRecordingRequest(command, command.readUnsignedByte()))
            request[9] = 2
            val malformed = DataInputStream(ByteArrayInputStream(request))
            assertThrows(IOException::class.java) { StreamProtocol.readRecordingRequest(malformed, malformed.readUnsignedByte()) }
        }
        for (invalid in listOf(state.copy(seconds = -1), state.copy(saving = true), state.copy(available = false))) {
            assertThrows(IOException::class.java) { StreamProtocol.writeRecordingState(DataOutputStream(ByteArrayOutputStream()), invalid) }
        }
        assertThrows(IOException::class.java) { StreamProtocol.writeRecordingRequest(DataOutputStream(ByteArrayOutputStream()), 0, true) }
        assertThrows(IOException::class.java) { StreamProtocol.readMessage(DataInputStream(ByteArrayInputStream(byteArrayOf(9, 2)))) }
    }
    @Test fun cameraSelectionMessagesAreBoundedAndPreserveVideoOrdering() {
        val state = StreamCameraState(listOf(StreamCameraOption("back/default", "Rear 1×"),
            StreamCameraOption("front", "Front")), "back/default")
        val bytes = ByteArrayOutputStream().also { raw ->
            val output = DataOutputStream(raw)
            StreamProtocol.writeCameraState(output, state)
            StreamProtocol.writeFrame(output, StreamFrame(0, 1, 1, byteArrayOf(7)))
            StreamProtocol.writeCameraResult(output, StreamCameraResult(1, true))
        }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(state, StreamProtocol.readMessage(input))
        assertArrayEquals(byteArrayOf(7), StreamProtocol.readFrame(input).bytes)
        assertEquals(StreamCameraResult(1, true), StreamProtocol.readMessage(input))
        val request = ByteArrayOutputStream().also { StreamProtocol.writeCameraRequest(DataOutputStream(it), 1, "front") }.toByteArray()
        val command = DataInputStream(ByteArrayInputStream(request))
        assertEquals(StreamCameraRequest(1, "front"), StreamProtocol.readCameraRequest(command, command.readUnsignedByte()))
        for (invalid in listOf(state.copy(selectedId = "missing"), state.copy(options = state.options + state.options),
            StreamCameraState(List(9) { StreamCameraOption("camera/$it", "Camera $it") }),
            state.copy(options = listOf(StreamCameraOption("x".repeat(33), "Front"))),
            state.copy(options = listOf(StreamCameraOption("front", "x".repeat(49)))))) {
            assertThrows(IOException::class.java) { StreamProtocol.writeCameraState(DataOutputStream(ByteArrayOutputStream()), invalid) }
        }
        assertThrows(IOException::class.java) { StreamProtocol.readMessage(DataInputStream(ByteArrayInputStream(byteArrayOf(6, 9)))) }
        assertThrows(IOException::class.java) { StreamProtocol.writeCameraRequest(DataOutputStream(ByteArrayOutputStream()), 0, "front") }
        assertThrows(IOException::class.java) { StreamProtocol.writeCameraRequest(DataOutputStream(ByteArrayOutputStream()), 1, "bad\nID") }
    }

    @Test fun openHandshakeIsBoundedAndBoundToTheSelectedSession() {
        val session = "a".repeat(32)
        val bytes = ByteArrayOutputStream().also { StreamProtocol.writeOpenAuth(DataOutputStream(it), session) }.toByteArray()
        assertEquals(36, bytes.size)
        StreamProtocol.readOpenAuth(DataInputStream(ByteArrayInputStream(bytes)), session)
        assertThrows(IOException::class.java) { StreamProtocol.readOpenAuth(DataInputStream(ByteArrayInputStream(bytes)), "b".repeat(32)) }
        assertThrows(IOException::class.java) { StreamProtocol.writeOpenAuth(DataOutputStream(ByteArrayOutputStream()), "short") }
        bytes[0] = 0
        assertThrows(IOException::class.java) { StreamProtocol.readOpenAuth(DataInputStream(ByteArrayInputStream(bytes)), session) }
    }

    @Test fun photoCommandsAndResultsAreBoundedAndDoNotCorruptVideo() {
        val bytes = ByteArrayOutputStream().also { raw ->
            val output = DataOutputStream(raw)
            StreamProtocol.writePhotoAvailable(output, true)
            StreamProtocol.writeFrame(output, StreamFrame(0, 1, 1, byteArrayOf(7)))
            StreamProtocol.writePhotoResult(output, StreamPhotoResult(1, true))
            StreamProtocol.writePhotoResult(output, StreamPhotoResult(2, false))
        }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(StreamPhotoAvailable(true), StreamProtocol.readMessage(input))
        assertArrayEquals(byteArrayOf(7), StreamProtocol.readFrame(input).bytes)
        assertEquals(StreamPhotoResult(1, true), StreamProtocol.readMessage(input))
        assertEquals(StreamPhotoResult(2, false), StreamProtocol.readMessage(input))
        val request = ByteArrayOutputStream().also { StreamProtocol.writePhotoRequest(DataOutputStream(it), 1) }.toByteArray()
        val command = DataInputStream(ByteArrayInputStream(request))
        assertEquals(1L, StreamProtocol.readPhotoRequest(command, command.readUnsignedByte()))
        for (invalid in listOf(byteArrayOf(3, 2), byteArrayOf(4, 0), byteArrayOf(9))) {
            assertThrows(IOException::class.java) { StreamProtocol.readMessage(DataInputStream(ByteArrayInputStream(invalid))) }
        }
        assertThrows(IOException::class.java) { StreamProtocol.writePhotoRequest(DataOutputStream(ByteArrayOutputStream()), 0) }
        assertThrows(IOException::class.java) { StreamProtocol.readPhotoRequest(DataInputStream(ByteArrayInputStream(request)), 9) }
    }

    @Test fun frameQueueRejectsBacklogAndReleasesCapacityAfterPolling() {
        val queue = StreamFrameQueue()
        repeat(6) { queue.offer(StreamFrame(it.toLong(), it.toLong(), 0, byteArrayOf(1))) }
        assertThrows(IOException::class.java) { queue.offer(StreamFrame(6, 6, 0, byteArrayOf(1))) }
        assertEquals(0, queue.poll()!!.sequence)
        queue.offer(StreamFrame(6, 6, 0, byteArrayOf(1)))
        queue.clear()
        repeat(2) { queue.offer(StreamFrame(it.toLong(), it.toLong(), 1, ByteArray(StreamProtocol.MAX_FRAME))) }
        assertThrows(IOException::class.java) { queue.offer(StreamFrame(2, 2, 0, byteArrayOf(1))) }
        assertEquals(0, queue.poll()!!.sequence)
        queue.offer(StreamFrame(2, 2, 0, byteArrayOf(1)))
    }

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
