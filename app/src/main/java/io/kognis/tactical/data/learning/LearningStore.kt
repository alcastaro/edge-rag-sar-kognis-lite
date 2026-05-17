package io.kognis.tactical.data.learning

import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * Facade over the eight learning entities. Keeps ObjectBox API surface contained
 * in one place so `LearningOrchestrator` and `LearningPromptBuilder` stay
 * focused on agent logic, not persistence plumbing.
 *
 * All operations are synchronous (ObjectBox is ~0.1 ms per read) and main-thread
 * safe for our access volumes. WorkManager-driven summary writes go through this
 * facade too.
 */
class LearningStore(private val boxStore: BoxStore) {

    private val sessions: Box<LearningSession>      get() = boxStore.boxFor(LearningSession::class.java)
    private val turns: Box<LearningTurn>            get() = boxStore.boxFor(LearningTurn::class.java)
    private val summaries: Box<LearningSummary>     get() = boxStore.boxFor(LearningSummary::class.java)
    private val facts: Box<LearningFact>            get() = boxStore.boxFor(LearningFact::class.java)
    private val preferences: Box<LearningPreference> get() = boxStore.boxFor(LearningPreference::class.java)
    private val mastery: Box<TopicMastery>          get() = boxStore.boxFor(TopicMastery::class.java)
    private val invocations: Box<SkillInvocation>   get() = boxStore.boxFor(SkillInvocation::class.java)
    private val curricula: Box<CurriculumModule>    get() = boxStore.boxFor(CurriculumModule::class.java)

    // ── Sessions ──────────────────────────────────────────────────────────

    fun startSession(curriculumId: String, language: String): LearningSession {
        val s = LearningSession(curriculumId = curriculumId, language = language)
        s.id = sessions.put(s)
        Log.i(TAG, "Started session ${s.id} curriculum=$curriculumId lang=$language")
        return s
    }

    fun closeSession(sessionId: Long, summary: String) {
        val s = sessions.get(sessionId) ?: return
        s.closeTs = System.currentTimeMillis()
        s.summaryShortText = summary.take(800)
        sessions.put(s)
        Log.i(TAG, "Closed session $sessionId with ${s.turnCount} turns")
    }

    fun lastSession(): LearningSession? =
        sessions.query().orderDesc(LearningSession_.startTs).build().findFirst()

    fun getSession(id: Long): LearningSession? = sessions.get(id)

    // ── Turns ─────────────────────────────────────────────────────────────

    fun appendTurn(sessionId: Long, role: String, text: String, tokens: Int = 0): LearningTurn {
        val sess = sessions.get(sessionId) ?: return LearningTurn()
        val nextIdx = sess.turnCount
        val turn = LearningTurn(
            sessionId = sessionId,
            turnIndex = nextIdx,
            role = role,
            text = text,
            tokens = tokens,
        )
        turn.id = turns.put(turn)
        sess.turnCount = nextIdx + 1
        sessions.put(sess)
        return turn
    }

    fun lastTurns(sessionId: Long, n: Int): List<LearningTurn> =
        turns.query(LearningTurn_.sessionId.equal(sessionId))
            .orderDesc(LearningTurn_.turnIndex)
            .build()
            .find(0, n.toLong())
            .reversed()

    // ── Summaries ─────────────────────────────────────────────────────────

    fun writeSummary(sessionId: Long, kind: String, fromTurn: Int, toTurn: Int, text: String) {
        summaries.put(
            LearningSummary(
                sessionId = sessionId,
                kind = kind,
                coverFromTurn = fromTurn,
                coverToTurn = toTurn,
                text = text,
            ),
        )
    }

    fun latestRollingSummary(sessionId: Long): LearningSummary? =
        summaries.query(
            LearningSummary_.sessionId.equal(sessionId)
                .and(LearningSummary_.kind.equal("rolling")),
        ).orderDesc(LearningSummary_.coverToTurn).build().findFirst()

    fun crossSessionSummaries(limit: Int = 5): List<LearningSummary> =
        summaries.query(LearningSummary_.kind.equal("cross_session"))
            .orderDesc(LearningSummary_.ts).build().find(0, limit.toLong())

    // ── Facts ─────────────────────────────────────────────────────────────

    fun addFact(
        sessionId: Long,
        subject: String,
        predicate: String,
        objectStr: String,
        confidence: Double,
    ): LearningFact {
        val f = LearningFact(
            sessionId = sessionId,
            subject = subject,
            predicate = predicate,
            objectStr = objectStr,
            confidence = confidence.coerceIn(0.0, 1.0),
            tokensIdx = computeFactTokens(subject, objectStr),
        )
        f.id = facts.put(f)
        return f
    }

    fun searchFacts(query: String, limit: Int = 20): List<LearningFact> {
        val token = computeFactTokens(query, "")
        if (token.isBlank()) return emptyList()
        val first = token.split(" ").first()
        return facts.query(
            LearningFact_.tokensIdx.contains(first).and(LearningFact_.supersededBy.equal(0L)),
        ).orderDesc(LearningFact_.confidence).build().find(0, limit.toLong())
    }

    fun supersedeFact(oldId: Long, newId: Long) {
        val old = facts.get(oldId) ?: return
        old.supersededBy = newId
        facts.put(old)
    }

    /** Promote high-confidence session facts to cross-session memory. */
    fun promoteSessionFacts(sessionId: Long, threshold: Double = 0.7): Int {
        val candidates = facts.query(
            LearningFact_.sessionId.equal(sessionId)
                .and(LearningFact_.confidence.greater(threshold))
                .and(LearningFact_.supersededBy.equal(0L)),
        ).build().find()
        candidates.forEach {
            it.crossSession = true
            facts.put(it)
        }
        Log.i(TAG, "Promoted ${candidates.size} facts to cross-session memory")
        return candidates.size
    }

    fun crossSessionFacts(limit: Int = 30): List<LearningFact> =
        facts.query(LearningFact_.crossSession.equal(true).and(LearningFact_.supersededBy.equal(0L)))
            .orderDesc(LearningFact_.confidence).build().find(0, limit.toLong())

    // ── Preferences ───────────────────────────────────────────────────────

    fun getPref(key: String, default: String = ""): String =
        preferences.query(LearningPreference_.key.equal(key)).build().findFirst()?.value ?: default

    fun setPref(key: String, value: String) {
        val existing = preferences.query(LearningPreference_.key.equal(key)).build().findFirst()
        val pref = (existing ?: LearningPreference(key = key)).apply {
            this.value = value
            updatedTs = System.currentTimeMillis()
        }
        preferences.put(pref)
    }

    fun allPrefs(): Map<String, String> =
        preferences.all.associate { it.key to it.value }

    // ── Topic mastery ─────────────────────────────────────────────────────

    fun upsertMastery(topic: String, success: Boolean): TopicMastery {
        val existing = mastery.query(TopicMastery_.topic.equal(topic)).build().findFirst()
        val updated = (existing ?: TopicMastery(topic = topic)).applyOutcome(success)
        updated.id = mastery.put(updated)
        return updated
    }

    fun setMastery(topic: String, score: Double): TopicMastery {
        val existing = mastery.query(TopicMastery_.topic.equal(topic)).build().findFirst()
        val updated = (existing ?: TopicMastery(topic = topic)).copy(
            mastery = score.coerceIn(0.0, 1.0),
            updatedTs = System.currentTimeMillis(),
            decayTs = System.currentTimeMillis(),
        )
        updated.id = mastery.put(updated)
        return updated
    }

    fun topMastery(n: Int = 5): List<TopicMastery> =
        mastery.query().orderDesc(TopicMastery_.mastery).build().find(0, n.toLong())

    fun lowMastery(n: Int = 5, threshold: Double = 0.5): List<TopicMastery> =
        mastery.query(TopicMastery_.mastery.less(threshold))
            .orderDesc(TopicMastery_.nSeen).build().find(0, n.toLong())

    // ── Skill audit ───────────────────────────────────────────────────────

    fun recordSkill(sessionId: Long, skill: String, argsJson: String, outcomeJson: String = "") {
        invocations.put(
            SkillInvocation(
                sessionId = sessionId,
                skill = skill,
                argsJson = argsJson,
                outcomeJson = outcomeJson,
            ),
        )
    }

    fun recentInvocations(sessionId: Long, n: Int = 10): List<SkillInvocation> =
        invocations.query(SkillInvocation_.sessionId.equal(sessionId))
            .orderDesc(SkillInvocation_.ts).build().find(0, n.toLong())

    // ── Curriculum cache ──────────────────────────────────────────────────

    fun upsertCurriculumModule(m: CurriculumModule) {
        val existing = curricula.query(
            CurriculumModule_.curriculumId.equal(m.curriculumId)
                .and(CurriculumModule_.moduleId.equal(m.moduleId)),
        ).build().findFirst()
        val toPut = (existing ?: m).apply {
            topic = m.topic
            difficulty = m.difficulty
            summary = m.summary
            caseStudiesJson = m.caseStudiesJson
            quizSeedsJson = m.quizSeedsJson
            loadedTs = System.currentTimeMillis()
        }
        curricula.put(toPut)
    }

    fun modulesForCurriculum(curriculumId: String): List<CurriculumModule> =
        curricula.query(CurriculumModule_.curriculumId.equal(curriculumId))
            .build().find()

    fun moduleByTopic(curriculumId: String, topic: String): CurriculumModule? =
        curricula.query(
            CurriculumModule_.curriculumId.equal(curriculumId)
                .and(CurriculumModule_.topic.equal(topic)),
        ).build().findFirst()

    fun clearAllForDebug() {
        sessions.removeAll()
        turns.removeAll()
        summaries.removeAll()
        facts.removeAll()
        preferences.removeAll()
        mastery.removeAll()
        invocations.removeAll()
        curricula.removeAll()
    }

    companion object {
        private const val TAG = "LearningStore"
    }
}
