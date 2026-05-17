package io.kognis.tactical.core.map

import androidx.compose.runtime.mutableStateListOf

/**
 * In-process accumulator of markers dropped during the current session.
 *
 * Lives in the `:main` process (same as the UI). Persists across composition
 * lifecycles but resets when the process is killed — intentional for now;
 * crossing to disk would complicate Zero-Signal posture and isn't required
 * for the hackathon demo flow.
 *
 * UI consumers observe [markers] (Compose snapshot list) directly — adding
 * a new entry triggers recomposition of the multi-marker map screen.
 */
object MarkerStore {

    data class Source(val key: String, val label: String) {
        companion object {
            val OSMAND_PLUS  = Source("osmand_plus", "OsmAnd+")
            val OSMAND_FREE  = Source("osmand_free", "OsmAnd")
            val OSMDROID     = Source("osmdroid_fallback", "Fallback")
            val OSMAND       = Source("osmand", "OsmAnd")
        }
    }

    /**
     * SAR marker types aligned with INSARAG field operations.
     * [symbol] = single character drawn inside the map marker circle.
     */
    enum class CotType(val label: String, val symbol: String, val colorArgb: Int) {
        VICTIM    ("Victim / Survivor",  "V", 0xFFE53935.toInt()),   // Red
        MEDICAL   ("Medical Post",       "+", 0xFFD32F2F.toInt()),   // Dark red
        HAZARD    ("Hazard",             "!", 0xFFF9A825.toInt()),   // Amber
        COMMAND   ("Command Post",       "C", 0xFF1565C0.toInt()),   // Blue
        EXTRACTION("Extraction / LZ",    "X", 0xFF2E7D32.toInt()),   // Green
        MISSING   ("Last Known Pos.",    "M", 0xFFE65100.toInt()),   // Orange
        BASE      ("Base Camp",          "B", 0xFF6D4C41.toInt()),   // Brown
        WATER     ("Water Point",        "W", 0xFF0277BD.toInt()),   // Light blue
    }

    data class Entry(
        val location: LocationJsonExtractor.Location,
        val source: Source,
        val timestampMs: Long = System.currentTimeMillis(),
        val queryPreview: String = "",
        val modelName: String = "",
        val cotType: CotType = CotType.COMMAND,
    )

    // Backing snapshot list — readable from Compose.
    val markers: List<Entry> get() = _markers
    private val _markers = mutableStateListOf<Entry>()

    fun add(entry: Entry) {
        // Deduplicate on (lat, lon, label) — same marker tapped twice doesn't
        // add a second entry, but timestamp on the existing one moves so most-
        // recent ordering still works if the UI sorts by ts.
        val existing = _markers.indexOfFirst {
            it.location.lat == entry.location.lat &&
                it.location.lon == entry.location.lon &&
                it.location.label == entry.location.label
        }
        if (existing >= 0) {
            _markers[existing] = entry  // refresh timestamp / source
        } else {
            _markers.add(entry)
        }
    }

    fun clear() {
        _markers.clear()
    }

    fun size(): Int = _markers.size
}
