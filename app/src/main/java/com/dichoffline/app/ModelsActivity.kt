package com.dichoffline.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dichoffline.app.databinding.ActivityModelsBinding
import com.dichoffline.app.databinding.ItemModelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsActivity : AppCompatActivity() {

    private lateinit var b: ActivityModelsBinding
    private lateinit var adapter: ModelAdapter
    private val downloading = HashMap<String, Int>()
    @Volatile private var cancelFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = ModelAdapter()
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvModels.adapter = adapter
    }

    private fun install(pack: ModelManager.Pack, position: Int) {
        if (downloading.containsKey(pack.code)) return
        downloading[pack.code] = 0
        cancelFlag = false
        adapter.notifyItemChanged(position)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    ModelManager.install(
                        ctx = this@ModelsActivity,
                        code = pack.code,
                        url = pack.url,
                        onProgress = { p ->
                            downloading[pack.code] = p
                            runOnUiThread { adapter.notifyItemChanged(position) }
                        },
                        isCancelled = { cancelFlag }
                    )
                }
            }
            downloading.remove(pack.code)
            adapter.notifyItemChanged(position)
            res.onSuccess {
                Toast.makeText(this@ModelsActivity, "Đã cài ${pack.name}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@ModelsActivity, "Lỗi: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class ModelAdapter : RecyclerView.Adapter<ModelAdapter.VH>() {

        private val data = ModelManager.packs()

        inner class VH(val v: ItemModelBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pack = data[position]
            val v = holder.v
            val installed = ModelManager.isInstalled(this@ModelsActivity, pack.code)
            val progress = downloading[pack.code]

            v.tvName.text = pack.name
            v.tvSize.text = when {
                progress != null && progress >= 0 -> "Đang tải… $progress%"
                progress != null -> "Đang giải nén…"
                installed -> "Đã cài · %.0f MB".format(
                    ModelManager.sizeOnDisk(this@ModelsActivity, pack.code) / 1048576.0
                )
                pack.code == ModelManager.SPK_CODE ->
                    "${pack.sizeMb} MB · cần cho chế độ tách giọng tự động"
                else -> "${pack.sizeMb} MB"
            }

            v.progress.visibility = if (progress != null) View.VISIBLE else View.GONE
            if (progress != null) {
                v.progress.setProgressCompat(if (progress < 0) 100 else progress, false)
            }

            v.btnAction.isEnabled = progress == null
            v.btnAction.setIconResource(
                if (installed) R.drawable.ic_delete else R.drawable.ic_download
            )
            v.btnAction.setOnClickListener {
                if (installed) {
                    AlertDialog.Builder(this@ModelsActivity)
                        .setTitle(pack.name)
                        .setMessage("Xoá gói này?")
                        .setPositiveButton("Xoá") { _, _ ->
                            ModelManager.delete(this@ModelsActivity, pack.code)
                            notifyItemChanged(position)
                        }
                        .setNegativeButton("Huỷ", null).show()
                } else {
                    install(pack, position)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFlag = true
    }
}
