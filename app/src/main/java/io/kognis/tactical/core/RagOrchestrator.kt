package io.kognis.tactical.core

import android.util.Log
import androidx.annotation.VisibleForTesting
import io.kognis.tactical.core.llm.Conversation
import io.kognis.tactical.core.llm.ModelRunner
import io.kognis.tactical.core.llm.ChatMessage
import io.kognis.tactical.core.llm.MessageResponse
import io.kognis.tactical.ai.EmbeddingEngine
import io.kognis.tactical.data.DocumentChunk
import io.kognis.tactical.data.decrypted
import io.objectbox.Box
import kotlinx.coroutines.flow.Flow

class RagOrchestrator(
    internal val embeddingEngine: EmbeddingEngine,
    private val documentBox: Box<DocumentChunk>,
    private val modelRunner: ModelRunner
) {

    companion object {
        private const val TAG = "RagOrchestrator"

        // HNSW cosine distance threshold (lower = more similar).
        // Applies ONLY when ONNX real mode is active.
        private const val RELEVANCE_THRESHOLD = 0.30

        // Text search score threshold (0.0–1.0, higher = more similar).
        // Applies when ONNX is in mock/unavailable mode.
        private const val TEXT_SCORE_THRESHOLD = 0.15

        // Detects "lat <number> lon <number>" in either order. Tolerates dot or
        // comma decimal, optional sign, optional whitespace. Triggers a RAG
        // bypass: when the operator already provides coords, the KB injection
        // is at best noise and at worst forces the model into the wrong locale.
        // Matches "lat X lon Y" keyword form OR bare high-precision decimal pair
        // "18.4741,-69.6212" (≥4 decimal places = intentional GPS, not random numbers).
        private val COORD_PATTERN = Regex(
            """(?i)\b(lat|latitude|latitud)\s*[:=]?\s*-?\d{1,3}[.,]?\d*.*?\b(lon|long|longitude|longitud)\s*[:=]?\s*-?\d{1,3}[.,]?\d*""" +
            """|(-?\d{1,2}\.\d{4,})\s*[,\s]\s*(-?\d{1,3}\.\d{4,})""",
        )
    }

    var verbosityLevel: String = "ESTANDAR"
    var language: String = "es"
    var modelSize: String = "350M"
    // 11.3: sliding window depth — 4 turns for 1.2B (~200MB KV cache savings vs 8), 8 for 350M
    var maxTurns: Int = 8
    /** Fired when the KV-cache window resets due to turn limit (not external resets). */
    var onSlidingWindowReset: ((turns: Int, max: Int) -> Unit)? = null

    /**
     * When true, the system prompt asks the model to append `LOCATION_JSON: {...}`
     * whenever the answer references a geographic point. The UI surfaces a
     * "Ver en mapa" button that drops the marker in OsmAnd (or osmdroid fallback).
     * Default off — wired to true for models where the demo flow needs it.
     */
    var mapMode: Boolean = false

    // D-DIL 11.2: lazy cache of decrypted chunks for BM25 (decrypt once, reuse per session)
    @Volatile private var decryptedChunksCache: List<DocumentChunk>? = null

    // PERF-02: inverted index — term → chunk indices. Built once from decrypted cache.
    @Volatile private var invertedIndex: Map<String, List<Int>>? = null
    // Forward index — chunk ID → token set. Used for IDF-weighted BM25 in hybrid search.
    @Volatile private var normalizedTokensCache: Map<Long, Set<String>>? = null
    // IDF scores — term → log((N+1)/(df+1)). Built alongside the inverted index.
    @Volatile private var idfCache: Map<String, Double>? = null

    /** Invalidate decrypted chunk cache — call after KB re-ingestion. */
    fun invalidateChunksCache() {
        decryptedChunksCache = null
        invertedIndex = null
        normalizedTokensCache = null
        idfCache = null
        Log.d(TAG, "Decrypted chunks cache invalidated")
    }

    /** PERF-01: Pre-decrypt all chunks into RAM during startup (avoids 5s hiccup on first query). */
    suspend fun warmupDecryptionCache() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (decryptedChunksCache != null) return@withContext
        val count = getAllDecryptedChunks().size
        Log.i(TAG, "Decryption warmup complete: $count chunks ready")
    }

    private fun getAllDecryptedChunks(): List<DocumentChunk> {
        return decryptedChunksCache ?: synchronized(this) {
            decryptedChunksCache ?: run {
                val chunks = documentBox.all.map { it.decrypted() }
                decryptedChunksCache = chunks
                // Build all indices atomically: inverted + forward + IDF
                val idx = mutableMapOf<String, MutableList<Int>>()
                val fwd = mutableMapOf<Long, Set<String>>()
                chunks.forEachIndexed { i, chunk ->
                    // Use tokenize() (stopword-filtered) — same pipeline as query tokens
                    val tokens = RagTextUtils.tokenize("${chunk.title} ${chunk.content}").toSet()
                    fwd[chunk.id] = tokens
                    tokens.forEach { term -> idx.getOrPut(term) { mutableListOf() }.add(i) }
                }
                val N = chunks.size
                val idf = idx.mapValues { (_, postings) ->
                    RagTextUtils.idfScore(N, postings.size)
                }
                invertedIndex = idx
                normalizedTokensCache = fwd
                idfCache = idf
                Log.d(TAG, "Decrypted ${chunks.size} chunks; index: ${idx.size} terms, IDF ready")
                chunks
            }
        }
    }



    // Persistent Conversation — reuse KV cache across consecutive queries.
    // Rebuilt only when system prompt changes or sliding window is exhausted.
    private var activeConversation: Conversation? = null
    private var lastPromptHash: Int = 0
    private var turnCount: Int = 0

    private fun getOrCreateConversation(radioMode: Boolean): Conversation {
        val prompt = buildSystemPrompt(radioMode)
        val hash = prompt.hashCode()
        if (activeConversation == null || hash != lastPromptHash || turnCount >= maxTurns) {
            val reason = when {
                activeConversation == null -> "first"
                hash != lastPromptHash     -> "prompt changed"
                else                       -> "sliding window"
            }
            if (reason == "sliding window") onSlidingWindowReset?.invoke(turnCount, maxTurns)
            Log.d(TAG, "Creating new Conversation (reason: $reason, turns: $turnCount/$maxTurns)")
            // Release native KV-cache + decoder state of the previous conversation
            // before allocating a new one. Critical for LiteRT-LM (Gemma 4) — without
            // this the underlying native Conversation handle leaks every reset.
            try { activeConversation?.close() } catch (e: Exception) {
                Log.w(TAG, "previous conversation close: ${e.message}")
            }
            activeConversation = modelRunner.createConversation(systemPrompt = prompt)
            lastPromptHash = hash
            turnCount = 0
        }
        turnCount++
        return activeConversation!!
    }

    /** Force-recreate the Conversation on the next query. Call when verbosity/language changes or memory is low. */
    fun resetConversation() {
        try { activeConversation?.close() } catch (e: Exception) {
            Log.w(TAG, "resetConversation close: ${e.message}")
        }
        activeConversation = null
        turnCount = 0
        Log.d(TAG, "Conversation invalidated")
    }

    /**
     * IDF-weighted BM25 presence score for [chunkId].
     * score = sum(matched_token_idf) / sum(query_token_idf).
     * Returns 0.0 if indices not ready (warmup not done yet).
     */
    private fun bm25Score(queryTokens: List<String>, chunkId: Long): Double {
        if (queryTokens.isEmpty()) return 0.0
        val tokenSet = normalizedTokensCache?.get(chunkId) ?: return 0.0
        val idf = idfCache ?: return 0.0
        val queryIdfSum = queryTokens.sumOf { idf[it] ?: 0.0 }.coerceAtLeast(1e-9)
        return queryTokens.filter { it in tokenSet }.sumOf { idf[it] ?: 0.0 } / queryIdfSum
    }

    @VisibleForTesting
    internal fun getTurnCount(): Int = turnCount

    @VisibleForTesting
    internal fun shouldResetConversation(promptHash: Int): Boolean =
        activeConversation == null || promptHash != lastPromptHash || turnCount >= maxTurns

    /**
     * Override the system prompt for the next turn. Set by [LearningOrchestrator]
     * when a training session is active so the model receives the 4-section
     * Hermes-style prompt instead of the standard field-assistant prompt.
     *
     * Null = revert to the default builder. Hash change forces a fresh KV-cache.
     */
    @Volatile var customSystemPrompt: String? = null

    /**
     * Per-turn override for [mapMode]. When non-null, the system-prompt builder
     * uses this value INSTEAD of the field. Set to `false` when [ragMode] is
     * "NoMap" so the mapModeAppendix is suppressed and the model doesn't emit
     * LOCATION_JSON for vision-pipeline or training-mode queries.
     */
    @Volatile var mapModeOverride: Boolean? = null

    @VisibleForTesting
    internal fun buildSystemPrompt(radioMode: Boolean = false): String {
        customSystemPrompt?.let { return it }
        val effectiveMapMode = mapModeOverride ?: mapMode
        return RagPromptBuilder.buildSystemPrompt(verbosityLevel, language, modelSize, radioMode, effectiveMapMode)
    }

    /**
     * Evaluate a query through the RAG pipeline.
     *
     * Strategy:
     *   1. If ONNX is active → vector HNSW search (semantic)
     *   2. If ONNX is NOT active → BM25-style text keyword search (always works)
     *
     * Returns RagResult with metadata + LLM response flow.
     */
    suspend fun evaluate(query: String, ragMode: String = "Auto", deviceLat: Double? = null, deviceLon: Double? = null, radioMode: Boolean = false): RagResult {
        // Per-turn mapMode toggle: NoMap suppresses LOCATION_JSON appendix entirely.
        // Vision and training queries set ragMode=NoMap so the model doesn't try to
        // place a map marker from the response.
        mapModeOverride = if (ragMode == "NoMap") false else null
        val mode = if (embeddingEngine.isRealModeActive()) "ONNX" else "TEXT"
        Log.d(TAG, "=== RAG evaluate [$mode mode] [ragMode: $ragMode] [radioMode: $radioMode]: '${query.take(80)}'")

        // RAG bypass: if the operator gave explicit coordinates in the query
        // (e.g., "marca lat 18.42 lon -69.43"), the model has everything it
        // needs from the prompt alone — running BM25/HNSW against the KB only
        // injects unrelated context (most KBs aren't full of lat/lon values)
        // and biases the response toward the wrong locale. Skip retrieval when
        // ragMode allows (Auto or Desactivado; Siempre still forces RAG).
        val hasExplicitCoords = ragMode != "Siempre" && COORD_PATTERN.containsMatchIn(query)
        if (hasExplicitCoords) {
            Log.i(TAG, "RAG bypass: explicit lat/lon detected in query — skipping retrieval")
        }

        val searchResult: RagSearchResult = if (hasExplicitCoords) {
            RagSearchResult(false, -1.0, null, null, "")
        } else if (ragMode == "Desactivado") {
            Log.d(TAG, "RAG Desactivado by user")
            RagSearchResult(false, -1.0, null, null, "")
        } else if (embeddingEngine.isRealModeActive()) {
            val hybrid = hybridSearch(query, ragMode)
            if (!hybrid.activated) {
                // HNSW threshold exceeded — fall back to full-text BM25 over all chunks.
                // Catches keyword-heavy queries (codes, proper nouns, exact terms) that
                // semantic vectors miss when embeddings have low cosine similarity.
                Log.d(TAG, "Hybrid RAG skipped (dist=${"%.3f".format(hybrid.score)}) — trying text fallback")
                val text = textSearch(query, ragMode)
                if (text.activated) {
                    Log.i(TAG, "Text fallback ACTIVATED: ${text.topTitle}")
                    text
                } else hybrid
            } else hybrid
        } else {
            textSearch(query, ragMode)
        }

        val en = language == "en"

        // Build final prompt: RAG documental + query
        val contextText = searchResult.contextText
        val finalPrompt = buildString {
            if (contextText.isNotBlank()) { append(contextText); append("\n\n") }
            append(if (en) "OPERATOR QUERY:\n$query" else "PREGUNTA DEL OPERADOR:\n$query")
            // QueryPreprocessor already pre-placed the marker — tell the model not to re-emit
            // LOCATION_JSON. Injected in the user message (not system prompt) so the Conversation
            // KV-cache is preserved and no context reset occurs.
            if (ragMode == "NoMap") append(
                if (en) "\n[SYSTEM NOTE: Marker already pre-placed by app. Do NOT emit LOCATION_JSON.]"
                else    "\n[NOTA DE SISTEMA: Marcador ya colocado por la app. NO emitas LOCATION_JSON.]"
            )
        }

        Log.d(TAG, "RAG ${if (searchResult.activated) "ACTIVATED" else "SKIPPED"}: score=${searchResult.score}, title='${searchResult.topTitle}'")
        Log.d(TAG, "Final prompt length: ${finalPrompt.length} chars")

        // Generate LLM response — reuse persistent Conversation (KV cache) when prompt unchanged
        val conversation = getOrCreateConversation(radioMode)
        val message = ChatMessage(role = ChatMessage.Role.USER, textContent = finalPrompt)
        val flow = conversation.generateResponse(message)

        return RagResult(
            ragActivated = searchResult.activated,
            score = searchResult.score,
            threshold = if (mode == "ONNX") RELEVANCE_THRESHOLD else TEXT_SCORE_THRESHOLD,
            chunkTitle = searchResult.topTitle,
            chunkContent = searchResult.topContent,
            embeddingMode = mode,
            allChunks = searchResult.allChunks,
            responseFlow = flow,
            ragBypassedCoords = hasExplicitCoords,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VECTOR SEARCH (ONNX real mode)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun vectorSearch(query: String, ragMode: String): RagSearchResult {
        Log.d(TAG, "→ Vector HNSW search")
        val queryVector = embeddingEngine.generateVector(query)

        val searchResults = try {
            documentBox.query()
                .nearestNeighbors(io.kognis.tactical.data.DocumentChunk_.vector, queryVector, 3)
                .build()
                .findWithScores()
        } catch (e: Exception) {
            Log.e(TAG, "HNSW query failed", e)
            return RagSearchResult(false, -1.0, null, null, "")
        }

        if (searchResults.isEmpty()) {
            Log.d(TAG, "No HNSW results")
            return RagSearchResult(false, -1.0, null, null, "")
        }

        val topResult = searchResults.first()
        val score = topResult.score
        val topDoc = topResult.get().decrypted()

        val effectiveThreshold = when (ragMode) {
            "Siempre" -> 1.0 // Muy laxo para aceptar casi cualquier cosa
            "Auto" -> 0.25 // Ligeramente más estricto para ignorar trivialidades
            else -> RELEVANCE_THRESHOLD
        }

        if (score < effectiveThreshold) {
            val relevantResults = searchResults.filter { it.score < effectiveThreshold }
            val contextBuilder = StringBuilder(if (language == "en") "TACTICAL CONTEXT RETRIEVED:\n" else "CONTEXTO TÁCTICO RECUPERADO:\n")
            relevantResults.forEach { result ->
                val chunk = result.get().decrypted()
                contextBuilder.append("«${chunk.title}» (score: ${"%.3f".format(result.score)})\n")
                contextBuilder.append("${chunk.content}\n\n")
            }
            val chunks = relevantResults.map { r ->
                val c = r.get().decrypted()
                RagChunk(c.title, c.content.take(1500), r.score, c.sourcePage)
            }
            Log.d(TAG, "Vector RAG: ${chunks.size} chunks injected")
            return RagSearchResult(
                activated = true,
                score = score,
                topTitle = topDoc.title,
                topContent = topDoc.content.take(500),
                contextText = contextBuilder.toString(),
                allChunks = chunks
            )
        }

        return RagSearchResult(activated = false, score = score, topTitle = topDoc.title, topContent = null, contextText = "", allChunks = emptyList())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HYBRID SEARCH — HNSW top-10 + BM25 keyword → RRF fusion (NEW-06)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reciprocal Rank Fusion over HNSW (semantic) and BM25 (keyword) rankings.
     * Wider HNSW pool (50 candidates) improves recall for acronym/keyword queries
     * that semantic embeddings handle poorly (TCCC, MARCH, SALUTE, callsigns).
     * RRF score = 1/(k+rank_hnsw) + 1/(k+rank_bm25), k=25.
     */
    private suspend fun hybridSearch(query: String, ragMode: String): RagSearchResult {
        Log.d(TAG, "→ Hybrid HNSW+BM25 RRF search")
        val queryVector = embeddingEngine.generateVector(query)

        val rawResults = try {
            documentBox.query()
                .nearestNeighbors(io.kognis.tactical.data.DocumentChunk_.vector, queryVector, 50)
                .build()
                .findWithScores()
        } catch (e: Exception) {
            Log.e(TAG, "HNSW query failed", e)
            return RagSearchResult(false, -1.0, null, null, "")
        }

        if (rawResults.isEmpty()) return RagSearchResult(false, -1.0, null, null, "")

        data class Candidate(val chunk: DocumentChunk, val hnswDist: Double)
        // Decrypt each candidate's title/content before BM25 scoring
        val candidates = rawResults.map { Candidate(it.get().decrypted(), it.score) }

        // IDF-weighted BM25 score — pre-computed token sets from warmup, no re-normalization
        val queryTokens = RagTextUtils.tokenize(query)
        val bm25Scores = candidates.map { c -> bm25Score(queryTokens, c.chunk.id) }

        // RRF: HNSW rank by dist ascending (index = rank), BM25 rank by score descending
        val k = 25
        val bm25Ranks = IntArray(candidates.size)
        candidates.indices.sortedByDescending { bm25Scores[it] }
            .forEachIndexed { rank, idx -> bm25Ranks[idx] = rank }

        val reranked = candidates.indices
            .sortedByDescending { i -> 1.0 / (k + i) + 1.0 / (k + bm25Ranks[i]) }

        val topIdx = reranked.first()
        val topDist = candidates[topIdx].hnswDist
        val topDoc = candidates[topIdx].chunk

        val effectiveThreshold = when (ragMode) {
            "Siempre" -> 1.0
            "Auto" -> 0.25
            else -> RELEVANCE_THRESHOLD
        }

        if (topDist >= effectiveThreshold) {
            Log.i(TAG, "Hybrid RAG SKIPPED: top dist=${"%.3f".format(topDist)} >= threshold=$effectiveThreshold")
            return RagSearchResult(false, topDist, topDoc.title, null, "")
        }

        val relevant = reranked.filter { candidates[it].hnswDist < effectiveThreshold }.take(3)
        val contextBuilder = StringBuilder(if (language == "en") "TACTICAL CONTEXT RETRIEVED:\n" else "CONTEXTO TÁCTICO RECUPERADO:\n")
        relevant.forEach { i ->
            val c = candidates[i]
            contextBuilder.append("«${c.chunk.title}» (score: ${"%.3f".format(c.hnswDist)})\n")
            contextBuilder.append("${c.chunk.content}\n\n")
        }

        val chunks = relevant.map { i ->
            val c = candidates[i]
            RagChunk(c.chunk.title, c.chunk.content.take(1500), c.hnswDist, c.chunk.sourcePage)
        }

        Log.i(TAG, "Hybrid RAG ACTIVATED: ${chunks.size} chunks via RRF (top dist=${"%.3f".format(topDist)})")
        return RagSearchResult(
            activated = true,
            score = topDist,
            topTitle = topDoc.title,
            topContent = topDoc.content.take(500),
            contextText = contextBuilder.toString(),
            allChunks = chunks
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEXT SEARCH (fallback — always works without ONNX)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BM25-inspired keyword overlap search over all chunks.
     * Scores each chunk by the fraction of query tokens that appear in title+content.
     * Guaranteed to work without any ML model.
     */
    private fun textSearch(query: String, ragMode: String): RagSearchResult {
        Log.d(TAG, "→ Text keyword search (ONNX unavailable)")

        val allChunks = try {
            getAllDecryptedChunks()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chunks from ObjectBox", e)
            return RagSearchResult(false, -1.0, null, null, "")
        }

        if (allChunks.isEmpty()) {
            Log.w(TAG, "Knowledge base is empty — no chunks to search")
            return RagSearchResult(false, -1.0, null, null, "")
        }

        Log.d(TAG, "Text search across ${allChunks.size} chunks")

        // Tokenize query: split into meaningful words (>=3 chars, no stopwords)
        val queryTokens = RagTextUtils.tokenize(query)
        Log.d(TAG, "Query tokens: $queryTokens")

        if (queryTokens.isEmpty()) {
            return RagSearchResult(false, -1.0, null, null, "")
        }

        // PERF-02: inverted index lookup — O(tokens × avg_postings) vs O(N × tokens)
        data class ScoredChunk(val chunk: DocumentChunk, val score: Double)
        val scored: List<ScoredChunk>
        val idx = invertedIndex
        if (idx != null) {
            // Collect candidate chunk indices via inverted index, then score with IDF-weighted BM25
            val candidateIndices = mutableSetOf<Int>()
            queryTokens.forEach { token -> idx[token]?.forEach { candidateIndices.add(it) } }
            scored = candidateIndices.map { i ->
                ScoredChunk(allChunks[i], bm25Score(queryTokens, allChunks[i].id))
            }.sortedByDescending { it.score }
        } else {
            // Fallback: linear scan (index not yet built — warmup not called)
            scored = allChunks.mapNotNull { chunk ->
                val tokenSet = RagTextUtils.tokenize("${chunk.title} ${chunk.content}").toSet()
                val matchCount = queryTokens.count { it in tokenSet }
                if (matchCount > 0) ScoredChunk(chunk, matchCount.toDouble() / queryTokens.size) else null
            }.sortedByDescending { it.score }
        }

        Log.d(TAG, "Text search results: ${scored.size} matches")
        scored.take(3).forEach { Log.d(TAG, "  score=${it.score.format3()}, title='${it.chunk.title}'") }

        val effectiveThreshold = when (ragMode) {
            "Siempre" -> 0.01 // Muy laxo, con que coincida una palabra
            "Auto" -> 0.15 // Más estricto que el defecto
            else -> TEXT_SCORE_THRESHOLD
        }

        if (scored.isEmpty() || scored.first().score < effectiveThreshold) {
            val topScore = scored.firstOrNull()?.score ?: -1.0
            val topTitle = scored.firstOrNull()?.chunk?.title
            Log.i(TAG, "TEXT RAG SKIPPED: best score=${topScore.format3()} < threshold=$effectiveThreshold")
            return RagSearchResult(false, topScore, topTitle, null, "")
        }

        // Build context from top matches that exceed threshold
        val relevant = scored.filter { it.score >= effectiveThreshold }.take(3)
        Log.i(TAG, "TEXT RAG ACTIVATED: ${relevant.size} relevant chunks (best score=${relevant.first().score.format3()})")

        val contextBuilder = StringBuilder(if (language == "en") "TACTICAL CONTEXT RETRIEVED:\n" else "CONTEXTO TÁCTICO RECUPERADO:\n")
        relevant.forEach { result ->
            contextBuilder.append("«${result.chunk.title}»\n")
            contextBuilder.append("${result.chunk.content}\n\n")
        }

        val topChunk = relevant.first().chunk
        val chunks = relevant.map { r -> RagChunk(r.chunk.title, r.chunk.content.take(1500), r.score, r.chunk.sourcePage) }
        return RagSearchResult(
            activated = true,
            score = relevant.first().score,
            topTitle = topChunk.title,
            topContent = topChunk.content.take(500),
            contextText = contextBuilder.toString(),
            allChunks = chunks
        )
    }


    private fun Double.format3() = "%.3f".format(this)

    // ─────────────────────────────────────────────────────────────────────────
    // Geo helpers (no external deps)
    // ─────────────────────────────────────────────────────────────────────────

    /** Haversine distance in meters between two lat/lon points. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Human-readable distance label: "15 m", "1.2 km" etc. */
    private fun formatDist(meters: Double): String = when {
        meters < 1000 -> "${meters.toInt()} m"
        else -> "${"%.1f".format(meters / 1000)} km"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal data class for search result
    // ─────────────────────────────────────────────────────────────────────────

    private data class RagSearchResult(
        val activated: Boolean,
        val score: Double,
        val topTitle: String?,
        val topContent: String?,
        val contextText: String,
        val allChunks: List<RagChunk> = emptyList()
    )
}
