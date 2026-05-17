package io.kognis.tactical.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EvalRunner {

    data class EvalQuestion(val id: Int, val category: String, val question: String)

    data class EvalResult(
        val id: Int,
        val category: String,
        val question: String,
        val answer: String,
        val durationMs: Long,
        val timedOut: Boolean,
    )

    fun loadQuestions(context: Context): List<EvalQuestion> {
        val raw = context.assets.open("sar_test.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val out = mutableListOf<EvalQuestion>()
        for (cat in listOf("multi_chunk_questions", "hallucination_testing_questions")) {
            val arr = root.optJSONArray(cat) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out += EvalQuestion(
                    id = obj.getInt("id"),
                    category = cat,
                    question = obj.getString("question"),
                )
            }
        }
        return out
    }

    fun exportResults(context: Context, results: List<EvalResult>): Uri? = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "kognis_eval_$ts.json")
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("category", r.category)
                put("question", r.question)
                put("answer", r.answer)
                put("duration_ms", r.durationMs)
                put("timed_out", r.timedOut)
            })
        }
        val root = JSONObject().apply {
            put("eval_date", ts)
            put("kb_version", 6)
            put("total_questions", results.size)
            put("timed_out_count", results.count { it.timedOut })
            put("multi_chunk_count", results.count { it.category == "multi_chunk_questions" })
            put("hallucination_count", results.count { it.category == "hallucination_testing_questions" })
            put("results", arr)
        }
        file.writeText(root.toString(2))
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }.getOrElse {
        android.util.Log.e("EvalRunner", "Export failed", it)
        null
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
