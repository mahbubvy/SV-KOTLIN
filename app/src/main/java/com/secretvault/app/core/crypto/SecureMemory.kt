package com.secretvault.app.core.crypto

import java.nio.ByteBuffer
import java.util.Arrays

/**
 * Utility for zeroing out sensitive byte buffers and arrays from memory.
 */
object SecureMemory {

    fun wipe(array: ByteArray?) {
        if (array != null) {
            Arrays.fill(array, 0.toByte())
        }
    }

    fun wipe(chars: CharArray?) {
        if (chars != null) {
            Arrays.fill(chars, '\u0000')
        }
    }

    fun wipe(buffer: ByteBuffer?) {
        if (buffer != null && !buffer.isReadOnly) {
            buffer.clear()
            while (buffer.hasRemaining()) {
                buffer.put(0.toByte())
            }
            buffer.clear()
        }
    }
}
