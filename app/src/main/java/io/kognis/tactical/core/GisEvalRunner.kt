package io.kognis.tactical.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.kognis.tactical.core.map.QueryPreprocessor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GisEvalRunner {

    data class GisQuestion(
        val id: Int,
        val question: String,
        val expectedLat: Double?,
        val expectedLon: Double?,
        val isGpsBased: Boolean,
    )

    data class GisResult(
        val id: Int,
        val question: String,
        val markerPlaced: Boolean,
        val lat: Double?,
        val lon: Double?,
        val label: String?,
        val cotType: String?,
        val gpsUsed: Boolean,
        val llmResponse: String,
        val durationMs: Long,
        val timedOut: Boolean,
    )

    fun loadQuestions(context: Context): List<GisQuestion> {
        val raw = context.assets.open("gis_test.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("mapping_orders")
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val q = obj.getString("question")
            val prep = QueryPreprocessor.preprocess(q)
            GisQuestion(
                id = obj.getInt("id"),
                question = q,
                expectedLat = prep.markerIntent?.lat,
                expectedLon = prep.markerIntent?.lon,
                isGpsBased = prep.gpsIntent != null,
            )
        }
    }

    fun exportResults(context: Context, results: List<GisResult>): Uri? = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "kognis_gis_eval_$ts.json")
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("question", r.question)
                put("marker_placed", r.markerPlaced)
                r.lat?.let { put("lat", it) }
                r.lon?.let { put("lon", it) }
                r.label?.let { put("label", it) }
                r.cotType?.let { put("cot_type", it) }
                put("gps_used", r.gpsUsed)
                put("llm_response", r.llmResponse)
                put("duration_ms", r.durationMs)
                put("timed_out", r.timedOut)
            })
        }
        val placed = results.count { it.markerPlaced }
        val root = JSONObject().apply {
            put("eval_date", ts)
            put("total_questions", results.size)
            put("markers_placed", placed)
            put("gps_questions", results.count { it.gpsUsed })
            put("coord_questions", results.count { !it.gpsUsed })
            put("timed_out_count", results.count { it.timedOut })
            put("placement_rate", if (results.isNotEmpty()) placed.toDouble() / results.size else 0.0)
            put("results", arr)
        }
        file.writeText(root.toString(2))
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse {
        android.util.Log.e("GisEvalRunner", "Export failed", it)
        null
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
