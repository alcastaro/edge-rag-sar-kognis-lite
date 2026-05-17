package io.kognis.tactical.core.map

/**
 * Pre-LLM intent router: detects coordinate marking commands and GPS marking
 * intent from the user's raw text BEFORE the query reaches the LLM.
 *
 * Key purpose: extract exact coordinates from the user's message so that the
 * app can place a marker with the user's own numbers — not the model's
 * reproduced version (which can be truncated or misquoted on small models).
 *
 * Also detects "my location" / GPS intent so the UI can use device GPS
 * to pre-place the marker without model coordinate hallucination.
 */
object QueryPreprocessor {

    // Decimal coordinate pair: two numbers with at least 3 decimal places each.
    // Covers formats: "18.448527, -69.941771"  /  "18.4486 -69.9418"  /  "lat 18.44 lon -69.94"
    private val COORD_PATTERN = Regex(
        """(-?\d{1,3}\.\d{2,10})\s*[,;\s]\s*(-?\d{1,3}\.\d{2,10})"""
    )

    // Marking intent keywords (EN + ES)
    private val MARK_WORDS = setOf(
        // English
        "add", "mark", "pin", "place", "set", "drop", "create", "flag",
        "register", "record", "locate", "plot", "insert",
        // Spanish
        "agrega", "agregar", "marca", "marcar", "añade", "añadir",
        "fija", "fijar", "crea", "crear", "registra", "registrar",
        "ubica", "ubicar", "pon", "poner", "coloca", "colocar",
    )

    // GPS / current-position intent phrases
    private val GPS_PHRASES = listOf(
        "my location", "my position", "my gps",
        "current location", "current position",
        "where i am", "where i stand",
        "mi ubicación", "mi posición", "mi gps", "mi localización",
        "ubicación actual", "posición actual",
        "donde estoy", "aquí", "here",
    )

    // Keyword → CotType name mapping (check in order; first match wins)
    private val TYPE_RULES = listOf(
        listOf("medical", "health", "hospital", "clinic", "tratamiento", "puesto médico",
               "médico", "médica", "salud", "clínica", "sanidad", "medevac") to "MEDICAL",
        listOf("victim", "survivor", "sobreviviente", "víctima", "casualty", "herido",
               "injured", "trapped", "atrapado") to "VICTIM",
        listOf("hazard", "danger", "peligro", "riesgo", "chemical", "químico",
               "collapse", "derrumbe", "fire", "incendio", "flood", "inundación") to "HAZARD",
        listOf("extraction", "lz", "landing zone", "helo", "helicopter", "helicóptero",
               "helipad", "evacuación", "evac") to "EXTRACTION",
        listOf("missing", "desaparecido", "last seen", "last known",
               "última posición", "búsqueda") to "MISSING",
        listOf("base camp", "base de operaciones", "staging", "campamento",
               "logistics", "logística") to "BASE",
        listOf("water", "agua", "hydration", "hidratación", "distribution",
               "distribución", "supply") to "WATER",
        listOf("command", "cp", "icp", "puesto de mando", "control", "mando",
               "coordination", "coordinación", "headquarters", "hq") to "COMMAND",
    )

    data class MarkerIntent(
        val lat: Double,
        val lon: Double,
        val label: String,
        val cotTypeName: String,
    )

    data class GpsMarkerIntent(
        val label: String,
        val cotTypeName: String,
    )

    data class Result(
        val markerIntent: MarkerIntent? = null,
        val gpsIntent: GpsMarkerIntent? = null,
        val isMarkingCommand: Boolean = false,
    )

    fun preprocess(query: String): Result {
        val lower = query.lowercase()
        val tokens = lower.split(Regex("\\W+")).toSet()
        val hasMarkWord = MARK_WORDS.any { tokens.contains(it) }
        val hasGpsPhrase = GPS_PHRASES.any { lower.contains(it) }
        val coordMatch = COORD_PATTERN.find(query)

        if (hasMarkWord && coordMatch != null) {
            val lat = coordMatch.groupValues[1].toDoubleOrNull()
            val lon = coordMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)) {
                val type = inferType(lower)
                val label = inferLabel(query, type)
                return Result(
                    markerIntent = MarkerIntent(lat, lon, label, type),
                    isMarkingCommand = true,
                )
            }
        }

        if (hasMarkWord && hasGpsPhrase) {
            val type = inferType(lower)
            val label = inferLabel(query, type)
            return Result(
                gpsIntent = GpsMarkerIntent(label, type),
                isMarkingCommand = true,
            )
        }

        return Result(isMarkingCommand = hasMarkWord)
    }

    private fun inferType(lower: String): String {
        for ((keywords, typeName) in TYPE_RULES) {
            if (keywords.any { lower.contains(it) }) return typeName
        }
        return "COMMAND"
    }

    private fun inferLabel(query: String, typeName: String): String {
        // Try quoted label first
        val quoted = Regex("""["']([^"']{2,40})["']""").find(query)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        return when (typeName) {
            "MEDICAL"    -> "Medical Post"
            "VICTIM"     -> "Victim Location"
            "HAZARD"     -> "Hazard Zone"
            "EXTRACTION" -> "Extraction / LZ"
            "MISSING"    -> "Last Known Position"
            "BASE"       -> "Base Camp"
            "WATER"      -> "Water Point"
            else         -> "Command Post"
        }
    }
}
