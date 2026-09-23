package com.onyx.browser.download

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single byte-range segment for parallel multi-threaded chunk downloading.
 */
data class DownloadChunk(
    val chunkId: Int,
    val startByte: Long,
    var currentByte: Long,
    val endByte: Long,
    var status: Int = STATUS_PENDING
) {
    val downloadedBytes: Long
        get() = (currentByte - startByte).coerceAtLeast(0L)

    val totalBytes: Long
        get() = if (endByte >= startByte) (endByte - startByte + 1L) else 0L

    val isCompleted: Boolean
        get() = currentByte > endByte

    fun toJson(): JSONObject = JSONObject().apply {
        put("chunkId", chunkId)
        put("startByte", startByte)
        put("currentByte", currentByte)
        put("endByte", endByte)
        put("status", status)
    }

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RUNNING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3

        fun fromJson(json: JSONObject): DownloadChunk {
            return DownloadChunk(
                chunkId = json.getInt("chunkId"),
                startByte = json.getLong("startByte"),
                currentByte = json.getLong("currentByte"),
                endByte = json.getLong("endByte"),
                status = json.optInt("status", STATUS_PENDING)
            )
        }

        fun chunksToJson(chunks: List<DownloadChunk>): String {
            val array = JSONArray()
            chunks.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun chunksFromJson(jsonStr: String): List<DownloadChunk> {
            val list = mutableListOf<DownloadChunk>()
            if (jsonStr.isBlank()) return list
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) {}
            return list
        }
    }
}
