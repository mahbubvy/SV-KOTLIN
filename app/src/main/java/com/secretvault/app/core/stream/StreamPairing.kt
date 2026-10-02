package com.secretvault.app.core.stream

import org.bouncycastle.crypto.agreement.jpake.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object StreamPairing {
    private const val MAGIC = 0x53565032
    private val group = JPAKEPrimeOrderGroups.NIST_3072

    fun authenticate(input: DataInputStream, output: DataOutputStream, pin: CharArray,
                     certificateHash: ByteArray, sessionId: String, sender: Boolean) {
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        require(certificateHash.size == 32 && sessionId.matches(Regex("[0-9a-f]{32}")))
        val binding = MessageDigest.getInstance("SHA-256").digest("SV-stream-v2:$sessionId:".toByteArray() + certificateHash)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val role = if (sender) "camera" else "viewer"
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val participant = JPAKEParticipant("sv2:$role:$binding:$nonce", pin)
        pin.fill('\u0000')
        try {
            fun header(round: Int, id: String) {
                output.writeInt(MAGIC); output.writeByte(round)
                val bytes = id.toByteArray(Charsets.US_ASCII); output.writeShort(bytes.size); output.write(bytes)
            }
            fun readId(round: Int): String {
                checkMessage(input.readInt() == MAGIC && input.readUnsignedByte() == round)
                val length = input.readUnsignedShort(); checkMessage(length in 1..144)
                val id = ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII)
                val other = if (sender) "viewer" else "camera"
                checkMessage(id.matches(Regex("sv2:$other:$binding:[0-9a-f]{32}")))
                return id
            }
            fun writeInteger(value: BigInteger) {
                val bytes = value.toByteArray(); checkMessage(bytes.size in 1..385)
                output.writeShort(bytes.size); output.write(bytes)
            }
            fun readInteger(maximum: Int = 385, bound: BigInteger? = group.p): BigInteger {
                val length = input.readUnsignedShort(); checkMessage(length in 1..maximum)
                val bytes = ByteArray(length).also(input::readFully)
                val value = BigInteger(bytes)
                checkMessage(value.toByteArray().contentEquals(bytes))
                if (bound != null) checkMessage(value.signum() >= 0 && value < bound)
                return value
            }
            fun readProof() = arrayOf(readInteger(), readInteger(33, group.q))
            val one = participant.createRound1PayloadToSend()
            fun writeOne() {
                header(1, one.participantId); writeInteger(one.gx1); writeInteger(one.gx2)
                one.knowledgeProofForX1.forEach(::writeInteger); one.knowledgeProofForX2.forEach(::writeInteger); output.flush()
            }
            if (sender) writeOne()
            val peerOne = JPAKERound1Payload(readId(1), readInteger(), readInteger(), readProof(), readProof())
            participant.validateRound1PayloadReceived(peerOne)
            if (!sender) writeOne()
            val two = participant.createRound2PayloadToSend()
            fun writeTwo() {
                header(2, two.participantId); writeInteger(two.a); two.knowledgeProofForX2s.forEach(::writeInteger); output.flush()
            }
            if (sender) writeTwo()
            participant.validateRound2PayloadReceived(JPAKERound2Payload(readId(2), readInteger(), readProof()))
            if (!sender) writeTwo()
            val material = participant.calculateKeyingMaterial()
            val three = participant.createRound3PayloadToSend(material)
            fun writeThree() { header(3, three.participantId); writeInteger(three.macTag); output.flush() }
            if (sender) writeThree()
            participant.validateRound3PayloadReceived(JPAKERound3Payload(readId(3), readInteger(33, null)), material)
            if (!sender) writeThree()
        } catch (error: Exception) { throw IOException("Streaming PIN pairing failed", error) }
        finally { pin.fill('\u0000') }
    }

    private fun checkMessage(valid: Boolean) { if (!valid) throw IOException("Invalid pairing message") }
}
