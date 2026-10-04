package com.secretvault.app.transfer

import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.transfer.TransferProtocol
import com.secretvault.app.core.transfer.TransferProtocol.ItemHeader
import com.secretvault.app.core.transfer.TransferProtocol.Offer
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class TransferProtocolTest {
    private val photo = ItemHeader(MediaType.PHOTO, "image/jpeg", "IMG_0001.jpg", 1234, 1_700_000_000_000, 0, 4000, 3000)

    private fun bytes(write: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { write(DataOutputStream(it)) }.toByteArray()
    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    @Test fun roundTripsEveryMessage() {
        val offer = Offer(3, 99_000)
        val video = ItemHeader(MediaType.VIDEO, "video/x-matroska", "clip one.mkv", 5L shl 30, 0, 61_000, 1920, 1080)
        val hash = ByteArray(32) { it.toByte() }
        val stream = input(bytes {
            TransferProtocol.writeOffer(it, offer); TransferProtocol.writeFlag(it, true)
            TransferProtocol.writeItemHeader(it, photo); TransferProtocol.writeItemHeader(it, video)
            TransferProtocol.writeHash(it, hash); TransferProtocol.writeFlag(it, false); TransferProtocol.writeEnd(it)
        })
        assertEquals(offer, TransferProtocol.readOffer(stream))
        assertTrue(TransferProtocol.readFlag(stream))
        assertEquals(photo, TransferProtocol.readItemHeader(stream))
        assertEquals(video, TransferProtocol.readItemHeader(stream))
        assertArrayEquals(hash, TransferProtocol.readHash(stream))
        assertFalse(TransferProtocol.readFlag(stream))
        TransferProtocol.readEnd(stream)
        assertEquals(-1, stream.read())
    }

    @Test fun rejectsOutOfRangeOffers() {
        fun offer(magic: Int, version: Int, count: Int, total: Long) = bytes {
            it.writeInt(magic); it.writeByte(version); it.writeInt(count); it.writeLong(total)
        }
        val good = offer(0x53564631, 1, 1, 0)
        TransferProtocol.readOffer(input(good))
        listOf(offer(0x53564632, 1, 1, 0), offer(0x53564631, 2, 1, 0), offer(0x53564631, 1, 0, 0),
            offer(0x53564631, 1, 501, 0), offer(0x53564631, 1, 1, -1), offer(0x53564631, 1, 1, (1L shl 40) + 1)
        ).forEach { assertThrows(IOException::class.java) { TransferProtocol.readOffer(input(it)) } }
    }

    @Test fun rejectsInvalidItemHeaders() {
        fun header(type: Int = 0, mime: String = "image/jpeg", name: String = "a.jpg", size: Long = 1,
                   created: Long = 0, duration: Long = 0, width: Int = 1, height: Int = 1) = bytes {
            it.writeByte(type); it.writeUTF(mime); it.writeUTF(name); it.writeLong(size)
            it.writeLong(created); it.writeLong(duration); it.writeInt(width); it.writeInt(height)
        }
        TransferProtocol.readItemHeader(input(header()))
        listOf(header(type = 2), header(mime = "text/plain"), header(mime = "video/mp4"), header(type = 1, mime = "image/jpeg"),
            header(mime = "image/JPEG"), header(name = ""), header(name = "../evil.jpg"), header(name = "a/b.jpg"),
            header(name = "a\\b.jpg"), header(name = ".hidden"), header(name = "a\u0000.jpg"), header(name = "x".repeat(129)),
            header(size = 0), header(size = (16L shl 30) + 1), header(created = -1), header(duration = 5),
            header(width = -1), header(height = 20_001)
        ).forEach { assertThrows(IOException::class.java) { TransferProtocol.readItemHeader(input(it)) } }
    }

    @Test fun rejectsBadFlagsAndEndMarker() {
        assertThrows(IOException::class.java) { TransferProtocol.readFlag(input(byteArrayOf(2))) }
        assertThrows(IOException::class.java) { TransferProtocol.readEnd(input(bytes { it.writeInt(0x53564631) })) }
    }

    @Test fun truncatedInputThrows() {
        val full = bytes { TransferProtocol.writeItemHeader(it, photo) }
        for (length in 0 until full.size) {
            assertThrows(IOException::class.java) { TransferProtocol.readItemHeader(input(full.copyOf(length))) }
        }
        assertThrows(EOFException::class.java) { TransferProtocol.readHash(input(ByteArray(31))) }
    }

    @Test fun exactlyReadsOnlyTheItemAndDetectsShortStreams() {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val item = TransferProtocol.exactly(source, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), item.readBytes())
        item.close()
        assertEquals(4, source.read())
        assertThrows(EOFException::class.java) { TransferProtocol.exactly(ByteArrayInputStream(byteArrayOf(1)), 2).readBytes() }
    }
}
