package com.dichoffline.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import java.util.Locale

/** Đọc lại nội dung ngôn ngữ gốc khi bấm biểu tượng loa. */
class TtsHelper(context: Context, private val onStatus: (String) -> Unit) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<Pair<String, String>>()

    init {
        tts = TextToSpeech(context.applicationContext, OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                pending.forEach { (text, tag) -> speak(text, tag) }
            } else {
                onStatus("Máy chưa cài bộ đọc (TTS)")
            }
            pending.clear()
        })
    }

    fun speak(text: String, bcp47: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            pending.add(text to bcp47)
            return
        }
        val locale = runCatching { Locale.forLanguageTag(bcp47) }.getOrDefault(Locale.US)
        when (engine.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                onStatus("Máy chưa có giọng đọc cho ngôn ngữ này")
                return
            }
        }
        engine.stop()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-${System.currentTimeMillis()}")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
