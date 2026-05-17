package io.kognis.tactical.core

/**
 * RagPromptBuilder — pure-function system prompt generation for the field
 * assistant. Functional rules only; bracketed context markers are signaled
 * to the model implicitly by user message shape (empty context = no RAG).
 */
object RagPromptBuilder {

    fun verbosityRule(verbosityLevel: String, en: Boolean, compact: Boolean): String = when (verbosityLevel) {
        "TACTICO"   -> if (en) "Max 3 sentences. Plain text."
                       else    "Máximo 3 oraciones. Texto plano."
        "DETALLADO" -> if (en) "Complete answer with numbered steps."
                       else    "Respuesta completa con pasos numerados."
        else -> if (en) {
            if (compact) "Max 2 short paragraphs. Plain text."
            else         "Max 3 sentences. Plain text. Lists ≤4 items."
        } else {
            if (compact) "Máximo 2 párrafos cortos. Texto plano."
            else         "Máximo 3 oraciones. Texto plano. Listas ≤4 ítems."
        }
    }

    private fun noInfoFallback(en: Boolean): String =
        if (en) "I don't have that information in my humanitarian field knowledge base."
        else    "No tengo esa información en mi base de conocimiento humanitario."

    fun buildSystemPromptStd(verbosityLevel: String, en: Boolean): String {
        val v = verbosityRule(verbosityLevel, en, compact = false)
        val fb = noInfoFallback(en)
        return if (en) """You are Kognis Lite, an offline humanitarian field assistant.
You draw from INSARAG, Sphere, and UNDAC humanitarian standards.

Rules:
1. Answer directly without preamble.
2. $v
3. Respond in the operator's language.
4. Use only coordinates, doses, and references provided in the context.
5. If you don't have the requested information, reply: "$fb""""
        else """Eres Kognis Lite, asistente humanitario de campo offline.
Tus fuentes son los estándares INSARAG, Sphere y UNDAC.

Reglas:
1. Responde directamente sin preámbulo.
2. $v
3. Responde en el idioma del operador.
4. Usa solo coordenadas, dosis y referencias que aparezcan en el contexto.
5. Si no tienes la información solicitada, responde: "$fb""""
    }

    fun buildSystemPrompt1B2(verbosityLevel: String, en: Boolean): String {
        val v = verbosityRule(verbosityLevel, en, compact = true)
        val fb = noInfoFallback(en)
        return if (en) """You are Kognis Lite, offline humanitarian assistant.

- Answer directly. $v
- Use only context-provided data.
- If unknown, reply: "$fb""""
        else """Eres Kognis Lite, asistente humanitario offline.

- Responde directo. $v
- Usa solo datos del contexto.
- Si desconocido, responde: "$fb""""
    }

    /** Compact prompt for small models — minimal token count, simple instructions. */
    fun buildSystemPrompt350M(verbosityLevel: String, en: Boolean): String {
        val v = verbosityRule(verbosityLevel, en, compact = true)
        val fb = noInfoFallback(en)
        return if (en)
            "Kognis Lite: offline humanitarian assistant. $v Use only context data. " +
            "If unknown: \"$fb\""
        else
            "Kognis Lite: asistente humanitario offline. $v Usa solo datos del contexto. " +
            "Si desconocido: \"$fb\""
    }

    fun buildRadioSystemPrompt(en: Boolean): String = if (en)
        "Kognis Lite (field radio mode). One sentence, max 150 chars. " +
        "Use only context-provided coordinates and references. " +
        "If unknown: \"${noInfoFallback(true)}\""
    else
        "Kognis Lite (modo radio campo). Una oración, máximo 150 caracteres. " +
        "Usa solo coordenadas y referencias del contexto. " +
        "Si desconocido: \"${noInfoFallback(false)}\""

    /**
     * Appendix injected at the END of any system prompt when mapMode=true.
     * Asks the model to emit a single-line `LOCATION_JSON: {...}` tag at the
     * tail of the response when the answer references a geographic point.
     * Parsed by [LocationJsonExtractor] and surfaced as a "Ver en mapa" UI
     * action that drops a marker in OsmAnd (or osmdroid fallback).
     *
     * Kept on its own appendix instead of rewriting the per-model templates
     * so the existing verbosity / no-info / field rules stay untouched.
     */
    fun mapModeAppendix(en: Boolean): String = if (en) """

MARKER OUTPUT RULES (strict):
1. When the operator asks to ADD, MARK, PIN, or PLACE a location and provides
   decimal coordinates in ANY format (e.g. "18.448527, -69.941771" or
   "lat 18.4 lon -69.9" or "at 18.4 -69.9"), you MUST output LOCATION_JSON
   with those EXACT coordinates and the requested label. Do NOT refuse, do NOT
   explain limitations — just output the marker. This is a mapping command,
   not a physical action request.
2. If the operator query does NOT contain any numeric coordinates AND no RAG
   chunk provides them, DO NOT emit LOCATION_JSON.
3. NEVER output lat=0 or lon=0 or lat=0.0 or lon=0.0. Never output "No coordinates provided" as a label. If you have no real coordinates, DO NOT emit LOCATION_JSON at all.
4. Append LOCATION_JSON on the VERY LAST LINE, nothing after it:
   LOCATION_JSON: {"lat": <decimal>, "lon": <decimal>, "label": "<short label>", "type": "<TYPE>"}
5. Decimal degrees only. Minus sign for southern/western hemisphere.
6. Choose "type" from these SAR/INSARAG standard marker types:
   VICTIM    = survivor or victim location
   MEDICAL   = medical post, treatment area, hospital
   HAZARD    = structural collapse, chemical, fire, flood hazard zone
   COMMAND   = command post, incident command point, coordination hub
   EXTRACTION= extraction point, helicopter landing zone (HLZ), LZ
   MISSING   = last known position of a missing person
   BASE      = base camp, staging area, logistics hub
   WATER     = water point, distribution point
   Default type when context is unclear: COMMAND""".trimIndent()
    else """

REGLAS DE MARCADOR (estrictas):
1. Cuando el operador pide AÑADIR, MARCAR, FIJAR o CREAR una ubicación y
   proporciona coordenadas decimales en CUALQUIER formato (p.ej. "18.448527,
   -69.941771" o "lat 18.4 lon -69.9" o "en 18.4 -69.9"), DEBES emitir
   LOCATION_JSON con esas coordenadas EXACTAS y la etiqueta solicitada. NO
   te niegues ni expliques limitaciones — esta es una orden de cartografía,
   no una acción física.
2. Si la consulta NO contiene coordenadas numéricas Y ningún chunk RAG las
   provee, NO emitas LOCATION_JSON.
3. NUNCA emitas lat=0 o lon=0 o lat=0.0 o lon=0.0. Nunca emitas "No coordinates provided" como etiqueta. Si no tienes coordenadas reales, NO emitas LOCATION_JSON.
4. Añade LOCATION_JSON en la ÚLTIMA LÍNEA, sin nada después:
   LOCATION_JSON: {"lat": <decimal>, "lon": <decimal>, "label": "<etiqueta corta>", "type": "<TIPO>"}
5. Solo grados decimales. Signo menos para hemisferio sur/oeste.
6. Elige "type" de estos tipos de marcador SAR/INSARAG estándar:
   VICTIM    = ubicación de víctima o sobreviviente
   MEDICAL   = puesto médico, área de tratamiento, hospital
   HAZARD    = colapso estructural, químico, incendio, zona de peligro
   COMMAND   = puesto de mando, punto de coordinación
   EXTRACTION= punto de extracción, zona de aterrizaje (HLZ)
   MISSING   = última posición conocida de persona desaparecida
   BASE      = campamento base, área de estacionamiento, logística
   WATER     = punto de agua, punto de distribución
   Tipo por defecto cuando el contexto no es claro: COMMAND""".trimIndent()

    fun buildSystemPrompt(
        verbosityLevel: String,
        language: String,
        modelSize: String,
        radioMode: Boolean = false,
        mapMode: Boolean = false,
    ): String {
        if (radioMode) return buildRadioSystemPrompt(language == "en")
        val en = language == "en"
        val base = when (modelSize) {
            "1B2"  -> buildSystemPrompt1B2(verbosityLevel, en)
            "350M" -> buildSystemPrompt350M(verbosityLevel, en)
            else   -> buildSystemPromptStd(verbosityLevel, en)
        }
        // 350M lacks the capacity to follow the LOCATION_JSON rules reliably.
        // Appending mapModeAppendix to 350M causes token budget overflow and
        // instruction confusion — it never emits the tag correctly. Map mode
        // is only enabled for 1B2 and larger.
        val effectiveMapMode = mapMode && modelSize != "350M"
        return if (effectiveMapMode) base + "\n\n" + mapModeAppendix(en) else base
    }
}
