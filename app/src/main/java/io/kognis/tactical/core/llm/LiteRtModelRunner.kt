package io.kognis.tactical.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

private const val TAG = "LiteRtModelRunner"

/**
 * LiteRT-LM-backed ModelRunner for Gemma 4 E2B (and other LiteRT-LM .litertlm models).
 *
 * Why a separate runner: llama.cpp's hybrid-architecture path (Mamba SSM + Gemma SWA)
 * crashes on 2nd-turn KV reuse for these models. LiteRT-LM is Google's runtime
 * built specifically for Gemma; it handles multi-turn KV cache correctly and
 * offers OpenCL Adreno GPU acceleration on Snapdragon.
 *
 * Model file expected: `<filesDir>/models/gemma-4-E2B-it.litertlm`
 *   - HuggingFace: litert-community/gemma-4-E2B-it-litert-lm
 *   - ~2.59 GB, 32K context, multimodal text+image+audio (we use text only)
 */
class LiteRtModelRunner private constructor(
    private val engine: Engine,
    private val systemPromptDefault: String,
) : ModelRunner {

    companion object {
        /**
         * Load a LiteRT-LM model. Blocks the calling thread — call from Dispatchers.IO.
         *
         * @param modelPath absolute path to the `.litertlm` file
         * @param ctx Android context (for cacheDir)
         * @param maxNumTokens max KV-cache size; 4096 covers ~8 turns of moderate-length chat
         */
        suspend fun load(
            modelPath: String,
            ctx: Context,
            maxNumTokens: Int = 4096,
            preferGpu: Boolean = true,
        ): LiteRtModelRunner {
            Log.i(TAG, "Loading LiteRT-LM model: $modelPath (GPU=$preferGpu, maxTokens=$maxNumTokens)")

            val cfg = EngineConfig(
                modelPath    = modelPath,
                backend      = if (preferGpu) Backend.GPU() else Backend.CPU(),
                visionBackend = null,   // text-only — saves memory
                audioBackend  = null,
                maxNumTokens = maxNumTokens,
                cacheDir     = ctx.cacheDir.absolutePath,
            )

            val engine = Engine(cfg)
            engine.initialize()
            Log.i(TAG, "LiteRT-LM engine initialized")
            return LiteRtModelRunner(engine, systemPromptDefault = "")
        }

        /** Expected filename in filesDir/models/ for the Gemma 4 E2B LiteRT-LM bundle. */
        const val GEMMA4_E2B_LITERTLM_FILENAME = "gemma-4-E2B-it.litertlm"
    }

    override fun createConversation(systemPrompt: String): Conversation {
        val cfg = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = 0.2,
            ),
            systemInstruction = if (systemPrompt.isNotBlank()) {
                com.google.ai.edge.litertlm.Contents.of(systemPrompt)
            } else {
                null
            },
        )
        val convo = engine.createConversation(cfg)
        Log.i(TAG, "Conversation created (system prompt len=${systemPrompt.length})")
        return LiteRtConversationAdapter(convo)
    }

    override fun unload() {
        Log.i(TAG, "Unloading LiteRT-LM engine")
        try { engine.close() } catch (e: Exception) { Log.w(TAG, "engine.close: ${e.message}") }
    }
}

/**
 * Wraps com.google.ai.edge.litertlm.Conversation to our internal Conversation interface,
 * emitting MessageResponse.Chunk per streamed token.
 *
 * Lifecycle: the underlying LiteRT-LM Conversation owns a native KV-cache + decoder state.
 * Callers MUST invoke close() when discarding the adapter (e.g., on new chat / model
 * switch / sliding-window reset), otherwise the native handle leaks until the Engine
 * itself is closed.
 */
internal class LiteRtConversationAdapter(
    private val convo: LiteRtConversation,
) : Conversation {

    @Volatile private var closed: Boolean = false

    override fun generateResponse(message: ChatMessage): Flow<MessageResponse> = callbackFlow {
        Log.d(TAG, "sendMessageAsync: ${message.textContent.take(80)}...")
        // Use String overload of sendMessageAsync (3-arg: input, callback, extraContext).
        // Matches google-ai-edge/gallery pattern; emptyMap is the documented default.
        convo.sendMessageAsync(message.textContent, object : MessageCallback {
            override fun onMessage(message: Message) {
                if (!isActive) return
                // Use Message.toString() — matches google-ai-edge/gallery LlmChatModelHelper.
                // Manually iterating message.contents.contents skips non-Text channels
                // (e.g., "thought" reasoning trace) and is fragile across litertlm versions.
                val text = message.toString()
                if (text.isNotEmpty()) trySend(MessageResponse.Chunk(text))
            }

            override fun onDone() {
                close()
            }

            override fun onError(throwable: Throwable) {
                // Treat CancellationException as graceful stop, others as errors.
                if (throwable is java.util.concurrent.CancellationException) {
                    close()
                } else {
                    Log.e(TAG, "LiteRT-LM error: ${throwable.message}")
                    close(throwable)
                }
            }
        }, emptyMap())

        awaitClose {
            // Flow consumer cancelled — propagate to LiteRT-LM. Does NOT close the
            // conversation; caller is responsible for that via Conversation.close().
            try { convo.cancelProcess() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        if (closed) return
        closed = true
        try { convo.cancelProcess() } catch (_: Exception) {}
        try { convo.close() } catch (e: Exception) {
            Log.w(TAG, "convo.close: ${e.message}")
        }
        Log.d(TAG, "LiteRT-LM Conversation closed")
    }
}
