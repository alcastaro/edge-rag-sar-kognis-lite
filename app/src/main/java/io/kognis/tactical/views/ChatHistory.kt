package io.kognis.tactical.views

import io.kognis.tactical.models.ChatMessageDisplayItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun ChatHistory(
    history: List<ChatMessageDisplayItem>,
    modifier: Modifier = Modifier,
    onFeedback: ((index: Int, rating: String) -> Unit)? = null,
) {
    val scrollState = rememberLazyListState()
    val messageCount = history.size
    // Track streaming length of the last message so the scroll can keep up as
    // tokens arrive — but only when the user is already near the bottom. If
    // they scrolled up to re-read something, leave them there.
    val lastMessageText = history.lastOrNull()?.text ?: ""
    LaunchedEffect(messageCount, lastMessageText) {
        if (messageCount == 0) return@LaunchedEffect
        val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val userIsNearBottom = lastVisible >= messageCount - 2
        if (userIsNearBottom) {
            scrollState.scrollToItem(messageCount - 1)
        }
    }
    LazyColumn(modifier = modifier, state = scrollState) {
        itemsIndexed(history) { index, message ->
            Box(modifier = Modifier.padding(vertical = 8.dp)) {
                when (message.role) {
                    "USER" -> {
                        UserMessage(message.text)
                    }

                    "ASSISTANT" -> {
                        AssistantMessage(
                            text = message.text,
                            reasoningText = message.reasoning,
                            ragInfo = message.ragInfo,
                            generationStats = message.generationStats,
                            cotAuditJson = message.cotAuditJson,
                            feedbackRating = message.feedbackRating,
                            onFeedback = onFeedback?.let { cb -> { rating -> cb(index, rating) } }
                        )
                    }

                    "TOOL", "SYSTEM" -> {
                        ToolMessage(message.text)
                    }

                    else -> {}
                }
            }
        }
        item {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Preview
@Composable
fun ChatHistoryPreview() {
    ChatHistory(
        listOf(
            ChatMessageDisplayItem("USER", text = "Hello robot!", reasoning = null),
            ChatMessageDisplayItem("ASSISTANT", text = "Hello user!", reasoning = "I should be friendly"),
            ChatMessageDisplayItem("USER", text = "Are you really a robot or a human?", reasoning = null),
            ChatMessageDisplayItem("ASSISTANT", text = "I am a language model.", reasoning = "I should be accurate"),
        ),
    )
}

