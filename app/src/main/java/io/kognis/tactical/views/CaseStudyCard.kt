package io.kognis.tactical.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a case study fetched in response to a `show_example` skill call.
 *
 * Pure presentation. Parent picks the case study from `CurriculumModule.caseStudiesJson`
 * (or a RAG hit) and supplies the text + key_points list.
 */
@Composable
fun CaseStudyCard(
    topic: String,
    text: String,
    keyPoints: List<String>,
) {
    val amber = io.kognis.tactical.ui.theme.RescueAmber
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .border(1.dp, amber, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = amber, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
            Text("CASE STUDY", color = amber, fontSize = 10.sp,
                 fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text(topic, color = Color.LightGray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (keyPoints.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Key points:", color = amber, fontSize = 11.sp,
                 fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            keyPoints.forEach { kp ->
                Text("• $kp", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
        }
    }
}
