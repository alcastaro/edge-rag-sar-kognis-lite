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
        val image = InputImage.fromBitmap(bitmap, 0)
        return runRecognizer(image)
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
     * Compose a prompt from OCR output for the RAG pipeline.
     * Routes the extracted label text into a dosage-lookup query.
     */
    fun buildMedicationQuery(ocr: OcrResult, en: Boolean): String {
        if (ocr.isEmpty) {
            return if (en) "No text detected on label."
                   else    "No se detectó texto en la etiqueta."
        }
        val labelText = ocr.blocks.joinToString(" · ").take(400)
        // English prompt regardless of UI language — Gemma 4 E2B follows English
        // instructions more reliably for structured medical Q&A. This is a vision
        // pipeline, NOT a map command — instruct the model explicitly to skip LOCATION_JSON.
        return "VISION INPUT (medication-label OCR). This is NOT a map command — do NOT emit LOCATION_JSON.\n" +
               "Identify the medication and provide indications, dosage, and contraindications from the humanitarian field corpus. " +
               "If you cannot identify the medication from the label, say so plainly.\n\n" +
               "Label text extracted on-device:\n$labelText"
    }
}
