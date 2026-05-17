package io.kognis.tactical.core

import io.kognis.tactical.core.llm.MessageResponse
import kotlinx.coroutines.flow.Flow

/**
 * A single retrieved chunk with its relevance score.
 * Used in the RAG audit panel to show all sources consulted.
 */
data class RagChunk(
    val title: String,
    val content: String,
    /** Score semantics depend on mode: ONNX = HNSW distance (lower=better), TEXT = match% (higher=better) */
    val score: Double,
    /** Source page reference from corpus v5 (e.g. "INSARAG vol.2 p.47"). Null for pre-v6 chunks. */
    val sourcePage: String? = null
)

/**
 * Encapsulates the result of a RAG evaluation, including metadata about
 * whether RAG was activated, the HNSW score, and ALL retrieved chunk info.
 * This allows the UI to display a full audit per-message.
 */
data class RagResult(
    /** Whether RAG context was injected into the prompt */
    val ragActivated: Boolean,
    /** Score of the TOP chunk (same semantics as RagChunk.score). -1.0 if no results. */
    val score: Double,
    /** The threshold used to decide activation */
    val threshold: Double,
    /** Title of the top matched chunk (null if no match) — kept for backward compat */
    val chunkTitle: String?,
    /** Content snippet of the top matched chunk (null if no match) — kept for backward compat */
    val chunkContent: String?,
    /** Embedding mode: "ONNX" (vector search) or "TEXT" (keyword search) */
    val embeddingMode: String,
    /** All chunks that passed the relevance threshold (empty if RAG not activated) */
    val allChunks: List<RagChunk>,
    /** The LLM response stream */
    val responseFlow: Flow<MessageResponse>,
    /**
     * True when retrieval was skipped because the query had explicit lat/lon
     * coords (RagOrchestrator.COORD_PATTERN matched). Distinct from
     * ragActivated=false due to "no relevant chunks" or "Desactivado" mode.
     */
    val ragBypassedCoords: Boolean = false,
)
