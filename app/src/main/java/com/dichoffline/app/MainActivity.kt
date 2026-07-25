package com.dichoffline.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dichoffline.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.SpeakerModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConvMode(val label: String) {
    SPEAKER_ID("Hội thoại · tách giọng tự động"),
    PUSH_TO_TALK("Hội thoại · bấm nút theo lượt"),
    ALTERNATE("Hội thoại · luân phiên tự động"),
    SINGLE("Một người nói")
}

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: ResultAdapter
    private lateinit var tts: TtsHelper

    private val items = mutableListOf<ResultItem>()
    private val main = Handler(Looper.getMainLooper())

    private var mode = ConvMode.SPEAKER_ID
    private var lang1 = LangCatalog.AUTO
    private var lang2 = LangCatalog.TARGET
    private var useVosk = true

    private var voskLive: VoskLiveRecognizer? = null
    private var googleLive: GoogleLiveRecognizer? = null
    private val clusterer = SpeakerClusterer()

    private var activeSpeaker = 0          // dùng cho bấm-theo-lượt và luân phiên
    private var liveIndex = -1
    private var reqCounter = 0L
    private var pendingTranslate: Runnable? = null

    @Volatile private var fileJobCancelled = false
    private var fileRunning = false

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Đã cấp quyền, bấm lại để bắt đầu")
        else toast("Cần quyền micro để dịch trực tiếp")
    }

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onAudioPicked(it) } }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        clusterer.threshold = prefs.getInt("spk_threshold", 72) / 100f

        tts = TtsHelper(this) { msg -> toast(msg) }

        adapter = ResultAdapter(items,
            onSpeak = { tts.speak(it.srcText, LangCatalog.bcp47Of(it.langCode)) },
            onCopy = { copy(it.viText.ifBlank { it.srcText }) },
            onDelete = { pos -> confirmDeleteItem(pos) },
            onLongPress = { pos -> changeSpeakerDialog(pos) }
        )
        b.rvResults.layoutManager = LinearLayoutManager(this)
        b.rvResults.adapter = adapter

        setupModeDropdown()
        setupLanguageDropdowns()

        b.swVosk.isChecked = true
        b.swVosk.setOnCheckedChangeListener { _, checked ->
            useVosk = checked
            b.swVosk.text = getString(
                if (checked) R.string.engine_vosk else R.string.engine_google
            )
            if (!checked && mode == ConvMode.SPEAKER_ID) {
                toast("Tách giọng tự động chỉ chạy với Vosk")
            }
        }

        b.btnModels.setOnClickListener { startActivity(Intent(this, ModelsActivity::class.java)) }

        b.btnMic.setOnClickListener { toggleListening(speaker = null) }
        b.btnP1.setOnClickListener { toggleListening(speaker = 0) }
        b.btnP2.setOnClickListener { toggleListening(speaker = 1) }

        b.btnFile.setOnClickListener {
            if (fileRunning) {
                fileJobCancelled = true
                status("Đang huỷ…")
            } else {
                pickAudio.launch(
                    arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")
                )
            }
        }

        b.btnSave.setOnClickListener { saveDialog() }
        b.btnCopyAll.setOnClickListener {
            if (items.isEmpty()) toast("Chưa có nội dung") else copy(adapter.allText())
        }
        b.btnClear.setOnClickListener { confirmClearAll() }

        applyModeUi()
        status(getString(R.string.status_ready))
    }

    private fun setupModeDropdown() {
        val labels = ConvMode.values().map { it.label }
        b.dropdownMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        b.dropdownMode.setText(mode.label, false)
        b.dropdownMode.setOnItemClickListener { _, _, pos, _ ->
            if (isListening()) stopListening()
            mode = ConvMode.values()[pos]
            clusterer.reset()
            activeSpeaker = 0
            applyModeUi()
        }
    }

    private fun setupLanguageDropdowns() {
        val names = LangCatalog.all.map { it.name }
        listOf(b.dropdownLang1, b.dropdownLang2).forEach {
            it.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        }
        b.dropdownLang1.setText(LangCatalog.nameOf(lang1), false)
        b.dropdownLang2.setText(LangCatalog.nameOf(lang2), false)

        b.dropdownLang1.setOnItemClickListener { _, _, pos, _ ->
            lang1 = LangCatalog.all[pos].code
            prewarm(lang1)
        }
        b.dropdownLang2.setOnItemClickListener { _, _, pos, _ ->
            lang2 = LangCatalog.all[pos].code
            prewarm(lang2)
        }
    }

    private fun prewarm(code: String) {
        if (code == LangCatalog.AUTO) return
        status("Đang nạp sẵn gói dịch ${LangCatalog.nameOf(code)}…")
        TranslateEngine.prewarm(code) { ok, err ->
            status(if (ok) getString(R.string.status_ready) else (err ?: ""))
        }
    }

    private fun applyModeUi() {
        val ptt = mode == ConvMode.PUSH_TO_TALK
        b.btnMic.visibility = if (ptt) View.GONE else View.VISIBLE
        b.btnP1.visibility = if (ptt) View.VISIBLE else View.GONE
        b.btnP2.visibility = if (ptt) View.VISIBLE else View.GONE
        b.tilLang2.visibility = if (ptt) View.VISIBLE else View.GONE
        b.tilLang1.hint = if (ptt) "Người 1" else "Ngôn ngữ hội thoại"
        b.btnFile.text = if (ptt) "File" else getString(R.string.btn_file)

        b.tvHint.text = when (mode) {
            ConvMode.SPEAKER_ID ->
                "App tự tách 2 giọng bằng gói “tách giọng người nói”. Cả hai nói cùng một thứ tiếng. Nhấn giữ một thẻ để sửa lại người nói nếu tách sai."
            ConvMode.PUSH_TO_TALK ->
                "Mỗi người bấm nút của mình trước khi nói. Hai bên có thể dùng hai thứ tiếng khác nhau."
            ConvMode.ALTERNATE ->
                "Cứ mỗi câu chốt xong thì đổi sang người kia. Hợp khi hai người nói luân phiên đều đặn."
            ConvMode.SINGLE ->
                "Chỉ một người nói, không gắn nhãn người nói."
        }
    }

    // ------------------------------------------------------------ nghe & dịch

    private fun isListening() = voskLive?.isRunning() == true || googleLive?.isRunning() == true

    private fun toggleListening(speaker: Int?) {
        if (isListening()) {
            // Đang nghe: bấm đúng nút đang hoạt động thì dừng, bấm nút kia thì đổi người
            if (speaker == null || speaker == activeSpeaker) {
                stopListening(); return
            }
            stopListening()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        activeSpeaker = speaker ?: 0
        startListening()
    }

    private fun startListening() {
        val langForRecognition = when (mode) {
            ConvMode.PUSH_TO_TALK -> if (activeSpeaker == 1) lang2 else lang1
            else -> lang1
        }

        if (!useVosk) {
            if (mode == ConvMode.SPEAKER_ID) {
                toast("Chế độ tách giọng cần bật Vosk"); return
            }
            val tag = if (langForRecognition == LangCatalog.AUTO)
                Locale.getDefault().toLanguageTag()
            else LangCatalog.bcp47Of(langForRecognition)

            val fixed = if (langForRecognition == LangCatalog.AUTO) null else langForRecognition
            googleLive = GoogleLiveRecognizer(
                ctx = this,
                onPartial = { onPartial(it) },
                onFinal = { onFinal(it, fixed, null) },
                onError = { status(it); setListeningUi(false) }
            ).also { it.start(tag) }
            setListeningUi(true)
            status(getString(R.string.status_listening))
            return
        }

        resolveVoskCode(langForRecognition) { code ->
            lifecycleScope.launch {
                status("Đang nạp gói nhận dạng ${LangCatalog.nameOf(code)}…")
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val m = VoskModels.load(this@MainActivity, code)
                        val s: SpeakerModel? = if (mode == ConvMode.SPEAKER_ID)
                            VoskModels.loadSpk(this@MainActivity) else null
                        m to s
                    }
                }
                loaded.onSuccess { (model, spk) ->
                    TranslateEngine.prewarm(code)
                    voskLive = VoskLiveRecognizer(
                        model = model,
                        spkModel = spk,
                        onPartial = { onPartial(it) },
                        onFinal = { seg -> onFinal(seg.text, code, seg) },
                        onError = { status(it); setListeningUi(false) }
                    ).also {
                        it.silenceMs = prefs.getInt("silence_ms", 800).toLong()
                        it.start()
                    }
                    setListeningUi(true)
                    status(getString(R.string.status_listening))
                }.onFailure {
                    status(it.message ?: "Không nạp được gói offline")
                    setListeningUi(false)
                    if (it.message?.contains("tách giọng") == true) promptInstallSpk()
                }
            }
        }
    }

    private fun stopListening() {
        voskLive?.stop(); voskLive = null
        googleLive?.stop(); googleLive = null
        liveIndex = -1
        setListeningUi(false)
        status(getString(R.string.status_ready))
    }

    private fun setListeningUi(listening: Boolean) {
        val ptt = mode == ConvMode.PUSH_TO_TALK
        if (ptt) {
            b.btnP1.text = if (listening && activeSpeaker == 0) "■ Người 1" else "Người 1"
            b.btnP2.text = if (listening && activeSpeaker == 1) "■ Người 2" else "Người 2"
        } else {
            b.btnMic.text =
                if (listening) getString(R.string.btn_stop) else getString(R.string.btn_translate)
            b.btnMic.setIconResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
        }
        b.btnFile.isEnabled = !listening
    }

    /** Kết quả tạm: hiện ngay, dịch với độ trễ rất ngắn */
    private fun onPartial(text: String) {
        if (text.isBlank()) return
        if (liveIndex < 0) {
            liveIndex = adapter.addItem(
                ResultItem(text, "", knownCode(), speaker = -1, live = true)
            )
            scrollToEnd()
        } else {
            adapter.updateAt(liveIndex) { it.srcText = text }
        }
        pendingTranslate?.let { main.removeCallbacks(it) }
        val idx = liveIndex
        val r = Runnable { translateInto(idx, text, fixedCodeForTranslate()) }
        pendingTranslate = r
        main.postDelayed(r, 180)
    }

    /** Đã chốt một câu: gắn nhãn người nói rồi dịch bản chính thức */
    private fun onFinal(text: String, code: String?, seg: VoskSegment?) {
        if (text.isBlank()) return
        pendingTranslate?.let { main.removeCallbacks(it) }

        val speaker = when (mode) {
            ConvMode.SINGLE -> -1
            ConvMode.SPEAKER_ID -> clusterer.assign(seg?.speakerVector, seg?.speakerFrames ?: 0)
            ConvMode.PUSH_TO_TALK -> activeSpeaker
            ConvMode.ALTERNATE -> activeSpeaker.also { activeSpeaker = 1 - activeSpeaker }
        }

        val idx = if (liveIndex >= 0) liveIndex
        else adapter.addItem(ResultItem(text, "", code ?: knownCode()))

        adapter.updateAt(idx) {
            it.srcText = text
            it.live = false
            it.speaker = speaker
            if (code != null) it.langCode = code
        }
        translateInto(idx, text, code ?: fixedCodeForTranslate())
        liveIndex = -1
        scrollToEnd()
    }

    private fun fixedCodeForTranslate(): String? {
        val c = when (mode) {
            ConvMode.PUSH_TO_TALK -> if (activeSpeaker == 1) lang2 else lang1
            else -> lang1
        }
        return if (c == LangCatalog.AUTO) null else c
    }

    private fun knownCode(): String =
        if (lang1 == LangCatalog.AUTO) "" else lang1

    private fun translateInto(index: Int, text: String, code: String?) {
        if (index !in items.indices) return
        val id = ++reqCounter
        items[index].reqId = id

        if (code == null) {
            TranslateEngine.autoTranslate(text) { detected, vi, err ->
                main.post {
                    if (index in items.indices && items[index].reqId == id) {
                        adapter.updateAt(index) {
                            it.langCode = detected ?: it.langCode
                            it.viText = vi ?: (err ?: "")
                        }
                    }
                }
            }
        } else {
            TranslateEngine.toVietnamese(text, code) { vi, err ->
                main.post {
                    if (index in items.indices && items[index].reqId == id) {
                        adapter.updateAt(index) {
                            it.langCode = code
                            it.viText = vi ?: (err ?: "")
                        }
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- file mode

    private fun onAudioPicked(uri: Uri) {
        resolveVoskCode(lang1) { code -> transcribeFile(uri, code) }
    }

    private fun transcribeFile(uri: Uri, code: String) {
        fileJobCancelled = false
        fileRunning = true
        clusterer.reset()
        b.progress.visibility = View.VISIBLE
        b.progress.progress = 0
        b.btnFile.text = getString(R.string.btn_cancel)
        b.btnMic.isEnabled = false
        b.btnP1.isEnabled = false
        b.btnP2.isEnabled = false

        val wantSpeakers = mode == ConvMode.SPEAKER_ID &&
                ModelManager.isInstalled(this, ModelManager.SPK_CODE)

        lifecycleScope.launch {
            status("Đang nạp gói nhận dạng ${LangCatalog.nameOf(code)}…")
            TranslateEngine.prewarm(code)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val model = VoskModels.load(this@MainActivity, code)
                    val spk = if (wantSpeakers) VoskModels.loadSpk(this@MainActivity) else null
                    withContext(Dispatchers.Main) { status(getString(R.string.status_decoding)) }
                    VoskFileRecognizer.transcribe(
                        ctx = this@MainActivity,
                        uri = uri,
                        model = model,
                        spkModel = spk,
                        onSegment = { seg ->
                            main.post {
                                val sp = if (wantSpeakers)
                                    clusterer.assign(seg.speakerVector, seg.speakerFrames) else -1
                                val idx = adapter.addItem(
                                    ResultItem(seg.text, "", code, speaker = sp)
                                )
                                translateInto(idx, seg.text, code)
                                scrollToEnd()
                            }
                        },
                        onProgress = { p -> main.post { b.progress.progress = (p * 100).toInt() } },
                        isCancelled = { fileJobCancelled }
                    )
                }
            }
            fileRunning = false
            b.progress.visibility = View.GONE
            b.btnFile.text = if (mode == ConvMode.PUSH_TO_TALK) "File" else getString(R.string.btn_file)
            b.btnMic.isEnabled = true
            b.btnP1.isEnabled = true
            b.btnP2.isEnabled = true
            result.onSuccess { status(getString(R.string.status_done)) }
                .onFailure { status(it.message ?: "Lỗi xử lý file") }
        }
    }

    // ----------------------------------------------------------- chọn gói Vosk

    private fun resolveVoskCode(preferred: String, onReady: (String) -> Unit) {
        if (preferred != LangCatalog.AUTO) {
            if (!ModelManager.isInstalled(this, preferred)) { promptInstallLang(preferred); return }
            onReady(preferred); return
        }
        val installed = ModelManager.installedLangCodes(this)
        when {
            installed.isEmpty() -> AlertDialog.Builder(this)
                .setTitle(R.string.models_title)
                .setMessage("Chưa có gói nhận dạng offline nào. Mở phần Quản lý gói để tải về?")
                .setPositiveButton("Mở") { _, _ ->
                    startActivity(Intent(this, ModelsActivity::class.java))
                }
                .setNegativeButton("Đóng", null)
                .show()

            installed.size == 1 -> onReady(installed.first())

            else -> {
                val names = installed.map { LangCatalog.nameOf(it) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Giọng nói thuộc ngôn ngữ nào?")
                    .setItems(names) { _, which -> onReady(installed[which]) }
                    .show()
            }
        }
    }

    private fun promptInstallLang(code: String) {
        val lang = LangCatalog.byCode(code)
        if (lang?.voskUrl == null) {
            AlertDialog.Builder(this)
                .setTitle(LangCatalog.nameOf(code))
                .setMessage("Ngôn ngữ này chưa có gói nhận dạng offline. Hãy tắt Vosk để dùng bộ nhận dạng của Google.")
                .setPositiveButton("Đã hiểu", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(lang.name)
            .setMessage("Chưa cài gói nhận dạng offline (~${lang.voskSizeMb}MB). Tải ngay?")
            .setPositiveButton("Tải") { _, _ ->
                startActivity(Intent(this, ModelsActivity::class.java))
            }
            .setNegativeButton("Để sau", null).show()
    }

    private fun promptInstallSpk() {
        AlertDialog.Builder(this)
            .setTitle("Gói tách giọng người nói")
            .setMessage("Chế độ này cần gói tách giọng (~${ModelManager.SPK_SIZE_MB}MB). Tải ngay?")
            .setPositiveButton("Tải") { _, _ ->
                startActivity(Intent(this, ModelsActivity::class.java))
            }
            .setNegativeButton("Để sau", null).show()
    }

    // ------------------------------------------------------------- lưu & xoá

    private fun saveDialog() {
        if (items.isEmpty()) { toast("Chưa có nội dung để lưu"); return }
        val input = EditText(this).apply {
            setText(
                "Hội thoại " +
                        SimpleDateFormat("dd/MM HH:mm", Locale("vi")).format(Date())
            )
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Lưu bản dịch")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val title = input.text.toString().ifBlank { "Bản dịch" }
                ConversationStore.save(this, title, items)
                toast("Đã lưu “$title”")
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun confirmDeleteItem(pos: Int) {
        AlertDialog.Builder(this)
            .setMessage("Xoá câu này khỏi bản dịch?")
            .setPositiveButton("Xoá") { _, _ ->
                if (liveIndex == pos) liveIndex = -1
                adapter.removeAt(pos)
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun confirmClearAll() {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("Xoá toàn bộ nội dung đang hiển thị? (Bản đã lưu không bị ảnh hưởng)")
            .setPositiveButton("Xoá hết") { _, _ ->
                adapter.clear(); liveIndex = -1; clusterer.reset(); activeSpeaker = 0
                status(getString(R.string.status_ready))
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun changeSpeakerDialog(pos: Int) {
        if (pos !in items.indices) return
        val options = arrayOf("Người 1", "Người 2", "Không gắn nhãn")
        AlertDialog.Builder(this)
            .setTitle("Đổi người nói")
            .setItems(options) { _, which ->
                adapter.updateAt(pos) { it.speaker = if (which == 2) -1 else which }
            }
            .show()
    }

    private fun settingsDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val sbThr = v.findViewById<SeekBar>(R.id.sbThreshold)
        val tvThr = v.findViewById<TextView>(R.id.tvThreshold)
        val sbSil = v.findViewById<SeekBar>(R.id.sbSilence)
        val tvSil = v.findViewById<TextView>(R.id.tvSilence)

        val thr0 = prefs.getInt("spk_threshold", 72)
        val sil0 = prefs.getInt("silence_ms", 800)
        sbThr.max = 45              // 50..95
        sbThr.progress = thr0 - 50
        tvThr.text = "Ngưỡng tách giọng: 0.$thr0"
        sbSil.max = 16              // 400..2000ms, bước 100
        sbSil.progress = (sil0 - 400) / 100
        tvSil.text = "Chốt câu sau: ${sil0}ms im lặng"

        sbThr.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                tvThr.text = "Ngưỡng tách giọng: 0.${p + 50}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbSil.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                tvSil.text = "Chốt câu sau: ${400 + p * 100}ms im lặng"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setView(v)
            .setPositiveButton("Lưu") { _, _ ->
                val thr = sbThr.progress + 50
                val sil = 400 + sbSil.progress * 100
                prefs.edit().putInt("spk_threshold", thr).putInt("silence_ms", sil).apply()
                clusterer.threshold = thr / 100f
                voskLive?.silenceMs = sil.toLong()
                toast("Đã lưu cài đặt")
            }
            .setNegativeButton("Huỷ", null).show()
    }

    // ---------------------------------------------------------------- tiện ích

    private fun status(msg: String) = main.post { b.tvStatus.text = msg }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dich", text))
        toast("Đã sao chép")
    }

    private fun scrollToEnd() {
        if (items.isNotEmpty()) b.rvResults.smoothScrollToPosition(items.size - 1)
    }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_models -> {
            startActivity(Intent(this, ModelsActivity::class.java)); true
        }
        R.id.action_saved -> {
            startActivity(Intent(this, SavedActivity::class.java)); true
        }
        R.id.action_settings -> { settingsDialog(); true }
        R.id.action_about -> {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton("Đóng", null).show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        if (isListening()) stopListening()
        tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileJobCancelled = true
        voskLive?.stop()
        googleLive?.stop()
        tts.release()
        VoskModels.releaseAll()
        TranslateEngine.release()
    }
}
