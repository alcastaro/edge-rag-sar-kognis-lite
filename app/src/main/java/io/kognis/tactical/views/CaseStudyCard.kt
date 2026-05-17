package io.kognis.tactical.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Case-study card. Color-coded by skill type, animated entrance, dismissible. */
@Composable
fun CaseStudyCard(
    topic: String,
    text: String,
    keyPoints: List<String>,
    onDismiss: () -> Unit = {},
) {
    val accent = Color(0xFF42A5F5)  // blue — case-study signature color
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
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxWidth()
                    .background(accent, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)),
            ) {}
            Column(modifier = Modifier.padding(start = 4.dp).padding(12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Book, null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CASE STUDY", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(topic, color = Color.LightGray, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Gray, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, lineHeight = 19.sp)
                if (keyPoints.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0C10), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Column {
                            Text("KEY POINTS", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(4.dp))
                            keyPoints.forEach { kp ->
                                Row(modifier = Modifier.padding(top = 3.dp)) {
                                    Text("▸ ", color = accent, fontSize = 12.sp)
                                    Text(kp, color = Color(0xFFD0D0D0), fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
