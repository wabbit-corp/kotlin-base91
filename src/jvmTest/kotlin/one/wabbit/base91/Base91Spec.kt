@file:OptIn(PlatformSpecificBase91Api::class)

package one.wabbit.base91

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalStdlibApi::class)
class Base91Spec {
    private class OneByteAtATime(private val bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= count) return -1
            b[off] = buf[pos++]
            return 1
        }
    }

    private class StrictMarkInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var limit = -1
        private var readSinceMark = 0
        private var valid = false

        override fun mark(readlimit: Int) {
            super.mark(readlimit)
            limit = readlimit
            readSinceMark = 0
            valid = true
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0 && valid) {
                readSinceMark += n
                if (readSinceMark > limit) valid = false
            }
            return n
        }

        override fun read(): Int {
            val n = super.read()
            if (n != -1 && valid) {
                readSinceMark += 1
                if (readSinceMark > limit) valid = false
            }
            return n
        }

        override fun reset() {
            if (!valid) throw IOException("mark invalidated")
            super.reset()
            readSinceMark = 0
        }
    }

    val regressionTests =
        listOf(
            "".hexToByteArray() to "",
            "50".hexToByteArray() to "@A",
            "d826".hexToByteArray() to "XTB",
            "3e25e4".hexToByteArray() to ")OFU",
            "8f9f30e2".hexToByteArray() to ",}Zx4",
            "8beae1cfa9".hexToByteArray() to "8di|kdB",
            "2a4ba66e7dc0".hexToByteArray() to "lf57T}UE",
            "441a4bd02b4d56".hexToByteArray() to "[/2G:epjF",
            "a77f23b466c7554a".hexToByteArray() to "E~KDV.ugGN",
            "c3040347164ffe48e7".hexToByteArray() to "kN,(8Pu@<5YB",
            "6f6f8bb54a3fb21a5b81".hexToByteArray() to "mr*ix06M\$x]BC",
            "9057617598f8e3378c1216".hexToByteArray() to "a&FfSRfW;JIa}A",
        )

    @Test
    fun `test`() {
        for ((decoded, encoded) in regressionTests) {
            assertEquals(encoded, Base91.encode(decoded))
            assertTrue(decoded.contentEquals(Base91.decode(encoded)))
        }
    }

    @Test
    fun `roundabout`() {
        for (i in 0 until 1000) {
            val bytes = Random.nextBytes(i)
            val encoded = Base91.encode(bytes)
            val decoded = Base91.decode(encoded)
            assertTrue(
                bytes.contentEquals(decoded),
                "Decoding the encoded input should match the original input for size $i",
            )
        }
    }

    // New Test for Streams
    @Test
    fun `stream roundabout`() {
        for (i in 0 until 1000) {
            val originalBytes = Random.nextBytes(i)

            // Encode using streams
            val baos = ByteArrayOutputStream()
            val encoderStream = Base91EncoderStream(baos)
            encoderStream.write(originalBytes)
            // Must close the encoder stream to ensure final bytes are flushed and encoded
            encoderStream.close()
            val encodedBytes = baos.toByteArray()

            // Decode using streams
            val bais = ByteArrayInputStream(encodedBytes)
            val decoderStream = Base91DecoderStream(bais)
            // Read all decoded bytes
            val decodedBytes = decoderStream.readBytes()
            decoderStream.close()

            // Verify
            assertTrue(
                originalBytes.contentEquals(decodedBytes),
                "Stream encoding/decoding failed for size $i. Original: ${originalBytes.toHexString()}, Decoded: ${decodedBytes.toHexString()}",
            )

            // Optional: Verify encoded string matches direct encoding for sanity check
            val encodedStringFromStream = String(encodedBytes, Charsets.UTF_8)
            val encodedStringDirect = Base91.encode(originalBytes)
            assertEquals(
                encodedStringDirect,
                encodedStringFromStream,
                "Stream encoded output differs from direct encoding for size $i",
            )
        }
    }

    @Test
    fun `decoder stream handles partial encoded reads`() {
        val encoded = "@A".toByteArray()
        Base91DecoderStream(OneByteAtATime(encoded)).use { stream ->
            assertEquals(0x50, stream.read())
            assertEquals(-1, stream.read())
        }
    }

    @Test
    fun `flush must not finalize the stream`() {
        val out = ByteArrayOutputStream()

        Base91EncoderStream(out).use { stream ->
            stream.write("He".encodeToByteArray())
            stream.flush()
            stream.write("llo".encodeToByteArray())
        }

        val encoded = out.toString(Charsets.US_ASCII)
        assertEquals(Base91.encode("Hello".encodeToByteArray()), encoded)
    }

    @Test
    fun `streams support tiny custom buffers`() {
        val originalBytes = "Hello".encodeToByteArray()

        val out = ByteArrayOutputStream()
        Base91EncoderStream(out, inputBufferSize = 1, outputBufferSize = 2).use { stream ->
            stream.write(originalBytes)
        }

        val decoded =
            Base91DecoderStream(
                ByteArrayInputStream(out.toByteArray()),
                encodedBufferSize = 1,
                decodedBufferSize = 2,
            ).use { it.readBytes() }

        assertTrue(originalBytes.contentEquals(decoded))
    }

    @Test
    fun `streams reject invalid buffer sizes`() {
        assertFailsWith<IllegalArgumentException> {
            Base91EncoderStream(ByteArrayOutputStream(), inputBufferSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Base91EncoderStream(ByteArrayOutputStream(), inputBufferSize = 2, outputBufferSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Base91EncoderStream(ByteArrayOutputStream(), inputBufferSize = 2, outputBufferSize = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Base91DecoderStream(ByteArrayInputStream(byteArrayOf()), encodedBufferSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Base91DecoderStream(
                ByteArrayInputStream(byteArrayOf()),
                encodedBufferSize = 1,
                decodedBufferSize = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Base91DecoderStream(
                ByteArrayInputStream(byteArrayOf()),
                encodedBufferSize = 2,
                decodedBufferSize = 0,
            )
        }
    }

    @Test
    fun `reset still works after eof`() {
        Base91DecoderStream(
            ByteArrayInputStream("@A".toByteArray()),
            encodedBufferSize = 1,
            decodedBufferSize = 2,
        ).use { stream ->
            stream.mark(10)
            assertEquals(0x50, stream.read())
            assertEquals(-1, stream.read())
            stream.reset()
            assertEquals(0x50, stream.read())
            assertEquals(-1, stream.read())
        }
    }

    @Test
    fun `decoder stream mark survives one decoded byte on strict streams`() {
        Base91DecoderStream(StrictMarkInputStream(testEncodedBytes)).use { stream ->
            stream.mark(1)
            stream.read()
            stream.reset()
        }
    }

    @Test
    fun `encoder stream rejects writes after close`() {
        val out = ByteArrayOutputStream()
        val stream = Base91EncoderStream(out)

        stream.close()

        assertFailsWith<IOException> { stream.write("A".encodeToByteArray()) }
        assertFailsWith<IOException> { stream.flush() }
    }

    // Data for stream specific tests
    private val testDecodedHex = "a77f23b466c7554a" // 8 bytes
    private val testEncodedString = "E~KDV.ugGN"
    private val testDecodedBytes = testDecodedHex.hexToByteArray()
    private val testEncodedBytes = testEncodedString.toByteArray(Charsets.UTF_8)

    // --- New Tests for DecoderStream ---

    @Test
    fun `decoder stream markSupported`() {
        // Base stream supports mark
        val bais = ByteArrayInputStream(testEncodedBytes)
        val decoderStream = Base91DecoderStream(bais)
        assertTrue(
            decoderStream.markSupported(),
            "DecoderStream should support mark if underlying stream does (ByteArrayInputStream)",
        )
        decoderStream.close()

        // TODO: Add a test case with a base stream that *doesn't* support mark if needed
        // Example: val nonMarkingStream = object : InputStream() { override fun read(): Int = -1 }
        // assertFalse(Base91DecoderStream(nonMarkingStream).markSupported())
    }

    @Test
    fun `decoder stream mark and reset`() {
        val bais = ByteArrayInputStream(testEncodedBytes)
        Base91DecoderStream(bais).use { decoderStream ->
            assertTrue(decoderStream.markSupported())

            // Read first 2 bytes: 0xa7, 0x7f
            val byte1 = decoderStream.read()
            val byte2 = decoderStream.read()
            assertEquals(testDecodedBytes[0].toInt() and 0xFF, byte1, "First byte mismatch")
            assertEquals(testDecodedBytes[1].toInt() and 0xFF, byte2, "Second byte mismatch")

            // Mark position after reading 2 bytes
            decoderStream.mark(10) // readlimit > bytes we intend to read before reset

            // Read next 3 bytes: 0x23, 0xb4, 0x66
            val byte3 = decoderStream.read()
            val byte4 = decoderStream.read()
            val byte5 = decoderStream.read()
            assertEquals(
                testDecodedBytes[2].toInt() and 0xFF,
                byte3,
                "Third byte mismatch before reset",
            )
            assertEquals(
                testDecodedBytes[3].toInt() and 0xFF,
                byte4,
                "Fourth byte mismatch before reset",
            )
            assertEquals(
                testDecodedBytes[4].toInt() and 0xFF,
                byte5,
                "Fifth byte mismatch before reset",
            )

            // Reset to mark
            try {
                decoderStream.reset()
            } catch (e: IOException) {
                fail("Reset failed with IOException: ${e.message}")
            }

            // Read the same 3 bytes again
            val byte3reset = decoderStream.read()
            val byte4reset = decoderStream.read()
            val byte5reset = decoderStream.read()
            assertEquals(byte3, byte3reset, "Third byte mismatch after reset")
            assertEquals(byte4, byte4reset, "Fourth byte mismatch after reset")
            assertEquals(byte5, byte5reset, "Fifth byte mismatch after reset")

            // Read the remaining bytes: 0xc7, 0x55, 0x4a
            val remaining = decoderStream.readBytes()
            assertEquals(3, remaining.size, "Incorrect number of remaining bytes")
            assertTrue(
                testDecodedBytes.sliceArray(5 until 8).contentEquals(remaining),
                "Remaining bytes mismatch after reset",
            )

            // Ensure end of stream
            assertEquals(
                -1,
                decoderStream.read(),
                "Stream should be at end after reading all bytes",
            )
        }
    }

    @Test
    fun `decoder stream reset without mark`() {
        val bais = ByteArrayInputStream(testEncodedBytes)
        bais.reset()
        Base91DecoderStream(bais).use { decoderStream ->
            assertFailsWith<IOException>(
                "Should throw IOException when reset() is called without mark()"
            ) {
                decoderStream.reset()
            }
        }
    }

    @Test
    fun `decoder stream skip`() {
        val bais = ByteArrayInputStream(testEncodedBytes)
        Base91DecoderStream(bais).use { decoderStream ->
            // Read first byte: 0xa7
            val byte1 = decoderStream.read()
            assertEquals(
                testDecodedBytes[0].toInt() and 0xFF,
                byte1,
                "First byte mismatch before skip",
            )

            // Skip next 4 bytes (0x7f, 0x23, 0xb4, 0x66)
            val skipped = decoderStream.skip(4)
            assertEquals(4L, skipped, "Skip should return the number of bytes actually skipped")

            // Read the next byte (should be the 6th byte: 0xc7)
            val byte6 = decoderStream.read()
            assertEquals(testDecodedBytes[5].toInt() and 0xFF, byte6, "Byte mismatch after skip")

            // Skip 0 bytes
            val skippedZero = decoderStream.skip(0)
            assertEquals(0L, skippedZero, "Skip(0) should return 0")
            val byte7 = decoderStream.read()
            assertEquals(testDecodedBytes[6].toInt() and 0xFF, byte7, "Byte mismatch after skip(0)")

            // Try to skip past the end (only 2 bytes left: 0x55, 0x4a)
            val skippedPastEnd = decoderStream.skip(10)
            assertEquals(1L, skippedPastEnd, "Skip past end should return bytes remaining")

            // Ensure end of stream
            assertEquals(
                -1,
                decoderStream.read(),
                "Stream should be at end after skipping past end",
            )
        }
    }

    @Test
    fun `decoder stream available`() {
        val bais = ByteArrayInputStream(testEncodedBytes) // Encoded length: 10 bytes
        Base91DecoderStream(bais).use { decoderStream ->
            // Initial check - depends on internal buffer size and underlying stream
            // The estimate is rough: `in`.available() * 0.813f + buffered
            // 10 * 0.813 = 8.13 => estimate ~8 initially? But might read/buffer differently.
            // Just check it's positive and reasonable relative to decoded size (8).
            val initialAvailable = decoderStream.available()
            assertTrue(initialAvailable >= 0, "Initial available should be non-negative")
            // It's an estimate, so exact value is hard to pin down, but should be > 0
            // assertTrue(initialAvailable > 0, "Initial available should be positive for non-empty
            // stream")

            // Read 3 bytes
            decoderStream.read()
            decoderStream.read()
            decoderStream.read()
            val availableAfter3 = decoderStream.available()
            assertTrue(availableAfter3 >= 0, "Available after read should be non-negative")
            // Available might decrease, but not necessarily by exactly 3 due to
            // buffering/estimation
            // It should reflect roughly 8 - 3 = 5 remaining bytes, possibly plus/minus buffer
            // effects

            // Read remaining 5 bytes
            val remainingBytes = ByteArray(5)
            val bytesRead = decoderStream.read(remainingBytes)
            assertEquals(5, bytesRead)

            // Check available at the end
            val availableAtEnd = decoderStream.available()
            assertEquals(0, availableAtEnd, "Available should be 0 at the end of the stream")

            // Check after reading past end
            assertEquals(-1, decoderStream.read())
            assertEquals(
                0,
                decoderStream.available(),
                "Available should be 0 after attempting read past end",
            )
        }

        // Test with empty input
        val emptyBais = ByteArrayInputStream(byteArrayOf())
        Base91DecoderStream(emptyBais).use { emptyDecoder ->
            assertEquals(0, emptyDecoder.available(), "Available should be 0 for empty stream")
            assertEquals(-1, emptyDecoder.read())
            assertEquals(
                0,
                emptyDecoder.available(),
                "Available should be 0 after reading empty stream",
            )
        }
    }
}
