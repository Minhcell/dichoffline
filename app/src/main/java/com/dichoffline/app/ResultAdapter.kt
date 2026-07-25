package com.dichoffline.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dichoffline.app.databinding.ItemResultBinding

data class ResultItem(
    var srcText: String,
    var viText: String = "",
    var langCode: String = "",
    var speaker: Int = -1,          // -1 = không phân biệt, 0 = Người 1, 1 = Người 2
    var live: Boolean = false,
    var reqId: Long = 0
)

class ResultAdapter(
    val items: MutableList<ResultItem>,
    private val onSpeak: (ResultItem) -> Unit,
    private val onCopy: (ResultItem) -> Unit,
    private val onDelete: ((Int) -> Unit)? = null,
    private val onLongPress: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    companion object {
        val SPEAKER_COLORS = intArrayOf(Color.parseColor("#00695C"), Color.parseColor("#E65100"))
        fun speakerName(i: Int) = when (i) {
            0 -> "NGƯỜI 1"
            1 -> "NGƯỜI 2"
            else -> ""
        }
    }

    inner class VH(val b: ItemResultBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.b

        // Nhãn người nói
        if (item.speaker >= 0) {
            b.tvSpeaker.visibility = View.VISIBLE
            b.tvSpeaker.text = speakerName(item.speaker)
            val c = SPEAKER_COLORS[item.speaker.coerceIn(0, 1)]
            b.tvSpeaker.backgroundTintList = ColorStateList.valueOf(c)
            b.barSpeaker.visibility = View.VISIBLE
            b.barSpeaker.setBackgroundColor(c)
        } else {
            b.tvSpeaker.visibility = View.GONE
            b.barSpeaker.visibility = View.GONE
        }

        b.tvLang.text = if (item.langCode.isBlank()) "đang nhận diện…"
        else LangCatalog.nameOf(item.langCode)

        b.tvSrc.text = item.srcText
        b.tvVi.text = if (item.viText.isBlank()) "…" else item.viText

        // Tiếng Việt thì không cần đọc lại
        val canSpeak = item.langCode.isNotBlank() && item.langCode != LangCatalog.TARGET
        b.btnSpeak.visibility = if (canSpeak) View.VISIBLE else View.GONE
        b.btnSpeak.setOnClickListener { onSpeak(item) }
        b.btnCopy.setOnClickListener { onCopy(item) }

        if (onDelete == null) {
            b.btnDelete.visibility = View.GONE
        } else {
            b.btnDelete.visibility = View.VISIBLE
            b.btnDelete.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onDelete.invoke(p)
            }
        }

        b.root.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (onLongPress != null && p != RecyclerView.NO_POSITION) {
                onLongPress.invoke(p); true
            } else false
        }

        b.viewLive.visibility = if (item.live) View.VISIBLE else View.GONE
    }

    fun addItem(item: ResultItem): Int {
        items.add(item)
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun updateAt(index: Int, block: (ResultItem) -> Unit) {
        if (index !in items.indices) return
        block(items[index])
        notifyItemChanged(index)
    }

    fun removeAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        notifyItemRemoved(index)
        notifyItemRangeChanged(index, items.size - index)
    }

    fun replaceAll(newItems: List<ResultItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun allText(): String = ConversationStore.asText(items)
}
