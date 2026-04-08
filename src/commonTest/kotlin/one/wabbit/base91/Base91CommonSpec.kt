package one.wabbit.base91

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.Buffer

@OptIn(ExperimentalStdlibApi::class)
class Base91CommonSpec {
    private fun Buffer.readAllBytes(): ByteArray {
        val bytes = ByteArray(size.toInt())
        val count = readAtMostTo(bytes)
        if (bytes.isEmpty()) {
            assertEquals(-1, count)
        } else {
            assertEquals(bytes.size, count)
        }
        return bytes
    }

    private val regressionTests =
        listOf(
            "".hexToByteArray() to "",
            "50".hexToByteArray() to "@A",
            "d826".hexToByteArray() to "XTB",
            "3e25e4".hexToByteArray() to ")OFU",
            "8f9f30e2".hexToByteArray() to ",}Zx4",
            "8beae1cfa9".hexToByteArray() to "8di|kdB",
            "2a4ba66e7dc0".hexToByteArray() to "lf57T}UE",
        )

    @Test
    fun `encodes known regression vectors`() {
        for ((decoded, encoded) in regressionTests) {
            assertEquals(encoded, Base91.encode(decoded))
            assertTrue(decoded.contentEquals(Base91.decode(encoded)))
        }
    }

    @Test
    fun `roundtrips random byte arrays`() {
        for (i in 0 until 512) {
            val bytes = Random.nextBytes(i)
            val encoded = Base91.encode(bytes)
            val decoded = Base91.decode(encoded)
            assertTrue(
                bytes.contentEquals(decoded),
                "Decoding the encoded input should match the original input for size $i",
            )
        }
    }

    @Test
    fun `kotlinx io wrappers roundtrip`() {
        for (i in 0 until 256) {
            val originalBytes = Random.nextBytes(i)

            val encodedBuffer = Buffer()
            encodedBuffer.base91Encoding().use { sink ->
                sink.write(originalBytes)
            }

            val encodedBytes = ByteArray(encodedBuffer.size.toInt())
            val encodedCount = encodedBuffer.readAtMostTo(encodedBytes)
            if (encodedBytes.isEmpty()) {
                assertEquals(-1, encodedCount)
            } else {
                assertEquals(encodedBytes.size, encodedCount)
            }

            val encodedSource = Buffer().apply { write(encodedBytes) }
            val decodedBuffer = Buffer()
            encodedSource.base91Decoding().use { source ->
                decodedBuffer.transferFrom(source)
            }

            val decodedBytes = ByteArray(decodedBuffer.size.toInt())
            val decodedCount = decodedBuffer.readAtMostTo(decodedBytes)
            if (decodedBytes.isEmpty()) {
                assertEquals(-1, decodedCount)
            } else {
                assertEquals(decodedBytes.size, decodedCount)
            }

            assertTrue(
                originalBytes.contentEquals(decodedBytes),
                "kotlinx-io Base91 wrapping failed for size $i",
            )
            assertEquals(
                Base91.encode(originalBytes),
                encodedBytes.decodeToString(),
                "kotlinx-io Base91 encoded bytes differed from direct encoding for size $i",
            )
        }
    }

    @Test
    fun `decode string throws Base91Exception for non ascii input`() {
        assertFailsWith<Base91Exception> { Base91.decode("é") }
    }

    @Test
    fun `kotlinx io wrappers support tiny custom buffers`() {
        val originalBytes = "Hello".encodeToByteArray()

        val encodedBuffer = Buffer()
        Buffer().apply { write(originalBytes) }.use { plain ->
            encodedBuffer.base91Encoding(inputBufferSize = 1, outputBufferSize = 2).use { sink ->
                sink.write(plain, plain.size)
            }
        }

        val decodedBuffer = Buffer()
        Buffer().apply { write(encodedBuffer.readAllBytes()) }.base91Decoding(
            encodedBufferSize = 1,
            decodedBufferSize = 2,
        ).use { source ->
            decodedBuffer.transferFrom(source)
        }

        assertTrue(originalBytes.contentEquals(decodedBuffer.readAllBytes()))
    }

    @Test
    fun `kotlinx io wrappers reject invalid buffer sizes`() {
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Encoding(inputBufferSize = 0, outputBufferSize = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Encoding(inputBufferSize = 2, outputBufferSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Encoding(inputBufferSize = 2, outputBufferSize = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Decoding(encodedBufferSize = 0, decodedBufferSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Decoding(encodedBufferSize = 1, decodedBufferSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Buffer().base91Decoding(encodedBufferSize = 2, decodedBufferSize = 0)
        }
    }

    @Test
    fun `decoding buffer validation rejects overflow sized encoded buffers`() {
        assertFailsWith<IllegalArgumentException> {
            requireBase91DecodingBufferSizes(Int.MAX_VALUE, 1)
        }
    }
}
