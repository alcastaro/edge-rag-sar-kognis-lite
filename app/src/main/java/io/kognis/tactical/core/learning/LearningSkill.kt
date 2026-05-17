package io.kognis.tactical.core.learning

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sealed registry of the learning-only skills the model may invoke.
 * Strictly NO code-execution skills — these are all app-side UI / state actions.
 *
 * Wire format: the model emits a final-line `SKILL: {"name": "...", ...args}` tag.
 * Parsed by [SkillCallExtractor] into one of these subclasses.
 */
sealed class LearningSkill {

    abstract val name: String

    data class ShowExample(
        val topic: String,
        val rationale: String = "",
    ) : LearningSkill() { override val name = "show_example" }

    data class QuizUser(
        val topic: String,
        val difficulty: String,
        val question: String,
        val options: List<String>,
        val correctIndex: Int,
        val explanation: String = "",
    ) : LearningSkill() { override val name = "quiz_user" }

    data class ReviewPastMisses(
        val limit: Int = 3,
    ) : LearningSkill() { override val name = "review_past_misses" }

    data class MarkMastery(
        val topic: String,
        val score: Double,
        val rationale: String = "",
    ) : LearningSkill() { override val name = "mark_mastery" }

    companion object {
        /** JSON-schema-like catalog the prompt embeds so the model knows what it can call. */
        fun catalogJson(): String = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "show_example")
                put("description", "Fetch a case study about a topic. App renders it as a card.")
                put("args", JSONObject().apply {
                    put("topic", "string — the SAR topic (e.g. 'MARCH protocol')")
                    put("rationale", "string — why you chose this example (optional)")
                })
            })
            put(JSONObject().apply {
                put("name", "quiz_user")
                put("description", "Render a multiple-choice question. Use quiz_seeds from the curriculum when possible.")
                put("args", JSONObject().apply {
                    put("topic", "string")
                    put("difficulty", "string: beginner | intermediate | advanced")
                    put("question", "string")
                    put("options", "array of 4 strings")
                    put("correct_index", "integer 0..3")
                    put("explanation", "string — short explanation of the correct answer")
                })
            })
            put(JSONObject().apply {
                put("name", "review_past_misses")
                put("description", "Surface previously-missed topics from learner memory.")
                put("args", JSONObject().apply { put("limit", "integer — how many topics") })
            })
            put(JSONObject().apply {
                put("name", "mark_mastery")
                put("description", "Update the learner's mastery score for a topic.")
                put("args", JSONObject().apply {
                    put("topic", "string")
                    put("score", "number 0..1")
                    put("rationale", "string")
                })
            })
        }.toString(2)
    }
}
