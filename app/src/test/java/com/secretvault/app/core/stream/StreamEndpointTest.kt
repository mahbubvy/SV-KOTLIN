package com.secretvault.app.core.stream

import org.junit.Assert.*
import org.junit.Test

class StreamEndpointTest {
    @Test fun publicMetadataIsBoundedAndRejectsUntrustedEndpoints() {
        val endpoint = StreamEndpoint("192.168.0.143", 34567, "a".repeat(64), "b".repeat(32), "SV camera")
        assertEquals(endpoint, StreamEndpoint.fromPublicBytes(endpoint.publicBytes()))
        assertTrue(endpoint.publicBytes().size <= 512)
        fun rejected(bytes: ByteArray) { try { StreamEndpoint.fromPublicBytes(bytes); fail("Accepted invalid metadata") } catch (_: Exception) { } }
        rejected(ByteArray(513))
        rejected(endpoint.publicBytes().toString(Charsets.UTF_8).replace("192.168.0.143", "8.8.8.8").toByteArray())
        rejected(endpoint.publicBytes().toString(Charsets.UTF_8).replace("\"v\":2", "\"v\":1").toByteArray())
        rejected(endpoint.publicBytes().toString(Charsets.UTF_8).replace("SV camera", "x".repeat(65)).toByteArray())
        rejected(endpoint.publicBytes().copyOf(30))
    }
}
