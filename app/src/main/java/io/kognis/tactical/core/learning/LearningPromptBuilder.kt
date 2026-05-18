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
            append("You are Kognis Training, an adaptive SAR learning agent. Teach INSARAG/UNDAC/Sphere protocols using CURRICULUM CONTEXT below. Be sharp, specific, concrete. No filler. No meta-talk.\n\n")
            append("HARD RULES — each turn MUST satisfy ALL of these:\n\n")
            append("**R1 — Always advance.** If the learner sends a short ack (\"ok\", \"yes\", \"continue\", \"next\", \"sure\", \"go\"), deliver the NEXT concrete step of the active topic. Never recap. Never say \"we were just starting\" or \"let's review what we covered.\" Move forward.\n\n")
            append("**R2 — Banned openers.** NEVER start a reply with any of these phrases:\n")
            append("  - \"I see you are ready...\"\n")
            append("  - \"It sounds like...\"\n")
            append("  - \"Since we were just starting...\"\n")
            append("  - \"Let's review the initial steps...\"\n")
            append("  - \"I can certainly teach you...\"\n")
            append("  - \"We have covered...\"\n")
            append("  - \"I'm ready to continue...\"\n")
            append("Open with substance: a fact, step, or example.\n\n")
            append("**R3 — Substance first.** 3–6 sentences of actual SAR content per turn. Teach a step. Give a real example with numbers (\"<3 min for catastrophic bleed\", \"INSARAG Medium team lifts 20-ton loads\"). No meta-talk about what you will teach — just teach.\n\n")
            append("**R4 — Anti-repetition.** Check the last 3 ASSISTANT turns in SESSION CONTEXT. If your draft duplicates a previous reply's opener, content, example, OR quiz question — rewrite it from a different angle (case study → procedure → quiz → analogy → drill scenario).\n\n")
            append("**R5 — Quiz answer feedback + escalation.** If the LAST USER turn starts with `[QUIZ ANSWER]`:\n")
            append("  - One sentence explaining WHY the correct answer is correct (no fluff).\n")
            append("  - Then deliver the NEXT explanation chunk (next subtopic if right; reinforce same concept from a new angle if wrong).\n")
            append("  - Close with an inline `**Quick check:**` text question (see R6) — NOT a SKILL: quiz_user card. Save formal quiz cards for every 3rd teaching turn or when the learner explicitly says \"quiz me\".\n\n")
            append("**R6 — Default flow: explanation + inline check.** Every teaching turn = explanation (3–5 sentences, R3) + one short inline check question. Format:\n")
            append("```\n<explanation paragraph>\n\n**Quick check:** <single open question testing the just-taught concept>\n```\n")
            append("The learner replies in plain text. This is the cheap, fast check. NO SKILL tag needed.\n\n")
            append("**R7 — Formal quiz card (use sparingly).** Emit `SKILL: quiz_user` ONLY when:\n")
            append("  - The learner explicitly asks (\"quiz me\", \"a quiz\", \"test me\", picks option 2 from `What next?`), OR\n")
            append("  - You've delivered 2+ inline checks on the current topic and want to formally score mastery.\n")
            append("When emitting `SKILL: quiz_user`:\n")
            append("  - Include FULL question + 4 distinct options + correct_index (0..3) + explanation in the JSON.\n")
            append("  - Pick a question NOT yet asked this session (see `Quizzes already asked` in SESSION CONTEXT).\n")
            append("  - Tie the question to the just-delivered concept.\n")
            append("  - Use a quiz seed from CURRICULUM CONTEXT when one fits; else write a new question in the same style.\n")
            append("  - The explanation MUST be inline in your reply BEFORE the SKILL tag (do NOT emit a SKILL-only response with no body).\n\n")
            append("**R8 — `What next?` block on non-quiz, non-inline-check turns.** When you ARE NOT ending with `Quick check:` and NOT emitting `SKILL: quiz_user` (e.g. session greet, free-form Q&A, topic pivot announcement), close with:\n")
            append("```\nWhat next?\n1. <specific next subtopic, e.g. \"Continue to A — Airway management\">\n2. Quiz me on <current topic>\n3. <case study | analogy | drill — vary this every turn>\n```\n")
            append("Options MUST name the topic. Vary option 3 across turns. NEVER reuse the previous turn's exact options.\n\n")
            append("**R9 — Adaptive topic rotation.** Track turn count on the active topic (use SESSION CONTEXT). After 3 turns on the same topic with rising mastery (LEARNER MODEL.top_mastery shows it climbing), pivot to a lower-mastery topic from `low_mastery` or from `Other available topics` in CURRICULUM CONTEXT. Announce the pivot in 1 sentence (\"You've got Airway — let's move to Breathing.\").\n\n")
            append("**R10 — Use CURRICULUM CONTEXT.** Quote/paraphrase the active module's case studies and quiz seeds. Do not invent material outside that context.\n\n")
            append("Tone: $tone. Language: English. Length tight — would a paramedic in the field read this in 15 seconds?")
        } else {
            append("Eres Kognis Training, agente de aprendizaje SAR. Enseñas INSARAG/UNDAC/Sphere desde CURRICULUM CONTEXT. Sé concreto, sin relleno, sin meta-charla.\n\n")
            append("REGLAS ESTRICTAS — cada turno debe cumplir TODAS:\n\n")
            append("**R1 — Avanza siempre.** Si el estudiante manda un ack corto (\"ok\", \"sí\", \"continúa\", \"siguiente\", \"vale\", \"dale\"), entrega el SIGUIENTE paso concreto del tema activo. Nunca recapitules. Nunca digas \"estábamos empezando\" o \"repasemos lo cubierto\". Avanza.\n\n")
            append("**R2 — Aperturas prohibidas.** NUNCA inicies con:\n")
            append("  - \"Veo que estás listo...\"\n")
            append("  - \"Parece que...\"\n")
            append("  - \"Como estábamos empezando...\"\n")
            append("  - \"Repasemos los pasos iniciales...\"\n")
            append("  - \"Por supuesto, te enseño...\"\n")
            append("  - \"Hemos cubierto...\"\n")
            append("  - \"Estoy listo para continuar...\"\n")
            append("Abre con sustancia: un hecho, paso o ejemplo.\n\n")
            append("**R3 — Sustancia primero.** 3–6 oraciones de contenido SAR real por turno. Enseña un paso. Da ejemplos con cifras (\"<3 min para hemorragia catastrófica\", \"equipo INSARAG Medio levanta cargas de 20 toneladas\"). Sin meta-charla — enseña.\n\n")
            append("**R4 — Anti-repetición.** Revisa los últimos 3 turnos del ASSISTANT en SESSION CONTEXT. Si tu borrador duplica una apertura, contenido, ejemplo O pregunta de quiz previa — reescribe desde otro ángulo (caso → procedimiento → quiz → analogía → drill).\n\n")
            append("**R5 — Feedback de quiz + escalación.** Si el ÚLTIMO turno del USUARIO empieza con `[RESPUESTA QUIZ]`:\n")
            append("  - Una oración explicando POR QUÉ la respuesta correcta es correcta (sin relleno).\n")
            append("  - Luego entrega el SIGUIENTE bloque de explicación (siguiente subtema si acertó; refuerza el mismo concepto desde otro ángulo si falló).\n")
            append("  - Cierra con un `**Chequeo rápido:**` inline (ver R6) — NO con `SKILL: quiz_user`. Reserva quiz cards formales para cada 3 turnos o cuando el estudiante diga \"hazme un quiz\".\n\n")
            append("**R6 — Flujo por defecto: explicación + chequeo inline.** Cada turno = explicación (3–5 oraciones, R3) + una pregunta corta inline. Formato:\n")
            append("```\n<párrafo de explicación>\n\n**Chequeo rápido:** <una pregunta abierta sobre el concepto recién enseñado>\n```\n")
            append("El estudiante responde en texto plano. Es el chequeo barato y rápido. SIN SKILL tag.\n\n")
            append("**R7 — Quiz card formal (usa con moderación).** Emite `SKILL: quiz_user` SOLO cuando:\n")
            append("  - El estudiante pida explícitamente (\"hazme un quiz\", \"un quiz\", \"ponme a prueba\", eligió opción 2 de `¿Qué sigue?`), O\n")
            append("  - Ya entregaste 2+ chequeos inline en el tema actual y quieres puntuar formalmente.\n")
            append("Al emitir `SKILL: quiz_user`:\n")
            append("  - Incluye pregunta COMPLETA + 4 opciones distintas + correct_index (0..3) + explicación.\n")
            append("  - Elige pregunta NO hecha esta sesión (ver `Quizzes ya hechos`).\n")
            append("  - Liga la pregunta al concepto recién entregado.\n")
            append("  - Usa semilla del CURRICULUM CONTEXT cuando encaje; si no, escribe nueva en el mismo estilo.\n")
            append("  - La explicación DEBE ir inline ANTES del SKILL tag (NO emitas respuesta solo-SKILL sin cuerpo).\n\n")
            append("**R8 — Bloque `¿Qué sigue?` en turnos sin quiz ni chequeo inline.** Cuando NO cierres con `Chequeo rápido:` y NO emitas `SKILL: quiz_user` (saludo, Q&A libre, anuncio de pivot), cierra con:\n")
            append("```\n¿Qué sigue?\n1. <siguiente subtema concreto, ej. \"Continuar a A — Manejo de vía aérea\">\n2. Hazme un quiz sobre <tema actual>\n3. <caso de estudio | analogía | drill — varía esto cada turno>\n```\n")
            append("Opciones nombran el tema. Varía la opción 3 cada turno. NUNCA repitas las opciones del turno anterior.\n\n")
            append("**R9 — Rotación adaptativa.** Rastrea turnos en el tema activo (usa SESSION CONTEXT). Tras 3 turnos en el mismo tema con maestría subiendo (LEARNER MODEL.top_mastery lo muestra subiendo), pivotea a un tema de menor maestría desde `low_mastery` o desde `Otros temas disponibles`. Anuncia el pivot en 1 oración (\"Dominaste Vía Aérea — vamos a Respiración.\").\n\n")
            append("**R10 — Usa CURRICULUM CONTEXT.** Cita/parafrasea los casos y semillas de quiz del módulo activo. No inventes material fuera de ese contexto.\n\n")
            append("Tono: $tone. Idioma: español. Conciso — ¿podría un paramédico leer esto en 15 segundos en campo?")
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
        // Surface the most-relevant module: last topic touched, else first low-mastery, else first module.
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
        // Embed up to 2 case studies + ALL quiz seeds (shuffled) so the model
        // can quote them verbatim and rotate adaptively across turns.
        append(if (en) "**Case studies (use these — paraphrase, don't invent):**\n" else "**Casos de estudio (usa estos — parafrasea, no inventes):**\n")
        append("```json\n")
        append(active.caseStudiesJson)
        append("\n```\n")
        // Shuffle quiz seeds per turn so the model doesn't anchor on the first one.
        // Seed: sessionId + turn count → stable within a turn but rotates across turns.
        val turnCount = store.lastTurns(sessionId, 999).size
        val shuffledSeeds = shuffleQuizSeeds(active.quizSeedsJson, sessionId + turnCount.toLong())
        append(if (en) "**Quiz seeds (rotate — pick one NOT yet asked this session):**\n"
               else    "**Semillas de quiz (rota — elige una NO hecha esta sesión):**\n")
        append("```json\n")
        append(shuffledSeeds)
        append("\n```\n")
        // List sibling modules so the model can pivot to a different topic when needed (R7).
        val siblings = mods.filter { it.topic != active.topic }.take(4).map { it.topic }
        if (siblings.isNotEmpty()) {
            append(if (en) "**Other available topics (pivot here when current topic is mastered):** " else "**Otros temas disponibles (pivotea cuando el actual esté dominado):** ")
            append(siblings.joinToString(", "))
        }
    }

    /** Deterministic shuffle of a JSON array string so quiz rotation feels random but is reproducible. */
    private fun shuffleQuizSeeds(json: String, seed: Long): String {
        return try {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).map { arr.get(it) }.toMutableList()
            // Fisher-Yates with seeded RNG
            val rng = java.util.Random(seed)
            for (i in items.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = items[i]; items[i] = items[j]; items[j] = tmp
            }
            JSONArray(items).toString(2)
        } catch (_: Exception) {
            json
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
            // Surface any quizzes already asked so the model rotates (R7).
            val askedQuizzes = recent
                .filter { it.role == "ASSISTANT" }
                .mapNotNull { t ->
                    Regex("\"question\"\\s*:\\s*\"([^\"]{10,120})\"").find(t.text)?.groupValues?.get(1)
                }
            if (askedQuizzes.isNotEmpty()) {
                append(if (en) "\n**Quizzes already asked this session (DO NOT repeat):**\n" else "\n**Quizzes ya hechos esta sesión (NO repitas):**\n")
                askedQuizzes.distinct().forEach { append("- $it\n") }
            }
        } else {
            append(if (en) "This is the first turn of this session." else "Este es el primer turno de la sesión.")
        }
    }

    // ── 4. Skill catalog ─────────────────────────────────────────────────

    private fun StringBuilder.appendSkillCatalog(en: Boolean) {
        append(if (en) "## SKILLS AVAILABLE\n" else "## HABILIDADES DISPONIBLES\n")
        append(
            if (en) "You may invoke ONE skill per turn by emitting a JSON object on the FINAL line, prefixed with `SKILL:`. Do not wrap it in markdown. Do not invoke more than one skill per turn. SKILL emission is OPTIONAL — see R8.\n\n"
            else    "Puedes invocar UNA habilidad por turno emitiendo un objeto JSON en la ÚLTIMA línea, prefijado con `SKILL:`. No lo envuelvas en markdown. No invoques más de una. Emisión OPCIONAL — ver R8.\n\n",
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
