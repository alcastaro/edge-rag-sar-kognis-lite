package io.kognis.tactical

import io.kognis.tactical.core.IFieldCore
import io.kognis.tactical.core.IFieldCallback
import io.kognis.tactical.core.SecurePrefs
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.kognis.tactical.core.FieldAssistantService
import io.kognis.tactical.models.ChatMessageDisplayItem
import io.kognis.tactical.views.ChatHistory
import io.kognis.tactical.ui.theme.KognisLiteTheme
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.zIndex
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.collections.plus
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import java.util.Locale
import android.content.res.Configuration

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items

class MainActivity : ComponentActivity() {

    private val kbPickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // Persist URI permission so the service (different process) can read it
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fieldCore?.updateKnowledgeBase(uri.toString())
    }

    // Location permission — required at runtime for GPS puck / tracking / distance.
    // Manifest-declared permissions are not auto-granted on API 23+; must be requested.
    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            android.widget.Toast.makeText(this, "Location enabled — waiting for fix…", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, "Location denied — GPS features disabled", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Public entry — request location permissions if not yet granted. */
    internal fun ensureLocationPermission() {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationPermissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    // Pending action when RECORD_AUDIO permission is requested mid-flow. Invoked
    // on permission result so the user only taps the mic button once.
    @Volatile private var pendingMicAction: (() -> Unit)? = null

    private val micPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else android.widget.Toast.makeText(this, "Mic permission denied", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Vision agent: camera capture + gallery picker for medication-label OCR.
    @Volatile private var pendingCameraCaptureUri: android.net.Uri? = null
    @Volatile private var pendingCameraAction: (() -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingCameraAction
        pendingCameraAction = null
        if (granted) action?.invoke()
        else android.widget.Toast.makeText(this, "Camera permission denied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraCaptureUri
        pendingCameraCaptureUri = null
        if (success && uri != null) runVisionAgent(uri)
        else android.widget.Toast.makeText(this, "Capture cancelled", android.widget.Toast.LENGTH_SHORT).show()
    }

    private val galleryPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) runVisionAgent(uri)
    }

    /** Vision agent entry point: run OCR on the given image URI, then route through RAG. */
    private fun runVisionAgent(uri: android.net.Uri) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@MainActivity, "Vision agent: reading label…", android.widget.Toast.LENGTH_SHORT).show()
            val ocr = runCatching {
                io.kognis.tactical.core.agent.VisionAgent.recognizeFromUri(this@MainActivity, uri)
            }.getOrElse {
                android.widget.Toast.makeText(this@MainActivity, "OCR failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (ocr.isEmpty) {
                android.widget.Toast.makeText(this@MainActivity, "No text detected on label", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val query = io.kognis.tactical.core.agent.VisionAgent.buildMedicationQuery(ocr, en = true)
            android.widget.Toast.makeText(this@MainActivity, "Extracted ${ocr.rawText.length} chars · querying RAG", android.widget.Toast.LENGTH_SHORT).show()
            this@MainActivity.isInGeneration.value = true
            // ragMode "NoMap" suppresses the LOCATION_JSON appendix so vision queries
            // never produce hallucinated map markers (perf log 2026-05-17 23:20:45 bug).
            sendText(query, "NoMap")
        }
    }

    /** Build a temp file in external cache + content:// URI for camera capture. */
    private fun newCameraCaptureUri(): android.net.Uri {
        val dir = java.io.File(externalCacheDir, "vision").also { it.mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "label_$ts.jpg")
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
    }

    /** Public entry — start camera capture flow. Handles permission. */
    internal fun launchVisionCamera() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val action: () -> Unit = {
            val uri = newCameraCaptureUri()
            pendingCameraCaptureUri = uri
            cameraCaptureLauncher.launch(uri)
        }
        if (granted) action() else { pendingCameraAction = action; cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
    }

    /** Public entry — start gallery picker. No permission needed (system picker). */
    internal fun launchVisionGallery() {
        galleryPickerLauncher.launch("image/*")
    }

    private val markerImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val result = io.kognis.tactical.core.map.JsonMarkerExporter.importFromUri(this, uri)
        android.widget.Toast.makeText(
            this,
            "${result.added} marcadores importados" + if (result.skipped > 0) " (${result.skipped} omitidos)" else "",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = SecurePrefs.get(newBase)
        val lang = prefs.getString("app_language", "es") ?: "es"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // The generation job instance.
    private var job: Job? = null

    @Volatile private var activeModelType: String = "GEMMA"

    @androidx.annotation.VisibleForTesting
    internal var fieldCore: IFieldCore? = null

    // Eval runner: intercept tokens when running automated eval
    @Volatile internal var evalDeferred: CompletableDeferred<String>? = null
    internal val evalBuffer = StringBuilder()

    @androidx.annotation.VisibleForTesting
    internal val isModelReady: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    @androidx.annotation.VisibleForTesting
    internal val coreStatus: MutableLiveData<String> by lazy {
        MutableLiveData<String>("Inicializando...")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            android.util.Log.d("MainActivity", "Service connected")
            fieldCore = IFieldCore.Stub.asInterface(service)
            fieldCore?.registerCallback(fieldCallback)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            android.util.Log.w("MainActivity", "Service disconnected — auto-rebinding")
            fieldCore = null
            isModelReady.postValue(false)
            isInGeneration.postValue(false)  // unstick UI (X button etc.)
            coreStatus.postValue("Reconectando...")
            snackbarEvent.postValue(
                "Servicio recuperándose tras error — la conversación se reiniciará"
            )
            // Auto-rebind after 1s. Service auto-restarts via START_STICKY contract,
            // so bindService will reconnect to the new process. The Activity's chat
            // UI keeps its current messages; the service-side conversation history
            // resets naturally because the process is new.
            android.os.Handler(mainLooper).postDelayed({
                val intent = android.content.Intent(this@MainActivity, FieldAssistantService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                bindService(intent, this, Context.BIND_AUTO_CREATE)
            }, 1_000L)
        }
    }

    // Holds RAG metadata between onRagMetadata() and the first token
    private var pendingRagInfo: String? = null

    // TTS agent — output side of the hands-free agentic loop.
    @Volatile internal var ttsEnabled: Boolean = false
    internal val ttsBuffer = StringBuilder()
    internal var ttsAgent: io.kognis.tactical.core.agent.TtsAgent? = null

    // Adaptive learning — accumulates assistant tokens to parse SKILL: tag client-side.
    // Service also parses + persists, but UI needs the parsed skill to render Quiz/Case cards.
    internal val skillBuffer = StringBuilder()
    @Volatile internal var skillCallback: ((io.kognis.tactical.core.learning.LearningSkill) -> Unit)? = null
    /** Set by the Composable so sendText() can dismiss prior quiz/case cards on a new user turn. */
    @Volatile internal var dismissTrainingCards: (() -> Unit)? = null

    private val fieldCallback = object : IFieldCallback.Stub() {
        override fun onTokenRetrieved(token: String) {
            android.util.Log.d("MainActivity", "onTokenRetrieved: ${token.length} chars")
            if (evalDeferred != null) evalBuffer.append(token)
            if (ttsEnabled) ttsBuffer.append(token)
            if (skillCallback != null) skillBuffer.append(token)
            runOnUiThread {
                appendToLastAssistantMessage(token)
            }
        }

        override fun onGenerationComplete(fullHistoryJson: String) {
            // Complete eval deferred before touching UI state
            evalDeferred?.let { def ->
                def.complete(evalBuffer.toString())
                evalBuffer.clear()
            }
            // Speak final response if TTS is on
            if (ttsEnabled && ttsBuffer.isNotEmpty()) {
                val text = ttsBuffer.toString()
                ttsBuffer.clear()
                runOnUiThread { ttsAgent?.speak(text) }
            } else {
                ttsBuffer.clear()
            }
            // Parse SKILL: tag from the assistant's final text — used to render QuizCard / CaseStudyCard
            if (skillCallback != null && skillBuffer.isNotEmpty()) {
                val text = skillBuffer.toString()
                skillBuffer.clear()
                val skill = io.kognis.tactical.core.learning.SkillCallExtractor.extract(text)
                // Strip raw SKILL: {...} JSON from the displayed chat message; the rendered
                // QuizCard / CaseStudyCard is the user-facing artifact.
                val stripped = io.kognis.tactical.core.learning.SkillCallExtractor.strip(text)
                if (stripped != text) {
                    runOnUiThread {
                        val hist = (chatMessageHistory.value ?: listOf()).toMutableList()
                        val last = hist.lastOrNull()
                        if (last?.role == "ASSISTANT") {
                            hist[hist.lastIndex] = last.copy(text = stripped)
                            chatMessageHistory.value = hist
                        }
                    }
                }
                if (skill != null) runOnUiThread { skillCallback?.invoke(skill) }
            } else {
                skillBuffer.clear()
            }
            runOnUiThread {
                isInGeneration.value = false
                pendingRagInfo = null
                if (fullHistoryJson.startsWith("{") && fullHistoryJson.contains("tokens")) {
                    updateLastAssistantMessageStats(fullHistoryJson)
                    Regex("\"turn\":(\\d+),\"max_turns\":(\\d+)").find(fullHistoryJson)?.let { m ->
                        val t = m.groupValues[1].toIntOrNull() ?: return@let
                        val max = m.groupValues[2].toIntOrNull() ?: return@let
                        conversationTurn.value = Pair(t, max)
                    }
                }
                chatMessageHistory.value?.let { saveChatHistory(it) }
            }
        }

        override fun onError(error: String) {
            android.util.Log.e("MainActivity", "Core error: $error")
            runOnUiThread {
                isInGeneration.value = false
                pendingRagInfo = null
                coreStatus.value = "Error: $error"
                // Hard init failure: bring UI back to loading state so user is not stuck
                // with a "ready" indicator while ragOrchestrator is null in :field_core.
                if (error.startsWith("Fallo CORE:") || error.startsWith("CORE failure:") ||
                    error.startsWith("Timeout")) {
                    isModelReady.value = false
                    kbProgress.value = null
                } else if (isModelReady.value == true) {
                    updateLastAssistantMessage("⚠️ $error", null, null)
                }
            }
        }

        override fun onStatusChange(status: String) {
            android.util.Log.d("MainActivity", "Core status: $status")
            runOnUiThread {
                if (status.startsWith("CONTEXT_RESET:")) {
                    val parts = status.removePrefix("CONTEXT_RESET:").split("/")
                    val max = parts.getOrNull(1)?.toIntOrNull() ?: 8
                    conversationTurn.value = Pair(0, max)
                    snackbarEvent.value = "Conversación reiniciada (ventana $max turnos)"
                    return@runOnUiThread
                }
                coreStatus.value = status
                if (status.startsWith("Core Operativo") || status.startsWith("Core Operative")) {
                    isModelReady.value = true
                    kbProgress.value = null  // hide progress bar once core is ready
                    embeddingMode.value = when {
                        status.contains("[ONNX]")  -> "ONNX Real (E5-small)"
                        status.contains("[TEXTO]") -> "Keyword / BM25"
                        else                       -> "Desconocido"
                    }
                    // Record cold start only once per launch (feedback 2.3)
                    if (coreBindStartMs > 0L && io.kognis.tactical.core.PerformanceLogger.coldStartMs < 0L) {
                        io.kognis.tactical.core.PerformanceLogger.coldStartMs =
                            System.currentTimeMillis() - coreBindStartMs
                    }
                }
            }
        }

        override fun onRagMetadata(ragInfoJson: String) {
            android.util.Log.d("MainActivity", "RAG metadata received: $ragInfoJson")
            pendingRagInfo = ragInfoJson
        }

        override fun onKbUpdateComplete(resultJson: String) {
            android.util.Log.d("MainActivity", "KB update complete: $resultJson")
            runOnUiThread { kbUpdateResult.value = resultJson }
        }

        override fun onLoadingProgress(stage: String) {
            // Stage formats: "TAG:current/total" (e.g. "KB:1234/4000") or "TAG" (e.g. "FAILED")
            runOnUiThread {
                when {
                    stage == "FAILED" -> {
                        kbProgress.value = null
                        isModelReady.value = false
                    }
                    stage.startsWith("KB:") -> {
                        val payload = stage.removePrefix("KB:")
                        val parts = payload.split("/")
                        val current = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val total = parts.getOrNull(1)?.toIntOrNull() ?: -1
                        kbProgress.value = current to total
                        // KB only loads after model is ready — unlock input early (feedback 2.1)
                        if (isLlmLoaded.value != true) isLlmLoaded.value = true
                    }
                }
            }
        }
    }

    private val isToolEnabled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    private val chatMessageHistory: MutableLiveData<List<ChatMessageDisplayItem>> by lazy {
        MutableLiveData<List<ChatMessageDisplayItem>>()
    }

    private var conversationHistoryJSONString: String? = null

    private val isInGeneration: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    /** Modo activo del motor de embeddings: "ONNX Real (E5-small)" o "Keyword/BM25" */
    private val embeddingMode: MutableLiveData<String> by lazy {
        MutableLiveData<String>("Iniciando...")
    }

    /** Fase 9: result JSON from onKbUpdateComplete, shown in result dialog. */
    private val kbUpdateResult: MutableLiveData<String?> by lazy {
        MutableLiveData<String?>(null)
    }

    /** KB ingest progress: Pair(current, estimatedTotal). estimatedTotal -1 = unknown. */
    private val kbProgress: MutableLiveData<Pair<Int, Int>?> by lazy {
        MutableLiveData<Pair<Int, Int>?>(null)
    }
    // (currentTurn, maxTurns) for the KV-cache sliding window — updated after each generation
    private val conversationTurn: MutableLiveData<Pair<Int, Int>> by lazy {
        MutableLiveData<Pair<Int, Int>>(Pair(0, 8))
    }
    // Timestamp of last bindService() call — used to compute cold start duration
    private var coreBindStartMs: Long = 0L
    // LLM is loaded once the first KB progress event arrives (KB loads only after model is ready)
    private val isLlmLoaded: MutableLiveData<Boolean> by lazy { MutableLiveData(false) }
    // One-shot event messages (conversation reset, reconnect) — consumed by LaunchedEffect in UI
    private val snackbarEvent: MutableLiveData<String?> by lazy {
        MutableLiveData<String?>(null)
    }

    /** Lista reactiva de sesiones para que el Drawer se actualice al crear/borrar sesiones */
    private val sessionsList: MutableLiveData<List<String>> by lazy {
        MutableLiveData<List<String>>(getSavedSessions())
    }


    private val json = Json { ignoreUnknownKeys = true }
    
    private var currentChatId: String = "session_${System.currentTimeMillis()}"

    private fun getSavedSessions(): List<String> {
        val prefs = SecurePrefs.get(this)
        return prefs.all.keys.filter { it.startsWith("session_") || it == "chat_history" }
            .sortedDescending()
    }

    private fun loadChatSession(sessionId: String) {
        currentChatId = sessionId
        val prefs = SecurePrefs.get(this)
        val historyJson = prefs.getString(sessionId, null)
        if (historyJson != null) {
            try {
                chatMessageHistory.value = json.decodeFromString(historyJson)
            } catch (e: Exception) {
                chatMessageHistory.value = emptyList()
            }
        } else {
            chatMessageHistory.value = emptyList()
        }
    }

    private fun updateLastAssistantMessageStats(statsJson: String) {
        val currentHistory = chatMessageHistory.value?.toMutableList() ?: mutableListOf()
        val lastAssistantIndex = currentHistory.indexOfLast { it.role == "ASSISTANT" }
        if (lastAssistantIndex != -1) {
            val lastMsg = currentHistory[lastAssistantIndex]
            val enrichedStats = try {
                val obj = org.json.JSONObject(statsJson)
                val totalMs = if (querySentMs > 0) System.currentTimeMillis() - querySentMs else 0L
                val genMs = (obj.optDouble("time", 0.0) * 1000).toLong()
                val overheadMs = (totalMs - genMs).coerceAtLeast(0L)
                if (overheadMs > 0) obj.put("overhead_ms", overheadMs)
                obj.toString()
            } catch (_: Exception) { statsJson }
            currentHistory[lastAssistantIndex] = lastMsg.copy(generationStats = enrichedStats)
            chatMessageHistory.postValue(currentHistory)
            saveChatHistory(currentHistory)
            logQueryToPerformance(lastMsg, enrichedStats, currentHistory, lastAssistantIndex)
        }
    }

    private fun logQueryToPerformance(
        lastMsg: ChatMessageDisplayItem,
        statsJson: String,
        history: List<ChatMessageDisplayItem>,
        assistantIndex: Int
    ) {
        try {
            val statsObj = org.json.JSONObject(statsJson)
            val tokens = statsObj.optInt("tokens", 0)
            val tps = statsObj.optDouble("tps", 0.0)
            val timeS = statsObj.optDouble("time", 0.0)
            val prevUser = history.take(assistantIndex).lastOrNull { it.role == "USER" }?.text ?: ""
            val ragObj = lastMsg.ragInfo?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val ragActivated = ragObj?.optBoolean("ragActivated", false) ?: false
            val ragScore = ragObj?.optDouble("score", -1.0) ?: -1.0
            val chunkTitle = ragObj?.optString("chunkTitle", "") ?: ""
            val embeddingMode = ragObj?.optString("embeddingMode", "TEXT") ?: "TEXT"
            // Capture all retrieved chunks for offline RAG validation (feedback 2.3)
            val chunksJson = ragObj?.optJSONArray("chunks")?.toString() ?: ""
            val ragBypassedCoords = ragObj?.optBoolean("ragBypassedCoords", false) ?: false
            val model = when (activeModelType) {
                "GEMMA" -> "Gemma4-E2B"
                else -> activeModelType
            }
            io.kognis.tactical.core.PerformanceLogger.record(
                io.kognis.tactical.core.PerformanceLogger.QueryEntry(
                    queryPreview = if (prevUser.length > 40) prevUser.take(37) + "…" else prevUser,
                    query = prevUser,
                    model = model,
                    durationMs = (timeS * 1000).toLong(),
                    tokensPerSec = tps,
                    tokens = tokens,
                    ragActivated = ragActivated,
                    ragScore = ragScore,
                    tempCelsius = io.kognis.tactical.core.ThermalGovernor.getCpuTemperature(),
                    responseText = lastMsg.text.take(2000),
                    chunkTitle = chunkTitle,
                    chunksJson = chunksJson,
                    embeddingMode = embeddingMode,
                    ragBypassedCoords = ragBypassedCoords,
                )
            )
        } catch (_: Exception) { }
    }

    private fun updateMessageFeedback(messageIndex: Int, rating: String) {
        val current = chatMessageHistory.value?.toMutableList() ?: return
        val msg = current.getOrNull(messageIndex) ?: return
        current[messageIndex] = msg.copy(feedbackRating = rating)
        chatMessageHistory.value = current
        saveChatHistory(current)
    }

    private fun saveFeedbackForMessage(messageIndex: Int, rating: String) {
        val history = chatMessageHistory.value ?: return
        val msg = history.getOrNull(messageIndex) ?: return
        val prevUserText = history.take(messageIndex).lastOrNull { it.role == "USER" }?.text ?: ""
        val queryHash = "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(prevUserText.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val ragScore = try {
            if (msg.ragInfo != null) org.json.JSONObject(msg.ragInfo).optDouble("score", -1.0) else -1.0
        } catch (_: Exception) { -1.0 }
        val inferenceMs = try {
            if (msg.generationStats != null)
                (org.json.JSONObject(msg.generationStats).optDouble("time", 0.0) * 1000).toLong()
            else 0L
        } catch (_: Exception) { 0L }
        val modelLabel = when (activeModelType) {
            "GEMMA" -> "gemma4-e2b"
            else -> "gemma4-e2b"
        }
        val entryStr = org.json.JSONObject().apply {
            put("ts", java.time.Instant.now().toString())
            put("model", modelLabel)
            put("rating", rating)
            put("query_hash", queryHash)
            if (ragScore >= 0) put("rag_score", ragScore)
            if (inferenceMs > 0) put("inference_ms", inferenceMs)
        }.toString()
        val dateStr = java.time.LocalDate.now().toString().replace("-", "")
        // IO on background — main thread file writes can be blocked by StrictMode on API 36
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = getExternalFilesDir("feedback") ?: filesDir.resolve("feedback").also { it.mkdirs() }
            dir.mkdirs()
            val file = java.io.File(dir, "feedback_$dateStr.json")
            try {
                java.io.FileOutputStream(file, true).bufferedWriter().use { w ->
                    w.write(entryStr)
                    w.newLine()
                }
                android.util.Log.d("MainActivity", "Feedback saved: $file")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Feedback save failed", e)
            }
        }
    }

    fun exportPerformanceJson() {
        val entries = io.kognis.tactical.core.PerformanceLogger.entries()
        if (entries.isEmpty()) return
        val arr = org.json.JSONArray()
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        entries.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("ts", fmt.format(java.util.Date(e.tsMs)))
                put("query", e.query.ifBlank { e.queryPreview })
                put("model", e.model)
                put("duration_ms", e.durationMs)
                put("tps", e.tokensPerSec)
                put("tokens", e.tokens)
                put("rag_activated", e.ragActivated)
                if (e.ragActivated) put("rag_score", e.ragScore)
                e.tempCelsius?.let { put("temp_c", it) }
                put("embedding_mode", e.embeddingMode)
                if (e.chunksJson.isNotBlank()) put("chunks", org.json.JSONArray(e.chunksJson))
                else if (e.chunkTitle.isNotBlank()) put("chunk_title", e.chunkTitle)
                if (e.responseText.isNotBlank()) put("response", e.responseText)
            })
        }
        val report = org.json.JSONObject().apply {
            put("exported_at", java.time.Instant.now().toString())
            put("session_entries", entries.size)
            put("avg_tps", io.kognis.tactical.core.PerformanceLogger.avgTps())
            put("avg_duration_ms", io.kognis.tactical.core.PerformanceLogger.avgDurationMs())
            put("rag_hit_rate", io.kognis.tactical.core.PerformanceLogger.ragHitRate())
            io.kognis.tactical.core.PerformanceLogger.maxTemp()?.let { put("max_temp_c", it) }
            val coldMs = io.kognis.tactical.core.PerformanceLogger.coldStartMs
            if (coldMs > 0) put("cold_start_ms", coldMs)
            put("queries", arr)
        }
        val dir = getExternalFilesDir("performance") ?: return
        dir.mkdirs()
        val ts = java.time.LocalDateTime.now().toString().replace(":", "-").take(19)
        val file = java.io.File(dir, "kognis_perf_$ts.json")
        try {
            file.writeText(report.toString(2))
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val intent = androidx.core.app.ShareCompat.IntentBuilder(this)
                .setType("application/json")
                .addStream(uri)
                .setChooserTitle("Export Performance Log")
                .createChooserIntent()
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Export failed", e)
            android.widget.Toast.makeText(this, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSessions() {
        sessionsList.postValue(getSavedSessions())
    }

    /** Devuelve el título de una sesión: primero busca el guardado explícito,
     *  luego lo auto-genera a partir del primer mensaje del usuario. */
    private fun titleForSession(sessionId: String): String {
        if (sessionId == "chat_history") return "Sesión Migrada"
        val prefs = SecurePrefs.get(this)
        prefs.getString("title_$sessionId", null)?.let { return it }
        val historyJson = prefs.getString(sessionId, null) ?: return "Nueva Sesión"
        return try {
            val history = json.decodeFromString<List<ChatMessageDisplayItem>>(historyJson)
            val firstMsg = history.firstOrNull { it.role == "USER" }?.text ?: return "Nueva Sesión"
            if (firstMsg.length > 36) firstMsg.take(33) + "…" else firstMsg
        } catch (e: Exception) { "Sesión" }
    }

    private fun saveTitleForSession(sessionId: String, title: String) {
        SecurePrefs.get(this)
            .edit().putString("title_$sessionId", title.trim()).apply()
    }

    private fun deleteSession(sessionId: String) {
        val prefs = SecurePrefs.get(this)
        prefs.edit().remove(sessionId).remove("title_$sessionId").apply()
        if (currentChatId == sessionId) {
            val remaining = getSavedSessions().filter { it != sessionId }
            if (remaining.isNotEmpty()) {
                loadChatSession(remaining.first())
            } else {
                currentChatId = "session_${System.currentTimeMillis()}"
                chatMessageHistory.postValue(emptyList())
            }
        }
        refreshSessions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request location permissions up front — GPS puck, tracking, and marker
        // distance all silently no-op without ACCESS_FINE_LOCATION. Asking on
        // first launch is less surprising than asking when the user taps a button.
        ensureLocationPermission()

        // Start FieldAssistantService via Intent.
        // startForegroundService() avoids BackgroundServiceStartNotAllowedException
        // when launched via `adb am start` (Android 14+ classifies adb-launched
        // activities as background until window is shown).
        val intent = Intent(this, FieldAssistantService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        io.kognis.tactical.core.PerformanceLogger.coldStartMs = -1L  // reset cold start timer
        val bindStartMs = System.currentTimeMillis()
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Store bind timestamp so onStatusChange can compute cold start duration
        this.coreBindStartMs = bindStartMs

        if (savedInstanceState != null) {
            loadState(savedInstanceState)
        }

        // Load persisted chat history
        val sessions = getSavedSessions()
        if (sessions.isNotEmpty()) {
            loadChatSession(sessions.first())
        } else {
            loadChatSession(currentChatId)
        }

        io.kognis.tactical.core.PerformanceLogger.init(this)

        enableEdgeToEdge()
        setContent {
            val prefs = SecurePrefs.get(this)
            val currentLanguage = prefs.getString("app_language", "es") ?: "es"

            KognisLiteTheme {
                MainContent(
                    currentLanguage = currentLanguage,
                    onLanguageChange = { newLang ->
                        if (newLang != currentLanguage) {
                            prefs.edit().putString("app_language", newLang).apply()
                            recreate()
                        }
                    }
                ) 
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister before unbind — prevents service from holding a dead Activity reference
        try { fieldCore?.unregisterCallback(fieldCallback) } catch (_: Exception) {}
        unbindService(serviceConnection)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (conversationHistoryJSONString != null) {
            outState.putString("history-json", conversationHistoryJSONString)
        }
    }

    /**
     * The composable of the main activity content
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(currentLanguage: String, onLanguageChange: (String) -> Unit) {
        var showSplash = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
        
        if (showSplash.value) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500L) // 1.5 seconds splash screen
                showSplash.value = false
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(io.kognis.tactical.ui.theme.OledBlack),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Text("⚡ KOGNIS", color = io.kognis.tactical.ui.theme.SilicaWhite, style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.Text("OFFLINE · ZERO SIGNAL", color = io.kognis.tactical.ui.theme.RescueAmber, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    androidx.compose.material3.Text("Gemma 4 E2B · INSARAG/UNDAC corpus", color = androidx.compose.ui.graphics.Color.Gray, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            }
            return
        }

        val isReady by isModelReady.observeAsState(false)
        val llmLoaded by isLlmLoaded.observeAsState(false)
        val statusText by coreStatus.observeAsState("Conectando Core...")
        val kbProgressState by kbProgress.observeAsState()
        val chatMessageHistory: List<ChatMessageDisplayItem> by chatMessageHistory.observeAsState(
            listOf()
        )
        var userInputFieldText by remember { mutableStateOf("") }
        var expandedModelMenu by remember { mutableStateOf(false) }
        var currentModelType by remember { mutableStateOf("GEMMA") }
        val chatHistoryFocusRequester = remember { FocusRequester() }
        val isInGeneration = this.isInGeneration.observeAsState(false)
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        
        var showAddMenu by remember { mutableStateOf(false) }
        var showSettingsMenu by remember { mutableStateOf(false) }
        var evalRunning by remember { mutableStateOf(false) }
        var evalProgress by remember { mutableStateOf(0) }
        var evalTotal by remember { mutableStateOf(0) }
        var evalCurrentQ by remember { mutableStateOf("") }
        var evalJob by remember { mutableStateOf<Job?>(null) }
        var gisRunning by remember { mutableStateOf(false) }
        var gisProgress by remember { mutableStateOf(0) }
        var gisTotal by remember { mutableStateOf(0) }
        var gisJob by remember { mutableStateOf<Job?>(null) }
        // Adaptive learning mode state — set when a training session is active.
        var trainingActive by remember { mutableStateOf(false) }
        var trainingSessionId by remember { mutableStateOf(0L) }
        var showLearnerPanel by remember { mutableStateOf(false) }
        var learnerModelJson by remember { mutableStateOf("{}") }
        // The most recent quiz/case-study card to render below the chat
        var pendingQuiz by remember { mutableStateOf<io.kognis.tactical.core.learning.LearningSkill.QuizUser?>(null) }
        var pendingCase by remember { mutableStateOf<io.kognis.tactical.core.learning.LearningSkill.ShowExample?>(null) }
        // Register the skill callback once — fired by fieldCallback.onGenerationComplete.
        DisposableEffect(Unit) {
            this@MainActivity.skillCallback = { skill ->
                when (skill) {
                    is io.kognis.tactical.core.learning.LearningSkill.QuizUser -> pendingQuiz = skill
                    is io.kognis.tactical.core.learning.LearningSkill.ShowExample -> pendingCase = skill
                    is io.kognis.tactical.core.learning.LearningSkill.MarkMastery -> { /* state already updated by service */ }
                    is io.kognis.tactical.core.learning.LearningSkill.ReviewPastMisses -> { /* handled in next turn's prompt */ }
                }
            }
            this@MainActivity.dismissTrainingCards = { pendingQuiz = null; pendingCase = null }
            onDispose {
                this@MainActivity.skillCallback = null
                this@MainActivity.dismissTrainingCards = null
            }
        }
        // Voice input agent state — on-device speech-to-text (SpeechRecognizer)
        var voiceListening by remember { mutableStateOf(false) }
        var voiceError by remember { mutableStateOf<String?>(null) }
        val voiceAgent = remember { io.kognis.tactical.core.agent.VoiceInputAgent(this@MainActivity) }
        // Flashlight tool state
        var flashlightOn by remember { mutableStateOf(io.kognis.tactical.core.agent.FlashlightTool.isOn) }
        // TTS output agent — closes hands-free loop (voice in → voice out).
        val ttsAgentLocal = remember {
            io.kognis.tactical.core.agent.TtsAgent(this@MainActivity).also {
                this@MainActivity.ttsAgent = it
                it.init()
            }
        }
        DisposableEffect(ttsAgentLocal) { onDispose { ttsAgentLocal.shutdown() } }
        var ttsOn by remember { mutableStateOf(false) }
        var showObservabilityModal by remember { mutableStateOf(false) }
        var showPerfDashboard by remember { mutableStateOf(false) }
        
        var currentRagMode by remember { mutableStateOf("Auto") }
        var expandedRagMenu by remember { mutableStateOf(false) }
        var showWaitlistDialog by remember { mutableStateOf(false) }
        var showLanguageDialog by remember { mutableStateOf(false) }

        var verbosityLevel by remember { mutableStateOf("ESTANDAR") }

        // 11.5 Zero-Signal
        val prefs = remember { SecurePrefs.get(this) }
        var isInternetAllowed by remember { mutableStateOf(prefs.getBoolean("internet_allowed", true)) }
        var showZeroSignalDialog by remember { mutableStateOf(false) }
        var zeroSignalInput by remember { mutableStateOf("") }

        var showMapScreen by remember { mutableStateOf(false) }
        var showInlineMap by remember { mutableStateOf(false) }

        // Auto-expand inline map when first marker is dropped
        val liveMarkerCount = io.kognis.tactical.core.map.MarkerStore.markers.size
        LaunchedEffect(liveMarkerCount) {
            if (liveMarkerCount > 0) showInlineMap = true
        }
        val convTurn by this@MainActivity.conversationTurn.observeAsState(Pair(0, 8))
        val snackEvent by this@MainActivity.snackbarEvent.observeAsState(null)
        LaunchedEffect(snackEvent) {
            val msg = snackEvent ?: return@LaunchedEffect
            android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
            this@MainActivity.snackbarEvent.postValue(null)
        }

        // Session management state (drawer long-press)
        val sessions by this@MainActivity.sessionsList.observeAsState(getSavedSessions())
        val embMode by this@MainActivity.embeddingMode.observeAsState("Iniciando...")
        var contextMenuSessionId by remember { mutableStateOf("") }
        var showSessionContextMenu by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        var renameFieldValue by remember { mutableStateOf("") }
        
        val ctx = LocalContext.current
        val ramText by androidx.compose.runtime.produceState(initialValue = "RAM: -- GB") {
            val activityManager = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            while(true) {
                activityManager.getMemoryInfo(memoryInfo)
                val availGb = memoryInfo.availMem.toDouble() / (1024 * 1024 * 1024)
                val totalGb = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
                value = String.format("RAM: %.1f/%.1f GB", totalGb - availGb, totalGb)
                kotlinx.coroutines.delay(2000L)
            }
        }


        if (showWaitlistDialog) {
            AlertDialog(
                onDismissRequest = { showWaitlistDialog = false },
                title = { Text(stringResource(R.string.soon_title), color = io.kognis.tactical.ui.theme.RescueAmber) },
                text = { Text(stringResource(R.string.soon_desc), color = androidx.compose.ui.graphics.Color.White) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showWaitlistDialog = false
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://kognis.tech"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }) { Text(stringResource(R.string.soon_btn), color = io.kognis.tactical.ui.theme.RescueAmber) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showWaitlistDialog = false }) {
                        Text(stringResource(R.string.cancel), color = androidx.compose.ui.graphics.Color.Gray)
                    }
                },
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            )
        }

        // --- Fase 9: KB Update result dialog ---
        val kbResult by kbUpdateResult.observeAsState()
        kbResult?.let { resultJson ->
            val obj = runCatching { org.json.JSONObject(resultJson) }.getOrNull()
            if (obj != null) {
                AlertDialog(
                    onDismissRequest = { kbUpdateResult.value = null },
                    title = { Text(stringResource(R.string.kb_updated_title), color = io.kognis.tactical.ui.theme.RescueAmber) },
                    text = {
                        val added = obj.optInt("added")
                        val skipped = obj.optInt("skipped")
                        val rejected = obj.optInt("rejected")
                        val total = obj.optLong("total_in_db")
                        val sha = obj.optString("sha256")
                        Text(
                            "${stringResource(R.string.kb_chunks_added, added)}\n" +
                            "${stringResource(R.string.kb_chunks_skipped, skipped)}\n" +
                            "${stringResource(R.string.kb_chunks_rejected, rejected)}\n" +
                            "${stringResource(R.string.kb_total_in_db, total)}\n\n" +
                            "${stringResource(R.string.kb_sha256)}\n$sha",
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    },
                    confirmButton = {
                        Button(onClick = { kbUpdateResult.value = null }) { Text("OK") }
                    },
                    containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
                )
            }
        }

        if (showLanguageDialog) {
            val es = currentLanguage == "es"
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(stringResource(R.string.settings_language), color = io.kognis.tactical.ui.theme.SilicaWhite) },
                text = {
                    Column {
                        androidx.compose.material3.TextButton(
                            onClick = { onLanguageChange("es"); showLanguageDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                        ) { Text(stringResource(R.string.language_es), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, color = if (currentLanguage == "es") io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.LightGray) }
                        androidx.compose.material3.TextButton(
                            onClick = { onLanguageChange("en"); showLanguageDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                        ) { Text(stringResource(R.string.language_en), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, color = if (currentLanguage == "en") io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.LightGray) }

                        androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Zero-Signal",
                            color = io.kognis.tactical.ui.theme.RescueAmber,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (es) "🛡 Internet" else "🛡 Internet",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.Switch(
                                checked = !isInternetAllowed,
                                onCheckedChange = { zeroSignalOn ->
                                    if (zeroSignalOn) {
                                        isInternetAllowed = false
                                        prefs.edit().putBoolean("internet_allowed", false).apply()
                                    } else {
                                        zeroSignalInput = ""
                                        showLanguageDialog = false
                                        showZeroSignalDialog = true
                                    }
                                },
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = io.kognis.tactical.ui.theme.RescueAmber,
                                    checkedTrackColor = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.4f)
                                )
                            )
                        }
                        Text(
                            if (!isInternetAllowed) {
                                if (es) "Zero-Signal ACTIVO — sin conexiones salientes" else "Zero-Signal ACTIVE — no outbound connections"
                            } else {
                                if (es) "Internet activo — conexiones salientes habilitadas" else "Internet active — outbound connections enabled"
                            },
                            color = if (!isInternetAllowed) androidx.compose.ui.graphics.Color(0xFFFF7043) else androidx.compose.ui.graphics.Color.Gray,
                            fontSize = 11.sp,
                        )
                    }
                },
                confirmButton = {},
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            )
        }

        if (showZeroSignalDialog) {
            val es = currentLanguage == "es"
            val phrase = if (es) "Quiero activar el internet" else "I want to enable internet"
            val inputMatches = zeroSignalInput.trim() == phrase
            AlertDialog(
                onDismissRequest = { showZeroSignalDialog = false },
                title = {
                    Text(
                        if (es) "Activar conexión a internet" else "Enable internet connection",
                        color = io.kognis.tactical.ui.theme.RescueAmber
                    )
                },
                text = {
                    Column {
                        Text(
                            if (es) "⚠️ Activar internet expone la ubicación y actividad del dispositivo. Solo actívalo en zona segura para descargar el modelo o actualizar la base de conocimiento."
                            else "⚠️ Enabling internet exposes device location and activity. Only enable in a safe zone to download the model or update the knowledge base.",
                            color = androidx.compose.ui.graphics.Color(0xFFFF7043),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Text(
                            if (es) "Escribe exactamente para confirmar:\n\n\"Quiero activar el internet\""
                            else "Type exactly to confirm:\n\n\"I want to enable internet\"",
                            color = androidx.compose.ui.graphics.Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = zeroSignalInput,
                            onValueChange = { zeroSignalInput = it },
                            singleLine = true,
                            placeholder = { Text(phrase, color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = io.kognis.tactical.ui.theme.SilicaWhite,
                                unfocusedTextColor = io.kognis.tactical.ui.theme.SilicaWhite,
                                focusedBorderColor = if (inputMatches) androidx.compose.ui.graphics.Color(0xFF81C784) else io.kognis.tactical.ui.theme.RescueAmber,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.DarkGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isInternetAllowed = true
                            // Local write keeps the toggle UI consistent in :main, but the
                            // service in :field_core has its own EncryptedSharedPreferences
                            // cache and would otherwise read the old value until restart.
                            // Route the canonical write through AIDL so the service writes
                            // from its own process and immediately re-enters initializeCore.
                            prefs.edit().putBoolean("internet_allowed", true).commit()
                            fieldCore?.enableInternetAndRetry()
                            showZeroSignalDialog = false
                        },
                        enabled = inputMatches,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (inputMatches) androidx.compose.ui.graphics.Color(0xFF81C784) else androidx.compose.ui.graphics.Color.Gray
                        )
                    ) { Text(if (es) "Activar Internet" else "Enable Internet") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showZeroSignalDialog = false }) {
                        Text(if (es) "Cancelar" else "Cancel", color = androidx.compose.ui.graphics.Color.Gray)
                    }
                },
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            )
        }

        if (showAddMenu) {
            @OptIn(ExperimentalMaterial3Api::class)
            ModalBottomSheet(onDismissRequest = { showAddMenu = false }, containerColor = io.kognis.tactical.ui.theme.MachinedGraphite) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showAddMenu = false
                        this@MainActivity.launchVisionCamera()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_menu_camera), null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Identify medication (camera)", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Vision agent: OCR label → RAG dosage lookup", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showAddMenu = false
                        this@MainActivity.launchVisionGallery()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_menu_gallery), null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Identify from gallery", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Vision agent: pick a label image → OCR → RAG", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showAddMenu = false
                        kbPickLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.kb_update_btn), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Import JSON corpus", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showAddMenu = false
                        fieldCore?.restoreKnowledgeBase()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RestoreFromTrash, null, tint = androidx.compose.ui.graphics.Color(0xFF81C784))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.kb_restore_btn), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Restore INSARAG/UNDAC default", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (showSettingsMenu) {
            @OptIn(ExperimentalMaterial3Api::class)
            val settingsSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSettingsMenu = false },
                sheetState = settingsSheetState,
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                ) {

                    // --- 11.5: Zero-Signal ---
                    Text("Zero-Signal", color = io.kognis.tactical.ui.theme.RescueAmber, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Row(
                        Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.zero_signal_label),
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.Switch(
                            checked = !isInternetAllowed,
                            onCheckedChange = { zeroSignalOn ->
                                if (zeroSignalOn) {
                                    isInternetAllowed = false
                                    prefs.edit().putBoolean("internet_allowed", false).apply()
                                } else {
                                    zeroSignalInput = ""
                                    showZeroSignalDialog = true
                                }
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = io.kognis.tactical.ui.theme.RescueAmber,
                                checkedTrackColor = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.4f)
                            )
                        )
                    }
                    Text(
                        if (!isInternetAllowed) stringResource(R.string.zero_signal_active) else stringResource(R.string.zero_signal_inactive),
                        color = if (!isInternetAllowed) androidx.compose.ui.graphics.Color(0xFFFF7043) else androidx.compose.ui.graphics.Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(bottom = 16.dp))

                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(bottom = 16.dp))

                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        markerImportLauncher.launch(arrayOf("application/json", "*/*"))
                    }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudUpload, null, tint=androidx.compose.ui.graphics.Color.White); Spacer(Modifier.width(16.dp)); Column { Text("Import map markers", color=androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge); Text("Load locations from JSON (same format as export)", color=androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall) } }

                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                    // Adaptive learning controls
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        try {
                            val sid = fieldCore?.startLearningSession(null) ?: 0L
                            if (sid > 0L) {
                                trainingActive = true
                                trainingSessionId = sid
                                android.widget.Toast.makeText(this@MainActivity, "Training session #$sid started", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, "Failed to start training session", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@MainActivity, "Training error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_menu_agenda), null, tint = if (!trainingActive) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.Gray)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(if (trainingActive) "Training session active" else "Start SAR training", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Adaptive multi-tool learning · INSARAG curriculum", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (trainingActive) {
                        Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                            showSettingsMenu = false
                            learnerModelJson = fieldCore?.getLearnerModelJson() ?: "{}"
                            showLearnerPanel = true
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(android.R.drawable.ic_menu_view), null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("Learner progress", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                                Text("Mastery, recent misses, skill activity", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                            showSettingsMenu = false
                            fieldCore?.endLearningSession()
                            trainingActive = false
                            trainingSessionId = 0L
                            pendingQuiz = null
                            pendingCase = null
                            android.widget.Toast.makeText(this@MainActivity, "Training session closed", android.widget.Toast.LENGTH_SHORT).show()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Close, null, tint = androidx.compose.ui.graphics.Color(0xFFEF5350))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("End training session", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                                Text("Save summary + promote facts to long-term memory", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        this@MainActivity.launchVisionCamera()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_menu_camera), null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Identify medication (camera)", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Vision agent: OCR label → RAG dosage", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        this@MainActivity.launchVisionGallery()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_menu_gallery), null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Identify from gallery", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Vision agent: pick image → OCR → RAG", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        kbPickLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = io.kognis.tactical.ui.theme.RescueAmber)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.kb_update_btn), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Import JSON corpus", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable {
                        showSettingsMenu = false
                        fieldCore?.restoreKnowledgeBase()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RestoreFromTrash, null, tint = androidx.compose.ui.graphics.Color(0xFF81C784))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.kb_restore_btn), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text("Restore INSARAG/UNDAC default", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    androidx.compose.material3.Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                    val evalReady = isReady && !evalRunning
                    Row(
                        Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable(enabled = evalReady) {
                            showSettingsMenu = false
                            val questions = io.kognis.tactical.core.EvalRunner.loadQuestions(ctx)
                            evalTotal = questions.size
                            evalProgress = 0
                            evalRunning = true
                            val results = mutableListOf<io.kognis.tactical.core.EvalRunner.EvalResult>()
                            evalJob = lifecycleScope.launch {
                                for ((idx, q) in questions.withIndex()) {
                                    evalProgress = idx + 1
                                    // Clear LLM context before each question to prevent OOM crash
                                    fieldCore?.clearConversation()
                                    delay(1_200L)
                                    val start = System.currentTimeMillis()
                                    this@MainActivity.evalBuffer.clear()
                                    this@MainActivity.evalDeferred = CompletableDeferred()
                                    sendText(q.question, "Auto", isEval = true)
                                    val answer = withTimeoutOrNull(90_000L) {
                                        this@MainActivity.evalDeferred!!.await()
                                    } ?: "TIMEOUT"
                                    this@MainActivity.evalDeferred = null
                                    val dur = System.currentTimeMillis() - start
                                    results += io.kognis.tactical.core.EvalRunner.EvalResult(
                                        id = q.id,
                                        category = q.category,
                                        question = q.question,
                                        answer = answer.trim(),
                                        durationMs = dur,
                                        timedOut = answer == "TIMEOUT",
                                    )
                                    delay(500L)
                                }
                                evalRunning = false
                                val uri = io.kognis.tactical.core.EvalRunner.exportResults(ctx, results)
                                if (uri != null) {
                                    ctx.startActivity(
                                        android.content.Intent.createChooser(
                                            io.kognis.tactical.core.EvalRunner.shareIntent(uri),
                                            "Share eval results"
                                        )
                                    )
                                }
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Dataset, null, tint = if (evalReady) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.Gray)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (evalRunning) "Eval running ($evalProgress/${evalTotal})…" else "Run RAG Eval",
                                color = if (evalReady) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "50 questions · exports JSON",
                                color = androidx.compose.ui.graphics.Color.Gray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    // GIS mapping test
                    val gisReady = isReady && !gisRunning && !evalRunning
                    Row(
                        Modifier.padding(vertical = 12.dp).fillMaxWidth().clickable(enabled = gisReady) {
                            showSettingsMenu = false
                            val questions = io.kognis.tactical.core.GisEvalRunner.loadQuestions(ctx)
                            gisTotal = questions.size
                            gisProgress = 0
                            gisRunning = true
                            val results = mutableListOf<io.kognis.tactical.core.GisEvalRunner.GisResult>()
                            gisJob = lifecycleScope.launch {
                                for ((idx, q) in questions.withIndex()) {
                                    gisProgress = idx + 1
                                    fieldCore?.clearConversation()
                                    delay(800L)
                                    val markersBefore = io.kognis.tactical.core.map.MarkerStore.markers.size
                                    val start = System.currentTimeMillis()
                                    this@MainActivity.evalBuffer.clear()
                                    this@MainActivity.evalDeferred = CompletableDeferred()
                                    // isEval=false → QueryPreprocessor fires → places marker with exact coords or GPS
                                    sendText(q.question, "Auto", isEval = false)
                                    val llmResp = withTimeoutOrNull(60_000L) {
                                        this@MainActivity.evalDeferred!!.await()
                                    } ?: "TIMEOUT"
                                    this@MainActivity.evalDeferred = null
                                    val dur = System.currentTimeMillis() - start
                                    // Inspect what was placed
                                    val markersAfter = io.kognis.tactical.core.map.MarkerStore.markers
                                    val placed = markersAfter.size > markersBefore
                                    val newMarker = if (placed) markersAfter.last() else null
                                    results += io.kognis.tactical.core.GisEvalRunner.GisResult(
                                        id = q.id,
                                        question = q.question,
                                        markerPlaced = placed,
                                        lat = newMarker?.location?.lat,
                                        lon = newMarker?.location?.lon,
                                        label = newMarker?.location?.label,
                                        cotType = newMarker?.cotType?.name,
                                        gpsUsed = q.isGpsBased,
                                        llmResponse = llmResp.trim(),
                                        durationMs = dur,
                                        timedOut = llmResp == "TIMEOUT",
                                    )
                                    delay(400L)
                                }
                                gisRunning = false
                                val uri = io.kognis.tactical.core.GisEvalRunner.exportResults(ctx, results)
                                if (uri != null) {
                                    ctx.startActivity(
                                        android.content.Intent.createChooser(
                                            io.kognis.tactical.core.GisEvalRunner.shareIntent(uri),
                                            "Share GIS test results"
                                        )
                                    )
                                }
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Map, null, tint = if (gisReady) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.Gray)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (gisRunning) "GIS test ($gisProgress/${gisTotal})…" else "Run GIS Map Test",
                                color = if (gisReady) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "13 markers (10 coord + 3 GPS) · exports JSON",
                                color = androidx.compose.ui.graphics.Color.Gray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        if (showLearnerPanel) {
            @OptIn(ExperimentalMaterial3Api::class)
            val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { showLearnerPanel = false }, sheetState = sheet,
                              containerColor = io.kognis.tactical.ui.theme.MachinedGraphite) {
                io.kognis.tactical.views.LearnerPanel(modelJson = learnerModelJson)
            }
        }

        if (showObservabilityModal) {
            @OptIn(ExperimentalMaterial3Api::class)
            val obsSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { showObservabilityModal = false }, sheetState = obsSheetState, containerColor = io.kognis.tactical.ui.theme.MachinedGraphite) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.observability_title), color = io.kognis.tactical.ui.theme.RescueAmber, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    
                    val batTempText by androidx.compose.runtime.produceState(initialValue = "--°C") {
                        val receiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(c: Context?, intent: Intent?) {
                                val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                                value = "${temp / 10.0}°C"
                            }
                        }
                        ctx.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        kotlinx.coroutines.delay(1000L) // Ensure immediate update attempt if possible
                        awaitDispose { ctx.unregisterReceiver(receiver) }
                    }

                    Text(stringResource(R.string.sys_mem), color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    Text(ramText, color = io.kognis.tactical.ui.theme.SilicaWhite, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))

                    Text(stringResource(R.string.cpu_temp), color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    val notAvailStr = stringResource(R.string.not_avail)
                    val cpuDetailed by androidx.compose.runtime.produceState(initialValue = "--") {
                        val valCpu = io.kognis.tactical.core.ThermalGovernor.getCpuTemperature()
                        value = valCpu?.let { "%.1f°C".format(it) } ?: notAvailStr
                    }
                    Text(cpuDetailed, color = io.kognis.tactical.ui.theme.SilicaWhite, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))

                    Text(stringResource(R.string.bat_temp), color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    Text(batTempText, color = io.kognis.tactical.ui.theme.SilicaWhite, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
                    
                    Text(stringResource(R.string.rag_status), color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.rag_thresh), color = io.kognis.tactical.ui.theme.SilicaWhite, style = MaterialTheme.typography.bodyLarge)

                    Text(stringResource(R.string.embedding_engine), color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        embMode,
                        color = if (embMode.contains("ONNX")) androidx.compose.ui.graphics.Color(0xFF4CAF50) else io.kognis.tactical.ui.theme.RescueAmber,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Divider(color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
                    val currentLang = Locale.getDefault().language
                    val promptPreview = remember(verbosityLevel, currentLang) {
                        io.kognis.tactical.core.RagPromptBuilder.buildSystemPromptStd(verbosityLevel, currentLang != "es")
                            .lines().take(7).joinToString("\n")
                    }
                    var showFullPrompt by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("System Prompt", color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelMedium)
                        androidx.compose.material3.TextButton(onClick = { showFullPrompt = !showFullPrompt }) {
                            Text(if (showFullPrompt) "▲ Hide" else "▼ Show", color = io.kognis.tactical.ui.theme.RescueAmber, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (showFullPrompt) {
                        Text(
                            io.kognis.tactical.core.RagPromptBuilder.buildSystemPromptStd(verbosityLevel, currentLang != "es"),
                            color = io.kognis.tactical.ui.theme.SilicaWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            promptPreview,
                            color = io.kognis.tactical.ui.theme.SilicaWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (showPerfDashboard) {
            io.kognis.tactical.views.PerformanceDashboard(onDismiss = { showPerfDashboard = false })
        }

        // ── Session context menu (long-press) ─────────────────────────────
        if (showSessionContextMenu) {
            ModalBottomSheet(
                onDismissRequest = { showSessionContextMenu = false },
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            ) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                    Text(
                        titleForSession(contextMenuSessionId),
                        color = io.kognis.tactical.ui.theme.RescueAmber,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    androidx.compose.material3.TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showSessionContextMenu = false
                            showRenameDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = io.kognis.tactical.ui.theme.SilicaWhite)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.rename), color = io.kognis.tactical.ui.theme.SilicaWhite, modifier = Modifier.weight(1f))
                    }
                    androidx.compose.material3.TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showSessionContextMenu = false
                            showDeleteDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Red)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.delete_session), color = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Rename dialog ──────────────────────────────────────────────────
        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text(stringResource(R.string.rename_session), color = io.kognis.tactical.ui.theme.SilicaWhite) },
                text = {
                    TextField(
                        value = renameFieldValue,
                        onValueChange = { renameFieldValue = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = io.kognis.tactical.ui.theme.SilicaWhite,
                            unfocusedTextColor = io.kognis.tactical.ui.theme.SilicaWhite,
                            focusedContainerColor = io.kognis.tactical.ui.theme.MachinedGraphite,
                            unfocusedContainerColor = io.kognis.tactical.ui.theme.MachinedGraphite,
                            focusedIndicatorColor = io.kognis.tactical.ui.theme.RescueAmber,
                        )
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        if (renameFieldValue.isNotBlank()) {
                            saveTitleForSession(contextMenuSessionId, renameFieldValue)
                            refreshSessions()
                        }
                        showRenameDialog = false
                    }) { Text(stringResource(R.string.save), color = io.kognis.tactical.ui.theme.RescueAmber) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showRenameDialog = false }) {
                        Text(stringResource(R.string.cancel), color = androidx.compose.ui.graphics.Color.Gray)
                    }
                },
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            )
        }

        // ── Delete confirmation dialog ─────────────────────────────────────
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.delete_session), color = io.kognis.tactical.ui.theme.SilicaWhite) },
                text = {
                    Text(
                        stringResource(R.string.delete_confirm, titleForSession(contextMenuSessionId)),
                        color = androidx.compose.ui.graphics.Color.LightGray
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        deleteSession(contextMenuSessionId)
                        showDeleteDialog = false
                        scope.launch { drawerState.close() }
                    }) { Text(stringResource(R.string.delete), color = androidx.compose.ui.graphics.Color.Red) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel), color = androidx.compose.ui.graphics.Color.Gray)
                    }
                },
                containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
            )
        }

        // Session marker map screen — full-screen Dialog with multi-marker
        // osmdroid view. Opens from the Map icon in the action row.
        if (showMapScreen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showMapScreen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                // Box layout so toolbar Surface (with shadow elevation) stays above the AndroidView map.
                Box(modifier = Modifier.fillMaxSize().statusBarsPadding().background(io.kognis.tactical.ui.theme.MachinedGraphite)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 56dp toolbar slot reserves space; map fills the rest.
                        Spacer(Modifier.height(56.dp))
                        io.kognis.tactical.core.map.MapFallbackViewMulti(
                            markers = io.kognis.tactical.core.map.MarkerStore.markers,
                            modifier = Modifier.fillMaxSize(),
                            onClear = { io.kognis.tactical.core.map.MarkerStore.clear() },
                        )
                    }
                    // Toolbar Surface — elevated above the map AndroidView so it never gets covered.
                    androidx.compose.material3.Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .align(Alignment.TopCenter)
                            .zIndex(10f),
                        color = io.kognis.tactical.ui.theme.MachinedGraphite,
                        shadowElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.map_markers_title),
                                color = io.kognis.tactical.ui.theme.RescueAmber,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                val hasMarkers = io.kognis.tactical.core.map.MarkerStore.markers.isNotEmpty()
                                // Share JSON markers — opens system share chooser (WhatsApp / email / file)
                                IconButton(
                                    onClick = {
                                        val result = io.kognis.tactical.core.map.JsonMarkerExporter.export(ctx)
                                        if (result != null) {
                                            ctx.startActivity(io.kognis.tactical.core.map.JsonMarkerExporter.shareIntent(ctx, result))
                                        } else {
                                            android.widget.Toast.makeText(ctx, "No markers to share", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = hasMarkers,
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share markers (JSON)",
                                        tint = if (hasMarkers) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.DarkGray,
                                    )
                                }
                                // Import JSON markers (same format as export)
                                IconButton(onClick = { markerImportLauncher.launch(arrayOf("application/json", "*/*")) }) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = "Import markers (JSON)", tint = androidx.compose.ui.graphics.Color.LightGray)
                                }
                                IconButton(onClick = { showMapScreen = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.LightGray)
                                }
                            }
                        }
                    }
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = io.kognis.tactical.ui.theme.MachinedGraphite,
                    modifier = Modifier.padding(end = 64.dp)
                ) {
                    Spacer(Modifier.height(32.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.quick_history), color = io.kognis.tactical.ui.theme.RescueAmber, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showLanguageDialog = true }) { Icon(Icons.Default.Settings, "Language", tint=androidx.compose.ui.graphics.Color.Gray) }
                    }
                    Divider(color = androidx.compose.ui.graphics.Color.DarkGray)
                    
                    androidx.compose.material3.TextButton(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = {
                            scope.launch { drawerState.close() }
                            this@MainActivity.fieldCore?.cancelGeneration()
                            this@MainActivity.currentChatId = "session_${System.currentTimeMillis()}"
                            this@MainActivity.chatMessageHistory.value = emptyList()
                            this@MainActivity.saveChatHistory(emptyList())
                            this@MainActivity.refreshSessions()
                        }
                    ) {
                        Text(stringResource(R.string.new_chat), color = io.kognis.tactical.ui.theme.RescueAmber)
                    }

                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
                        if (sessions.isEmpty()) {
                            item {
                                Text(stringResource(R.string.no_past_sessions), color = androidx.compose.ui.graphics.Color.Gray, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                        items(sessions.take(15)) { sessionId ->
                            val label = titleForSession(sessionId)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            scope.launch { drawerState.close() }
                                            loadChatSession(sessionId)
                                        },
                                        onLongClick = {
                                            contextMenuSessionId = sessionId
                                            renameFieldValue = titleForSession(sessionId)
                                            showSessionContextMenu = true
                                        }
                                    )
                                    .background(
                                        if (sessionId == currentChatId)
                                            io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.2f)
                                        else
                                            androidx.compose.ui.graphics.Color.Transparent,
                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    label,
                                    color = io.kognis.tactical.ui.theme.SilicaWhite,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Divider(color = androidx.compose.ui.graphics.Color.DarkGray)
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.TextButton(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        onClick = {
                            scope.launch { drawerState.close() }
                            showPerfDashboard = true
                        }
                    ) {
                        Text(
                            "⚡ Performance Dashboard",
                            color = io.kognis.tactical.ui.theme.RescueAmber,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    androidx.compose.material3.TextButton(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        onClick = {
                            scope.launch { drawerState.close() }
                            this@MainActivity.exportPerformanceJson()
                        }
                    ) {
                        Text(
                            "📤 Export JSON",
                            color = androidx.compose.ui.graphics.Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        NavigationBarDefaults.windowInsets.union(
                            WindowInsets.ime
                        )
                    ),
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "KOGNIS",
                                color = io.kognis.tactical.ui.theme.SilicaWhite,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { showPerfDashboard = true }
                                )
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = io.kognis.tactical.ui.theme.SilicaWhite)
                            }
                        },
                        actions = {
                            // Turn counter badge — shows KV-cache window depth
                            if (convTurn.first > 0) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Color(0xFF1B2838),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "Mem: ${convTurn.first}/${convTurn.second}",
                                        color = androidx.compose.ui.graphics.Color(0xFF8899AA),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .clickable { showObservabilityModal = true }
                            ) {
                                val batTempC by androidx.compose.runtime.produceState(initialValue = -1.0) {
                                    while (true) {
                                        val intent = androidx.core.content.ContextCompat.registerReceiver(
                                            this@MainActivity, null,
                                            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                                            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                                        )
                                        val raw = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10
                                        if (raw > 0) value = raw / 10.0
                                        kotlinx.coroutines.delay(3000L)
                                    }
                                }
                                val tempText = if (batTempC < 0) "Temp: --°C" else "Temp: ${"%.1f".format(batTempC)}°C"
                                val tempColor = when {
                                    batTempC < 0    -> io.kognis.tactical.ui.theme.RescueAmber
                                    batTempC < 38.0 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                    batTempC < 45.0 -> io.kognis.tactical.ui.theme.RescueAmber
                                    else            -> androidx.compose.ui.graphics.Color.Red
                                }
                                Text(ramText, color = io.kognis.tactical.ui.theme.SilicaWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(tempText, color = tempColor, fontSize = 10.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = io.kognis.tactical.ui.theme.OledBlack)
                    )
                },
                bottomBar = {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        if (true) {
                            Column {
                                // Fase 2.6: Botones de Acción Rápida (Conservados)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val chipsIds = remember(chatMessageHistory) {
                                        val lastContent = chatMessageHistory.lastOrNull()?.text?.lowercase() ?: ""
                                        when {
                                            listOf("herid", "sangr", "medic", "victim", "víctim", "dolor", "torniquete", "fractur").any { lastContent.contains(it) } ->
                                                listOf(R.string.chip_medevac, R.string.chip_march, R.string.chip_bleeding)
                                            listOf("colapso", "derrumbe", "hazard", "peligro", "químic", "incendi", "inundac", "estructur").any { lastContent.contains(it) } ->
                                                listOf(R.string.chip_hazard_zone, R.string.chip_extraction_path, R.string.chip_water_point)
                                            listOf("zona", "mapa", "ubicación", "coordenada", "ruta", "lz", "helicópter").any { lastContent.contains(it) } ->
                                                listOf(R.string.chip_lz, R.string.chip_base_camp, R.string.chip_water_point)
                                            else ->
                                                listOf(R.string.chip_march, R.string.chip_lz, R.string.chip_water_point)
                                        }
                                    }
                                    chipsIds.forEach { chipResId ->
                                        val chipStr = stringResource(chipResId)
                                        androidx.compose.material3.OutlinedButton(
                                            onClick = { sendText(chipStr) },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = io.kognis.tactical.ui.theme.RescueAmber,
                                                containerColor = io.kognis.tactical.ui.theme.OledBlack
                                            )
                                        ) {
                                            Text(chipStr, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                
                                // Training-mode cards: rendered above the input when present
                                pendingCase?.let { case ->
                                    val mod = remember(case.topic) {
                                        // Pull case-study text from the curriculum cache via the orchestrator (best-effort)
                                        runCatching {
                                            val raw = applicationContext.assets.open("curriculum_sar.json").bufferedReader().use { it.readText() }
                                            val root = org.json.JSONObject(raw)
                                            val mods = root.optJSONArray("modules") ?: org.json.JSONArray()
                                            var hit: Triple<String, String, List<String>>? = null
                                            for (i in 0 until mods.length()) {
                                                val m = mods.optJSONObject(i) ?: continue
                                                if (m.optString("topic").equals(case.topic, ignoreCase = true)) {
                                                    val cs = m.optJSONArray("case_studies") ?: org.json.JSONArray()
                                                    if (cs.length() > 0) {
                                                        val c = cs.optJSONObject(0)!!
                                                        val kp = c.optJSONArray("key_points") ?: org.json.JSONArray()
                                                        hit = Triple(case.topic, c.optString("text", ""), (0 until kp.length()).map { kp.optString(it) })
                                                    }
                                                    break
                                                }
                                            }
                                            hit
                                        }.getOrNull()
                                    }
                                    if (mod != null) {
                                        io.kognis.tactical.views.CaseStudyCard(
                                            topic = mod.first,
                                            text = mod.second,
                                            keyPoints = mod.third,
                                            onDismiss = { pendingCase = null },
                                        )
                                    }
                                }
                                pendingQuiz?.let { q ->
                                    io.kognis.tactical.views.QuizCard(
                                        topic = q.topic,
                                        difficulty = q.difficulty,
                                        question = q.question,
                                        options = q.options,
                                        correctIndex = q.correctIndex,
                                        explanation = q.explanation,
                                        onAnswer = { correct, topic ->
                                            fieldCore?.recordQuizOutcome(topic, correct)
                                        },
                                        onDismiss = { pendingQuiz = null },
                                    )
                                }

                                // Nueva Input Bar Gemini-Style Multiline
                                Box(modifier = Modifier.fillMaxWidth().background(io.kognis.tactical.ui.theme.MachinedGraphite, RoundedCornerShape(24.dp)).padding(all = 8.dp)) {
                                    Column {
                                        androidx.compose.foundation.text.BasicTextField(
                                                value = userInputFieldText,
                                                onValueChange = { userInputFieldText = it },
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("InputBox"),
                                                textStyle = androidx.compose.ui.text.TextStyle(color = io.kognis.tactical.ui.theme.SilicaWhite, fontSize = 16.sp),
                                                enabled = !isInGeneration.value,
                                                decorationBox = { innerTextField ->
                                                    if (userInputFieldText.isEmpty()) {
                                                        Text(stringResource(R.string.ask_kognis), color = androidx.compose.ui.graphics.Color.Gray, fontSize = 16.sp)
                                                    }
                                                    innerTextField()
                                                }
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onClick = { showSettingsMenu = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = androidx.compose.ui.graphics.Color.LightGray) }

                                                // Map icon — tap = toggle inline panel, badge shows count.
                                                val activeMarkers = io.kognis.tactical.core.map.MarkerStore.markers
                                                val mapIconColor = if (activeMarkers.isEmpty())
                                                    androidx.compose.ui.graphics.Color.LightGray
                                                else
                                                    io.kognis.tactical.ui.theme.RescueAmber
                                                BadgedBox(
                                                    badge = {
                                                        if (activeMarkers.isNotEmpty()) {
                                                            Badge(
                                                                containerColor = io.kognis.tactical.ui.theme.RescueAmber,
                                                                contentColor = androidx.compose.ui.graphics.Color.Black,
                                                            ) { Text("${activeMarkers.size}", fontSize = 9.sp) }
                                                        }
                                                    }
                                                ) {
                                                    IconButton(onClick = { showInlineMap = !showInlineMap }) {
                                                        Icon(Icons.Default.Map, contentDescription = "Toggle map panel", tint = mapIconColor)
                                                    }
                                                }

                                                Box {
                                                    val ragColor = if (currentRagMode == "Desactivado") androidx.compose.ui.graphics.Color.Gray else io.kognis.tactical.ui.theme.RescueAmber
                                                    IconButton(onClick = { expandedRagMenu = true }) {
                                                        Icon(Icons.Default.Dataset, contentDescription = "RAG Mode", tint = ragColor)
                                                    }
                                                    androidx.compose.material3.DropdownMenu(
                                                        expanded = expandedRagMenu,
                                                        onDismissRequest = { expandedRagMenu = false },
                                                        modifier = Modifier.background(io.kognis.tactical.ui.theme.MachinedGraphite)
                                                    ) {
                                                        androidx.compose.material3.DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.rag_auto), color = androidx.compose.ui.graphics.Color.White) },
                                                            onClick = { currentRagMode = "Auto"; expandedRagMenu = false }
                                                        )
                                                        androidx.compose.material3.DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.rag_always), color = androidx.compose.ui.graphics.Color.White) },
                                                            onClick = { currentRagMode = "Siempre"; expandedRagMenu = false }
                                                        )
                                                        androidx.compose.material3.DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.rag_disabled), color = androidx.compose.ui.graphics.Color.White) },
                                                            onClick = { currentRagMode = "Desactivado"; expandedRagMenu = false }
                                                        )
                                                    }
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box {
                                                    // Compact verbosity selector — replaces the wide "Gemma 4 · Offline" chip
                                                    // so the Send button always fits at the right edge.
                                                    IconButton(onClick = { expandedModelMenu = true }) {
                                                        Icon(
                                                            Icons.Default.Tune,
                                                            contentDescription = "Verbosity",
                                                            tint = io.kognis.tactical.ui.theme.RescueAmber,
                                                        )
                                                    }

                                                    androidx.compose.material3.DropdownMenu(
                                                        expanded = expandedModelMenu,
                                                        onDismissRequest = { expandedModelMenu = false },
                                                        modifier = Modifier.background(io.kognis.tactical.ui.theme.MachinedGraphite)
                                                    ) {
                                                        Text(
                                                            stringResource(R.string.verbosity_label),
                                                            color = androidx.compose.ui.graphics.Color.Gray,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                                        )
                                                        listOf(
                                                            Triple("TACTICO", R.string.verbosity_tactical, R.string.verbosity_tactical_desc),
                                                            Triple("ESTANDAR", R.string.verbosity_standard, R.string.verbosity_standard_desc),
                                                            Triple("DETALLADO", R.string.verbosity_detailed, R.string.verbosity_detailed_desc)
                                                        ).forEach { (level, labelRes, descRes) ->
                                                            androidx.compose.material3.DropdownMenuItem(
                                                                text = {
                                                                    val prefix = if (verbosityLevel == level) "✓ " else ""
                                                                    Text(
                                                                        "$prefix${stringResource(labelRes)} · ${stringResource(descRes)}",
                                                                        color = if (verbosityLevel == level) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.White
                                                                    )
                                                                },
                                                                onClick = {
                                                                    verbosityLevel = level
                                                                    expandedModelMenu = false
                                                                    fieldCore?.setVerbosity(level)
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                                
                                                // TTS output agent — speaks LLM responses when on (hands-free output)
                                                IconButton(onClick = {
                                                    ttsOn = !ttsOn
                                                    this@MainActivity.ttsEnabled = ttsOn
                                                    ttsAgentLocal.setLanguage(en = (currentLanguage == "en"))
                                                    if (!ttsOn) ttsAgentLocal.stop()
                                                }) {
                                                    Icon(
                                                        if (ttsOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                                        contentDescription = if (ttsOn) "Stop speaking" else "Speak responses",
                                                        tint = if (ttsOn) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.LightGray,
                                                    )
                                                }

                                                // Voice input agent — on-device speech-to-text → existing pipeline
                                                if (voiceAgent.isAvailable()) {
                                                    IconButton(onClick = {
                                                        if (voiceListening) {
                                                            voiceAgent.stop()
                                                            voiceListening = false
                                                            return@IconButton
                                                        }
                                                        val start: () -> Unit = {
                                                            voiceError = null
                                                            voiceAgent.setLanguage(en = (currentLanguage == "en"))
                                                            voiceListening = true
                                                            voiceAgent.start(
                                                                onPartial = { partial -> userInputFieldText = partial },
                                                                onFinal = { final ->
                                                                    voiceListening = false
                                                                    userInputFieldText = final
                                                                    // Auto-send when transcript is non-empty
                                                                    if (final.isNotBlank() && (isReady || llmLoaded)) {
                                                                        this@MainActivity.isInGeneration.value = true
                                                                        sendText(final, currentRagMode)
                                                                        userInputFieldText = ""
                                                                    }
                                                                },
                                                                onError = { code ->
                                                                    voiceListening = false
                                                                    voiceError = io.kognis.tactical.core.agent.VoiceInputAgent.errorMessage(code, currentLanguage == "en")
                                                                },
                                                            )
                                                        }
                                                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                            this@MainActivity, android.Manifest.permission.RECORD_AUDIO
                                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                        if (granted) start()
                                                        else { pendingMicAction = start; micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                                                    }) {
                                                        Icon(
                                                            if (voiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                                                            contentDescription = if (voiceListening) "Stop voice" else "Voice input",
                                                            tint = if (voiceListening) androidx.compose.ui.graphics.Color.Red else io.kognis.tactical.ui.theme.RescueAmber,
                                                        )
                                                    }
                                                }

                                                if (isInGeneration.value) {
                                                    IconButton(onClick = { fieldCore?.cancelGeneration() }) { Icon(Icons.Default.Close, contentDescription = "Stop", tint = androidx.compose.ui.graphics.Color.Red) }
                                                } else {
                                                    // Send always enabled when text non-blank. Downstream handles "not ready" gracefully.
                                                    val canSend = userInputFieldText.isNotBlank()
                                                    IconButton(
                                                        onClick = {
                                                            if (canSend) {
                                                                val text = userInputFieldText
                                                                this@MainActivity.isInGeneration.value = true
                                                                sendText(text, currentRagMode)
                                                                userInputFieldText = ""
                                                                chatHistoryFocusRequester.requestFocus()
                                                            }
                                                        },
                                                        enabled = canSend,
                                                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (canSend) io.kognis.tactical.ui.theme.RescueAmber else androidx.compose.ui.graphics.Color.Gray) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (trainingActive) "Powered by Gemma 4 E2B · Training" else "Powered by Gemma 4 E2B",
                        color = io.kognis.tactical.ui.theme.RescueAmber,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                // KB ingest: thin non-blocking bar — user can still type (feedback 2.1)
                if (kbProgressState != null) {
                    val (kbCurrent, kbTotal) = kbProgressState!!
                    val kbPct = if (kbTotal > 0) (kbCurrent.toFloat() / kbTotal).coerceIn(0f, 1f) else 0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { kbPct },
                            color = io.kognis.tactical.ui.theme.RescueAmber,
                            trackColor = io.kognis.tactical.ui.theme.MachinedGraphite,
                            modifier = Modifier.weight(1f).height(3.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "KB ${(kbPct * 100).toInt()}%",
                            color = androidx.compose.ui.graphics.Color(0xFF8899AA),
                            fontSize = 10.sp
                        )
                    }
                }
                // Eval progress bar — shown while automated test is running
                if (evalRunning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFF1A1A1A))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (evalTotal > 0) evalProgress.toFloat() / evalTotal else 0f },
                            color = io.kognis.tactical.ui.theme.RescueAmber,
                            trackColor = androidx.compose.ui.graphics.Color.DarkGray,
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                        Text(
                            "Eval $evalProgress/$evalTotal",
                            color = io.kognis.tactical.ui.theme.RescueAmber,
                            fontSize = 10.sp,
                        )
                        androidx.compose.material3.IconButton(
                            onClick = {
                                evalJob?.cancel()
                                evalJob = null
                                this@MainActivity.evalDeferred = null
                                evalRunning = false
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel eval",
                                tint = androidx.compose.ui.graphics.Color.Gray,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // GIS test progress bar
                if (gisRunning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFF0D1A0D))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (gisTotal > 0) gisProgress.toFloat() / gisTotal else 0f },
                            color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                            trackColor = androidx.compose.ui.graphics.Color.DarkGray,
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                        Text(
                            "GIS $gisProgress/$gisTotal",
                            color = androidx.compose.ui.graphics.Color(0xFF66BB6A),
                            fontSize = 10.sp,
                        )
                        androidx.compose.material3.IconButton(
                            onClick = {
                                gisJob?.cancel()
                                gisJob = null
                                this@MainActivity.evalDeferred = null
                                gisRunning = false
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel GIS test",
                                tint = androidx.compose.ui.graphics.Color.Gray,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                if (!isReady) {
                    val isDownloading = statusText.contains("Descargando") || statusText.contains("Downloading")
                    if (isDownloading) {
                        // Model download: keep full blocking panel (user likes this style for downloads)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(androidx.compose.ui.graphics.Color(0xFF1B2838), RoundedCornerShape(8.dp)).padding(8.dp)
                        ) {
                            Text(statusText.substringBefore("(").trim(), color = io.kognis.tactical.ui.theme.SilicaWhite, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            val downloadPercent = Regex("(\\d+)%").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (downloadPercent ?: 0) / 100f },
                                color = io.kognis.tactical.ui.theme.RescueAmber,
                                trackColor = io.kognis.tactical.ui.theme.MachinedGraphite,
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                            )
                        }
                    } else if (statusText.isNotBlank() && !statusText.contains("Core Operativo") && kbProgressState == null) {
                        Text(statusText, color = io.kognis.tactical.ui.theme.SilicaWhite, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), textAlign = TextAlign.Center)
                    }
                }

                // Inline collapsible map panel — auto-expands when markers present
                androidx.compose.animation.AnimatedVisibility(
                    visible = showInlineMap,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit  = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .clipToBounds()
                    ) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        io.kognis.tactical.core.map.MapFallbackViewMulti(
                            markers = io.kognis.tactical.core.map.MarkerStore.markers,
                            modifier = Modifier.fillMaxSize(),
                            onClear = { io.kognis.tactical.core.map.MarkerStore.clear() },
                            onMarkMyLocation = { lat, lon ->
                                io.kognis.tactical.core.map.MarkerStore.add(
                                    io.kognis.tactical.core.map.MarkerStore.Entry(
                                        location = io.kognis.tactical.core.map.LocationJsonExtractor.Location(lat, lon, "My Location"),
                                        source = io.kognis.tactical.core.map.MarkerStore.Source.OSMDROID,
                                        cotType = io.kognis.tactical.core.map.MarkerStore.CotType.COMMAND,
                                    )
                                )
                            },
                        )
                        // Top-right button row
                        Row(
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val hasMarkers = io.kognis.tactical.core.map.MarkerStore.markers.isNotEmpty()
                            if (hasMarkers) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        val result = io.kognis.tactical.core.map.JsonMarkerExporter.export(ctx)
                                        if (result != null) ctx.startActivity(io.kognis.tactical.core.map.JsonMarkerExporter.shareIntent(ctx, result))
                                    },
                                    modifier = Modifier.background(androidx.compose.ui.graphics.Color(0xDD000000), RoundedCornerShape(8.dp)),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, tint = io.kognis.tactical.ui.theme.RescueAmber, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export", color = io.kognis.tactical.ui.theme.RescueAmber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { showMapScreen = true },
                                modifier = Modifier.background(androidx.compose.ui.graphics.Color(0xDD000000), RoundedCornerShape(8.dp)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, tint = io.kognis.tactical.ui.theme.RescueAmber, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Full map", color = io.kognis.tactical.ui.theme.RescueAmber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .focusRequester(chatHistoryFocusRequester)
                        .focusable(true)
                ) {
                    ChatHistory(
                        history = chatMessageHistory,
                        onFeedback = { index, rating ->
                            updateMessageFeedback(index, rating)
                            saveFeedbackForMessage(index, rating)
                        }
                    )
                }
            }
        }
        } // Close ModalNavigationDrawer
    }

    @androidx.annotation.VisibleForTesting
    private var querySentMs: Long = 0L

    internal fun sendText(input: String, ragMode: String = "Auto", isEval: Boolean = false) {
        // Dismiss prior training cards when a new user turn starts.
        runOnUiThread { dismissTrainingCards?.invoke() }
        var effectiveRagMode = ragMode
        if (!isEval) {
            val prep = io.kognis.tactical.core.map.QueryPreprocessor.preprocess(input)
            when {
                prep.markerIntent != null -> {
                    // Pre-place marker with EXACT user coords — bypass LLM coord reproduction entirely
                    val m = prep.markerIntent
                    val cotType = runCatching { io.kognis.tactical.core.map.MarkerStore.CotType.valueOf(m.cotTypeName) }
                        .getOrDefault(io.kognis.tactical.core.map.MarkerStore.CotType.COMMAND)
                    io.kognis.tactical.core.map.MarkerStore.add(
                        io.kognis.tactical.core.map.MarkerStore.Entry(
                            location = io.kognis.tactical.core.map.LocationJsonExtractor.Location(
                                lat = m.lat, lon = m.lon, label = m.label, markerType = m.cotTypeName
                            ),
                            source = io.kognis.tactical.core.map.MarkerStore.Source.OSMDROID,
                            cotType = cotType,
                        )
                    )
                    // Tell LLM not to emit LOCATION_JSON (marker already placed correctly)
                    effectiveRagMode = "NoMap"
                }
                prep.gpsIntent != null -> {
                    // GPS marking: use device location
                    val gps = lastKnownLatLon()
                    if (gps != null) {
                        val g = prep.gpsIntent
                        val cotType = runCatching { io.kognis.tactical.core.map.MarkerStore.CotType.valueOf(g.cotTypeName) }
                            .getOrDefault(io.kognis.tactical.core.map.MarkerStore.CotType.COMMAND)
                        io.kognis.tactical.core.map.MarkerStore.add(
                            io.kognis.tactical.core.map.MarkerStore.Entry(
                                location = io.kognis.tactical.core.map.LocationJsonExtractor.Location(
                                    lat = gps.first, lon = gps.second, label = g.label, markerType = g.cotTypeName
                                ),
                                source = io.kognis.tactical.core.map.MarkerStore.Source.OSMDROID,
                                cotType = cotType,
                            )
                        )
                        effectiveRagMode = "NoMap"
                    }
                }
            }
        }
        appendUserMessage(input)
        isInGeneration.value = true
        pendingRagInfo = null
        querySentMs = System.currentTimeMillis()
        val query = if (isEval) input else buildQueryWithMarkers(input)
        fieldCore?.sendQuery(query, effectiveRagMode)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownLatLon(): Pair<Double, Double>? {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        return listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }

    private fun buildQueryWithMarkers(query: String): String {
        val markers = io.kognis.tactical.core.map.MarkerStore.markers
        val gps = lastKnownLatLon()
        if (markers.isEmpty() && gps == null) return query
        val capped = markers.take(10)
        val sb = StringBuilder()
        if (gps != null) {
            sb.append("[DEVICE GPS: lat=${"%.5f".format(gps.first)} lon=${"%.5f".format(gps.second)}]\n")
        }
        if (capped.isNotEmpty()) {
            sb.append("[SESSION MARKERS — ${markers.size}]\n")
            capped.forEachIndexed { idx, entry ->
                val lat = "%.5f".format(entry.location.lat)
                val lon = "%.5f".format(entry.location.lon)
                val distFromGps = if (gps != null) {
                    " (~${io.kognis.tactical.core.map.GeoUtils.distanceLabel(gps.first, gps.second, entry.location.lat, entry.location.lon)} from device)"
                } else ""
                sb.append("${idx + 1}. [${entry.cotType.symbol}] ${entry.location.label}: lat=$lat lon=$lon$distFromGps\n")
            }
            if (markers.size > 10) sb.append("… +${markers.size - 10} more\n")
        }
        sb.append("\n")
        sb.append(query)
        return sb.toString()
    }

    private fun sendToolText(toolText: String) {
        appendToolMessage(toolText)
    }

    /**
     * Load displayed chat history from the instance state bundle.
     */
    private fun loadState(state: Bundle) {
        // State loading delegated to FieldAssistantService in the future
    }

    /**
     * Helper to save chat history to SharedPreferences
     */
    private fun saveChatHistory(history: List<ChatMessageDisplayItem>) {
        try {
            val truncatedHistory = if (history.size > 50) history.takeLast(50) else history
            val jsonStr = json.encodeToString(truncatedHistory)
            val prefs = SecurePrefs.get(this)
            prefs.edit().putString(currentChatId, jsonStr).apply()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error saving chat history", e)
        }
    }

    /**
     * Append a user message to the history
     */
    private fun appendUserMessage(content: String) {
        val chatMessageHistoryValue = chatMessageHistory.value ?: listOf()
        // Auto-title: on first user message of a session, set title from the message text
        if (chatMessageHistoryValue.isEmpty()) {
            val autoTitle = if (content.length > 36) content.take(33) + "…" else content
            saveTitleForSession(currentChatId, autoTitle)
            refreshSessions()
        }
        val newMessage = ChatMessageDisplayItem(role = "USER", text = content)
        val updatedList = chatMessageHistoryValue + newMessage
        chatMessageHistory.value = updatedList
        saveChatHistory(updatedList)
    }

    /**
     * Append a system message to the history
     */
    private fun appendToolMessage(content: String) {
        val chatMessageHistoryValue = chatMessageHistory.value ?: listOf()
        val newMessage = ChatMessageDisplayItem(role = "SYSTEM", text = content)
        val updatedList = chatMessageHistoryValue + newMessage
        chatMessageHistory.value = updatedList
        saveChatHistory(updatedList)
    }

    /**
     * If the last message is not an assistant message, insert a new one. Otherwise, update that
     * assistant message.
     */
    private fun appendToLastAssistantMessage(delta: String) {
        val history = (chatMessageHistory.value ?: listOf()).toMutableList()
        val last = history.lastOrNull()
        if (last?.role == "ASSISTANT") {
            history[history.lastIndex] = last.copy(text = last.text + delta)
        } else {
            history.add(ChatMessageDisplayItem(role = "ASSISTANT", text = delta, ragInfo = pendingRagInfo))
        }
        chatMessageHistory.value = history
    }

    private fun updateLastAssistantMessage(content: String, reasoning: String?, ragInfo: String? = null) {
        val chatMessageHistoryValue = chatMessageHistory.value
        val newChatMessageHistory = (chatMessageHistoryValue ?: listOf()).toMutableList()
        // Preserve ragInfo from existing message if not provided (streaming updates)
        val existingRagInfo = if (ragInfo == null && newChatMessageHistory.lastOrNull()?.role == "ASSISTANT") {
            newChatMessageHistory.lastOrNull()?.ragInfo
        } else ragInfo
        if (newChatMessageHistory.lastOrNull()?.role == "ASSISTANT") {
            newChatMessageHistory.removeAt(newChatMessageHistory.lastIndex)
        }
        newChatMessageHistory.add(
            ChatMessageDisplayItem(
                role = "ASSISTANT",
                text = content,
                reasoning = reasoning,
                ragInfo = existingRagInfo,
            ),
        )
        chatMessageHistory.value = newChatMessageHistory
        saveChatHistory(newChatMessageHistory)
    }

    companion object {
        const val MODEL_NAME = "Gemma4-E2B"
        const val QUANTIZATION_SLUG = "Q4_K_M"
    }
}
