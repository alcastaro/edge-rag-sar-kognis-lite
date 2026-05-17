package io.kognis.tactical.core.learning

import android.content.Context
import android.net.Uri
import android.util.Log
import io.kognis.tactical.data.learning.CurriculumLoader
import io.kognis.tactical.data.learning.LearningPreferenceKeys
import io.kognis.tactical.data.learning.LearningStore
import io.objectbox.BoxStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates the adaptive-learning training mode. Owns:
 *   - active session lifecycle (start / append / close)
 *   - curriculum cache (delegates to [CurriculumLoader])
 *   - system-prompt assembly (delegates to [LearningPromptBuilder])
 *   - skill dispatch outcomes (writes to [LearningStore])
 *
 * Lives inside `:field_core` service so it can mutate ObjectBox without crossing
 * the AIDL boundary on every read.
 *
 * NOT a replacement for [io.kognis.tactical.core.RagOrchestrator] — they coexist.
 * When a learning session is active, queries route through this class and the
 * system prompt is rebuilt per turn; RAG retrieval still fires for grounding.
 */
class LearningOrchestrator(
    private val context: Context,
    boxStore: BoxStore,
) {

    val store = LearningStore(boxStore)

    @Volatile var activeSessionId: Long = 0L
        private set

    @Volatile var activeCurriculumId: String = ""
        private set

    @Volatile var activeLanguage: String = "es"
        private set

    val isActive: Boolean get() = activeSessionId > 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Start a session. If `curriculumUri` is null, loads the bundled asset. */
    fun startSession(curriculumUri: String?): Long {
        val loaded = if (curriculumUri.isNullOrBlank()) {
            CurriculumLoader.loadDefault(context, store)
        } else {
            CurriculumLoader.loadFromUri(context, store, Uri.parse(curriculumUri))
        }
        if (loaded == null) {
            Log.e(TAG, "Curriculum load failed; cannot start session")
            return 0L
        }
        val session = store.startSession(loaded.curriculumId, loaded.language)
        activeSessionId = session.id
        activeCurriculumId = loaded.curriculumId
        activeLanguage = loaded.language
        store.setPref(LearningPreferenceKeys.LANGUAGE, loaded.language)
        store.setPref(LearningPreferenceKeys.LAST_SESSION_ID, session.id.toString())
        Log.i(TAG, "Training session ${session.id} started (curriculum=${loaded.curriculumId})")
        return session.id
    }

    fun endSession() {
        if (activeSessionId == 0L) return
        val sid = activeSessionId
        val brief = LearningPromptBuilder.briefForClose(store, sid)
        store.closeSession(sid, brief)
        store.writeSummary(sid, kind = "session", fromTurn = 0, toTurn = -1, text = brief)
        val promoted = store.promoteSessionFacts(sid, threshold = 0.7)
        Log.i(TAG, "Ended training session $sid; promoted $promoted facts to long-term memory")
        activeSessionId = 0L
    }

    // ── Per-turn hooks ────────────────────────────────────────────────────

    /** Build the per-turn system prompt for the LLM. Called by FieldAssistantService. */
    fun systemPromptForActiveSession(): String? {
        val sid = activeSessionId
        if (sid == 0L) return null
        return LearningPromptBuilder.build(store, sid, activeLanguage, activeCurriculumId)
    }

    fun appendUserTurn(text: String) {
        if (activeSessionId == 0L) return
        store.appendTurn(activeSessionId, role = "user", text = text)
    }

    fun appendAssistantTurn(text: String, tokens: Int) {
        if (activeSessionId == 0L) return
        // Strip the SKILL tag from what we persist as the visible assistant turn — the
        // skill outcome is logged separately so it's not double-counted.
        val visible = SkillCallExtractor.strip(text)
        store.appendTurn(activeSessionId, role = "assistant", text = visible, tokens = tokens)
        // Extract + dispatch skill if present.
        SkillCallExtractor.extract(text)?.let { dispatchSkill(it) }
    }

    // ── Skill dispatch ────────────────────────────────────────────────────

    private fun dispatchSkill(skill: LearningSkill) {
        val sid = activeSessionId
        if (sid == 0L) return
        when (skill) {
            is LearningSkill.MarkMastery -> {
                store.setMastery(skill.topic, skill.score)
                store.recordSkill(
                    sid, skill.name,
                    argsJson = JSONObject().apply {
                        put("topic", skill.topic); put("score", skill.score); put("rationale", skill.rationale)
                    }.toString(),
                    outcomeJson = JSONObject().apply { put("ok", true) }.toString(),
                )
            }
            is LearningSkill.ShowExample -> {
                store.recordSkill(
                    sid, skill.name,
                    argsJson = JSONObject().apply { put("topic", skill.topic) }.toString(),
                )
                store.setPref(LearningPreferenceKeys.LAST_TOPIC, skill.topic)
            }
            is LearningSkill.QuizUser -> {
                store.recordSkill(
                    sid, skill.name,
                    argsJson = JSONObject().apply {
                        put("topic", skill.topic); put("difficulty", skill.difficulty)
                    }.toString(),
                )
            }
            is LearningSkill.ReviewPastMisses -> {
                store.recordSkill(
                    sid, skill.name,
                    argsJson = JSONObject().apply { put("limit", skill.limit) }.toString(),
                )
            }
        }
    }

    /** UI-driven outcome: the learner answered a quiz card. */
    fun recordQuizOutcome(topic: String, correct: Boolean) {
        val sid = activeSessionId
        if (sid == 0L) return
        val updated = store.upsertMastery(topic, correct)
        store.addFact(
            sessionId = sid,
            subject = "learner",
            predicate = if (correct) "answered_correctly" else "missed",
            objectStr = topic,
            confidence = if (correct) 0.8 else 0.3,
        )
        Log.i(TAG, "Quiz outcome topic=$topic correct=$correct → mastery=${"%.2f".format(updated.mastery)}")
    }

    // ── Snapshot ──────────────────────────────────────────────────────────

    /** JSON snapshot of the learner model — for the LearnerPanel UI / AIDL exposure. */
    fun learnerModelJson(): String = JSONObject().apply {
        put("active_session_id", activeSessionId)
        put("curriculum_id", activeCurriculumId)
        put("language", activeLanguage)
        put("prefs", JSONObject(store.allPrefs() as Map<*, *>))
        put("top_mastery", JSONArray().apply {
            store.topMastery(5).forEach {
                put(JSONObject().apply {
                    put("topic", it.topic); put("score", it.mastery); put("n_seen", it.nSeen)
                })
            }
        })
        put("low_mastery", JSONArray().apply {
            store.lowMastery(5).forEach {
                put(JSONObject().apply { put("topic", it.topic); put("score", it.mastery) })
            }
        })
        put("recent_invocations", JSONArray().apply {
            if (activeSessionId > 0) {
                store.recentInvocations(activeSessionId, 10).forEach {
                    put(JSONObject().apply { put("skill", it.skill); put("ts", it.ts) })
                }
            }
        })
    }.toString(2)

    companion object {
        private const val TAG = "LearningOrchestrator"
    }
}
