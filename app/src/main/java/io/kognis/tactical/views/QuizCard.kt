package io.kognis.tactical.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Multiple-choice quiz card. Animates in, color-coded by correctness, dismissible. */
@Composable
fun QuizCard(
    topic: String,
    difficulty: String,
    question: String,
    options: List<String>,
    correctIndex: Int,
    explanation: String,
    onAnswer: (correct: Boolean, topic: String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val accent = Color(0xFFFFC107)  // amber — quiz signature color
    val bgCard = Color(0xFF14151A)

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(bgCard, RoundedCornerShape(14.dp)),
        ) {
            // Left accent stripe
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxWidth()
                    .background(accent, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)),
            ) {}
            Column(modifier = Modifier.padding(start = 4.dp).padding(12.dp).fillMaxWidth()) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Quiz, null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("QUIZ · ${difficulty.uppercase()}", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(topic, color = Color.LightGray, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Gray, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(question, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                options.forEachIndexed { idx, opt ->
                    val isSel = selected == idx
                    val isCorrect = selected != null && idx == correctIndex
                    val isWrong = isSel && idx != correctIndex
                    val rowBg = when {
                        isCorrect -> Color(0xFF1F3A1F)
                        isWrong -> Color(0xFF3A1F1F)
                        else -> Color(0xFF1F2128)
                    }
                    val borderColor = when {
                        isCorrect -> Color(0xFF66BB6A)
                        isWrong -> Color(0xFFEF5350)
                        else -> Color.Transparent
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(rowBg, RoundedCornerShape(10.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                            .clickable(enabled = selected == null) {
                                selected = idx
                                onAnswer(idx == correctIndex, topic)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(accent.copy(alpha = if (isSel || selected == null) 0.20f else 0.08f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(('A' + idx).toString(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(opt, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (isCorrect) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                        if (isWrong) Icon(Icons.Default.Cancel, null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                    }
                }
                if (selected != null && explanation.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0C10), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text("ⓘ", color = accent, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(explanation, color = Color(0xFFD0D0D0), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
