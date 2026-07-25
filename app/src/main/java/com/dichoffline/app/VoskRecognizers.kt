package com.dichoffline.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Một đoạn đã nhận dạng xong, kèm vector giọng nếu có gói tách giọng. */
class VoskSegment(
    val text: String,
    val speakerVector: FloatArray?,
    val speakerFrames: Int
)

object VoskModels {
    private val cache = ConcurrentHashMap<String, Model>()
    private var spk: SpeakerModel? = null

    @Throws(IOException::class)
    fun load(ctx: Context, code: String): Model {
        val cached = cache[code]
        if (cached != null) return cached
        if (!ModelManager.isInstalled(ctx, code)) {
            throw IOException("Chưa cài gói offline cho ${LangCatalog.nameOf(code)}")
        }
        val m = Model(ModelManager.dirOf(ctx, code).absolutePath)
        cache[code] = m
        return m
    }

    @Throws(IOException::class)
    fun loadSpk(ctx: Context): SpeakerModel {
        val cached = spk
        if (cached != null) return cached
        if (!ModelManager.isInstalled(ctx, ModelManager.SPK_CODE)) {
            throw IOException("Chưa cài gói tách giọng người nói")
        }
        val m = SpeakerModel(ModelManager.dirOf(ctx, ModelManager.SPK_CODE).absolutePath)
        spk = m
        return m
    }

    fun releaseAll() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
        runCatching { spk?.close() }
        spk = null
    }
}

internal fun parseSegment(json: String?, textKey: String = "text"): VoskSegment? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val text = o.optString(textKey, "").trim()
        val arr = o.optJSONArray("spk")
        var vec: FloatArray? = null
        if (arr != null && arr.length() > 0) {
            val f = FloatArray(arr.length())
            for (i in 0 until arr.length()) f[i] = arr.optDouble(i, 0.0).toFloat()
            vec = f
        }
        VoskSegment(text, vec, o.optInt("spk_frames", 0))
    }.getOrNull()
}

internal fun tuneRecognizer(rec: Recognizer) {
    // Gọi qua reflection để không phụ thuộc phiên bản vosk-android
    val bool = java.lang.Boolean.TYPE
    runCatching { rec.javaClass.getMethod("setWords", bool).invoke(rec, false) }
    runCatching { rec.javaClass.getMethod("setPartialWords", bool).invoke(rec, false) }
}

/** Tạo Recognizer, có hoặc không kèm gói tách giọng. */
@Throws(IOException::class)
internal fun newRecognizer(model: Model, speakerModel: SpeakerModel?): Recognizer {
    val rate = AudioDecoder.TARGET_RATE.toFloat()
    val rec = if (speakerModel != null) {
        Recognizer(model, rate, speakerModel)
    } else {
        Recognizer(model, rate)
    }
    tuneRecognizer(rec)
    return rec
}

/**
 * Nhận dạng trực tiếp từ micro, offline 100%.
 *
 *  - Đọc khối 100ms nên chữ hiện nhanh.
 *  - Tự chốt câu khi kết quả tạm đứng yên quá [silenceMs] thay vì đợi Vosk
 *    tự ngắt (Vosk mặc định đợi rất lâu, nhất là với tiếng Hàn).
 *  - Luồng âm thanh chạy ở mức ưu tiên URGENT_AUDIO.
 */
class VoskLiveRecognizer(
    private val model: Model,
    private val spkModel: SpeakerModel?,
    private val onPartial: (String) -> Unit,
    private val onFinal: (VoskSegment) -> Unit,
    private val onError: (String) -> Unit
) {
    var silenceMs: Long = 800

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun isRunning() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var recRef: Recognizer? = null
            var arRef: AudioRecord? = null
            try {
                val rec = newRecognizer(model, spkModel)
                recRef = rec

                val minBuf = AudioRecord.getMinBufferSize(
                    AudioDecoder.TARGET_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioDecoder.TARGET_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, AudioDecoder.TARGET_RATE) * 2
                )
                arRef = ar
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    throw IOException("Không truy cập được micro")
                }
                ar.startRecording()

                val buffer = ShortArray(AudioDecoder.TARGET_RATE / 10) // 100ms
                var lastPartial = ""
                var lastChangeAt = System.currentTimeMillis()

                while (running) {
                    val n = ar.read(buffer, 0, buffer.size)
                    if (n <= 0) continue

                    if (rec.acceptWaveForm(buffer, n)) {
                        val seg = parseSegment(rec.result)
                        lastPartial = ""
                        lastChangeAt = System.currentTimeMillis()
                        if (seg != null && seg.text.isNotEmpty()) main.post { onFinal(seg) }
                    } else {
                        val p = parseSegment(rec.partialResult, "partial")?.text.orEmpty()
                        val now = System.currentTimeMillis()
                        if (p != lastPartial) {
                            lastPartial = p
                            lastChangeAt = now
                            if (p.isNotEmpty()) main.post { onPartial(p) }
                        } else if (p.isNotEmpty() && now - lastChangeAt >= silenceMs) {
                            // Người nói đã dừng -> chốt câu ngay, không đợi thêm
                            val seg = parseSegment(rec.finalResult)
                            runCatching { rec.reset() }
                            lastPartial = ""
                            lastChangeAt = now
                            if (seg != null && seg.text.isNotEmpty()) main.post { onFinal(seg) }
                        }
                    }
                }

                val tail = parseSegment(rec.finalResult)
                if (tail != null && tail.text.isNotEmpty()) {
                    main.post { onFinal(tail) }
                } else if (lastPartial.isNotEmpty()) {
                    val fb = lastPartial
                    main.post { onFinal(VoskSegment(fb, null, 0)) }
                }

            } catch (e: Exception) {
                val msg = e.message ?: "Lỗi nhận dạng"
                main.post { onError(msg) }
            } finally {
                runCatching { arRef?.stop() }
                runCatching { arRef?.release() }
                runCatching { recRef?.close() }
                running = false
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(1500) }
        thread = null
    }
}

/** Nhận dạng file ghi âm có sẵn, có kèm vector giọng để tách người nói. */
object VoskFileRecognizer {

    @Throws(IOException::class)
    fun transcribe(
        ctx: Context,
        uri: Uri,
        model: Model,
        spkModel: SpeakerModel?,
        onSegment: (VoskSegment) -> Unit,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val rec = newRecognizer(model, spkModel)
        try {
            AudioDecoder.decode(
                context = ctx,
                uri = uri,
                onPcm = { pcm ->
                    if (rec.acceptWaveForm(pcm, pcm.size)) {
                        val seg = parseSegment(rec.result)
                        if (seg != null && seg.text.isNotEmpty()) onSegment(seg)
                    }
                },
                onProgress = onProgress,
                isCancelled = isCancelled
            )
            val tail = parseSegment(rec.finalResult)
            if (tail != null && tail.text.isNotEmpty()) onSegment(tail)
        } finally {
            runCatching { rec.close() }
        }
    }
}
