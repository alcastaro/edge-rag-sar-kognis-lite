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

    /**
     * Brace-counting JSON extractor — replaces the naive `\{[^{}]*\}` regex which
     * truncates when a string value contains `}`. Walks the text character-by-
     * character tracking depth + string state + escapes. Returns the full JSON
     * object substring after `SKILL:` or null.
     */
    private fun extractJsonAfterSkill(text: String): String? {
        val skillIdx = text.indexOf("SKILL", ignoreCase = true)
        if (skillIdx < 0) return null
        val colonIdx = text.indexOf(':', skillIdx)
        if (colonIdx < 0) return null
        val openIdx = text.indexOf('{', colonIdx)
        if (openIdx < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIdx until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openIdx, i + 1)
                }
            }
        }
        return null
    }

    fun extract(text: String): LearningSkill? {
        val rawJson = extractJsonAfterSkill(text) ?: return null
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
    fun strip(text: String): String {
        val jsonStart = text.indexOf("SKILL", ignoreCase = true)
        val json = extractJsonAfterSkill(text) ?: return text
        // Anchor on the `SKILL` token itself so we also strip the prefix + colon.
        val cutFrom = if (jsonStart in 0..text.indexOf(json)) jsonStart else text.indexOf(json)
        return (text.substring(0, cutFrom) + text.substring(text.indexOf(json) + json.length)).trimEnd()
    }
}
