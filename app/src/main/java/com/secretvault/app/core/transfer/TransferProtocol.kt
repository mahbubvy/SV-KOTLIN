package com.secretvault.app.core.transfer

import com.secretvault.app.core.model.MediaType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Wire format for SV-to-SV file sharing, version 1, sent inside the PIN-paired TLS socket. See SPEC-file-share.md. */
object TransferProtocol {
    private const val OFFER_MAGIC = 0x53564631 // "SVF1"
    private const val END_MAGIC = 0x53564645 // "SVFE"
    private const val VERSION = 1
    const val MAX_ITEMS = 500
    const val MAX_TOTAL_BYTES = 1L shl 40
    const val MAX_ITEM_BYTES = 16L shl 30
    const val HASH_BYTES = 32
    private const val MAX_DIMENSION = 20_000
    private val MIME = Regex("(image|video)/[a-z0-9][a-z0-9.+-]{0,47}")

    data class Offer(val itemCount: Int, val totalBytes: Long) {
        init { require(itemCount in 1..MAX_ITEMS && totalBytes in 0..MAX_TOTAL_BYTES) }
    }

    data class ItemHeader(val type: MediaType, val mimeType: String, val name: String, val size: Long,
                          val createdAt: Long, val durationMs: Long, val width: Int, val height: Int) {
        init {
            require(MIME.matches(mimeType) && mimeType.startsWith(if (type == MediaType.PHOTO) "image/" else "video/"))
            require(validName(name) && size in 1..MAX_ITEM_BYTES && createdAt >= 0 && durationMs >= 0)
            require(width in 0..MAX_DIMENSION && height in 0..MAX_DIMENSION)
            require(type == MediaType.VIDEO || durationMs == 0L)
        }
    }

    fun validName(name: String) = name.length in 1..128 && !name.startsWith('.') &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }

    fun writeOffer(output: DataOutputStream, offer: Offer) {
        output.writeInt(OFFER_MAGIC); output.writeByte(VERSION); output.writeInt(offer.itemCount); output.writeLong(offer.totalBytes)
        output.flush()
    }

    fun readOffer(input: DataInputStream): Offer = parse {
        check(input.readInt() == OFFER_MAGIC && input.readUnsignedByte() == VERSION)
        Offer(input.readInt(), input.readLong())
    }

    fun writeItemHeader(output: DataOutputStream, item: ItemHeader) {
        output.writeByte(if (item.type == MediaType.PHOTO) 0 else 1)
        output.writeUTF(item.mimeType); output.writeUTF(item.name); output.writeLong(item.size)
        output.writeLong(item.createdAt); output.writeLong(item.durationMs); output.writeInt(item.width); output.writeInt(item.height)
    }

    fun readItemHeader(input: DataInputStream): ItemHeader = parse {
        val type = when (input.readUnsignedByte()) { 0 -> MediaType.PHOTO; 1 -> MediaType.VIDEO; else -> error("type") }
        ItemHeader(type, input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readLong(), input.readInt(), input.readInt())
    }

    /** One-byte answers: offer decision, per-item result and the final acknowledgement. */
    fun writeFlag(output: DataOutputStream, value: Boolean) { output.writeByte(if (value) 1 else 0); output.flush() }

    fun readFlag(input: DataInputStream): Boolean = parse {
        when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("flag") }
    }

    fun writeHash(output: DataOutputStream, hash: ByteArray) { require(hash.size == HASH_BYTES); output.write(hash); output.flush() }
    fun readHash(input: DataInputStream): ByteArray = ByteArray(HASH_BYTES).also(input::readFully)

    fun writeEnd(output: DataOutputStream) { output.writeInt(END_MAGIC); output.flush() }
    fun readEnd(input: DataInputStream) = parse { check(input.readInt() == END_MAGIC) }

    /** Reads exactly [size] bytes of [input] and never closes it; ending early throws [EOFException]. */
    fun exactly(input: InputStream, size: Long): InputStream = object : FilterInputStream(input) {
        private var left = size
        override fun read(): Int {
            if (left == 0L) return -1
            val value = super.read(); if (value < 0) throw EOFException("Transfer ended early")
            left--; return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return -1
            val count = super.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (count < 0) throw EOFException("Transfer ended early")
            left -= count; return count
        }
        override fun skip(n: Long) = throw IOException("skip not supported")
        override fun available() = minOf(super.available().toLong(), left).toInt()
        override fun markSupported() = false
        override fun close() = Unit
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("Invalid transfer message", error) }
}
