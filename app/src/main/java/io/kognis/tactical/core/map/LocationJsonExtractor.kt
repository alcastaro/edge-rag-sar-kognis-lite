package io.kognis.tactical.core.map

import org.json.JSONObject

/**
 * Parses `LOCATION_JSON: {"lat": ..., "lon": ..., "label": "..."}` tags emitted
 * by the LLM when [RagPromptBuilder.mapModeAppendix] is active.
 *
 * The model is instructed to put the tag on its own line at the end of the
 * response, but we tolerate variations: optional leading whitespace, anywhere
 * in the text, optional trailing punctuation. First valid match wins; if
 * multiple appear we take the LAST one (most-recent answer wins on streaming
 * partial outputs).
 *
 * Returns null if no valid `LOCATION_JSON: {...}` appears or the JSON object
 * is missing the required lat/lon fields or values are out of range.
 *
 * Coordinate bounds: lat ∈ [-90, 90], lon ∈ [-180, 180]. Anything outside is
 * rejected (hallucinations like lat=999 land here).
 */
object LocationJsonExtractor {

    // Match `LOCATION_JSON:` then a JSON object (single or multi-line).
    private val PATTERN = Regex(
        """(?i)LOCATION_JSON\s*:\s*(\{[^{}]*\})""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    // Orphan tail: leftover `"key": "value"}` fragments from multi-line JSON
    // that the main pattern failed to absorb.
    private val ORPHAN_TAIL = Regex("""^\s*"[a-z_]+"\s*:.*\}\s*$""", RegexOption.MULTILINE)

    data class Location(val lat: Double, val lon: Double, val label: String, val markerType: String = "")

    /** Strip the `LOCATION_JSON: {...}` tag (and orphan tails) from [text]. */
    fun stripTag(text: String): String {
        val stripped = PATTERN.replace(text, "")
        return ORPHAN_TAIL.replace(stripped, "").trimEnd()
    }

    /** Returns the last valid Location in [text], or null. */
    fun extract(text: String): Location? {
        val matches = PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return null
        // Iterate from the end so the latest tag wins.
        for (m in matches.asReversed()) {
            val raw = m.groupValues[1]
            val loc = parse(raw) ?: continue
            return loc
        }
        return null
    }

    private fun parse(rawJson: String): Location? {
        val obj = try { JSONObject(rawJson) } catch (_: Exception) { return null }
        if (!obj.has("lat") || !obj.has("lon")) return null
        val lat = obj.optDouble("lat", Double.NaN)
        val lon = obj.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // Reject (0,0) — Gulf of Guinea / model sentinel for "no coordinates"
        if (lat == 0.0 && lon == 0.0) return null
        val rawLabel = obj.optString("label", "").trim()
        // Reject model sentinel labels
        if (rawLabel.lowercase().contains("no coordinates") || rawLabel.lowercase().contains("not provided")) return null
        val label = rawLabel.take(80).ifBlank { "Marker" }
        val markerType = obj.optString("type", "").trim().uppercase()
        return Location(lat = lat, lon = lon, label = label, markerType = markerType)
    }
}
