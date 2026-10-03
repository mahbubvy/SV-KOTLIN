package com.secretvault.app.stream

import com.secretvault.app.core.stream.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class StreamInteractionProtocolTest {
    @Test fun interactionMessagesKeepVideoFramingAndCameraIdentity() {
        val state = StreamInteractionState("back/default", 1f, 10f, 2f, 0.45f, true, true)
        val bytes = ByteArrayOutputStream().also {
            val output = DataOutputStream(it)
            StreamProtocol.writeInteractionState(output, state)
            StreamProtocol.writeInteractionResult(output, StreamInteractionResult(3, 0, true))
            StreamProtocol.writeFrame(output, StreamFrame(0, 1, 1, byteArrayOf(8)))
        }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(state, StreamProtocol.readMessage(input))
        assertEquals(StreamInteractionResult(3, 0, true), StreamProtocol.readMessage(input))
        assertArrayEquals(byteArrayOf(8), StreamProtocol.readFrame(input).bytes)
        for (request in listOf(StreamInteractionRequest(1, "front", 0, 2.5f),
            StreamInteractionRequest(2, "back/2", 1, 0.25f, 0.8f), StreamInteractionRequest(3, "front", 2, 0f))) {
            val raw = ByteArrayOutputStream().also { StreamProtocol.writeInteractionRequest(DataOutputStream(it), request) }
            val command = DataInputStream(ByteArrayInputStream(raw.toByteArray()))
            assertEquals(request, StreamProtocol.readInteractionRequest(command, command.readUnsignedByte()))
        }
    }
    @Test fun invalidCameraCoordinatesAndZoomAreRejected() {
        val output = DataOutputStream(ByteArrayOutputStream())
        for (request in listOf(StreamInteractionRequest(0, "front", 0, 2f), StreamInteractionRequest(1, "bad camera", 0, 2f),
            StreamInteractionRequest(1, "front", 0, Float.NaN), StreamInteractionRequest(1, "front", 1, -0.1f),
            StreamInteractionRequest(1, "front", 1, 0.5f, Float.POSITIVE_INFINITY), StreamInteractionRequest(1, "front", 2, 0.5f))) {
            assertThrows(IOException::class.java) { StreamProtocol.writeInteractionRequest(output, request) }
        }
        assertThrows(IOException::class.java) { StreamProtocol.writeInteractionState(output, StreamInteractionState(maxZoom = 0.5f)) }
    }
}
