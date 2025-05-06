package one.wabbit

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * An InputStream that decodes Base91 encoded data read from an underlying InputStream.
 * Supports mark/reset if the underlying stream does.
 */
class Base91DecoderStream(
    inputStream: InputStream,
    // Buffer sizes can be tuned.
    private val encodedBufferSize: Int = 16 * 3, // Read ~3 blocks of encoded data
    private val decodedBufferSize: Int = 14 * 3  // Room for ~3 blocks of decoded data + finish
) : FilterInputStream(inputStream) {

    private val decoder = Base91.Decoder()
    // Buffer to hold data read from the underlying stream (Base91 encoded)
    private val encodedBuffer: ByteArray = ByteArray(encodedBufferSize)
    // Buffer to hold decoded bytes ready for reading
    private var decodedBuffer: ByteArray = ByteArray(decodedBufferSize)

    private var decodedBufferReadPos: Int = 0 // Current reading position in decodedBuffer
    private var decodedBufferCount: Int = 0  // Number of valid decoded bytes in decodedBuffer (-1 signifies EOF)

    // --- Mark/Reset State ---
    private var markDecodedBuffer: ByteArray? = null // Saved decoded buffer state
    private var markDecodedBufferReadPos: Int = 0
    private var markDecodedBufferCount: Int = 0
    private var isMarkSet: Boolean = false // Tracks if our mark() method was called

    /**
     * Reads encoded data from the underlying stream and decodes it into the
     * decodedBuffer until the buffer has data or EOF is reached.
     *
     * @return true if data was successfully decoded into the buffer, false if EOF was reached.
     * @throws IOException if an I/O error occurs reading from the underlying stream.
     * @throws Base91Exception if invalid Base91 data is encountered.
     */
    @Throws(IOException::class, Base91Exception::class)
    private fun fillDecodedBuffer(): Boolean {
        // Reset read position as we are filling the buffer anew
        decodedBufferReadPos = 0
        decodedBufferCount = 0

        // Read encoded data from the underlying stream
        val bytesRead = `in`.read(encodedBuffer)

        if (bytesRead > 0) {
            // Decode the chunk read from the underlying stream
            decodedBufferCount = decoder.decodeChunk(encodedBuffer, 0, bytesRead, decodedBuffer, 0)
        } else {
            // Underlying stream reached EOF, process final bits in decoder
            decodedBufferCount = decoder.finish(decodedBuffer, 0)
            // If finish produced no bytes and decode was already finished, signal EOF
            if (decodedBufferCount == 0) {
                decodedBufferCount = -1 // Mark stream as ended
                return false
            }
        }
        return decodedBufferCount > 0 // Return true if we got some decoded bytes
    }


    @Throws(IOException::class)
    override fun read(): Int {
        // If buffer is exhausted or stream ended, try to fill buffer
        while (decodedBufferReadPos >= decodedBufferCount && decodedBufferCount != -1) {
            if (!fillDecodedBuffer()) {
                // fillDecodedBuffer returned false (EOF)
                return -1
            }
        }

        // Check again if EOF was marked by fillDecodedBuffer
        if (decodedBufferCount == -1 || decodedBufferReadPos >= decodedBufferCount) {
            return -1
        }

        // Return next byte from buffer
        return decodedBuffer[decodedBufferReadPos++].toInt() and 0xFF
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) {
            throw IndexOutOfBoundsException()
        }
        if (len == 0) {
            return 0
        }

        var totalBytesRead = 0
        while (totalBytesRead < len) {
            // Check if buffer is exhausted or stream ended
            if (decodedBufferReadPos >= decodedBufferCount && decodedBufferCount != -1) {
                if (!fillDecodedBuffer()) {
                    // EOF reached, return bytes read so far (-1 if none, >0 otherwise)
                    return if (totalBytesRead == 0) -1 else totalBytesRead
                }
            }

            // Check again if EOF was marked or buffer is still empty after trying to fill
            if (decodedBufferCount == -1 || decodedBufferReadPos >= decodedBufferCount) {
                return if (totalBytesRead == 0) -1 else totalBytesRead
            }

            // Calculate how many bytes can be copied from the current buffer
            val bytesToCopy = minOf(len - totalBytesRead, decodedBufferCount - decodedBufferReadPos)
            System.arraycopy(decodedBuffer, decodedBufferReadPos, b, off + totalBytesRead, bytesToCopy)

            decodedBufferReadPos += bytesToCopy
            totalBytesRead += bytesToCopy
        }
        return totalBytesRead
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        if (decodedBufferCount == -1) return 0 // Already at EOF

        var remainingSkip = n

        // 1. Skip within the current decoded buffer
        val availableInBuffer = (decodedBufferCount - decodedBufferReadPos).toLong()
        if (availableInBuffer > 0) {
            val bytesToSkipInBuffer = minOf(remainingSkip, availableInBuffer)
            decodedBufferReadPos += bytesToSkipInBuffer.toInt()
            remainingSkip -= bytesToSkipInBuffer
        }

        // 2. If more skipping needed, read and discard decoded bytes without storing them
        //    (This is simpler than trying to optimize by skipping on the underlying stream,
        //     as we need the decoder's state to be correct).
        val discardBuffer = ByteArray(512) // Temporary buffer for discarding
        while (remainingSkip > 0 && decodedBufferCount != -1) {
            val bytesToRead = minOf(remainingSkip, discardBuffer.size.toLong()).toInt()
            val bytesRead = read(discardBuffer, 0, bytesToRead) // Use our own read method

            if (bytesRead == -1) {
                // Reached EOF while skipping
                decodedBufferCount = -1
                break
            }
            remainingSkip -= bytesRead
        }

        return n - remainingSkip // Return total bytes actually skipped
    }

    /**
     * Returns an estimate of the number of bytes that can be read (or skipped over)
     * from this input stream without blocking.
     * This is based on bytes remaining in the decoded buffer plus an estimate
     * from the underlying stream, adjusted by the approximate Base91 ratio.
     * Note: This is only an estimate.
     */
    @Throws(IOException::class)
    override fun available(): Int {
        if (decodedBufferCount == -1) return 0
        // Estimate based on decoded buffer + underlying stream (approx ratio 14/16 = 0.875)
        val availableInBuffer = decodedBufferCount - decodedBufferReadPos
        val estimateFromUnderlying = (`in`.available() * (Base91.BITS_14 / 16.0)).toInt()
        // Ensure non-negative result, handle potential overflow
        return try {
            Math.addExact(availableInBuffer, estimateFromUnderlying)
        } catch (e: ArithmeticException) {
            Int.MAX_VALUE
        }
    }

    @Throws(IOException::class)
    override fun close() {
        `in`.close()
        // Clear state to prevent further use and allow GC
        decodedBufferCount = -1
        decodedBufferReadPos = 0
        decoder.reset()
        markDecodedBuffer = null
        isMarkSet = false
    }

    // --- Mark/Reset Implementation ---

    override fun markSupported(): Boolean {
        return `in`.markSupported()
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        if (!markSupported()) return
        `in`.mark(readlimit * 2) // Mark underlying stream (allow more due to encoding expansion)
        decoder.saveMark() // Save decoder state

        // Save decoded buffer state
        markDecodedBuffer = if (decodedBufferCount > 0) {
            Arrays.copyOf(decodedBuffer, decodedBufferCount)
        } else {
            null
        }
        markDecodedBufferReadPos = decodedBufferReadPos
        markDecodedBufferCount = decodedBufferCount
        isMarkSet = true // Mark that our stream's mark() was called
    }

    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        if (!markSupported()) {
            throw IOException("mark/reset not supported by underlying stream")
        }
        if (!isMarkSet || !decoder.isMarkSaved()) { // Check if our mark method was called and decoder state was saved
            throw IOException("mark() not called or invalidated before reset()")
        }

        `in`.reset() // Reset underlying stream
        decoder.restoreMark() // Restore decoder state

        // Restore decoded buffer state
        markDecodedBuffer?.let {
            // Ensure buffer has enough capacity (might have been resized)
            if(decodedBuffer.size < it.size) {
                decodedBuffer = ByteArray(it.size)
            }
            System.arraycopy(it, 0, decodedBuffer, 0, it.size)
        }
        decodedBufferReadPos = markDecodedBufferReadPos
        decodedBufferCount = markDecodedBufferCount

        // According to InputStream spec, mark is typically invalidated after reset,
        // but some impls allow multiple resets. Let's keep mark valid for simplicity here.
        // isMarkSet = false; // Optional: Invalidate mark after reset
    }
}
