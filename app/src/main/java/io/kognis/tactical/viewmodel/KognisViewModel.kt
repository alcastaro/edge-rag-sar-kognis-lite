package io.kognis.tactical.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.kognis.tactical.core.SecurePrefs
import io.kognis.tactical.models.ChatMessageDisplayItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * CQ-01 extract: chat state + persistence logic, isolated from Activity lifecycle.
 * Enables unit testing of chat history management without needing a real Activity.
 */
class KognisViewModel : ViewModel() {

    private val TAG = "KognisVM"
    private val json = Json { ignoreUnknownKeys = true }

    // ── Observable state ──────────────────────────────────────────────────

    val chatMessageHistory = MutableLiveData<List<ChatMessageDisplayItem>>(emptyList())
    val isInGeneration = MutableLiveData(false)
    val coreStatus = MutableLiveData("Conectando Core...")
    val isModelReady = MutableLiveData(false)
    val embeddingMode = MutableLiveData("Iniciando...")
    val activeCotMarkers = MutableLiveData<List<String>>(emptyList())
    val kbUpdateResult = MutableLiveData<String?>(null)
    val sessionsList = MutableLiveData<List<String>>(emptyList())

    var currentChatId: String = "session_${System.currentTimeMillis()}"
        private set

    // Holds RAG metadata between onRagMetadata() and first token
    var pendingRagInfo: String? = null

    // ── Session management ────────────────────────────────────────────────

    fun initSessions(context: Context) {
        sessionsList.value = getSavedSessions(context)
    }

    fun getSavedSessions(context: Context): List<String> {
        val prefs = SecurePrefs.get(context)
        return prefs.all.keys
            .filter { it.startsWith("session_") || it == "chat_history" }
            .sortedDescending()
    }

    fun loadChatSession(context: Context, sessionId: String) {
        currentChatId = sessionId
        val prefs = SecurePrefs.get(context)
        val historyJson = prefs.getString(sessionId, null)
        chatMessageHistory.value = if (historyJson != null) {
            try { json.decodeFromString(historyJson) } catch (_: Exception) { emptyList() }
        } else emptyList()
    }

    fun newChat(context: Context) {
        currentChatId = "session_${System.currentTimeMillis()}"
        chatMessageHistory.value = emptyList()
        refreshSessions(context)
    }

    fun refreshSessions(context: Context) {
        sessionsList.value = getSavedSessions(context)
    }

    fun titleForSession(context: Context, sessionId: String): String {
        return SecurePrefs.get(context).getString("title_$sessionId", sessionId) ?: sessionId
    }

    fun saveTitleForSession(context: Context, sessionId: String, title: String) {
        SecurePrefs.get(context).edit().putString("title_$sessionId", title).apply()
    }

    fun deleteSession(context: Context, sessionId: String) {
        val prefs = SecurePrefs.get(context)
        prefs.edit()
            .remove(sessionId)
            .remove("title_$sessionId")
            .apply()
        if (sessionId == currentChatId) newChat(context)
        refreshSessions(context)
    }

    // ── Chat history mutations ────────────────────────────────────────────

    fun appendUserMessage(context: Context, content: String) {
        val current = chatMessageHistory.value ?: emptyList()
        if (current.isEmpty()) {
            val autoTitle = if (content.length > 36) content.take(33) + "…" else content
            saveTitleForSession(context, currentChatId, autoTitle)
            refreshSessions(context)
        }
        val updated = current + ChatMessageDisplayItem(role = "USER", text = content)
        chatMessageHistory.value = updated
        saveChatHistory(context, updated)
    }

    fun appendToolMessage(context: Context, content: String) {
        val updated = (chatMessageHistory.value ?: emptyList()) +
            ChatMessageDisplayItem(role = "SYSTEM", text = content)
        chatMessageHistory.value = updated
        saveChatHistory(context, updated)
    }

    fun appendToLastAssistantMessage(context: Context, delta: String) {
        val history = (chatMessageHistory.value ?: emptyList()).toMutableList()
        val last = history.lastOrNull()
        if (last?.role == "ASSISTANT") {
            history[history.lastIndex] = last.copy(text = last.text + delta)
        } else {
            history.add(ChatMessageDisplayItem(role = "ASSISTANT", text = delta, ragInfo = pendingRagInfo))
        }
        chatMessageHistory.value = history
    }

    fun updateLastAssistantMessage(context: Context, content: String, reasoning: String?, ragInfo: String? = null) {
        val history = (chatMessageHistory.value ?: emptyList()).toMutableList()
        val existingRagInfo = if (ragInfo == null && history.lastOrNull()?.role == "ASSISTANT")
            history.lastOrNull()?.ragInfo else ragInfo
        if (history.lastOrNull()?.role == "ASSISTANT") history.removeAt(history.lastIndex)
        history.add(ChatMessageDisplayItem(role = "ASSISTANT", text = content, reasoning = reasoning, ragInfo = existingRagInfo))
        chatMessageHistory.value = history
        saveChatHistory(context, history)
    }

    fun updateLastAssistantMessageStats(context: Context, statsJson: String) {
        val history = chatMessageHistory.value?.toMutableList() ?: return
        val idx = history.indexOfLast { it.role == "ASSISTANT" }
        if (idx != -1) {
            history[idx] = history[idx].copy(generationStats = statsJson)
            chatMessageHistory.postValue(history)
            saveChatHistory(context, history)
        }
    }

    fun updateLastAssistantMessageCotAudit(context: Context, cotAuditJson: String) {
        val history = chatMessageHistory.value?.toMutableList() ?: return
        val idx = history.indexOfLast { it.role == "ASSISTANT" }
        if (idx != -1) {
            history[idx] = history[idx].copy(cotAuditJson = cotAuditJson)
            chatMessageHistory.postValue(history)
            saveChatHistory(context, history)
        }
    }

    fun updateMessageFeedback(context: Context, messageIndex: Int, rating: String) {
        val history = chatMessageHistory.value?.toMutableList() ?: return
        val msg = history.getOrNull(messageIndex) ?: return
        history[messageIndex] = msg.copy(feedbackRating = rating)
        chatMessageHistory.value = history
        saveChatHistory(context, history)
    }

    // ── Persistence ───────────────────────────────────────────────────────

    fun saveChatHistory(context: Context, history: List<ChatMessageDisplayItem>) {
        try {
            val truncated = if (history.size > 50) history.takeLast(50) else history
            SecurePrefs.get(context).edit()
                .putString(currentChatId, json.encodeToString(truncated))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat history", e)
        }
    }

    // ── CoT markers ───────────────────────────────────────────────────────

    fun updateCotMarker(cotJson: String) {
        val current = activeCotMarkers.value?.toMutableList() ?: mutableListOf()
        val uid = Regex("\"uid\"\\s*:\\s*\"([^\"]+)\"").find(cotJson)?.groupValues?.get(1) ?: ""
        val updated = current.filter { existing ->
            Regex("\"uid\"\\s*:\\s*\"([^\"]+)\"").find(existing)?.groupValues?.get(1) != uid
        }.toMutableList()
        updated.add(0, cotJson)
        if (updated.size > 50) updated.subList(50, updated.size).clear()
        activeCotMarkers.value = updated
    }
}
