package io.kognis.tactical.data.learning

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads a SAR curriculum JSON file (either bundled asset or SAF URI) and caches
 * parsed modules into ObjectBox via [LearningStore]. Idempotent — re-running on
 * the same curriculum just refreshes `loadedTs`.
 *
 * Schema reference: see `docs/learning_mode.md` and `assets/curriculum_sar.json`.
 */
object CurriculumLoader {

    private const val TAG = "CurriculumLoader"
    const val DEFAULT_ASSET = "curriculum_sar.json"

    data class LoadResult(
        val curriculumId: String,
        val title: String,
        val language: String,
        val moduleCount: Int,
    )

    /** Load the bundled default curriculum from `assets/curriculum_sar.json`. */
    fun loadDefault(context: Context, store: LearningStore): LoadResult? = runCatching {
        val raw = context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
        parseAndCache(raw, store)
    }.onFailure { Log.e(TAG, "Default curriculum load failed: ${it.message}", it) }.getOrNull()

    /** Load an instructor-provided curriculum via SAF URI. */
    fun loadFromUri(context: Context, store: LearningStore, uri: Uri): LoadResult? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return null
        parseAndCache(raw, store)
    }.onFailure { Log.e(TAG, "SAF curriculum load failed: ${it.message}", it) }.getOrNull()

    private fun parseAndCache(raw: String, store: LearningStore): LoadResult {
        val root = JSONObject(raw)
        val curriculumId = root.optString("id", "default")
        val title = root.optString("title", "Untitled curriculum")
        val language = root.optString("language", "es")
        val modules = root.optJSONArray("modules") ?: JSONArray()
        var count = 0
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val module = CurriculumModule(
                curriculumId = curriculumId,
                moduleId = m.optString("id", "module_$i"),
                topic = m.optString("topic", ""),
                difficulty = m.optString("difficulty", "beginner"),
                summary = m.optString("summary", ""),
                caseStudiesJson = m.optJSONArray("case_studies")?.toString() ?: "[]",
                quizSeedsJson = m.optJSONArray("quiz_seeds")?.toString() ?: "[]",
            )
            store.upsertCurriculumModule(module)
            count++
        }
        Log.i(TAG, "Loaded curriculum $curriculumId ($count modules, lang=$language)")
        return LoadResult(curriculumId, title, language, count)
    }
}
