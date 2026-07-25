package com.dichoffline.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Nhận dạng bằng bộ máy của Google (ưu tiên gói offline đã tải trong máy).
 * Tự khởi động lại liên tục để "nói tới đâu dịch tới đó".
 * Có tắt tiếng "bíp" khó chịu khi khởi động lại nhiều lần.
 */
class GoogleLiveRecognizer(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var lastPartial = ""
    private var bcp47 = "en-US"
    private var muted = false

    fun isRunning() = running

    fun start(languageTag: String) {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Máy chưa có dịch vụ nhận dạng của Google. Hãy chuyển sang chế độ Vosk.")
            return
        }
        bcp47 = languageTag.ifBlank { "en-US" }
        running = true
        muteBeep(true)
        listenOnce()
    }

    fun stop() {
        running = false
        flushPartial()
        main.post {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        muteBeep(false)
    }

    private fun flushPartial() {
        val p = lastPartial.trim()
        lastPartial = ""
        if (p.isNotEmpty()) onFinal(p)
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

    private fun listenOnce() {
        main.post {
            if (!running) return@post
            runCatching { recognizer?.destroy() }
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val t = list?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        lastPartial = t
                        onPartial(t)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val list = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val t = list?.firstOrNull()?.trim().orEmpty()
                    // Lớp dự phòng: nhiều máy (HyperOS/MIUI) trả kết quả rỗng
                    val use = if (t.isNotEmpty()) t else lastPartial.trim()
                    lastPartial = ""
                    if (use.isNotEmpty()) onFinal(use)
                    restart(120)
                }

                override fun onError(error: Int) {
                    // Lớp dự phòng thứ hai: dùng lại partial cuối cùng
                    flushPartial()
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(250)

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            running = false
                            muteBeep(false)
                            this@GoogleLiveRecognizer.onError("Chưa được cấp quyền micro")
                        }
                        else -> restart(400)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            runCatching { r.startListening(buildIntent()) }
                .onFailure { restart(500) }
        }
    }

    private fun restart(delay: Long) {
        if (!running) return
        main.postDelayed({ if (running) listenOnce() }, delay)
    }

    private fun muteBeep(mute: Boolean) {
        if (muted == mute) return
        muted = mute
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM
        )
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        streams.forEach { s -> runCatching { am.adjustStreamVolume(s, dir, 0) } }
    }
}
