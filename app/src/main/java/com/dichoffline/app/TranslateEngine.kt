package com.dichoffline.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Dịch offline bằng ML Kit.
 *
 * Tối ưu tốc độ so với bản 1:
 *  - Chỉ gọi downloadModelIfNeeded() MỘT lần cho mỗi ngôn ngữ (prewarm),
 *    các lần dịch sau gọi thẳng translate() nên nhanh hơn hẳn.
 *  - Có bộ nhớ đệm câu đã dịch để không dịch lại kết quả tạm trùng nhau.
 */
object TranslateEngine {

    private val cache = ConcurrentHashMap<String, Translator>()
    private val ready = Collections.synchronizedSet(HashSet<String>())
    private val memo = object : LinkedHashMap<String, String>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.34f)
            .build()
    )

    fun isReady(code: String) = code == LangCatalog.TARGET || ready.contains(code)

    private fun translatorFor(src: String): Translator? {
        val s = TranslateLanguage.fromLanguageTag(src) ?: return null
        val t = TranslateLanguage.fromLanguageTag(LangCatalog.TARGET) ?: return null
        return cache.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(s)
                    .setTargetLanguage(t)
                    .build()
            )
        }
    }

    /** Nạp sẵn gói dịch. Gọi ngay khi người dùng chọn ngôn ngữ để lúc dịch không phải chờ. */
    fun prewarm(src: String, onResult: (ok: Boolean, error: String?) -> Unit = { _, _ -> }) {
        if (src.isBlank() || src == LangCatalog.AUTO) { onResult(true, null); return }
        if (isReady(src)) { onResult(true, null); return }
        val tr = translatorFor(src)
        if (tr == null) { onResult(false, "Chưa hỗ trợ dịch ngôn ngữ này"); return }
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready.add(src)
                // "hâm nóng" bằng một câu ngắn để lần dịch thật không bị khựng
                tr.translate("xin chào")
                onResult(true, null)
            }
            .addOnFailureListener {
                onResult(false, "Cần mạng lần đầu để tải gói dịch ${LangCatalog.nameOf(src)}")
            }
    }

    fun detect(text: String, onResult: (String?) -> Unit) {
        if (text.isBlank()) { onResult(null); return }
        langId.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                onResult(if (tag == "und") null else tag.substringBefore('-'))
            }
            .addOnFailureListener { onResult(null) }
    }

    /** Dịch sang tiếng Việt. Đường nhanh: model đã sẵn sàng thì gọi translate() luôn. */
    fun toVietnamese(text: String, srcCode: String, onResult: (String?, String?) -> Unit) {
        if (text.isBlank()) { onResult("", null); return }
        if (srcCode == LangCatalog.TARGET) { onResult(text, null); return }

        val key = "$srcCode|$text"
        synchronized(memo) { memo[key] }?.let { onResult(it, null); return }

        val tr = translatorFor(srcCode)
        if (tr == null) { onResult(null, "Không hỗ trợ ${LangCatalog.nameOf(srcCode)}"); return }

        fun run() {
            tr.translate(text)
                .addOnSuccessListener {
                    synchronized(memo) { memo[key] = it }
                    onResult(it, null)
                }
                .addOnFailureListener { e -> onResult(null, e.message) }
        }

        if (isReady(srcCode)) {
            run()
        } else {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { ready.add(srcCode); run() }
                .addOnFailureListener {
                    onResult(null, "Cần mạng lần đầu để tải gói dịch ${LangCatalog.nameOf(srcCode)}")
                }
        }
    }

    fun autoTranslate(text: String, onResult: (String?, String?, String?) -> Unit) {
        detect(text) { code ->
            val src = code ?: "en"
            toVietnamese(text, src) { vi, err -> onResult(src, vi, err) }
        }
    }

    fun release() {
        cache.values.forEach { it.close() }
        cache.clear()
        ready.clear()
        synchronized(memo) { memo.clear() }
    }
}
