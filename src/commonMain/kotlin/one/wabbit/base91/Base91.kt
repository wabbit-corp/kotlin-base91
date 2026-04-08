package one.wabbit.base91
import kotlin.math.ceil

/** Custom exception for Base91 encoding/decoding errors, including invalid input characters. */
class Base91Exception(message: String) :
    IllegalArgumentException(message) // Inherit from IllegalArgumentException

/**
 * Object providing Base91 encoding and decoding functionality. Base91 is a binary-to-text encoding
 * scheme that uses a 91-character alphabet, providing more efficient encoding than Base64 for many
 * types of data.
 */
object Base91 {
    private const val ENCODING_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+,./:;<=>?@[]^_`{|}~\""
    private const val BASE = 91
    private const val AVERAGE_ENCODING_RATIO = 1.2307692307692308

    // Encoding table: Character for each value 0-90
    private val ENCODING_TABLE: ByteArray =
        ByteArray(ENCODING_ALPHABET.length) { index -> ENCODING_ALPHABET[index].code.toByte() }

    // Decoding table: Value for each character (ASCII 0-255), -1 for invalid chars
    private val DECODING_TABLE: ByteArray =
        ByteArray(256) { -1 }
            .apply {
                ENCODING_TABLE.forEachIndexed { index, byte ->
                    // Use toInt() and UByte to handle potential negative byte values correctly as
                    // indices
                    this[byte.toUByte().toInt()] = index.toByte()
                }
            }

    // --- Constants for Bit Manipulation ---
    // The threshold value determines if we process 13 or 14 bits
    internal const val THRESHOLD_13_BITS =
        88 // If (value & MASK_13_BITS) > THRESHOLD_13_BITS, use 13 bits, else 14
    internal const val BITS_13 = 13
    internal const val BITS_14 = 14
    internal const val MASK_13_BITS = (1 shl BITS_13) - 1 // 8191
    internal const val MASK_14_BITS = (1 shl BITS_14) - 1 // 16383

    /**
     * Estimates the maximum required buffer size for Base91 encoding. ~14 bits of input become 2
     * characters (16 bits). Worst case is slightly larger. Using ceil(inputSize * 1.231) ensures
     * enough space. (Ratio derived from 16/13)
     */
    private fun estimateEncodedSize(inputSize: Int): Int {
        if (inputSize == 0) return 0
        // Ceil(inputSize * 16 / 13) is a safe upper bound.
        // Using floating point math for simplicity: ceil(inputSize * 1.2307...) + margin
        return ceil(inputSize * (16.0 / BITS_13)).toInt() + 1 // Add small margin
    }

    /**
     * Estimates the maximum required buffer size for Base91 decoding. 2 characters (16 bits) become
     * ~13-14 bits. Using inputSize directly is a safe overestimate, as decoded size <= encoded
     * size.
     */
    private fun estimateDecodedSize(inputSize: Int): Int {
        // return ceil(inputSize * (14.0 / 16.0)).toInt() // Theoretical max
        // Practical: Decoded size will never exceed encoded length in bytes.
        return inputSize
    }

    /**
     * Encodes the input byte array into a Base91 string.
     *
     * @param input The byte array to encode.
     * @return The Base91 encoded string.
     */
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        val encoder = Encoder()
        val estimatedSize = estimateEncodedSize(input.size)
        val outputBuffer = ByteArray(estimatedSize)

        val mainEncodedLength = encoder.encodeChunk(input, 0, input.size, outputBuffer, 0)
        val finalEncodedLength = encoder.finish(outputBuffer, mainEncodedLength)
        val totalLength = mainEncodedLength + finalEncodedLength

        return outputBuffer.decodeAsciiToString(totalLength)
    }

    /**
     * Decodes a Base91 encoded string (or byte array) into a byte array.
     *
     * @param input The Base91 encoded string.
     * @return The decoded byte array.
     * @throws Base91Exception if the input contains invalid Base91 characters.
     */
    fun decode(input: String): ByteArray = decode(input.requireAsciiBytes())

    /**
     * Decodes a Base91 encoded byte array into a byte array.
     *
     * @param input The Base91 encoded byte array (ASCII/UTF-8 recommended).
     * @return The decoded byte array.
     * @throws Base91Exception if the input contains invalid Base91 characters.
     */
    fun decode(input: ByteArray): ByteArray {
        if (input.isEmpty()) return byteArrayOf()

        val decoder = Decoder()
        val estimatedSize = estimateDecodedSize(input.size)
        val outputBuffer = ByteArray(estimatedSize)

        val mainDecodedLength = decoder.decodeChunk(input, 0, input.size, outputBuffer, 0)
        val finalDecodedLength = decoder.finish(outputBuffer, mainDecodedLength)
        val totalLength = mainDecodedLength + finalDecodedLength

        // Trim the buffer to the actual size
        return outputBuffer.copyOf(totalLength)
    }

    /**
     * Internal stateful encoder for Base91. Processes byte chunks and maintains leftover bits
     * between calls.
     */
    internal class Encoder {
        private var bitQueue = 0 // Holds bits waiting to be processed
        private var bitCount = 0 // Number of valid bits in the queue (0-15)

        /**
         * Encodes a chunk of the input byte array.
         *
         * @param input Input byte array.
         * @param offset Starting offset in input array.
         * @param length Number of bytes to encode from input.
         * @param output Output byte array for encoded characters.
         * @param outOffset Starting offset in the output array.
         * @return The number of bytes written to the output array.
         */
        fun encodeChunk(
            input: ByteArray,
            offset: Int,
            length: Int,
            output: ByteArray,
            outOffset: Int,
        ): Int {
            var currentIn = offset
            val endIn = offset + length
            var currentOut = outOffset

            while (currentIn < endIn) {
                // Add next byte to the bit queue
                bitQueue = bitQueue or ((input[currentIn].toInt() and 0xFF) shl bitCount)
                bitCount += 8
                currentIn++

                // Process queue while it holds enough bits (13 or 14)
                if (bitCount > BITS_13) { // Need at least 13/14 bits
                    var value: Int
                    // Determine if we use 13 or 14 bits for this cycle
                    if ((bitQueue and MASK_13_BITS) > THRESHOLD_13_BITS) {
                        // Use 13 bits
                        value = bitQueue and MASK_13_BITS
                        bitQueue = bitQueue shr BITS_13
                        bitCount -= BITS_13
                    } else {
                        // Use 14 bits
                        value = bitQueue and MASK_14_BITS
                        bitQueue = bitQueue shr BITS_14
                        bitCount -= BITS_14
                    }
                    // Convert the value (0-8191 or 0-16383) into two Base91 characters
                    output[currentOut++] = ENCODING_TABLE[value % BASE]
                    output[currentOut++] = ENCODING_TABLE[value / BASE]
                }
            }
            return currentOut - outOffset // Return bytes written in this chunk
        }

        /**
         * Processes any remaining bits after all input bytes have been consumed. Writes the final 1
         * or 2 characters to the output buffer.
         *
         * @param output Output byte array.
         * @param outOffset Offset in the output array where writing should start.
         * @return The number of bytes written (0, 1, or 2).
         */
        fun finish(output: ByteArray, outOffset: Int): Int {
            var currentOut = outOffset
            if (bitCount > 0) {
                // We have leftover bits, treat them as the final value
                val value = bitQueue // No mask needed here, value is already < 16384
                output[currentOut++] = ENCODING_TABLE[value % BASE]
                // Need a second character only if more than 7 bits are left,
                // OR if the value itself requires it (value > 90)
                if (bitCount > 7 || value > 90) {
                    output[currentOut++] = ENCODING_TABLE[value / BASE]
                }
            }
            reset() // Reset state after finishing
            return currentOut - outOffset
        }

        /** Resets the encoder state. */
        fun reset() {
            bitQueue = 0
            bitCount = 0
        }

        /** Initializes the encoder state. */
        init {
            reset()
        }
    }

    /**
     * Internal stateful decoder for Base91. Processes character chunks and maintains state between
     * calls.
     */
    internal class Decoder {
        internal data class State(
            val bitQueue: Int,
            val bitCount: Int,
            val decodeValue: Int,
        )

        private var bitQueue = 0 // Holds reconstructed bits
        private var bitCount = 0 // Number of valid bits in the queue
        private var decodeValue =
            -1 // Holds value of the first character in a pair (-1 means waiting for first char)

        /**
         * Decodes a chunk of the input Base91 byte array.
         *
         * @param input Input Base91 byte array.
         * @param offset Starting offset in input array.
         * @param length Number of bytes to decode from input.
         * @param output Output byte array for decoded bytes.
         * @param outOffset Starting offset in the output array.
         * @return The number of bytes written to the output array.
         * @throws Base91Exception if an invalid Base91 character is encountered.
         */
        fun decodeChunk(
            input: ByteArray,
            offset: Int,
            length: Int,
            output: ByteArray,
            outOffset: Int,
        ): Int {
            var currentIn = offset
            val endIn = offset + length
            var currentOut = outOffset

            while (currentIn < endIn) {
                val charValue = DECODING_TABLE[input[currentIn].toUByte().toInt()].toInt()

                // Input validation
                if (charValue == -1) {
                    throw Base91Exception(
                        "Invalid Base91 character '${input[currentIn].toInt().toChar()}' (ASCII: ${input[currentIn].toUByte()}) at input position $currentIn"
                    )
                }

                if (decodeValue == -1) {
                    // First character of a pair, just store its value
                    decodeValue = charValue
                } else {
                    // Second character of a pair, combine values
                    decodeValue += charValue * BASE
                    // Add the combined value (13 or 14 bits) to the bit queue
                    bitQueue = bitQueue or (decodeValue shl bitCount)
                    // Determine if 13 or 14 bits were added
                    bitCount +=
                        if ((decodeValue and MASK_13_BITS) > THRESHOLD_13_BITS) BITS_13 else BITS_14
                    decodeValue = -1 // Reset waiting for the next pair

                    // Extract full bytes (8 bits) from the queue
                    while (bitCount > 7) {
                        output[currentOut++] = bitQueue.toByte() // Lower 8 bits
                        bitQueue = bitQueue shr 8
                        bitCount -= 8
                    }
                }
                currentIn++
            }
            return currentOut - outOffset // Return bytes written in this chunk
        }

        /**
         * Processes the final (potentially unpaired) character after all input is read. Writes the
         * last remaining byte(s) if any.
         *
         * @param output Output byte array.
         * @param outOffset Offset in the output array where writing should start.
         * @return The number of bytes written (0 or 1).
         */
        fun finish(output: ByteArray, outOffset: Int): Int {
            var currentOut = outOffset
            // If decodeValue != -1, we have a dangling first character of a pair.
            // This character contributes to the final byte(s).
            if (decodeValue != -1) {
                // Add its value to the queue (represents the final bits)
                bitQueue = bitQueue or (decodeValue shl bitCount)
                // We don't know if it was originally 13 or 14 bits, but we process remaining bits
                // anyway
                bitCount +=
                    if ((decodeValue and MASK_13_BITS) > THRESHOLD_13_BITS) BITS_13
                    else BITS_14 // Approx.
            }

            // Write out the last byte if there are enough bits remaining in the queue
            if (bitCount > 7) { // Should really only be one byte max here
                output[currentOut++] = bitQueue.toByte()
            } else if (bitCount > 0 && decodeValue != -1) {
                // Handle the case where the final single character resulted in < 8 bits,
                // but still represents a final partial byte.
                output[currentOut++] = bitQueue.toByte()
            }
            // There might be edge cases where finish needs to write more, but typically 0 or 1.

            val bytesWritten = currentOut - outOffset
            reset() // Reset state after finishing
            return bytesWritten
        }

        /** Resets the decoder state. */
        fun reset() {
            bitQueue = 0
            bitCount = 0
            decodeValue = -1
        }

        fun snapshotState(): State = State(bitQueue = bitQueue, bitCount = bitCount, decodeValue = decodeValue)

        fun restoreState(state: State) {
            bitQueue = state.bitQueue
            bitCount = state.bitCount
            decodeValue = state.decodeValue
        }

        /** Initializes the decoder state. */
        init {
            reset()
        }
    }
}

private fun String.requireAsciiBytes(): ByteArray {
    val result = ByteArray(length)
    for (index in indices) {
        val char = this[index]
        if (char.code > 0x7F) {
            throw Base91Exception(
                "Base91 input must be ASCII, but found '$char' (code ${char.code}) at index $index"
            )
        }
        result[index] = char.code.toByte()
    }
    return result
}

private fun ByteArray.decodeAsciiToString(length: Int): String =
    CharArray(length) { index -> (this[index].toInt() and 0xFF).toChar() }.concatToString()

internal fun requireBase91EncodingBufferSizes(inputBufferSize: Int, outputBufferSize: Int) {
    require(inputBufferSize > 0) { "inputBufferSize must be > 0" }
    require(outputBufferSize > 0) { "outputBufferSize must be > 0" }

    val requiredOutputSize =
        maxOf(
            2L,
            (((inputBufferSize.toLong() * 8L) + 12L) / Base91.BITS_13) * 2L,
        )
    require(requiredOutputSize <= Int.MAX_VALUE.toLong()) {
        "inputBufferSize is too large to validate safely: $inputBufferSize"
    }
    require(outputBufferSize.toLong() >= requiredOutputSize) {
        "outputBufferSize must be >= $requiredOutputSize for inputBufferSize=$inputBufferSize"
    }
}

internal fun requireBase91DecodingBufferSizes(encodedBufferSize: Int, decodedBufferSize: Int) {
    require(encodedBufferSize > 0) { "encodedBufferSize must be > 0" }
    require(decodedBufferSize > 0) { "decodedBufferSize must be > 0" }

    val maxPairsCompletedPerChunk = (encodedBufferSize.toLong() + 1L) / 2L
    val requiredDecodedSize =
        maxOf(
            1L,
            (7L + maxPairsCompletedPerChunk * Base91.BITS_14) / 8L,
        )
    require(requiredDecodedSize <= Int.MAX_VALUE.toLong()) {
        "encodedBufferSize is too large to validate safely: $encodedBufferSize"
    }
    require(decodedBufferSize.toLong() >= requiredDecodedSize) {
        "decodedBufferSize must be >= $requiredDecodedSize for encodedBufferSize=$encodedBufferSize"
    }
}
