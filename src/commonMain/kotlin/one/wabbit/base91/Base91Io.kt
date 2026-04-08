package one.wabbit.base91

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

fun Sink.base91Encoding(
    inputBufferSize: Int = 13 * 3,
    outputBufferSize: Int = 16 * 3,
): Sink {
    requireBase91EncodingBufferSizes(inputBufferSize, outputBufferSize)
    return Base91EncodingRawSink(this, inputBufferSize, outputBufferSize).buffered()
}

fun Source.base91Decoding(
    encodedBufferSize: Int = 16 * 3,
    decodedBufferSize: Int = 14 * 3,
): Source {
    requireBase91DecodingBufferSizes(encodedBufferSize, decodedBufferSize)
    return Base91DecodingRawSource(this, encodedBufferSize, decodedBufferSize).buffered()
}

private class Base91EncodingRawSink(
    private val downstream: Sink,
    inputBufferSize: Int,
    outputBufferSize: Int,
) : RawSink {
    private val encoder = Base91.Encoder()
    private val inputBuffer = ByteArray(inputBufferSize)
    private val outputBuffer = ByteArray(outputBufferSize)
    private var inputBufferCount = 0
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "sink is closed" }
        require(byteCount >= 0L) { "byteCount: $byteCount" }

        var remaining = byteCount
        val scratch = ByteArray(minOf(inputBuffer.size, 8192))

        while (remaining > 0L) {
            val chunkSize = minOf(scratch.size.toLong(), remaining).toInt()
            val read = source.readAtMostTo(scratch, 0, chunkSize)
            require(read == chunkSize) {
                "Source exhausted before writing $byteCount bytes (read ${byteCount - remaining + read})"
            }
            writeChunk(scratch, 0, read)
            remaining -= read.toLong()
        }
    }

    override fun flush() {
        check(!closed) { "sink is closed" }
        flushBufferedInput()
        downstream.flush()
    }

    override fun close() {
        if (closed) return
        try {
            flushBufferedInput()
            val finalEncodedCount = encoder.finish(outputBuffer, 0)
            if (finalEncodedCount > 0) {
                downstream.write(outputBuffer, 0, finalEncodedCount)
            }
        } finally {
            closed = true
            downstream.close()
            encoder.reset()
        }
    }

    private fun writeChunk(bytes: ByteArray, offset: Int, length: Int) {
        var remainingLen = length
        var currentOffset = offset

        while (remainingLen > 0) {
            val spaceInBuffer = inputBuffer.size - inputBufferCount
            val bytesToCopy = minOf(remainingLen, spaceInBuffer)

            bytes.copyInto(
                destination = inputBuffer,
                destinationOffset = inputBufferCount,
                startIndex = currentOffset,
                endIndex = currentOffset + bytesToCopy,
            )
            inputBufferCount += bytesToCopy
            currentOffset += bytesToCopy
            remainingLen -= bytesToCopy

            if (inputBufferCount >= inputBuffer.size) {
                flushBufferedInput()
            }
        }
    }

    private fun flushBufferedInput() {
        if (inputBufferCount <= 0) return
        val encodedCount = encoder.encodeChunk(inputBuffer, 0, inputBufferCount, outputBuffer, 0)
        if (encodedCount > 0) {
            downstream.write(outputBuffer, 0, encodedCount)
        }
        inputBufferCount = 0
    }
}

private class Base91DecodingRawSource(
    private val upstream: Source,
    encodedBufferSize: Int,
    decodedBufferSize: Int,
) : RawSource {
    private val decoder = Base91.Decoder()
    private val encodedBuffer = ByteArray(encodedBufferSize)
    private val decodedBuffer = ByteArray(decodedBufferSize)
    private var decodedReadPos = 0
    private var decodedCount = 0
    private var upstreamExhausted = false
    private var emittedFinalBytes = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "source is closed" }
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        if (byteCount == 0L) return 0L

        while (decodedReadPos >= decodedCount) {
            if (!refillDecodedBuffer()) return -1L
        }

        val bytesToRead =
            minOf(byteCount, (decodedCount - decodedReadPos).toLong()).toInt()
        sink.write(decodedBuffer, decodedReadPos, decodedReadPos + bytesToRead)
        decodedReadPos += bytesToRead
        return bytesToRead.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        upstream.close()
        decoder.reset()
    }

    private fun refillDecodedBuffer(): Boolean {
        decodedReadPos = 0
        decodedCount = 0

        while (decodedCount == 0) {
            if (!upstreamExhausted) {
                val bytesRead = upstream.readAtMostTo(encodedBuffer)
                if (bytesRead == -1) {
                    upstreamExhausted = true
                } else if (bytesRead > 0) {
                    decodedCount =
                        decoder.decodeChunk(
                            encodedBuffer,
                            0,
                            bytesRead,
                            decodedBuffer,
                            0,
                        )
                    if (decodedCount > 0) return true
                    continue
                }
            }

            if (upstreamExhausted && !emittedFinalBytes) {
                emittedFinalBytes = true
                decodedCount = decoder.finish(decodedBuffer, 0)
                return decodedCount > 0
            }

            return false
        }

        return true
    }
}
