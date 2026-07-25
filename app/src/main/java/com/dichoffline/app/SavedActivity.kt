package com.dichoffline.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dichoffline.app.databinding.ActivitySavedBinding
import com.dichoffline.app.databinding.ItemSessionBinding

class SavedActivity : AppCompatActivity() {

    private lateinit var b: ActivitySavedBinding
    private lateinit var adapter: SessionAdapter
    private var data = mutableListOf<SavedSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySavedBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = SessionAdapter()
        b.rvSessions.layoutManager = LinearLayoutManager(this)
        b.rvSessions.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        data = ConversationStore.list(this).toMutableList()
        adapter.notifyDataSetChanged()
        b.tvEmpty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.VH>() {

        inner class VH(val v: ItemSessionBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = data[position]
            holder.v.tvTitle.text = s.title
            holder.v.tvMeta.text = "${s.timeText()} · ${s.count} câu"

            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@SavedActivity, SessionActivity::class.java)
                        .putExtra("id", s.id)
                )
            }
            holder.v.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@SavedActivity)
                    .setTitle(s.title)
                    .setMessage("Xoá bản dịch đã lưu này?")
                    .setPositiveButton("Xoá") { _, _ ->
                        ConversationStore.delete(this@SavedActivity, s.id)
                        reload()
                    }
                    .setNegativeButton("Huỷ", null).show()
            }
        }
    }
}
