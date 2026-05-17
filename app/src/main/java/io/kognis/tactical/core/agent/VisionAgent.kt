package io.kognis.tactical.core.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Vision agent — on-device OCR tool for the agentic loop.
 *
 * Wraps Google ML Kit's bundled Latin text recognizer. Runs entirely on-device
 * with no network: the recognition model ships inside the app (~3 MB). Matches
 * Kognis Lite's zero-signal posture — image bytes never leave the phone.
 *
 * Architectural role: a TOOL that converts a captured/loaded image into
 * structured text the rest of the agentic pipeline can route. In the
 * "medication identification" flow the extracted label text is then handed
 * to `QueryPreprocessor` + `RagOrchestrator` to resolve dosage from the
 * humanitarian field corpus.
 *
 * This is NOT Gemma 4's native vision modality. LiteRT-LM 0.11.0 does not yet
 * expose the Gemma 4 image input API in its Kotlin runtime, so we use ML Kit
 * as a deterministic pre-LLM vision tool. Honest framing in the writeup.
 */
object VisionAgent {

    private const val TAG = "VisionAgent"

    data class OcrResult(
        val rawText: String,
        val blocks: List<String>,
        val confidence: Float = 0f,
    ) {
        val isEmpty: Boolean get() = rawText.isBlank()
    }

    /** Recognize text from a URI (camera capture file OR gallery picker). */
    suspend fun recognizeFromUri(context: Context, uri: Uri): OcrResult {
        val image = InputImage.fromFilePath(context, uri)
        return runRecognizer(image)
    }

    /** Recognize text from a local file path. */
    suspend fun recognizeFromPath(path: String): OcrResult {
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return OcrResult(rawText = "", blocks = emptyList())
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            runRecognizer(image)
        } finally {
            // ML Kit's InputImage.fromBitmap does NOT take ownership; bitmap stays on
            // the heap until GC. Vision is fired twice in the demo (Act 3 + a retake);
            // unrecycled the second decode can OOM under thermal pressure on S24 Ultra.
            runCatching { bitmap.recycle() }
        }
    }

    private suspend fun runRecognizer(image: InputImage): OcrResult =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val blocks = result.textBlocks.map { it.text.trim() }.filter { it.isNotBlank() }
                    val raw = result.text.trim()
                    Log.i(TAG, "OCR extracted ${raw.length} chars, ${blocks.size} blocks")
                    cont.resume(OcrResult(rawText = raw, blocks = blocks))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}", e)
                    cont.resumeWithException(e)
                }
            cont.invokeOnCancellation {
                runCatching { recognizer.close() }
            }
        }

    /**
     * Two-part query for the medication-OCR flow.
     *
     * `embeddingQuery` is the clean label text routed through the embedding/RAG
     * pipeline. The verbose system instructions are NOT embedded because they
     * dilute the semantic signal of `multilingual-e5-small` — pre-fix logs
     * showed `chunks: []` even when the corpus contained relevant medication
     * data, because the boilerplate dominated the embedding vector.
     *
     * `userMessage` is what the LLM sees. Instructions are appended AFTER the
     * label text, so retrieval is fed clean tokens but the model still gets
     * the "no LOCATION_JSON, answer from corpus" guidance.
     */
    data class MedicationQuery(val embeddingQuery: String, val userMessage: String)

    fun buildMedicationQuery(ocr: OcrResult, en: Boolean): MedicationQuery {
        if (ocr.isEmpty) {
            val msg = if (en) "No text detected on label."
                      else    "No se detectó texto en la etiqueta."
            return MedicationQuery(embeddingQuery = msg, userMessage = msg)
        }
        val labelText = ocr.blocks.joinToString(" · ").take(400)
        // Clean retrieval query — drug name + form tokens are what we want the
        // embedding to weight. No system instructions, no negative prompts.
        val embeddingQuery = "medication label: $labelText"
        // English instructions regardless of UI language — Gemma 4 E2B follows
        // English instructions more reliably for structured medical Q&A.
        val userMessage = "Identify the medication and provide indications, dosage, " +
            "and contraindications from the humanitarian field corpus. If the label " +
            "data is not in the corpus, say so plainly. Do NOT emit LOCATION_JSON.\n\n" +
            "Label text extracted on-device:\n$labelText"
        return MedicationQuery(embeddingQuery, userMessage)
    }
}
