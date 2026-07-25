package com.dichoffline.app

import kotlin.math.sqrt

/**
 * Phân 2 người nói từ vector giọng (x-vector 128 chiều) của Vosk.
 *
 * Cách làm: chuẩn hoá vector, so cosine với tâm cụm của từng người.
 *  - Chưa có ai   -> tạo Người 1
 *  - Mới có 1 người -> nếu giống (>= ngưỡng) thì gộp, khác thì tạo Người 2
 *  - Đã có 2 người -> gán vào bên giống hơn, rồi cập nhật tâm cụm
 */
class SpeakerClusterer(var threshold: Float = 0.72f) {

    private val centroids = arrayOfNulls<FloatArray>(2)
    private val counts = IntArray(2)
    private var lastSpeaker = 0

    /** Số khung tối thiểu để tin vector giọng (mỗi khung ~10ms) */
    var minFrames = 25

    fun reset() {
        centroids[0] = null
        centroids[1] = null
        counts[0] = 0
        counts[1] = 0
        lastSpeaker = 0
    }

    fun knownSpeakers(): Int = centroids.count { it != null }

    /** Trả về 0 (Người 1) hoặc 1 (Người 2). */
    fun assign(vector: FloatArray?, frames: Int): Int {
        if (vector == null || vector.isEmpty() || frames < minFrames) return lastSpeaker
        val v = normalize(vector)

        val c0 = centroids[0]
        if (c0 == null) {
            centroids[0] = v; counts[0] = 1; lastSpeaker = 0
            return 0
        }
        val sim0 = cosine(v, c0)

        val c1 = centroids[1]
        if (c1 == null) {
            return if (sim0 >= threshold) {
                merge(0, v); lastSpeaker = 0; 0
            } else {
                centroids[1] = v; counts[1] = 1; lastSpeaker = 1; 1
            }
        }

        val sim1 = cosine(v, c1)
        val idx = if (sim0 >= sim1) 0 else 1
        merge(idx, v)
        lastSpeaker = idx
        return idx
    }

    /** Độ chênh giữa 2 cụm, dùng để báo cho người dùng biết tách có chắc không. */
    fun separation(): Float {
        val a = centroids[0] ?: return 0f
        val b = centroids[1] ?: return 0f
        return 1f - cosine(a, b)
    }

    private fun merge(idx: Int, v: FloatArray) {
        val c = centroids[idx] ?: run { centroids[idx] = v; counts[idx] = 1; return }
        val n = counts[idx].coerceAtLeast(1)
        // Giới hạn trọng số cũ để tâm cụm còn thích nghi được
        val w = n.coerceAtMost(12).toFloat()
        val out = FloatArray(c.size)
        for (i in c.indices) out[i] = (c[i] * w + v[i]) / (w + 1f)
        centroids[idx] = normalize(out)
        counts[idx] = n + 1
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val norm = sqrt(s).toFloat()
        if (norm <= 1e-6f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        for (i in 0 until n) dot += (a[i] * b[i]).toDouble()
        return dot.toFloat()
    }
}
