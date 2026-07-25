package com.dichoffline.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedSession(
    val id: String,
    val title: String,
    val timeMs: Long,
    val count: Int
) {
    fun timeText(): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi")).format(Date(timeMs))
}

/** Lưu / mở / xoá bản dịch đã lưu. Mỗi phiên là 1 file JSON trong bộ nhớ riêng của app. */
object ConversationStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun save(ctx: Context, title: String, items: List<ResultItem>): String {
        val id = "s_${System.currentTimeMillis()}"
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("src", item.srcText)
                    put("vi", item.viText)
                    put("lang", item.langCode)
                    put("spk", item.speaker)
                }
            )
        }
        val root = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("time", System.currentTimeMillis())
            put("items", arr)
        }
        File(dir(ctx), "$id.json").writeText(root.toString())
        return id
    }

    fun list(ctx: Context): List<SavedSession> =
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    SavedSession(
                        id = o.optString("id", f.nameWithoutExtension),
                        title = o.optString("title", "Bản dịch"),
                        timeMs = o.optLong("time", f.lastModified()),
                        count = o.optJSONArray("items")?.length() ?: 0
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.timeMs }
            ?: emptyList()

    fun load(ctx: Context, id: String): List<ResultItem> {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ResultItem(
                    srcText = o.optString("src"),
                    viText = o.optString("vi"),
                    langCode = o.optString("lang"),
                    speaker = o.optInt("spk", -1)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun titleOf(ctx: Context, id: String): String {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return "Bản dịch"
        return runCatching { JSONObject(f.readText()).optString("title", "Bản dịch") }
            .getOrDefault("Bản dịch")
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun asText(items: List<ResultItem>): String = items.joinToString("\n\n") { item ->
        val who = when (item.speaker) {
            0 -> "Người 1"
            1 -> "Người 2"
            else -> LangCatalog.nameOf(item.langCode)
        }
        "$who (${LangCatalog.nameOf(item.langCode)}): ${item.srcText}\n➜ ${item.viText}"
    }
}
