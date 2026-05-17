package io.kognis.tactical.data

import android.content.Context
import android.util.JsonReader
import android.util.Log
import io.kognis.tactical.core.ChunkEncryptor
import io.kognis.tactical.core.SecurePrefs
import io.objectbox.BoxStore

/**
 * Ingesta de Knowledge Base — Fase 5.2 (JSON real) + Fase 5.3 (vectores reales)
 *
 * Lee manuales_base.json desde assets/ y carga los DocumentChunks con
 * sus vectores pre-computados (384 dims, multilingual-e5-small normalizado)
 * en ObjectBox HNSW.
 *
 * El JSON es generado por kognis-pipeline/vectorize.py en Mac.
 * Los vectores ya vienen calculados — NO se recalculan en el dispositivo.
 *
 * Si el JSON no existe en assets, hace fallback a 3 chunks mock
 * para mantener el sistema operativo sin crashear.
 */
object KnowledgeBaseLoader {
    private const val TAG = "KnowledgeBaseLoader"
    private const val ASSET_PATH = "manuales_base.json"

    // Bump this whenever manuales_base.json is replaced/updated OR encryption scheme changes.
    // Triggers automatic re-ingestion on next app start.
    // v5: INSARAG/UNDAC humanitarian corpus — schema: {id, question, answer, source_doc, vector[384]}
    private const val KB_VERSION = 6
    private const val KB_VERSION_PREF = "kb_asset_version"


    /**
     * Ingesta el knowledge base si ObjectBox está vacío O si el asset cambió (KB_VERSION mismatch).
     * Prioridad: assets/manuales_base.json → fallback mock interno
     *
     * @param onProgress optional (currentInserted, estimatedTotal) — called on each batch flush.
     *                   estimatedTotal is -1 if unknown.
     */
    fun ingestIfNeeded(
        context: Context,
        boxStore: BoxStore,
        onProgress: ((Int, Int) -> Unit)? = null,
    ) {
        val chunkBox = boxStore.boxFor(DocumentChunk::class.java)
        val prefs = SecurePrefs.get(context)
        val storedVersion = prefs.getInt(KB_VERSION_PREF, 0)

        if (!chunkBox.isEmpty && storedVersion == KB_VERSION) {
            Log.d(TAG, "KB current v$KB_VERSION (${chunkBox.count()} chunks). Skipping.")
            return
        }

        if (!chunkBox.isEmpty && storedVersion != KB_VERSION) {
            Log.i(TAG, "KB version changed ($storedVersion → $KB_VERSION) — clearing ${chunkBox.count()} stale chunks")
            chunkBox.removeAll()
        }

        val ingestedFromAssets = tryIngestFromAssets(context, chunkBox, onProgress)

        if (ingestedFromAssets) {
            prefs.edit().putInt(KB_VERSION_PREF, KB_VERSION).apply()
            Log.i(TAG, "KB version stored: $KB_VERSION")
        } else {
            Log.w(TAG, "manuales_base.json not found in assets — using hardcoded mock fallback")
            Log.w(TAG, "Run kognis-pipeline/run_pipeline.sh to generate real embeddings")
            ingestMockFallback(chunkBox)
        }
    }

    /**
     * Fuerza una re-ingesta completa (borra y re-inserta).
     * Usar cuando se actualice manuales_base.json con nuevos documentos.
     */
    fun forceReingest(context: Context, boxStore: BoxStore) {
        val chunkBox = boxStore.boxFor(DocumentChunk::class.java)
        Log.i(TAG, "Force reingest: clearing ${chunkBox.count()} existing chunks...")
        chunkBox.removeAll()
        // Reset stored version so ingestIfNeeded always runs
        SecurePrefs.get(context).edit().remove(KB_VERSION_PREF).apply()
        ingestIfNeeded(context, boxStore)
    }

    // ── Ingesta desde assets/ (streaming) ────────────────────────────────────
    // Uses JsonReader to avoid loading the full file into RAM.
    // Works for both formats:
    //   • manuales_base.json with "vector" field → HNSW + BM25
    //   • manuales_qa.json without "vector" field → BM25 text search only

    private fun tryIngestFromAssets(
        context: Context,
        chunkBox: io.objectbox.Box<DocumentChunk>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Boolean {
        return try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (ASSET_PATH !in assetList) {
                Log.w(TAG, "$ASSET_PATH not found in assets/")
                return false
            }

            // Estimate total chunks from file size for the progress UI.
            // Average chunk JSON (incl. 384-dim vector serialized + text) ≈ 13.5 KB.
            val assetSize = runCatching {
                context.assets.openFd(ASSET_PATH).use { it.length }
            }.getOrDefault(-1L)
            val estimatedTotal = if (assetSize > 0) (assetSize / 13_500L).toInt().coerceAtLeast(1) else -1

            Log.i(TAG, "Loading KB from assets/$ASSET_PATH (streaming, ~${assetSize / (1024 * 1024)} MB, est. ~$estimatedTotal chunks)...")

            var total = 0
            var withVectors = 0
            val batch = mutableListOf<DocumentChunk>()
            val ingestStartMs = System.currentTimeMillis()
            // Smaller batch (100) → first counter visible in ~3-5 s instead of ~20-30 s.
            val batchSize = 100

            // Emit initial 0 so the UI shows the counter immediately, not just a spinner.
            onProgress?.invoke(0, estimatedTotal)

            JsonReader(context.assets.open(ASSET_PATH).bufferedReader()).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    // Schema v4: {title, content, question, chunk_id, vector}
                    // Schema v5: {id, question, answer, source_doc, source_page, vector, ...}
                    var title = ""
                    var content = ""
                    var question = ""
                    var chunkId = ""
                    // v5 fields
                    var id = ""
                    var answer = ""
                    var sourceDoc = ""
                    var sourcePage = ""
                    var vector: FloatArray? = null

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "title"      -> title = reader.nextString()
                            "content"    -> content = reader.nextString()
                            "question"   -> question = reader.nextString()
                            "chunk_id"   -> chunkId = reader.nextString()
                            "id"         -> id = reader.nextString()
                            "answer"     -> answer = reader.nextString()
                            "source_doc"  -> sourceDoc = reader.nextString()
                            "source_page" -> sourcePage = reader.nextString()
                            "vector"     -> {
                                val vList = mutableListOf<Float>()
                                reader.beginArray()
                                while (reader.hasNext()) vList.add(reader.nextDouble().toFloat())
                                reader.endArray()
                                if (vList.size == 384) {
                                    vector = vList.toFloatArray()
                                    withVectors++
                                } else {
                                    Log.w(TAG, "Entry vector dim=${vList.size} ≠ 384, skipping vector")
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    // Resolve: v5 schema uses answer+source_doc; v4 uses title+content
                    val resolvedTitle   = if (sourceDoc.isNotBlank()) sourceDoc else title
                    val resolvedContent = if (answer.isNotBlank()) answer else content
                    val resolvedChunkId = when {
                        id.isNotBlank()      -> id
                        chunkId.isNotBlank() -> chunkId
                        else                 -> ""
                    }

                    if (resolvedTitle.isNotBlank() && resolvedContent.isNotBlank()) {
                        val richContent = if (question.isNotBlank()) "P: $question\n$resolvedContent" else resolvedContent
                        batch.add(DocumentChunk(
                            title = ChunkEncryptor.encrypt(resolvedTitle),
                            content = ChunkEncryptor.encrypt(richContent),
                            chunkId = resolvedChunkId.ifBlank { null },
                            sourcePage = sourcePage.ifBlank { null },
                            vector = vector
                        ))
                        total++
                        if (batch.size >= batchSize) {
                            chunkBox.put(batch)
                            val elapsedSec = (System.currentTimeMillis() - ingestStartMs) / 1000.0
                            val rate = if (elapsedSec > 0) total / elapsedSec else 0.0
                            Log.i(TAG, "KB ingest progress: $total / ~$estimatedTotal chunks (${"%.0f".format(rate)} chunks/s)")
                            onProgress?.invoke(total, estimatedTotal)
                            batch.clear()
                        }
                    }
                }
                reader.endArray()
            }

            if (batch.isNotEmpty()) {
                chunkBox.put(batch)
                onProgress?.invoke(total, estimatedTotal)
            }

            if (total == 0) {
                Log.e(TAG, "No valid entries parsed from $ASSET_PATH")
                return false
            }

            val totalSec = (System.currentTimeMillis() - ingestStartMs) / 1000.0
            Log.i(TAG, "✅ KB loaded: $total chunks ($withVectors with vectors) in ${"%.1f".format(totalSec)}s from assets/$ASSET_PATH")
            if (withVectors == 0) {
                Log.w(TAG, "No vectors found — HNSW disabled, BM25 text search active")
            }

            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load from assets: ${e::class.simpleName} — ${e.message}", e)
            false
        }
    }

    // ── SAF URI Ingestion (Fase 9) ────────────────────────────────────────

    data class KbUpdateResult(
        val added: Int,
        val skipped: Int,
        val rejected: Int,
        val sha256: String,
        val totalInDb: Long
    )

    fun ingestFromUri(
        context: Context,
        uri: android.net.Uri,
        boxStore: BoxStore
    ): KbUpdateResult {
        val chunkBox = boxStore.boxFor(DocumentChunk::class.java)

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open URI: $uri")

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        // Build dedup key set: use chunkId when set, else content hash for pre-Fase9 chunks.
        // Decrypt content before hashing — stored content may be AES-256-GCM encrypted (D-DIL 11.2).
        val existingKeys = chunkBox.all
            .mapTo(mutableSetOf()) { c ->
                val plainContent = ChunkEncryptor.decrypt(c.content)
                c.chunkId?.ifBlank { contentHash(plainContent) } ?: contentHash(plainContent)
            }

        // Tolerate either a bare array OR an object wrapping an array under a few common keys.
        val text = String(bytes, Charsets.UTF_8)
        val array: org.json.JSONArray = runCatching { org.json.JSONArray(text) }.getOrElse {
            val obj = org.json.JSONObject(text)
            obj.optJSONArray("chunks")
                ?: obj.optJSONArray("documents")
                ?: obj.optJSONArray("data")
                ?: throw IllegalArgumentException("JSON root must be array or contain chunks/documents/data array")
        }
        var added = 0; var skipped = 0; var rejected = 0

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: run { rejected++; continue }

            // Schema-tolerant field resolution. Supports:
            //   v4 (manuales_base.json older): title, content, chunk_id, vector
            //   v5 (current corpus + qa_embedded): id, question, answer, source_doc, source_page, vector
            val v4Title   = obj.optString("title", "")
            val v4Content = obj.optString("content", "")
            val question  = obj.optString("question", "")
            val answer    = obj.optString("answer", "")
            val sourceDoc = obj.optString("source_doc", "")
            val sourcePage = obj.optString("source_page", "").ifBlank {
                runCatching { obj.opt("source_page")?.toString() ?: "" }.getOrDefault("")
            }
            val v5Id      = obj.optString("id", "")
            val v4ChunkId = obj.optString("chunk_id", "")

            val title = sourceDoc.ifBlank { v4Title }
            val rawContent = answer.ifBlank { v4Content }
            val content = if (question.isNotBlank() && rawContent.isNotBlank()) "P: $question\n$rawContent" else rawContent
            val resolvedChunkId = when {
                v5Id.isNotBlank()      -> v5Id
                v4ChunkId.isNotBlank() -> v4ChunkId
                else                   -> contentHash(content)
            }

            if (title.isBlank() || content.isBlank()) { rejected++; continue }
            val vectorArr = obj.optJSONArray("vector")
            if (vectorArr == null || vectorArr.length() != 384) { rejected++; continue }

            if (resolvedChunkId in existingKeys) { skipped++; continue }

            val vector = FloatArray(384) { vectorArr.getDouble(it).toFloat() }
            chunkBox.put(DocumentChunk(
                title = ChunkEncryptor.encrypt(title),
                content = ChunkEncryptor.encrypt(content),
                chunkId = resolvedChunkId,
                sourcePage = sourcePage.ifBlank { null },
                vector = vector
            ))
            existingKeys.add(resolvedChunkId)
            added++
        }

        Log.i(TAG, "KB SAF ingest: +$added added, $skipped skipped, $rejected rejected (total=${chunkBox.count()})")
        return KbUpdateResult(added, skipped, rejected, sha256, chunkBox.count())
    }

    private fun contentHash(content: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    // ── Mock Fallback ──────────────────────────────────────────────────────

    /**
     * 3 chunks mock con vectores keyword-based.
     * Compatible con el mock mode de EmbeddingEngine.
     * Este fallback se activará solo si el pipeline ONNX no se ha corrido.
     */
    private fun ingestMockFallback(chunkBox: io.objectbox.Box<DocumentChunk>) {
        try {
            val docs = listOf(
                DocumentChunk(
                    title = "Principios Humanitarios (mock fallback)",
                    content = """La acción humanitaria salva vidas y alivia el sufrimiento sin distinción.
1. Humanidad: motor principal frente a crisis por conflicto o desastre.
2. Imparcialidad: asistencia basada exclusivamente en la necesidad.
3. Neutralidad: no tomar partido en hostilidades o controversias.
4. Independencia: autonomía respecto a objetivos políticos, económicos o militares.""".trimIndent(),
                    vector = FloatArray(384) { if (it == 0) 1.0f else 0.01f }
                ),
                DocumentChunk(
                    title = "Triage por colores en víctimas múltiples (mock fallback)",
                    content = """El sistema START de triage clasifica con cuatro colores:
ROJO (inmediato): intervención que salva la vida en minutos.
AMARILLO (diferido): lesiones serias pero estables, entre 30 minutos y dos horas.
VERDE (leve): heridas menores, ambulatorio, pueden esperar.
NEGRO (fallecido o expectante): sin signos vitales o incompatible con supervivencia dado el recurso disponible.
La triage se repite cuando cambia la situación.""".trimIndent(),
                    vector = FloatArray(384) { if (it == 100) 1.0f else 0.01f }
                ),
                DocumentChunk(
                    title = "Control de hemorragia externa (mock fallback)",
                    content = """Orden de actuación:
1. Compresión directa con gasa o tela limpia sobre la herida, al menos 5 minutos sin levantar.
2. Si la herida está en una extremidad y la compresión no controla, colocar torniquete 5 a 7 cm proximal a la herida (nunca sobre articulación).
3. Anotar hora de aplicación visible en la víctima.
4. Apósito hemostático en cuello o tronco donde no se puede aplicar torniquete.""".trimIndent(),
                    vector = FloatArray(384) { if (it == 300) 1.0f else 0.01f }
                )
            )

            chunkBox.put(docs)
            Log.i(TAG, "Mock fallback ingested: ${docs.size} chunks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ingest mock fallback", e)
        }
    }
}
