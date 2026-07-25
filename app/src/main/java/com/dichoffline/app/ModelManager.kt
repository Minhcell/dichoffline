package com.dichoffline.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Tải + giải nén gói Vosk (nhận dạng giọng nói và gói tách giọng người nói).
 * Cấu trúc: filesDir/vosk/<mã>/
 */
object ModelManager {

    const val SPK_CODE = "spk"
    const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    const val SPK_SIZE_MB = 13

    data class Pack(val code: String, val name: String, val url: String, val sizeMb: Int)

    fun packs(): List<Pack> =
        listOf(Pack(SPK_CODE, "Gói tách giọng người nói", SPK_URL, SPK_SIZE_MB)) +
                LangCatalog.withVosk.map { Pack(it.code, it.name, it.voskUrl!!, it.voskSizeMb) }

    fun rootDir(ctx: Context): File = File(ctx.filesDir, "vosk").apply { mkdirs() }

    fun dirOf(ctx: Context, code: String): File = File(rootDir(ctx), code)

    fun isInstalled(ctx: Context, code: String): Boolean {
        val d = dirOf(ctx, code)
        if (!d.isDirectory) return false
        return if (code == SPK_CODE) {
            File(d, "final.ext.raw").exists() || File(d, "mean.vec").exists()
        } else {
            File(d, "conf").isDirectory && File(d, "am").isDirectory
        }
    }

    fun installedLangCodes(ctx: Context): List<String> =
        LangCatalog.withVosk.map { it.code }.filter { isInstalled(ctx, it) }

    fun sizeOnDisk(ctx: Context, code: String): Long {
        val d = dirOf(ctx, code)
        if (!d.exists()) return 0
        return d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(ctx: Context, code: String) {
        dirOf(ctx, code).deleteRecursively()
    }

    /** Tải và cài đặt gói. Chạy trên luồng nền. onProgress(-1) = đang giải nén. */
    @Throws(IOException::class)
    fun install(
        ctx: Context,
        code: String,
        url: String,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean = { false }
    ) {
        val tmpZip = File(ctx.cacheDir, "model_$code.zip")
        if (tmpZip.exists()) tmpZip.delete()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("Máy chủ trả về mã ${conn.responseCode}")
            }
            val total = conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmpZip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("Đã huỷ")
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }

        onProgress(-1)
        val target = dirOf(ctx, code)
        target.deleteRecursively()
        target.mkdirs()
        unzipStripTop(tmpZip, target, isCancelled)
        tmpZip.delete()

        if (!isInstalled(ctx, code)) {
            target.deleteRecursively()
            throw IOException("Gói tải về không đúng định dạng Vosk")
        }
    }

    private fun unzipStripTop(zip: File, target: File, isCancelled: () -> Boolean) {
        val canonicalTarget = target.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (isCancelled()) throw IOException("Đã huỷ")
                val rel = entry.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val out = File(target, rel)
                    if (!out.canonicalPath.startsWith(canonicalTarget)) {
                        throw IOException("Đường dẫn không hợp lệ trong file nén")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fo ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                fo.write(buf, 0, n)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
