package one.wabbit

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * An OutputStream that encodes written bytes into Base91 format and writes the result
 * to an underlying OutputStream.
 *
 * Remember to close() the stream to ensure all buffered data is encoded and flushed.
 */
class Base91EncoderStream(
    outputStream: OutputStream,
    // Using buffer sizes that are multiples of typical block sizes (e.g., 13/14 bytes in, ~16 bytes out)
    // Can be tuned, but these are reasonable defaults.
    private val inputBufferSize: Int = 13 * 3, // Process ~3 blocks of input
    private val outputBufferSize: Int = 16 * 3 // Room for ~3 blocks of output + finish
) : FilterOutputStream(outputStream) {

    private val encoder = Base91.Encoder()
    private val inputBuffer: ByteArray = ByteArray(inputBufferSize)
    private val outputBuffer: ByteArray = ByteArray(outputBufferSize)
    private var inputBufferCount: Int = 0 // Number of bytes currently in inputBuffer

    /**
     * Encodes the contents of the input buffer and writes the resulting
     * Base91 characters to the underlying output stream.
     */
    @Throws(IOException::class)
    private fun flushBuffer() {
        if (inputBufferCount > 0) {
            val encodedCount = encoder.encodeChunk(inputBuffer, 0, inputBufferCount, outputBuffer, 0)
            if (encodedCount > 0) {
                out.write(outputBuffer, 0, encodedCount)
            }
            inputBufferCount = 0 // Reset buffer count after flushing
        }
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        // Add the byte to the buffer
        inputBuffer[inputBufferCount++] = b.toByte()
        // If the buffer is full, flush it
        if (inputBufferCount >= inputBuffer.size) {
            flushBuffer()
        }
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || len > b.size - off) {
            throw IndexOutOfBoundsException()
        }
        if (len == 0) {
            return
        }

        var remainingLen = len
        var currentOffset = off

        // Fill the buffer repeatedly and flush
        while (remainingLen > 0) {
            val spaceInBuffer = inputBuffer.size - inputBufferCount
            val bytesToCopy = minOf(remainingLen, spaceInBuffer)

            System.arraycopy(b, currentOffset, inputBuffer, inputBufferCount, bytesToCopy)
            inputBufferCount += bytesToCopy
            currentOffset += bytesToCopy
            remainingLen -= bytesToCopy

            // If buffer is full, flush it
            if (inputBufferCount >= inputBuffer.size) {
                flushBuffer()
            }
        }
    }

    /**
     * Flushes the stream. This encodes any buffered bytes and writes them to the
     * underlying stream. It then calls the `finish` method on the encoder
     * to handle any final leftover bits and writes the result. Finally, it flushes
     * the underlying output stream.
     */
    @Throws(IOException::class)
    override fun flush() {
        // Encode and write any remaining bytes in the input buffer
        flushBuffer()
        // Process leftover bits in the encoder's queue
        val finalEncodedCount = encoder.finish(outputBuffer, 0)
        if (finalEncodedCount > 0) {
            out.write(outputBuffer, 0, finalEncodedCount)
        }
        // Flush the underlying stream
        super.flush()
    }

    /**
     * Closes this output stream and releases any system resources associated
     * with this stream. This method first flushes the stream, then closes the
     * underlying output stream.
     */
    @Throws(IOException::class)
    override fun close() {
        try {
            // Ensure everything is flushed before closing
            flush()
        } finally {
            // Close the underlying stream even if flush fails
            super.close()
            // Clean up encoder state (optional, as stream is closed)
            encoder.reset()
        }
    }
}
