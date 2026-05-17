package io.kognis.tactical.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only panel rendering the learner-model JSON returned by the AIDL
 * `getLearnerModelJson()`. Used from the gear menu → "Learner progress".
 */
@Composable
fun LearnerPanel(modelJson: String) {
    val amber = io.kognis.tactical.ui.theme.RescueAmber
    val parsed = runCatching { JSONObject(modelJson) }.getOrNull()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("LEARNER MODEL", color = amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (parsed == null) {
            Text("No active learner state.", color = Color.Gray)
            return@Column
        }
        val sid = parsed.optLong("active_session_id", 0L)
        Text(
            if (sid == 0L) "No active session — start one from the gear menu." else "Active session: $sid",
            color = if (sid == 0L) Color.Gray else Color.White,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("Curriculum: ${parsed.optString("curriculum_id", "—")}", color = Color.LightGray, fontSize = 11.sp)
        Text("Language: ${parsed.optString("language", "—")}", color = Color.LightGray, fontSize = 11.sp)

        Spacer(Modifier.height(12.dp))
        SectionHeader("Top mastery", amber)
        renderMasteryArray(parsed.optJSONArray("top_mastery"))

        Spacer(Modifier.height(8.dp))
        SectionHeader("Needs review", amber)
        renderMasteryArray(parsed.optJSONArray("low_mastery"))

        Spacer(Modifier.height(8.dp))
        SectionHeader("Preferences", amber)
        val prefs = parsed.optJSONObject("prefs") ?: JSONObject()
        prefs.keys().forEach { k ->
            Text("$k: ${prefs.optString(k, "")}", color = Color.LightGray, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Recent skill invocations", amber)
        val inv = parsed.optJSONArray("recent_invocations") ?: JSONArray()
        if (inv.length() == 0) {
            Text("(none yet)", color = Color.Gray, fontSize = 11.sp)
        } else {
            for (i in 0 until inv.length()) {
                val o = inv.optJSONObject(i) ?: continue
                Text("• ${o.optString("skill")}", color = Color.LightGray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, amber: Color) {
    Text(label, color = amber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier
             .fillMaxWidth()
             .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
             .padding(horizontal = 6.dp, vertical = 3.dp))
}

@Composable
private fun renderMasteryArray(arr: JSONArray?) {
    if (arr == null || arr.length() == 0) {
        Text("(no data)", color = Color.Gray, fontSize = 11.sp)
        return
    }
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val topic = o.optString("topic", "?")
        val score = o.optDouble("score", 0.0)
        val pct = (score * 100).toInt()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(topic, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$pct%", color = if (score >= 0.7) Color(0xFF66BB6A) else Color(0xFFFFC107), fontSize = 12.sp)
        }
    }
}
