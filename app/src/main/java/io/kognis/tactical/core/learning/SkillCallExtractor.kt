package io.kognis.tactical.core.learning

import android.util.Log
import org.json.JSONObject

/**
 * Parses `SKILL: {"name": "...", ...}` sentinel tags emitted by the model on the
 * final line of a training-mode response. Mirrors the existing
 * `LocationJsonExtractor` pattern so the contract is familiar.
 *
 * Returns null when:
 *   - No SKILL tag found
 *   - JSON is malformed
 *   - `name` field is not in the sealed registry
 *   - Required arg is missing
 */
object SkillCallExtractor {

    private const val TAG = "SkillCallExtractor"

    private val PATTERN = Regex(
        """(?i)SKILL\s*:\s*(\{[^{}]*\})""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun extract(text: String): LearningSkill? {
        val match = PATTERN.find(text) ?: return null
        val rawJson = match.groupValues[1]
        return runCatching {
            val obj = JSONObject(rawJson)
            when (obj.optString("name", "").lowercase()) {
                "show_example" -> LearningSkill.ShowExample(
                    topic = obj.optString("topic", "").ifBlank { return null },
                    rationale = obj.optString("rationale", ""),
                )
                "quiz_user" -> {
                    val opts = obj.optJSONArray("options") ?: return null
                    if (opts.length() != 4) return null
                    LearningSkill.QuizUser(
                        topic = obj.optString("topic", ""),
                        difficulty = obj.optString("difficulty", "beginner"),
                        question = obj.optString("question", "").ifBlank { return null },
                        options = (0 until 4).map { opts.optString(it, "") },
                        correctIndex = obj.optInt("correct_index", -1).also { if (it !in 0..3) return null },
                        explanation = obj.optString("explanation", ""),
                    )
                }
                "review_past_misses" -> LearningSkill.ReviewPastMisses(
                    limit = obj.optInt("limit", 3).coerceIn(1, 10),
                )
                "mark_mastery" -> LearningSkill.MarkMastery(
                    topic = obj.optString("topic", "").ifBlank { return null },
                    score = obj.optDouble("score", 0.5).coerceIn(0.0, 1.0),
                    rationale = obj.optString("rationale", ""),
                )
                else -> null
            }
        }.onFailure { Log.w(TAG, "SKILL parse error: ${it.message} — raw=$rawJson") }
            .getOrNull()
    }

    /** Strip the `SKILL: {...}` tag from text so it isn't shown to the user. */
    fun strip(text: String): String = text.replace(PATTERN, "").trimEnd()
}
