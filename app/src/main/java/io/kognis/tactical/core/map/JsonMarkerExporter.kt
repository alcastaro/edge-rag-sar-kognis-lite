package io.kognis.tactical.core.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/imports [MarkerStore] entries as a JSON file shareable via WhatsApp,
 * email, or any file-sharing app.
 *
 * JSON schema:
 * {
 *   "exported_at": "2026-05-16T12:00:00Z",
 *   "marker_count": N,
 *   "markers": [
 *     { "lat": 18.48, "lon": -69.96, "label": "Base Camp",
 *       "timestamp_ms": 1716432000000, "query_preview": "...", "model": "GEMMA" }
 *   ]
 * }
 *
 * Import tolerates missing optional fields (query_preview, model) so JSON files
 * sent by field teams with minimal structure are accepted.
 */
object JsonMarkerExporter {

    private const val TAG = "JsonMarkerExporter"
    private const val AUTHORITY_SUFFIX = ".provider"

    data class ExportResult(val file: File, val markerCount: Int)
    data class ImportResult(val added: Int, val skipped: Int)

    // ── Export ────────────────────────────────────────────────────────────────

    fun export(context: Context): ExportResult? {
        val markers = MarkerStore.markers
        if (markers.isEmpty()) {
            Log.w(TAG, "No markers to export")
            return null
        }
        val dir = File(context.getExternalFilesDir(null), "markers").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "kognis_markers_$ts.json")
        return try {
            file.writeText(buildJson(markers))
            Log.i(TAG, "Exported ${markers.size} markers → ${file.name}")
            ExportResult(file, markers.size)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            null
        }
    }

    /** Share intent for WhatsApp / email / any app that handles JSON files. */
    fun shareIntent(context: Context, result: ExportResult): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            result.file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Kognis markers — ${result.markerCount} puntos")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartir marcadores…")
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ImportResult(0, 0)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open URI: $uri — ${e.message}")
            return ImportResult(0, 0)
        }

        val json = try {
            org.json.JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            // Try bare array format (simpler field exports)
            return importFromArray(bytes)
        }

        val arr = json.optJSONArray("markers") ?: return importFromArray(bytes)
        return parseArray(arr)
    }

    private fun importFromArray(bytes: ByteArray): ImportResult {
        val arr = try {
            org.json.JSONArray(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON — neither object nor array")
            return ImportResult(0, 0)
        }
        return parseArray(arr)
    }

    private fun parseArray(arr: org.json.JSONArray): ImportResult {
        var added = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val lat = m.optDouble("lat", Double.NaN)
            val lon = m.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                skipped++
                continue
            }
            val label = m.optString("label", "").trim().ifBlank { "Imported" }
            val cotType = try { MarkerStore.CotType.valueOf(m.optString("cot_type", "")) }
                          catch (_: Exception) { MarkerStore.CotType.COMMAND }
            MarkerStore.add(
                MarkerStore.Entry(
                    location = LocationJsonExtractor.Location(lat, lon, label),
                    source = MarkerStore.Source.OSMDROID,
                    timestampMs = m.optLong("timestamp_ms", System.currentTimeMillis()),
                    queryPreview = m.optString("query_preview", ""),
                    modelName = m.optString("model", ""),
                    cotType = cotType,
                )
            )
            added++
        }
        Log.i(TAG, "Import complete: $added added, $skipped skipped")
        return ImportResult(added, skipped)
    }

    // ── JSON builder ──────────────────────────────────────────────────────────

    private fun buildJson(markers: List<MarkerStore.Entry>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val arr = org.json.JSONArray()
        markers.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("lat", e.location.lat)
                put("lon", e.location.lon)
                put("label", e.location.label)
                put("cot_type", e.cotType.name)
                put("timestamp_ms", e.timestampMs)
                put("query_preview", e.queryPreview)
                put("model", e.modelName)
            })
        }
        return org.json.JSONObject().apply {
            put("exported_at", iso.format(Date()))
            put("marker_count", markers.size)
            put("markers", arr)
        }.toString(2)
    }
}
