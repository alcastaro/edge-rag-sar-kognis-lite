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

    fun build(store: LearningStore, sessionId: Long, language: String): String {
        val en = language == "en"
        return buildString {
            appendIdentity(store, en)
            append("\n\n")
            appendLearnerModel(store, en)
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
            append("You teach INSARAG, UNDAC, and Sphere protocols using a curriculum grounded in real field standards. ")
            append("Your tone is $tone. Respond in English. Stay concise (3–5 sentences per turn unless asked to expand). ")
            append("You always finish with either (a) a skill invocation as `SKILL: {...}` on the last line, or (b) a clear next-step question to the learner.")
        } else {
            append("Eres Kognis Training, un agente de aprendizaje adaptativo para Búsqueda y Rescate (SAR). ")
            append("Enseñas protocolos INSARAG, UNDAC y Sphere usando un currículo basado en estándares reales de campo. ")
            append("Tu tono es $tone. Responde en español. Sé conciso (3–5 oraciones por turno salvo que se te pida expandir). ")
            append("Siempre terminas con (a) una invocación de habilidad como `SKILL: {...}` en la última línea, o (b) una pregunta clara de siguiente paso al estudiante.")
        }
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
