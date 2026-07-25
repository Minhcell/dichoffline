package com.dichoffline.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dichoffline.app.databinding.ActivitySessionBinding

class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private lateinit var tts: TtsHelper
    private lateinit var adapter: ResultAdapter
    private val items = mutableListOf<ResultItem>()
    private var id: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        id = intent.getStringExtra("id").orEmpty()
        b.toolbar.title = ConversationStore.titleOf(this, id)

        tts = TtsHelper(this) { msg -> toast(msg) }

        items.addAll(ConversationStore.load(this, id))
        adapter = ResultAdapter(items,
            onSpeak = { tts.speak(it.srcText, LangCatalog.bcp47Of(it.langCode)) },
            onCopy = { copy(it.viText.ifBlank { it.srcText }) }
        )
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter

        b.btnCopyAll.setOnClickListener { copy(ConversationStore.asText(items)) }
        b.btnShare.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ConversationStore.asText(items))
                    }, "Chia sẻ bản dịch"
                )
            )
        }
        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Xoá hẳn bản dịch đã lưu này?")
                .setPositiveButton("Xoá") { _, _ ->
                    ConversationStore.delete(this, id); finish()
                }
                .setNegativeButton("Huỷ", null).show()
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dich", text))
        toast("Đã sao chép")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.release()
    }
}
