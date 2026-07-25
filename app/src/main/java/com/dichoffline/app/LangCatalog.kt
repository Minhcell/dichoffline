package com.dichoffline.app

/**
 * Danh mục ngôn ngữ.
 * code   : mã ML Kit (dùng để dịch)
 * bcp47  : dùng cho SpeechRecognizer của Google và TTS đọc lại
 * vosk*  : gói nhận dạng giọng nói offline (tải về khi cần)
 */
data class Lang(
    val code: String,
    val bcp47: String,
    val name: String,
    val voskUrl: String? = null,
    val voskSizeMb: Int = 0
)

object LangCatalog {

    const val AUTO = "auto"
    const val TARGET = "vi" // Mọi ngôn ngữ đều dịch ra tiếng Việt

    private const val BASE = "https://alphacephei.com/vosk/models/"

    val all: List<Lang> = listOf(
        Lang(AUTO, "", "Tự nhận diện ngôn ngữ"),

        Lang("vi", "vi-VN", "Tiếng Việt", BASE + "vosk-model-small-vn-0.4.zip", 32),
        Lang("en", "en-US", "Tiếng Anh", BASE + "vosk-model-small-en-us-0.15.zip", 40),
        Lang("zh", "zh-CN", "Tiếng Trung", BASE + "vosk-model-small-cn-0.22.zip", 42),
        Lang("ja", "ja-JP", "Tiếng Nhật", BASE + "vosk-model-small-ja-0.22.zip", 48),
        Lang("ko", "ko-KR", "Tiếng Hàn", BASE + "vosk-model-small-ko-0.22.zip", 82),
        Lang("fr", "fr-FR", "Tiếng Pháp", BASE + "vosk-model-small-fr-0.22.zip", 41),
        Lang("de", "de-DE", "Tiếng Đức", BASE + "vosk-model-small-de-0.15.zip", 45),
        Lang("es", "es-ES", "Tiếng Tây Ban Nha", BASE + "vosk-model-small-es-0.42.zip", 39),
        Lang("pt", "pt-BR", "Tiếng Bồ Đào Nha", BASE + "vosk-model-small-pt-0.3.zip", 31),
        Lang("it", "it-IT", "Tiếng Ý", BASE + "vosk-model-small-it-0.22.zip", 48),
        Lang("ru", "ru-RU", "Tiếng Nga", BASE + "vosk-model-small-ru-0.22.zip", 45),
        Lang("nl", "nl-NL", "Tiếng Hà Lan", BASE + "vosk-model-small-nl-0.22.zip", 39),
        Lang("tr", "tr-TR", "Tiếng Thổ Nhĩ Kỳ", BASE + "vosk-model-small-tr-0.3.zip", 35),
        Lang("hi", "hi-IN", "Tiếng Hindi", BASE + "vosk-model-small-hi-0.22.zip", 42),
        Lang("pl", "pl-PL", "Tiếng Ba Lan", BASE + "vosk-model-small-pl-0.22.zip", 50),
        Lang("uk", "uk-UA", "Tiếng Ukraina", BASE + "vosk-model-small-uk-v3-nano.zip", 73),
        Lang("cs", "cs-CZ", "Tiếng Séc", BASE + "vosk-model-small-cs-0.4-rhasspy.zip", 44),
        Lang("ca", "ca-ES", "Tiếng Catalan", BASE + "vosk-model-small-ca-0.4.zip", 42),
        Lang("fa", "fa-IR", "Tiếng Ba Tư", BASE + "vosk-model-small-fa-0.42.zip", 53),
        Lang("ka", "ka-GE", "Tiếng Gruzia", BASE + "vosk-model-small-ka-0.42.zip", 45),
        Lang("te", "te-IN", "Tiếng Telugu", BASE + "vosk-model-small-te-0.42.zip", 58),
        Lang("gu", "gu-IN", "Tiếng Gujarati", BASE + "vosk-model-small-gu-0.42.zip", 100),
        Lang("eo", "eo", "Quốc tế ngữ (Esperanto)", BASE + "vosk-model-small-eo-0.42.zip", 42),
        Lang("ar", "ar-SA", "Tiếng Ả Rập", BASE + "vosk-model-ar-mgb2-0.4.zip", 318),
        Lang("tl", "fil-PH", "Tiếng Philippines", BASE + "vosk-model-tl-ph-generic-0.6.zip", 320),
        Lang("sv", "sv-SE", "Tiếng Thụy Điển", BASE + "vosk-model-small-sv-rhasspy-0.15.zip", 289),

        // Các ngôn ngữ ML Kit dịch được nhưng chưa có gói Vosk:
        // vẫn dùng được khi nhận dạng bằng Google hoặc khi tự nhận diện văn bản
        Lang("th", "th-TH", "Tiếng Thái"),
        Lang("id", "id-ID", "Tiếng Indonesia"),
        Lang("ms", "ms-MY", "Tiếng Mã Lai"),
        Lang("bn", "bn-BD", "Tiếng Bengal"),
        Lang("ta", "ta-IN", "Tiếng Tamil"),
        Lang("ur", "ur-PK", "Tiếng Urdu"),
        Lang("he", "iw-IL", "Tiếng Do Thái"),
        Lang("el", "el-GR", "Tiếng Hy Lạp"),
        Lang("ro", "ro-RO", "Tiếng Rumani"),
        Lang("hu", "hu-HU", "Tiếng Hungary"),
        Lang("fi", "fi-FI", "Tiếng Phần Lan"),
        Lang("da", "da-DK", "Tiếng Đan Mạch"),
        Lang("no", "nb-NO", "Tiếng Na Uy"),
        Lang("bg", "bg-BG", "Tiếng Bulgaria"),
        Lang("hr", "hr-HR", "Tiếng Croatia"),
        Lang("sk", "sk-SK", "Tiếng Slovakia"),
        Lang("sl", "sl-SI", "Tiếng Slovenia"),
        Lang("sq", "sq-AL", "Tiếng Albania"),
        Lang("sw", "sw-KE", "Tiếng Swahili"),
        Lang("af", "af-ZA", "Tiếng Afrikaans")
    )

    /** Chỉ các ngôn ngữ có gói nhận dạng offline Vosk */
    val withVosk: List<Lang> get() = all.filter { it.voskUrl != null }

    fun byCode(code: String?): Lang? = all.firstOrNull { it.code == code }

    fun nameOf(code: String?): String = byCode(code)?.name ?: (code ?: "?")

    fun bcp47Of(code: String?): String = byCode(code)?.bcp47 ?: "en-US"
}
