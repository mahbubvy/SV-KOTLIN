package com.secretvault.app.core.stream

import org.json.JSONObject

data class StreamEndpoint(val host: String, val port: Int, val fingerprint: String,
                          val sessionId: String, val name: String, val requiresPin: Boolean = true, val cameraControls: Boolean = false) {
    val version: Int get() = if (cameraControls) { if (requiresPin) 4 else 5 } else { if (requiresPin) 2 else 3 }
    init {
        require(host.length <= 15)
        val parts = host.split('.').map { it.toIntOrNull() }
        require(host.length <= 15 && parts.size == 4 && parts.all { it != null && it in 0..255 })
        require(parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1]!! in 16..31)) { "Use the same local Wi-Fi" }
        require(port in 1..65535 && fingerprint.matches(Regex("[0-9a-f]{64}")) && sessionId.matches(Regex("[0-9a-f]{32}")))
        require(name.length in 1..64 && name.none { it.isISOControl() })
    }
    fun fingerprintBytes(): ByteArray = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun publicBytes(): ByteArray = JSONObject().put("v", version).put("h", host).put("p", port)
        .put("f", fingerprint).put("s", sessionId).put("n", name).toString().toByteArray(Charsets.UTF_8)
        .also { require(it.size <= 512) }

    companion object {
        fun fromPublicBytes(bytes: ByteArray): StreamEndpoint {
            require(bytes.size in 1..512)
            val data = JSONObject(bytes.toString(Charsets.UTF_8))
            require(data.length() == 6 && data.get("v") in listOf(2, 3, 4, 5) && data.get("p") is Int)
            val version = data.getInt("v")
            return StreamEndpoint(data.getString("h"), data.getInt("p"), data.getString("f"), data.getString("s"), data.getString("n"), version == 2 || version == 4, version >= 4)
        }
    }
}
