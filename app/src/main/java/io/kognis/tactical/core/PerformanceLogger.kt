package io.kognis.tactical.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PerformanceLogger {

    data class QueryEntry(
        val type: String = "QUERY", // "QUERY" or "SYSTEM"
        val tsMs: Long = System.currentTimeMillis(),
        val queryPreview: String,
        val query: String = "",       // full query text (added feedback 2.3)
        val model: String,
        val durationMs: Long,
        val tokensPerSec: Double,
        val tokens: Int,
        val ragActivated: Boolean,
        val ragScore: Double,
        val tempCelsius: Double?,
        val responseText: String = "",
        val chunkTitle: String = "",
        val chunksJson: String = "",  // all retrieved chunks: [{title,score},...] (added feedback 2.3)
        val embeddingMode: String = "TEXT",
        // Map markers dropped after this response. Appended via addMarkerToLastQuery
        // when the user taps "Ver en mapa". Format: JSON array of
        // [{"lat":..., "lon":..., "label":..., "source":..., "tsMs":...}].
        // Empty string means no markers logged for this entry.
        val markersJson: String = "",
        // True when the orchestrator bypassed retrieval because the query had
        // explicit lat/lon. Useful in the dashboard to distinguish "RAG off
        // by user choice" from "RAG off because query is a marker command".
        val ragBypassedCoords: Boolean = false,
    )

    private const val MAX = 200
    private const val PREFS_KEY = "perf_log_entries"
    private val _entries = ArrayDeque<QueryEntry>()
    private var appContext: Context? = null
    // Cold start: ms from bindService() to first "Core Operativo" status (set externally)
    @Volatile var coldStartMs: Long = -1L

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromPrefs()
    }

    @Synchronized fun record(entry: QueryEntry) {
        if (_entries.size >= MAX) _entries.removeFirst()
        _entries.addLast(entry)
        saveToPrefs()
    }

    @Synchronized fun entries(): List<QueryEntry> = _entries.toList()

    @Synchronized fun clear() {
        _entries.clear()
        appContext?.let { ctx ->
            SecurePrefs.get(ctx).edit().remove(PREFS_KEY).apply()
        }
    }

    @Synchronized fun isEmpty(): Boolean = _entries.isEmpty()

    /**
     * Append a marker drop event to the most recent QUERY entry. Called from
     * the UI when the user taps "Ver en mapa" on an assistant message — the
     * association with a specific query is by recency (last QUERY in the deque).
     * Persists to SecurePrefs on every call so the dashboard / export sees it.
     */
    @Synchronized fun addMarkerToLastQuery(
        lat: Double,
        lon: Double,
        label: String,
        source: String,
    ) {
        val idx = _entries.indexOfLast { it.type == "QUERY" }
        if (idx < 0) return
        val current = _entries[idx]
        val arr = try { JSONArray(current.markersJson.ifBlank { "[]" }) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("label", label)
            put("source", source)
            put("tsMs", System.currentTimeMillis())
        })
        _entries[idx] = current.copy(markersJson = arr.toString())
        saveToPrefs()
    }

    /** Total marker drops logged across all entries. */
    @Synchronized fun markerDropCount(): Int = _entries.sumOf {
        if (it.markersJson.isBlank()) 0
        else try { JSONArray(it.markersJson).length() } catch (_: Exception) { 0 }
    }

    fun avgTps(): Double {
        val e = entries()
        return if (e.isEmpty()) 0.0 else e.sumOf { it.tokensPerSec } / e.size
    }

    fun avgDurationMs(): Long {
        val e = entries()
        return if (e.isEmpty()) 0L else e.sumOf { it.durationMs } / e.size
    }

    fun ragHitRate(): Double {
        val q = entries().filter { it.type == "QUERY" }
        return if (q.isEmpty()) 0.0 else q.count { it.ragActivated }.toDouble() / q.size
    }
    
    fun lastColdStartMs(): Long? = entries().lastOrNull { it.type == "SYSTEM" }?.durationMs

    fun maxTemp(): Double? = entries().mapNotNull { it.tempCelsius }.maxOrNull()

    private fun saveToPrefs() {
        val ctx = appContext ?: return
        val arr = JSONArray()
        _entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("type", e.type)
                put("tsMs", e.tsMs)
                put("queryPreview", e.queryPreview)
                put("query", e.query)
                put("model", e.model)
                put("durationMs", e.durationMs)
                put("tokensPerSec", e.tokensPerSec)
                put("tokens", e.tokens)
                put("ragActivated", e.ragActivated)
                put("ragScore", e.ragScore)
                if (e.tempCelsius != null) put("tempCelsius", e.tempCelsius) else put("tempCelsius", JSONObject.NULL)
                put("responseText", e.responseText)
                put("chunkTitle", e.chunkTitle)
                put("chunksJson", e.chunksJson)
                put("embeddingMode", e.embeddingMode)
                put("markersJson", e.markersJson)
                put("ragBypassedCoords", e.ragBypassedCoords)
            })
        }
        SecurePrefs.get(ctx).edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    private fun loadFromPrefs() {
        val ctx = appContext ?: return
        val json = SecurePrefs.get(ctx).getString(PREFS_KEY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                _entries.addLast(QueryEntry(
                    type = o.optString("type", "QUERY"),
                    tsMs = o.getLong("tsMs"),
                    queryPreview = o.getString("queryPreview"),
                    query = o.optString("query", ""),
                    model = o.getString("model"),
                    durationMs = o.getLong("durationMs"),
                    tokensPerSec = o.getDouble("tokensPerSec"),
                    tokens = o.getInt("tokens"),
                    ragActivated = o.getBoolean("ragActivated"),
                    ragScore = o.getDouble("ragScore"),
                    tempCelsius = if (o.isNull("tempCelsius")) null else o.getDouble("tempCelsius"),
                    responseText = o.optString("responseText", ""),
                    chunkTitle = o.optString("chunkTitle", ""),
                    chunksJson = o.optString("chunksJson", ""),
                    embeddingMode = o.optString("embeddingMode", "TEXT"),
                    markersJson = o.optString("markersJson", ""),
                    ragBypassedCoords = o.optBoolean("ragBypassedCoords", false),
                ))
            }
        } catch (_: Exception) { /* corrupt data — start fresh */ }
    }
}
