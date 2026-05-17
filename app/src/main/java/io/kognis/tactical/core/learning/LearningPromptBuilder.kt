package io.kognis.tactical.core.learning

import io.kognis.tactical.data.learning.LearningPreferenceKeys
import io.kognis.tactical.data.learning.LearningStore
import io.kognis.tactical.data.learning.LearningTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes-inspired 4-section system prompt assembled per turn. The agent reads:
 *   1. Identity (static, tone-tinted)
 *   2. Learner model (mastery snapshot, recent misses, pace)
 *   3. Session context (latest rolling summary + last 6 raw turns)
 *   4. Skill catalog (JSON function-call shapes)
 *
 * The prompt is regenerated on EVERY turn — KV-cache is reset if the hash
 * changes (handled by `getOrCreateConversation` in RagOrchestrator). For long
 * sessions, only the learner-model and session-context sections change frequently;
 * identity + skill catalog are stable so cache hits are common in practice.
 */
object LearningPromptBuilder {

    fun build(store: LearningStore, sessionId: Long, language: String, curriculumId: String = ""): String {
        val en = language == "en"
        return buildString {
            appendIdentity(store, en)
            append("\n\n")
            appendLearnerModel(store, en)
            append("\n\n")
            appendCurriculumContext(store, sessionId, curriculumId, en)
            append("\n\n")
            appendSessionContext(store, sessionId, en)
            append("\n\n")
            appendSkillCatalog(en)
        }
    }

    // ── 1. Identity ──────────────────────────────────────────────────────

    private fun StringBuilder.appendIdentity(store: LearningStore, en: Boolean) {
        val tone = store.getPref(LearningPreferenceKeys.TONE, "supportive")
        append(if (en) "## IDENTITY\n" else "## IDENTIDAD\n")
        if (en) {
            append("You are Kognis Training, an adaptive learning agent for Search-and-Rescue (SAR) operations. ")
            append("You teach INSARAG, UNDAC, and Sphere protocols using the CURRICULUM CONTEXT section below.\n\n")
            append("RULES (strict):\n")
            append("1. DELIVER content directly. NEVER ask the learner if they want an example/quiz — they do. Just deliver it.\n")
            append("2. When you give an example, WRITE THE FULL EXAMPLE TEXT inline in your reply BEFORE the SKILL tag. ")
            append("Do NOT only emit `SKILL: show_example` — the learner needs to read the example in your message.\n")
            append("3. When you emit `SKILL: quiz_user`, INCLUDE the complete question and four options in the JSON. The card UI renders only what the JSON contains.\n")
            append("4. NEVER repeat an example or quiz you already delivered in the previous 3 turns (check SESSION CONTEXT below).\n")
            append("5. Tone: $tone. Respond in English. 3–6 sentences per turn unless explicitly asked to expand.\n")
            append("6. Always close with `SKILL: {...}` on the very last line — pick ONE skill.")
        } else {
            append("Eres Kognis Training, un agente de aprendizaje adaptativo para Búsqueda y Rescate (SAR). ")
            append("Enseñas protocolos INSARAG, UNDAC y Sphere usando la sección CURRICULUM CONTEXT más abajo.\n\n")
            append("REGLAS (estrictas):\n")
            append("1. ENTREGA contenido directo. NUNCA preguntes al estudiante si quiere un ejemplo o quiz — ya lo quiere. Entrégalo.\n")
            append("2. Cuando des un ejemplo, ESCRIBE EL TEXTO COMPLETO DEL EJEMPLO dentro de tu respuesta ANTES del tag SKILL. ")
            append("NO emitas solo `SKILL: show_example` — el estudiante necesita leer el ejemplo en tu mensaje.\n")
            append("3. Cuando emitas `SKILL: quiz_user`, INCLUYE la pregunta completa y las cuatro opciones en el JSON. La tarjeta UI muestra solo lo que el JSON contiene.\n")
            append("4. NUNCA repitas un ejemplo o quiz que ya entregaste en los últimos 3 turnos (revisa SESSION CONTEXT abajo).\n")
            append("5. Tono: $tone. Responde en español. 3–6 oraciones por turno salvo que pidan expandir.\n")
            append("6. Siempre cierra con `SKILL: {...}` en la última línea — elige UNA habilidad.")
        }
    }

    // ── 2.5 Curriculum context ──────────────────────────────────────────
    // Injects the active topic's case studies + quiz seeds so the model has
    // concrete material to teach from. Without this section the model
    // hallucinates offers ("would you like an example?") it can't deliver.

    private fun StringBuilder.appendCurriculumContext(
        store: LearningStore,
        sessionId: Long,
        curriculumId: String,
        en: Boolean,
    ) {
        append(if (en) "## CURRICULUM CONTEXT\n" else "## CONTEXTO DEL CURRÍCULO\n")
        if (curriculumId.isBlank()) {
            append(if (en) "(no curriculum bound yet)" else "(currículo no vinculado aún)")
            return
        }
        val lastTopicPref = store.getPref(LearningPreferenceKeys.LAST_TOPIC, "")
        // Try to surface the most-relevant module: last topic touched, else first low-mastery, else first module.
        val mods = store.modulesForCurriculum(curriculumId)
        if (mods.isEmpty()) {
            append(if (en) "(no modules loaded)" else "(sin módulos cargados)")
            return
        }
        val lows = store.lowMastery(3).map { it.topic }.toSet()
        val active = mods.firstOrNull { it.topic.equals(lastTopicPref, ignoreCase = true) }
            ?: mods.firstOrNull { it.topic in lows }
            ?: mods.first()
        append(if (en) "**Active module:** ${active.topic} (${active.difficulty})\n"
               else    "**Módulo activo:** ${active.topic} (${active.difficulty})\n")
        if (active.summary.isNotBlank()) {
            append(active.summary)
            append("\n\n")
        }
        // Embed up to 2 case studies + 2 quiz seeds as raw JSON so the model
        // can quote them verbatim.
        append(if (en) "**Case studies (use these — paraphrase, don't invent):**\n" else "**Casos de estudio (usa estos — parafrasea, no inventes):**\n")
        append("```json\n")
        append(active.caseStudiesJson)
        append("\n```\n")
        append(if (en) "**Quiz seeds (when emitting SKILL: quiz_user, prefer one of these):**\n"
               else    "**Semillas de quiz (cuando emitas SKILL: quiz_user, prefiere una de estas):**\n")
        append("```json\n")
        append(active.quizSeedsJson)
        append("\n```")
    }

    // ── 2. Learner model ─────────────────────────────────────────────────

    private fun StringBuilder.appendLearnerModel(store: LearningStore, en: Boolean) {
        append(if (en) "## LEARNER MODEL\n" else "## MODELO DEL ESTUDIANTE\n")
        val pace = store.getPref(LearningPreferenceKeys.PACE, "medium")
        val difficultyBias = store.getPref(LearningPreferenceKeys.DIFFICULTY_BIAS, "match")
        val lastTopic = store.getPref(LearningPreferenceKeys.LAST_TOPIC, "")

        val top5 = store.topMastery(5)
        val lows = store.lowMastery(5, threshold = 0.5)
        val crossFacts = store.crossSessionFacts(10)

        val model = JSONObject().apply {
            put("pace", pace)
            put("difficulty_bias", difficultyBias)
            put("last_topic", lastTopic)
            put("top_mastery", JSONArray().apply {
                top5.forEach {
                    put(JSONObject().apply {
                        put("topic", it.topic)
                        put("score", String.format("%.2f", it.mastery).toDouble())
                        put("n_seen", it.nSeen)
                    })
                }
            })
            put("low_mastery", JSONArray().apply {
                lows.forEach {
                    put(JSONObject().apply {
                        put("topic", it.topic)
                        put("score", String.format("%.2f", it.mastery).toDouble())
                    })
                }
            })
            put("known_facts", JSONArray().apply {
                crossFacts.forEach {
                    put(JSONObject().apply {
                        put("subject", it.subject)
                        put("predicate", it.predicate)
                        put("object", it.objectStr)
                        put("confidence", String.format("%.2f", it.confidence).toDouble())
                    })
                }
            })
        }
        append("```json\n")
        append(model.toString(2))
        append("\n```\n")
        append(
            if (en) "Use this model to choose what to teach. Prefer surfacing low-mastery topics before high-mastery ones. Skip topics the learner already demonstrated."
            else    "Usa este modelo para elegir qué enseñar. Prefiere mostrar temas de baja maestría antes que los de alta. Omite los que el estudiante ya demostró.",
        )
    }

    // ── 3. Session context ───────────────────────────────────────────────

    private fun StringBuilder.appendSessionContext(store: LearningStore, sessionId: Long, en: Boolean) {
        append(if (en) "## SESSION CONTEXT\n" else "## CONTEXTO DE SESIÓN\n")
        store.latestRollingSummary(sessionId)?.let {
            append(if (en) "**Rolling summary (turns ${it.coverFromTurn}–${it.coverToTurn}):** "
                   else    "**Resumen móvil (turnos ${it.coverFromTurn}–${it.coverToTurn}):** ")
            append(it.text)
            append("\n\n")
        }
        val recent = store.lastTurns(sessionId, 6)
        if (recent.isNotEmpty()) {
            append(if (en) "**Last ${recent.size} turns:**\n" else "**Últimos ${recent.size} turnos:**\n")
            recent.forEach { t -> append("- [${t.role}] ${t.text.take(220)}\n") }
        } else {
            append(if (en) "This is the first turn of this session." else "Este es el primer turno de la sesión.")
        }
    }

    // ── 4. Skill catalog ─────────────────────────────────────────────────

    private fun StringBuilder.appendSkillCatalog(en: Boolean) {
        append(if (en) "## SKILLS AVAILABLE\n" else "## HABILIDADES DISPONIBLES\n")
        append(
            if (en) "You may invoke ONE skill per turn by emitting a JSON object on the FINAL line, prefixed with `SKILL:`. Do not wrap it in markdown. Do not invoke more than one skill per turn.\n\n"
            else    "Puedes invocar UNA habilidad por turno emitiendo un objeto JSON en la ÚLTIMA línea, prefijado con `SKILL:`. No lo envuelvas en markdown. No invoques más de una habilidad por turno.\n\n",
        )
        append("```json\n")
        append(LearningSkill.catalogJson())
        append("\n```\n")
        append(
            if (en) "Example: `SKILL: {\"name\": \"quiz_user\", \"topic\": \"MARCH protocol\", \"difficulty\": \"intermediate\", \"question\": \"...\", \"options\": [\"a\",\"b\",\"c\",\"d\"], \"correct_index\": 1, \"explanation\": \"...\"}`"
            else    "Ejemplo: `SKILL: {\"name\": \"quiz_user\", \"topic\": \"protocolo MARCH\", \"difficulty\": \"intermediate\", \"question\": \"...\", \"options\": [\"a\",\"b\",\"c\",\"d\"], \"correct_index\": 1, \"explanation\": \"...\"}`",
        )
    }

    /** Brief summary for cross-session promotion. Same builder logic, condensed. */
    fun briefForClose(store: LearningStore, sessionId: Long): String {
        val turns: List<LearningTurn> = store.lastTurns(sessionId, 12)
        val recentText = turns.joinToString(" / ") { "${it.role}:${it.text.take(60)}" }
        val lows = store.lowMastery(3, 0.5).joinToString(", ") { it.topic }
        val tops = store.topMastery(3).joinToString(", ") { "${it.topic}(${"%.2f".format(it.mastery)})" }
        return "Session covered: $recentText. Strong topics: $tops. Needs review: $lows."
    }
}
