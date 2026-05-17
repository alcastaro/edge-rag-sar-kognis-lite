package io.kognis.tactical.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a `LearningSkill.QuizUser` invocation as a multi-choice card.
 *
 * Lifecycle: parent supplies the question + options + correctIndex + explanation
 * (extracted from the SKILL: JSON). User taps an option → onAnswer callback fires
 * with (correct: Boolean, topic: String). Card then shows feedback + explanation.
 */
@Composable
fun QuizCard(
    topic: String,
    difficulty: String,
    question: String,
    options: List<String>,
    correctIndex: Int,
    explanation: String,
    onAnswer: (correct: Boolean, topic: String) -> Unit,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val amber = io.kognis.tactical.ui.theme.RescueAmber
    val borderColor = if (selected == null) amber else if (selected == correctIndex) Color(0xFF66BB6A) else Color(0xFFEF5350)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("QUIZ", color = amber, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text("$topic · $difficulty", color = Color.LightGray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(question, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { idx, opt ->
            val isSel = selected == idx
            val correct = selected != null && idx == correctIndex
            val wrong = isSel && idx != correctIndex
            val bg = when {
                correct -> Color(0xFF1F3A1F)
                wrong -> Color(0xFF3A1F1F)
                else -> Color(0xFF2A2A2A)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(bg, RoundedCornerShape(8.dp))
                    .clickable(enabled = selected == null) {
                        selected = idx
                        onAnswer(idx == correctIndex, topic)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(('A' + idx).toString(), color = amber, fontSize = 12.sp,
                     fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                Text(opt, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (correct) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                if (wrong) Icon(Icons.Default.Cancel, null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
            }
        }
        if (selected != null && explanation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                explanation,
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp))
                    .padding(8.dp),
            )
        }
    }
}
