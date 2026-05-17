package io.kognis.tactical.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import io.kognis.tactical.core.llm.LiteRtModelRunner
import io.kognis.tactical.core.llm.MessageResponse
import io.kognis.tactical.core.llm.ModelRunner
import io.kognis.tactical.ai.EmbeddingEngine
import io.kognis.tactical.data.DocumentChunk

class FieldAssistantService : Service() {

    companion object {
        private const val TAG = "FieldAssistant"
        private const val NOTIFICATION_CHANNEL_ID = "kognis_core_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var modelRunner: ModelRunner? = null
    private var ragOrchestrator: RagOrchestrator? = null
    private var boxStore: io.objectbox.BoxStore? = null
    private var learningOrchestrator: io.kognis.tactical.core.learning.LearningOrchestrator? = null
    // Accumulates the assistant's tokens for the current turn so we can persist the
    // full text into the learning session (and parse the SKILL: tag) on completion.
    private val learningBuffer = StringBuilder()

    private var currentModelName = "Gemma4-E2B"
    private var currentQuant = "litertlm"

    private var initJob: Job? = null
    private var queryJob: Job? = null

    private var callback: IFieldCallback? = null
    private var currentLang: String = "es"

    private fun s(es: String, en: String): String = if (currentLang == "en") en else es

    private val binder = object : IFieldCore.Stub() {
        override fun registerCallback(cb: IFieldCallback?) {
            Log.d(TAG, "registerCallback called, cb=$cb")
            callback = cb
            val lang = SecurePrefs.get(this@FieldAssistantService)
                .getString("app_language", "es") ?: "es"
            currentLang = lang
            ragOrchestrator?.language = lang
            if (modelRunner == null && (initJob == null || initJob?.isActive == false)) {
                initJob = serviceScope.launch { initializeCore() }
            } else if (modelRunner != null && ragOrchestrator != null) {
                val modeLabel = if (ragOrchestrator?.embeddingEngine?.isRealModeActive() == true) "ONNX" else "TEXT"
                safeCallback { it.onStatusChange(s("Core Operativo — RAG [$modeLabel]", "Core Operative — RAG [$modeLabel]")) }
            } else {
                safeCallback { it.onStatusChange(s("Cargando modelo...", "Loading model...")) }
            }
        }

        override fun unregisterCallback(cb: IFieldCallback?) {
            if (callback == cb) {
                callback = null
                Log.d(TAG, "Callback unregistered")
            }
        }

        override fun sendQuery(query: String?, ragMode: String?) {
            if (query == null) return
            val mode = ragMode ?: "Auto"
            Log.d(TAG, "sendQuery: $query (mode: $mode)")
            // Snapshot the previous job and join it inside the new coroutine so the
            // old generation finishes its decoder pass before the new one allocates a
            // Conversation. Without this, two coroutines briefly share the LiteRT
            // native handle and the second one's response truncates to `" }"` —
            // the corruption pattern observed in the 2026-05-17 perf log.
            val previous = queryJob
            queryJob = serviceScope.launch {
                previous?.cancelAndJoin()
                try {
                    val orchestrator = ragOrchestrator
                    if (orchestrator == null) {
                        safeCallback { it.onError(s("Core aún no está listo", "Core not ready yet")) }
                        return@launch
                    }

                    // Training mode: if a learning session is active, override the system
                    // prompt with the Hermes-style 4-section build and persist this turn.
                    val learning = learningOrchestrator
                    val trainingActive = learning != null && learning.isActive
                    var effectiveQuery = query
                    var effectiveMode = mode
                    if (trainingActive) {
                        // Detect language from the user's message itself so the model
                        // always replies in the language of the query, regardless of the
                        // app-wide setting. Spanish markers (accents + common stopwords)
                        // are strong signal; otherwise default to English.
                        val sample = query.take(200).lowercase()
                        val spanishMarker = Regex("[áéíóúñ¿¡]|\\b(qué|cómo|cuál|protocolo|enséñame|háblame|cuándo|estoy|tengo|hola|gracias|ejemplo|también|aprender|entiendo|sí|por favor)\\b")
                        val englishMarker = Regex("\\b(the|is|are|how|what|please|teach|learn|example|protocol|show|give|tell|me|can|you|i)\\b")
                        val spCount = spanishMarker.findAll(sample).count()
                        val enCount = englishMarker.findAll(sample).count()
                        val perTurnLang = when {
                            spCount > enCount -> "es"
                            enCount > spCount -> "en"
                            else -> currentLang   // tie → app pref
                        }
                        Log.d(TAG, "Training language detected: $perTurnLang (sp=$spCount en=$enCount)")
                        // Snapshot the orchestrator into a non-null local — endLearningSession()
                        // from a parallel AIDL call can null `learningOrchestrator` between the
                        // training-active check above and these calls.
                        val learningSnap = learning
                        learningSnap?.setLanguage(perTurnLang)
                        orchestrator.language = perTurnLang
                        orchestrator.customSystemPrompt = learningSnap?.systemPromptForActiveSession(perTurnLang)
                        learningSnap?.appendUserTurn(query)
                        learningBuffer.clear()
                        // Force RAG ON during training — corpus is the authoritative source
                        // for SAR protocols. Auto/NoMap can cause empty retrieval.
                        effectiveMode = "Siempre"
                        // Suffix the user message with a strong SKILL reminder. Gemma 4 E2B
                        // follows end-of-prompt instructions more reliably than start-of-prompt.
                        effectiveQuery = query + "\n\n[REMINDER: end your reply with `SKILL: {...}` " +
                            "on the FINAL line. Pick exactly ONE of: show_example, quiz_user, " +
                            "review_past_misses, mark_mastery. Do NOT wrap the JSON in markdown.]"
                    } else {
                        orchestrator.customSystemPrompt = null
                    }

                    if (ThermalGovernor.isOverheating()) {
                        val temp = ThermalGovernor.getCpuTemperature()
                        safeCallback { it.onError(s(
                            "Temperatura elevada (${"%.0f".format(temp)}°C). Espera unos segundos.",
                            "High temperature (${"%.0f".format(temp)}°C). Wait a moment."
                        )) }
                        return@launch
                    }

                    val ragResult = orchestrator.evaluate(effectiveQuery, effectiveMode, null, null)
                    val ragJson = buildRagMetadataJson(ragResult)
                    safeCallback { it.onRagMetadata(ragJson) }

                    var tokenCount = 0
                    val startTime = System.currentTimeMillis()
                    var firstTokenTime: Long = -1L
                    var lastEmitMs = 0L
                    val pendingDelta = StringBuilder()

                    ragResult.responseFlow.onEach { response ->
                        if (response is MessageResponse.Chunk) {
                            if (firstTokenTime == -1L) firstTokenTime = System.currentTimeMillis()
                            pendingDelta.append(response.text)
                            if (learningOrchestrator?.isActive == true) learningBuffer.append(response.text)
                            tokenCount++
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 50L) {
                                val delta = pendingDelta.toString()
                                pendingDelta.clear()
                                lastEmitMs = now
                                safeCallback { it.onTokenRetrieved(delta) }
                            }
                        }
                    }.catch { e ->
                        Log.e(TAG, "Error in generation flow", e)
                        safeCallback { it.onError(e.message ?: s("Error en el core", "Core error")) }
                    }.onCompletion {
                        if (pendingDelta.isNotEmpty()) {
                            safeCallback { it.onTokenRetrieved(pendingDelta.toString()) }
                        }
                        // Persist the assistant's full turn into the learning session
                        // and dispatch any SKILL: tag it emitted.
                        val active = learningOrchestrator
                        if (active != null && active.isActive && learningBuffer.isNotEmpty()) {
                            active.appendAssistantTurn(learningBuffer.toString(), tokenCount)
                            learningBuffer.clear()
                        }
                        val endTime = System.currentTimeMillis()
                        val diffS = (endTime - startTime) / 1000.0
                        val pureS = if (firstTokenTime != -1L) (endTime - firstTokenTime) / 1000.0 else 0.0
                        val tps = if (pureS > 0) tokenCount / pureS else 0.0
                        val turn = orchestrator.getTurnCount()
                        val maxT = orchestrator.maxTurns
                        val statsJson = "{\"tokens\":$tokenCount,\"time\":${"%.1f".format(diffS).replace(',', '.')},\"tps\":${"%.1f".format(tps).replace(',', '.')},\"turn\":$turn,\"max_turns\":$maxT}"
                        safeCallback { it.onGenerationComplete(statsJson) }
                    }.collect()

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in sendQuery", e)
                    safeCallback { it.onError(e.message ?: "Unknown error") }
                }
            }
        }

        override fun cancelGeneration() {
            queryJob?.cancel()
        }

        override fun isModelReady(): Boolean = modelRunner != null

        override fun switchModel(modelName: String, quantization: String) {
            Log.d(TAG, "switchModel: $modelName $quantization")
            if (currentModelName == modelName && currentQuant == quantization) return
            currentModelName = modelName
            currentQuant = quantization
            queryJob?.cancel()
            val prevJob = initJob
            initJob = serviceScope.launch {
                prevJob?.cancelAndJoin()
                try {
                    safeCallback { it.onStatusChange(s("Liberando memoria del modelo anterior...", "Releasing previous model memory...")) }
                    withTimeoutOrNull(5000L) {
                        withContext(Dispatchers.IO) { modelRunner?.unload() }
                    }
                    modelRunner = null
                    ragOrchestrator = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error unloading during switch", e)
                }
                initializeCore()
            }
        }

        override fun setVerbosity(level: String) {
            ragOrchestrator?.verbosityLevel = level
            ragOrchestrator?.resetConversation()
        }

        override fun setLanguage(lang: String) {
            currentLang = lang
            ragOrchestrator?.language = lang
            ragOrchestrator?.resetConversation()
        }

        override fun enableInternetAndRetry() {
            SecurePrefs.get(this@FieldAssistantService).edit()
                .putBoolean("internet_allowed", true)
                .commit()
            if (modelRunner == null && (initJob == null || initJob?.isActive == false)) {
                initJob = serviceScope.launch { initializeCore() }
            }
        }

        override fun updateKnowledgeBase(uriString: String?) {
            if (uriString == null) return
            val store = boxStore ?: return
            serviceScope.launch(Dispatchers.IO) {
                try {
                    safeCallback { it.onStatusChange(s("Actualizando KB...", "Updating KB...")) }
                    val uri = android.net.Uri.parse(uriString)
                    val result = io.kognis.tactical.data.KnowledgeBaseLoader.ingestFromUri(
                        this@FieldAssistantService, uri, store
                    )
                    val json = org.json.JSONObject().apply {
                        put("added", result.added)
                        put("skipped", result.skipped)
                        put("rejected", result.rejected)
                        put("total_in_db", result.totalInDb)
                        put("sha256", result.sha256)
                    }.toString()
                    safeCallback { it.onKbUpdateComplete(json) }
                    ragOrchestrator?.invalidateChunksCache()
                    ragOrchestrator?.warmupDecryptionCache()
                    val modeLabel = if (ragOrchestrator?.embeddingEngine?.isRealModeActive() == true) "ONNX" else "TEXT"
                    safeCallback { it.onStatusChange(s("Core Operativo — RAG [$modeLabel]", "Core Operative — RAG [$modeLabel]")) }
                } catch (e: Exception) {
                    Log.e(TAG, "updateKnowledgeBase failed", e)
                    safeCallback { it.onError(s("Error actualizando KB: ${e.message}", "KB update failed: ${e.message}")) }
                }
            }
        }

        override fun restoreKnowledgeBase() {
            val store = boxStore ?: return
            serviceScope.launch(Dispatchers.IO) {
                try {
                    safeCallback { it.onStatusChange(s("Restaurando KB original...", "Restoring original KB...")) }
                    io.kognis.tactical.data.KnowledgeBaseLoader.forceReingest(
                        this@FieldAssistantService, store
                    )
                    val total = store.boxFor(io.kognis.tactical.data.DocumentChunk::class.java).count()
                    val json = org.json.JSONObject().apply {
                        put("added", total)
                        put("skipped", 0)
                        put("rejected", 0)
                        put("total_in_db", total)
                        put("sha256", "asset")
                    }.toString()
                    safeCallback { it.onKbUpdateComplete(json) }
                    ragOrchestrator?.invalidateChunksCache()
                    ragOrchestrator?.warmupDecryptionCache()
                    val modeLabel = if (ragOrchestrator?.embeddingEngine?.isRealModeActive() == true) "ONNX" else "TEXT"
                    safeCallback { it.onStatusChange(s("Core Operativo — RAG [$modeLabel]", "Core Operative — RAG [$modeLabel]")) }
                } catch (e: Exception) {
                    Log.e(TAG, "restoreKnowledgeBase failed", e)
                    safeCallback { it.onError(s("Error restaurando KB: ${e.message}", "KB restore failed: ${e.message}")) }
                }
            }
        }

        // ── Adaptive learning subsystem ─────────────────────────────────
        override fun startLearningSession(curriculumUriString: String?): Long {
            val store = boxStore ?: return 0L
            val orch = learningOrchestrator ?: io.kognis.tactical.core.learning.LearningOrchestrator(
                this@FieldAssistantService, store,
            ).also { learningOrchestrator = it }
            val sid = orch.startSession(curriculumUriString)
            // Force a conversation reset so the new system prompt takes hold.
            ragOrchestrator?.resetConversation()
            safeCallback {
                it.onStatusChange(s(
                    "Sesión de entrenamiento iniciada (sid=$sid)",
                    "Training session started (sid=$sid)",
                ))
            }
            return sid
        }

        override fun getLearnerModelJson(): String =
            learningOrchestrator?.learnerModelJson() ?: "{}"

        override fun endLearningSession() {
            learningOrchestrator?.endSession()
            ragOrchestrator?.customSystemPrompt = null
            ragOrchestrator?.resetConversation()
            safeCallback {
                it.onStatusChange(s(
                    "Sesión de entrenamiento cerrada",
                    "Training session closed",
                ))
            }
        }

        override fun recordQuizOutcome(topic: String?, correct: Boolean) {
            if (topic.isNullOrBlank()) return
            learningOrchestrator?.recordQuizOutcome(topic, correct)
        }

        override fun clearConversation() {
            ragOrchestrator?.resetConversation()
        }
    }

    private fun safeCallback(action: (IFieldCallback) -> Unit) {
        val cb = callback ?: return
        try { action(cb) } catch (e: Exception) {
            Log.e(TAG, "Callback failed: ${e.message}")
        }
    }

    private fun buildRagMetadataJson(result: RagResult): String {
        val chunks = org.json.JSONArray()
        result.allChunks.forEach { chunk ->
            chunks.put(org.json.JSONObject().apply {
                put("title", chunk.title)
                put("content", chunk.content)
                put("score", "%.4f".format(chunk.score).replace(',', '.').toDouble())
                chunk.sourcePage?.let { put("source_page", it) }
            })
        }
        return org.json.JSONObject().apply {
            put("ragActivated", result.ragActivated)
            put("score", "%.4f".format(result.score).replace(',', '.').toDouble())
            put("threshold", result.threshold)
            put("chunkTitle", result.chunkTitle ?: "")
            put("chunkContent", result.chunkContent ?: "")
            put("embeddingMode", result.embeddingMode)
            put("chunks", chunks)
            put("ragBypassedCoords", result.ragBypassedCoords)
        }.toString()
    }

    private suspend fun initializeCore() {
        currentLang = SecurePrefs.get(this).getString("app_language", "es") ?: "es"
        val initStartMs = System.currentTimeMillis()
        Log.i(TAG, "=== initializeCore START === lang=$currentLang")
        try {
            safeCallback { it.onStatusChange(s("Inicializando Core...", "Initializing Core...")) }

            val modelsDir = java.io.File(filesDir, "models").also { it.mkdirs() }
            val filename = LiteRtModelRunner.GEMMA4_E2B_LITERTLM_FILENAME
            val modelFile = java.io.File(modelsDir, filename)

            if (!modelFile.exists()) {
                val msg = s(
                    "Modelo no encontrado: ${modelFile.absolutePath}\n" +
                    "Sideload: adb push $filename /sdcard/ && adb shell mv /sdcard/$filename ${modelFile.absolutePath}",
                    "Model not found: ${modelFile.absolutePath}\n" +
                    "Sideload: adb push $filename /sdcard/ && adb shell mv /sdcard/$filename ${modelFile.absolutePath}"
                )
                safeCallback { it.onStatusChange(s("Error: modelo no encontrado", "Error: model file not found")) }
                safeCallback { it.onError(msg) }
                return
            }

            safeCallback { it.onStatusChange(s("Cargando Gemma 4 E2B...", "Loading Gemma 4 E2B...")) }

            val loadStart = System.currentTimeMillis()
            val loaded: ModelRunner? = try {
                withTimeoutOrNull(240_000L) {
                    withContext(Dispatchers.IO) {
                        LiteRtModelRunner.load(
                            modelPath    = modelFile.absolutePath,
                            ctx          = this@FieldAssistantService,
                            maxNumTokens = 4096,
                            preferGpu    = true,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRtModelRunner.load failed", e)
                safeCallback { it.onStatusChange(s("Error: Modelo corrupto o no compatible.", "Error: Model corrupted or incompatible.")) }
                safeCallback { it.onError("${e.javaClass.simpleName}: ${e.message}") }
                return
            }

            if (loaded == null) {
                val elapsedSec = (System.currentTimeMillis() - loadStart) / 1000
                safeCallback { it.onStatusChange(s("Timeout cargando modelo (${elapsedSec}s)", "Timeout loading model (${elapsedSec}s)")) }
                safeCallback { it.onError(s("Timeout al cargar modelo. Prueba reiniciar.", "Model load timeout. Try restarting.")) }
                return
            }
            modelRunner = loaded
            Log.i(TAG, "Gemma 4 E2B loaded!")

            safeCallback { it.onStatusChange(s("Inicializando...", "Initializing...")) }
            val onnxDeferred = serviceScope.async(Dispatchers.IO) {
                EmbeddingEngine(this@FieldAssistantService, 4)
            }

            ChunkEncryptor.init(this@FieldAssistantService)

            val store = boxStore ?: run {
                val newStore = io.kognis.tactical.data.MyObjectBox.builder()
                    .androidContext(this@FieldAssistantService)
                    .build()
                boxStore = newStore
                newStore
            }

            // Eager-init the learning orchestrator so its `init` block runs and
            // restores any unclosed session BEFORE the user touches gear → Start.
            // If they had a session in progress when the app died, it's back now.
            if (learningOrchestrator == null) {
                learningOrchestrator = io.kognis.tactical.core.learning.LearningOrchestrator(
                    this@FieldAssistantService, store,
                )
                learningOrchestrator?.let {
                    if (it.isActive) {
                        Log.i(TAG, "Restored training session ${it.activeSessionId} on service init")
                    }
                }
            }

            safeCallback { it.onStatusChange(s("Cargando base de conocimiento...", "Loading knowledge base...")) }
            io.kognis.tactical.data.KnowledgeBaseLoader.ingestIfNeeded(
                this@FieldAssistantService,
                store
            ) { current, estimatedTotal ->
                val totalLabel = if (estimatedTotal > 0) estimatedTotal.toString() else "?"
                safeCallback { it.onLoadingProgress("KB:$current/$totalLabel") }
            }

            safeCallback { it.onStatusChange(s("Cargando motor ONNX...", "Loading ONNX engine...")) }
            val engine = onnxDeferred.await()

            if (engine.isRealModeActive()) {
                val chunkBox = store.boxFor(DocumentChunk::class.java)
                val hasMockChunks = chunkBox.count() <= 5L && run {
                    val sample = chunkBox.all.firstOrNull()
                    sample?.vector?.let { v -> v.all { it == 0.0f } || v.all { it == 0.01f } } == true
                }
                if (hasMockChunks) {
                    safeCallback { it.onStatusChange(s("Re-indexando KB con vectores reales...", "Re-indexing KB with real vectors...")) }
                    io.kognis.tactical.data.KnowledgeBaseLoader.forceReingest(this@FieldAssistantService, store)
                    ragOrchestrator?.invalidateChunksCache()
                }
            }

            val box = store.boxFor(DocumentChunk::class.java)
            ragOrchestrator = RagOrchestrator(engine, box, modelRunner!!).also { orch ->
                orch.language = SecurePrefs.get(this).getString("app_language", "es") ?: "es"
                orch.modelSize = "E2B"
                orch.maxTurns = 8
                // mapMode is OFF by default. The model emits LOCATION_JSON only when the
                // user explicitly asks to mark a location and QueryPreprocessor could not
                // pre-place the marker (handled per-turn via ragMode="Mapa"). Knowledge
                // queries and casual greetings no longer drop spurious markers.
                orch.mapMode = false
                orch.onSlidingWindowReset = { turns, max ->
                    safeCallback { it.onStatusChange("CONTEXT_RESET:$turns/$max") }
                }
            }

            safeCallback { it.onStatusChange(s("Indexando RAG...", "Indexing RAG...")) }
            ragOrchestrator?.warmupDecryptionCache()

            val coldStartMs = System.currentTimeMillis() - initStartMs
            Log.i(TAG, "=== initializeCore COMPLETE in ${coldStartMs}ms ===")

            PerformanceLogger.record(
                PerformanceLogger.QueryEntry(
                    type = "SYSTEM",
                    queryPreview = "Cold Start",
                    model = currentModelName,
                    durationMs = coldStartMs,
                    tokensPerSec = 0.0,
                    tokens = 0,
                    ragActivated = false,
                    ragScore = 0.0,
                    tempCelsius = ThermalGovernor.getCpuTemperature(),
                    responseText = "KB+ONNX Loaded",
                    chunkTitle = "",
                    embeddingMode = if (engine.isRealModeActive()) "ONNX" else "TEXT"
                )
            )

            val modeLabel = if (engine.isRealModeActive()) "ONNX" else "TEXT"
            safeCallback { it.onStatusChange(s("Core Operativo — RAG [$modeLabel]", "Core Operative — RAG [$modeLabel]")) }

        } catch (e: Exception) {
            Log.e(TAG, "=== initializeCore FAILED ===", e)
            modelRunner = null
            ragOrchestrator = null
            safeCallback { it.onLoadingProgress("FAILED") }
            safeCallback { it.onError("${s("Fallo CORE:", "CORE failure:")} ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FieldAssistantService onCreate")
        startForegroundWithNotification()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Kognis Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Field Assistant AI active"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Kognis Lite SAR — Core Active")
            .setContentText("Gemma 4 E2B · RAG · Zero-Signal")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_MODERATE && level < TRIM_MEMORY_RUNNING_CRITICAL) {
            ragOrchestrator?.resetConversation()
        }
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "TRIM_MEMORY_RUNNING_CRITICAL — unloading model")
            serviceScope.launch {
                try { modelRunner?.unload() } catch (e: Exception) { Log.e(TAG, "unload failed", e) }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        initJob?.cancel()
        queryJob?.cancel()
        val engineRef = ragOrchestrator?.embeddingEngine
        CoroutineScope(Dispatchers.IO).launch {
            try { modelRunner?.unload() } catch (e: Exception) { Log.e(TAG, "unload failed", e) }
            try { engineRef?.close() } catch (e: Exception) { Log.e(TAG, "engine close failed", e) }
            try { boxStore?.close() } catch (e: Exception) { Log.e(TAG, "boxStore close failed", e) }
        }
        serviceScope.cancel()
    }
}
