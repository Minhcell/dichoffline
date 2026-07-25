package com.dichoffline.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Giải mã MỌI định dạng ghi âm mà Android hỗ trợ
 * (.mp3 .m4a .aac .wav .amr .3gp .ogg .opus .flac .mp4 .mkv ...)
 * về PCM 16-bit, mono, 16 kHz - đúng chuẩn Vosk cần.
 */
object AudioDecoder {

    const val TARGET_RATE = 16000

    /** Bộ đổi tần số lấy mẫu kiểu nội suy tuyến tính, giữ trạng thái giữa các khối. */
    class Resampler(private var srcRate: Int, private val dstRate: Int) {
        private var carry = ShortArray(0)
        private var pos = 0.0

        fun setSrcRate(rate: Int) {
            if (rate != srcRate) {
                srcRate = rate
                carry = ShortArray(0)
                pos = 0.0
            }
        }

        fun process(chunk: ShortArray): ShortArray {
            if (srcRate == dstRate) return chunk
            if (chunk.isEmpty()) return chunk
            val buf = if (carry.isEmpty()) chunk else carry + chunk
            val step = srcRate.toDouble() / dstRate
            val estimate = (((buf.size - pos) / step).toInt() + 2).coerceAtLeast(1)
            val out = ShortArray(estimate)
            var k = 0
            var p = pos
            while (p + 1 < buf.size && k < estimate) {
                val i = p.toInt()
                val f = p - i
                val v = buf[i] * (1.0 - f) + buf[i + 1] * f
                out[k++] = v.toInt().coerceIn(-32768, 32767).toShort()
                p += step
            }
            val consumed = p.toInt().coerceIn(0, buf.size)
            carry = buf.copyOfRange(consumed, buf.size)
            pos = p - consumed
            return if (k == estimate) out else out.copyOf(k)
        }
    }

    private fun bytesToShorts(bytes: ByteArray, size: Int): ShortArray {
        val n = size / 2
        val out = ShortArray(n)
        ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(out)
        return out
    }

    private fun downmix(data: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return data
        val frames = data.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += data[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * @param onPcm nhận từng khối PCM 16k mono
     * @param onProgress 0f..1f
     */
    @Throws(IOException::class)
    fun decode(
        context: Context,
        uri: Uri,
        onPcm: (ShortArray) -> Unit,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("Không mở được file ghi âm")

        try {
            afd.use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i; format = f; break
                    }
                }
                if (trackIndex < 0 || format == null) {
                    throw IOException("File không chứa dữ liệu âm thanh")
                }
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = runCatching {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(1)
                val durationUs = runCatching {
                    format.getLong(MediaFormat.KEY_DURATION)
                }.getOrDefault(0L)

                val resampler = Resampler(srcRate, TARGET_RATE)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                try {
                    while (!outputDone) {
                        if (isCancelled()) throw IOException("Đã huỷ")

                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10000)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val pts = extractor.sampleTime
                                    codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                                    extractor.advance()
                                    if (durationUs > 0) {
                                        onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }

                        when (val outIdx = codec.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val nf = codec.outputFormat
                                runCatching {
                                    srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    resampler.setSrcRate(srcRate)
                                }
                                runCatching {
                                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> { /* chờ tiếp */ }
                            else -> {
                                if (outIdx >= 0) {
                                    if (info.size > 0) {
                                        val out = codec.getOutputBuffer(outIdx)!!
                                        out.position(info.offset)
                                        out.limit(info.offset + info.size)
                                        val bytes = ByteArray(info.size)
                                        out.get(bytes)
                                        out.clear()
                                        val pcm = bytesToShorts(bytes, info.size)
                                        val mono = downmix(pcm, channels)
                                        val res = resampler.process(mono)
                                        if (res.isNotEmpty()) onPcm(res)
                                    }
                                    codec.releaseOutputBuffer(outIdx, false)
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        outputDone = true
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
                onProgress(1f)
            }
        } finally {
            runCatching { extractor.release() }
        }
    }
}
