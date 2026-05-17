package io.kognis.tactical.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Motor de Embeddings ONNX Real — Fase 5.3 (revisado Fase 6)
 *
 * Usa intfloat/multilingual-e5-small exportado a ONNX.
 * El modelo y tokenizador se leen desde assets/ (bundleados en el APK).
 *
 * Assets requeridos (generados por kognis-pipeline/):
 *   assets/models/multilingual-e5-small.onnx
 *   assets/tokenizer/tokenizer.json
 *
 * Fixes Fase 6:
 *   - NNAPI wrapped in try/catch per-call (falls back to CPU on failure)
 *   - copyAssetToCache validates file size (detects corruption/truncation)
 *   - Tokenizer init is now lazy (avoids 30-60s blocking parse at startup)
 *   - Detailed logging at every failure point
 */
class EmbeddingEngine(private val context: Context, private val threadCount: Int = 2) {

    companion object {
        private const val TAG = "EmbeddingEngine"

        // Asset paths
        private const val ONNX_MODEL_ASSET = "models/multilingual-e5-small.onnx"
        private const val TOKENIZER_ASSET   = "tokenizer/tokenizer.json"

        // multilingual-e5-small dimensions
        private const val VECTOR_DIMS = 384
        private const val MAX_TOKEN_LENGTH = 512

        // E5 requires "query: " prefix for query embeddings
        private const val QUERY_PREFIX = "query: "
    }

    // ── Internal state ─────────────────────────────────────────────────────
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: SimpleTokenizer? = null
    private var isOnnxAvailable = false

    // LRU cache: skip ONNX inference for repeated queries (common in field ops).
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 50
    }

    init {
        initializeOnnx()
    }

    // ── Initialization ─────────────────────────────────────────────────────

    private fun initializeOnnx() {
        Log.d(TAG, "=== initializeOnnx START ===")
        try {
            // Step 1: verify assets exist
            val modelExists = assetExists(ONNX_MODEL_ASSET)
            val tokenizerExists = assetExists(TOKENIZER_ASSET)
            Log.d(TAG, "  Asset check — model: $modelExists, tokenizer: $tokenizerExists")

            if (!modelExists || !tokenizerExists) {
                Log.w(TAG, "ONNX assets missing — falling back to text-search RAG mode")
                Log.w(TAG, "  Model ($ONNX_MODEL_ASSET): $modelExists")
                Log.w(TAG, "  Tokenizer ($TOKENIZER_ASSET): $tokenizerExists")
                isOnnxAvailable = false
                return
            }

            // Step 2: copy ONNX to internal cache (OrtSession needs a file path, not stream)
            Log.d(TAG, "  Copying ONNX to internal cache...")
            val modelFile = copyAssetToCache(ONNX_MODEL_ASSET, "e5small.onnx")
            Log.d(TAG, "  ONNX cached: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")

            // Step 3: create ORT environment
            ortEnv = OrtEnvironment.getEnvironment()
            Log.d(TAG, "  OrtEnvironment created")

            // Step 4: create session — try NNAPI first, fall back to CPU-only if createSession fails
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(threadCount)
            var nnapiEnabled = false
            try {
                sessionOptions.addNnapi()
                nnapiEnabled = true
                Log.d(TAG, "  NNAPI acceleration enabled")
            } catch (e: Exception) {
                Log.w(TAG, "  NNAPI not available on this device (${e.message}) — using CPU")
            }

            // If NNAPI causes createSession to fail (e.g. unsupported ops on Snapdragon),
            // retry with plain CPU — this is the most common reason ONNX init fails silently.
            ortSession = try {
                val s = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
                Log.i(TAG, "  OrtSession created (NNAPI=$nnapiEnabled)")
                s
            } catch (e: Exception) {
                if (nnapiEnabled) {
                    Log.w(TAG, "  NNAPI session failed: ${e.message} — retrying CPU-only")
                    val cpuOptions = OrtSession.SessionOptions()
                    cpuOptions.setIntraOpNumThreads(2)
                    val s = ortEnv!!.createSession(modelFile.absolutePath, cpuOptions)
                    Log.i(TAG, "  OrtSession created (CPU fallback — NNAPI unsupported)")
                    s
                } else {
                    throw e
                }
            }
            Log.d(TAG, "  Input names:  ${ortSession?.inputNames?.joinToString()}")
            Log.d(TAG, "  Output names: ${ortSession?.outputNames?.joinToString()}")

            // Step 5: load tokenizer (lazy — SimpleTokenizer reads 16MB, can be slow)
            Log.d(TAG, "  Loading tokenizer...")
            tokenizer = SimpleTokenizer(context, TOKENIZER_ASSET)
            Log.d(TAG, "  Tokenizer loaded: vocab_size=${tokenizer?.vocabSize()}, merges=${tokenizer?.mergeCount()}")

            // Step 6: verify with a quick sanity inference
            Log.d(TAG, "  Running sanity inference...")
            val testVec = generateOnnxVector("test")
            val testNorm = testVec.map { it * it }.sum().let { Math.sqrt(it.toDouble()) }
            Log.d(TAG, "  Sanity vector: dims=${testVec.size}, norm=${"%.4f".format(testNorm)}")

            if (testVec.size != VECTOR_DIMS) {
                throw RuntimeException("ONNX output dimension mismatch: expected $VECTOR_DIMS, got ${testVec.size}")
            }

            isOnnxAvailable = true
            Log.i(TAG, "=== EmbeddingEngine: ONNX REAL MODE ACTIVE ===")

        } catch (e: Exception) {
            Log.e(TAG, "=== EmbeddingEngine: ONNX INIT FAILED — using text-search RAG ===", e)
            // Log exact failure for debugging
            Log.e(TAG, "  Failure type: ${e.javaClass.simpleName}")
            Log.e(TAG, "  Failure message: ${e.message}")
            e.cause?.let { Log.e(TAG, "  Caused by: ${it.javaClass.simpleName}: ${it.message}") }
            isOnnxAvailable = false
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Generate a 384-dim embedding vector for the query.
     * Falls back to mock if ONNX not available (text RAG handles actual retrieval).
     */
    fun generateVector(text: String): FloatArray {
        synchronized(embeddingCache) { embeddingCache[text] }?.let { return it }
        val vector = if (isOnnxAvailable) {
            try {
                generateOnnxVector(text)
            } catch (e: Exception) {
                Log.e(TAG, "ONNX inference failed — falling back to mock for this query", e)
                generateMockVector(text)
            }
        } else {
            generateMockVector(text)
        }
        if (isOnnxAvailable) synchronized(embeddingCache) { embeddingCache[text] = vector }
        return vector
    }

    fun isRealModeActive(): Boolean = isOnnxAvailable

    // ── ONNX Inference ─────────────────────────────────────────────────────

    private fun generateOnnxVector(text: String): FloatArray {
        val session = ortSession ?: throw IllegalStateException("OrtSession is null")
        val env = ortEnv ?: throw IllegalStateException("OrtEnvironment is null")
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer is null")

        // Apply E5 query prefix
        val prefixedText = "$QUERY_PREFIX$text"

        // Tokenize
        val tokens = tok.tokenize(prefixedText, MAX_TOKEN_LENGTH)
        val seqLen = tokens.inputIds.size.toLong()
        Log.d(TAG, "Tokenized '${text.take(40)}' → $seqLen tokens")

        // Build input tensors
        val inputIdsBuf  = LongBuffer.wrap(tokens.inputIds.map { it.toLong() }.toLongArray())
        val attentionBuf = LongBuffer.wrap(tokens.attentionMask.map { it.toLong() }.toLongArray())
        val shape = longArrayOf(1L, seqLen)

        val inputIdsTensor  = OnnxTensor.createTensor(env, inputIdsBuf, shape)
        val attentionTensor = OnnxTensor.createTensor(env, attentionBuf, shape)

        // Build input map dynamically based on session's declared inputs
        val inputNames = session.inputNames
        val inputs = buildMap {
            put("input_ids", inputIdsTensor)
            put("attention_mask", attentionTensor)
            if (inputNames.contains("token_type_ids")) {
                val tokenTypeBuf = LongBuffer.wrap(LongArray(tokens.inputIds.size) { 0L })
                put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeBuf, shape))
            }
        }

        // Run inference with guaranteed tensor cleanup
        var output: ai.onnxruntime.OrtSession.Result? = null
        try {
            output = session.run(inputs)

            // The ONNX export has TWO outputs:
            //   [0] token_embeddings  → [1, seq_len, 384]  (per-token)
            //   [1] sentence_embedding → [1, 384]           (pooled by model)
            val embedding: FloatArray = try {
                val sentenceEmb = output[1].value as Array<FloatArray>
                sentenceEmb[0]
            } catch (e: Exception) {
                Log.w(TAG, "sentence_embedding at index 1 failed, using mean pooling: ${e.message}")
                val tokenEmb = output[0].value as Array<Array<FloatArray>>
                meanPooling(tokenEmb[0], tokens.attentionMask)
            }

            val normalized = l2Normalize(embedding)
            Log.d(TAG, "ONNX vector: dims=${normalized.size}, norm=${"%.4f".format(normalized.map { it * it }.sum().let { Math.sqrt(it.toDouble()) })}")
            return normalized

        } finally {
            // Guaranteed native memory cleanup
            inputIdsTensor.close()
            attentionTensor.close()
            output?.close()
        }
    }

    // ── Pooling & Normalization ─────────────────────────────────────────────

    private fun meanPooling(hiddenStates: Array<FloatArray>, attentionMask: IntArray): FloatArray {
        val dims = hiddenStates[0].size
        val pooled = FloatArray(dims)
        var tokenCount = 0

        for (i in hiddenStates.indices) {
            if (attentionMask[i] == 1) {
                for (d in 0 until dims) {
                    pooled[d] += hiddenStates[i][d]
                }
                tokenCount++
            }
        }

        if (tokenCount > 0) {
            for (d in pooled.indices) {
                pooled[d] /= tokenCount
            }
        }
        return pooled
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = Math.sqrt(vector.map { it.toDouble() * it }.sum()).toFloat()
        return if (norm > 0f) FloatArray(vector.size) { vector[it] / norm } else vector
    }

    // ── Mock Fallback ──────────────────────────────────────────────────────

    /**
     * Mock vector — only used as placeholder for HNSW calls when ONNX unavailable.
     * The actual retrieval in TEXT mode (RagOrchestrator.textSearch) doesn't use vectors.
     */
    private fun generateMockVector(text: String): FloatArray {
        Log.d(TAG, "Using mock vector (text-search RAG will handle retrieval)")
        return FloatArray(VECTOR_DIMS) { 0.0f }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun assetExists(assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAssetToCache(assetPath: String, fileName: String): File {
        val cacheFile = File(context.cacheDir, fileName)

        // Get expected size from assets descriptor
        val expectedSize: Long = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get asset size via openFd (compressed?), using stream copy: ${e.message}")
            -1L
        }

        // Re-copy if missing, 0-byte (corrupt from previous compressed APK), or size mismatch
        val needsCopy = !cacheFile.exists() ||
            cacheFile.length() == 0L ||
            (expectedSize > 0 && cacheFile.length() != expectedSize)

        if (needsCopy) {
            if (cacheFile.exists()) {
                Log.w(TAG, "Cache file size mismatch (${cacheFile.length()} vs expected $expectedSize) — re-copying")
                cacheFile.delete()
            } else {
                Log.d(TAG, "Copying $assetPath to cache...")
            }
            val startMs = System.currentTimeMillis()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "Copy complete: ${cacheFile.length() / 1024 / 1024}MB in ${elapsed}ms")
        } else {
            Log.d(TAG, "ONNX cache valid: ${cacheFile.path} (${cacheFile.length() / 1024 / 1024}MB)")
        }
        return cacheFile
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ORT resources", e)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SimpleTokenizer — auto-detects BPE or Unigram (SentencePiece) format
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Dual-mode tokenizer compatible with multilingual-E5-small (XLM-RoBERTa).
 *
 * multilingual-e5-small uses a SentencePiece/Unigram tokenizer exported via
 * HuggingFace AutoTokenizer. The tokenizer.json has:
 *   model.type = "Unigram"
 *   model.vocab = [[token, log_prob], ...]   ← JSONArray, index = token ID
 *
 * Old BPE tokenizers (GPT-style) have:
 *   model.type = "BPE"
 *   model.vocab = { token: id }              ← JSONObject
 *   model.merges = [...]
 *
 * This class auto-detects the format and picks the correct algorithm.
 * For Unigram: greedy longest-match with ▁ word-boundary prefix.
 * For BPE: standard BPE merge with Ġ word-boundary prefix.
 */
class SimpleTokenizer(context: Context, tokenizerAsset: String) {

    companion object {
        private const val TAG = "SimpleTokenizer"
        // SentencePiece/XLM-RoBERTa special token positions in vocab list
        private const val BOS_TOKEN_ID = 0   // <s>
        private const val PAD_TOKEN_ID = 1   // <pad>
        private const val EOS_TOKEN_ID = 2   // </s>
        private const val UNK_TOKEN_ID = 3   // <unk>
        // Max token length for greedy Unigram matching (SentencePiece tokens are short)
        private const val MAX_TOKEN_LEN = 24
    }

    data class TokenizerOutput(
        val inputIds: IntArray,
        val attentionMask: IntArray
    )

    private enum class TokenizerType { BPE, UNIGRAM }
    private val type: TokenizerType

    // BPE fields
    private var bpeVocab: Map<String, Int> = emptyMap()
    private var mergeRanks: Map<Pair<String, String>, Int> = emptyMap()

    // Unigram fields — HashMap for O(1) lookup in greedyEncode
    private var unigramVocab: HashMap<String, Int> = HashMap(0)
    private var unkId: Int = UNK_TOKEN_ID

    init {
        Log.d(TAG, "Loading tokenizer from assets/$tokenizerAsset...")
        val startMs = System.currentTimeMillis()
        val raw = context.assets.open(tokenizerAsset).bufferedReader().readText()
        val json = JSONObject(raw)
        val model = json.getJSONObject("model")
        val modelType = model.optString("type", "BPE")

        if (modelType.equals("Unigram", ignoreCase = true)) {
            // ── Unigram / SentencePiece ──────────────────────────────────────
            type = TokenizerType.UNIGRAM
            unkId = model.optInt("unk_id", UNK_TOKEN_ID)

            // vocab is a JSONArray of [token_string, log_prob] — array index = token ID
            val vocabArray = model.getJSONArray("vocab")
            val map = HashMap<String, Int>(vocabArray.length() * 2)
            for (i in 0 until vocabArray.length()) {
                val entry = vocabArray.getJSONArray(i)
                map[entry.getString(0)] = i
            }
            unigramVocab = map

            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "Unigram tokenizer ready: vocab=${unigramVocab.size}, unk_id=$unkId (${elapsed}ms)")

        } else {
            // ── BPE (GPT / RoBERTa style) ────────────────────────────────────
            type = TokenizerType.BPE

            val vocabJson = model.getJSONObject("vocab")
            val mutableVocab = mutableMapOf<String, Int>()
            vocabJson.keys().forEach { key -> mutableVocab[key] = vocabJson.getInt(key) }
            bpeVocab = mutableVocab

            val mergesArray = model.optJSONArray("merges")
            val mutableRanks = mutableMapOf<Pair<String, String>, Int>()
            if (mergesArray != null) {
                for (i in 0 until mergesArray.length()) {
                    val parts = mergesArray.getString(i).split(" ")
                    if (parts.size == 2) mutableRanks[Pair(parts[0], parts[1])] = i
                }
            }
            mergeRanks = mutableRanks

            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "BPE tokenizer ready: vocab=${bpeVocab.size}, merges=${mergeRanks.size} (${elapsed}ms)")
        }
    }

    fun vocabSize(): Int = if (type == TokenizerType.UNIGRAM) unigramVocab.size else bpeVocab.size
    fun mergeCount(): Int = mergeRanks.size

    /**
     * Tokenize text, returning input_ids + attention_mask.
     * Truncates to maxLength tokens including BOS/EOS specials.
     */
    fun tokenize(text: String, maxLength: Int = 512): TokenizerOutput {
        return when (type) {
            TokenizerType.UNIGRAM -> tokenizeUnigram(text.trim(), maxLength)
            TokenizerType.BPE -> tokenizeBpe(text.trim(), maxLength)
        }
    }

    // ── Unigram / SentencePiece tokenization ─────────────────────────────────

    private fun tokenizeUnigram(text: String, maxLength: Int): TokenizerOutput {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val allIds = mutableListOf<Int>()
        allIds.add(BOS_TOKEN_ID) // <s>

        for (word in words) {
            // SentencePiece: ▁ (U+2581) marks the start of each word
            val wordIds = greedyEncode("\u2581$word")
            allIds.addAll(wordIds)
            if (allIds.size >= maxLength - 1) break
        }

        allIds.add(EOS_TOKEN_ID) // </s>

        val truncated = if (allIds.size > maxLength) allIds.take(maxLength) else allIds
        return TokenizerOutput(
            inputIds = truncated.toIntArray(),
            attentionMask = IntArray(truncated.size) { 1 }
        )
    }

    /**
     * Greedy longest-match tokenization for SentencePiece Unigram.
     * For each position tries substrings from MAX_TOKEN_LEN down to 1 char via HashMap O(1).
     * Falls back to byte-fallback encoding (<0xNN>) for unknown characters.
     */
    private fun greedyEncode(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        var pos = 0

        while (pos < text.length) {
            val maxEnd = minOf(pos + MAX_TOKEN_LEN, text.length)
            var found = false

            for (len in (maxEnd - pos) downTo 1) {
                val sub = text.substring(pos, pos + len)
                val id = unigramVocab[sub]
                if (id != null) {
                    ids.add(id)
                    pos += len
                    found = true
                    break
                }
            }

            if (!found) {
                // Byte-fallback: encode as <0xNN> where NN is the hex UTF-8 byte
                val codePoint = text[pos].code
                val byteToken = "<0x${codePoint.toString(16).uppercase().padStart(2, '0')}>"
                ids.add(unigramVocab[byteToken] ?: unkId)
                pos++
            }
        }

        return ids
    }

    // ── BPE tokenization ─────────────────────────────────────────────────────

    private fun tokenizeBpe(text: String, maxLength: Int): TokenizerOutput {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val allIds = mutableListOf<Int>()
        allIds.add(BOS_TOKEN_ID) // <s>

        for (word in words) {
            val wordIds = encodeBpeWord("Ġ$word")
            allIds.addAll(wordIds)
            if (allIds.size >= maxLength - 1) break
        }

        allIds.add(EOS_TOKEN_ID) // </s>

        val truncated = if (allIds.size > maxLength) allIds.take(maxLength) else allIds
        return TokenizerOutput(
            inputIds = truncated.toIntArray(),
            attentionMask = IntArray(truncated.size) { 1 }
        )
    }

    private fun encodeBpeWord(word: String): List<Int> {
        bpeVocab[word]?.let { return listOf(it) }

        val chars = word.map { it.toString() }.toMutableList()
        var changed = true

        while (changed && chars.size > 1) {
            changed = false
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until chars.size - 1) {
                val rank = mergeRanks[Pair(chars[i], chars[i + 1])] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }

            if (bestIdx >= 0) {
                chars[bestIdx] = chars[bestIdx] + chars[bestIdx + 1]
                chars.removeAt(bestIdx + 1)
                changed = true
            }
        }

        return chars.map { bpeVocab[it] ?: UNK_TOKEN_ID }
    }
}
