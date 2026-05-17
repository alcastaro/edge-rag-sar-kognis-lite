package io.kognis.tactical.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kognis.tactical.core.PerformanceLogger
import io.kognis.tactical.ui.theme.MachinedGraphite
import io.kognis.tactical.ui.theme.RescueAmber
import io.kognis.tactical.ui.theme.SilicaWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceDashboard(onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(PerformanceLogger.entries().reversed()) }
    val recent = entries.take(10)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MachinedGraphite) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚡ PERFORMANCE",
                    color = RescueAmber,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = {
                    PerformanceLogger.clear()
                    entries = emptyList()
                }) {
                    Text("Borrar", color = Color.Red.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text("No queries yet this session.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            val ctx = LocalContext.current
            val batTempC by produceState(initialValue = -1.0) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                        val raw = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10
                        if (raw > 0) value = raw / 10.0
                    }
                }
                ctx.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                awaitDispose { ctx.unregisterReceiver(receiver) }
            }

            // ── Summary ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryCell("Queries", "${entries.size}")
                SummaryCell("Avg TPS", "${"%.1f".format(PerformanceLogger.avgTps())}")
                SummaryCell("Avg Time", "${PerformanceLogger.avgDurationMs()}ms")
                SummaryCell("RAG Hits", "${"%.0f".format(PerformanceLogger.ragHitRate() * 100)}%")
                val coldMs = PerformanceLogger.lastColdStartMs()
                SummaryCell("Cold Start", if (coldMs != null) "${coldMs}ms" else "--")
                val maxT = PerformanceLogger.maxTemp()
                SummaryCell("CPU", if (maxT != null) "${"%.1f".format(maxT)}°C" else "--°C")
                val batText = if (batTempC < 0) "--°C" else "${"%.1f".format(batTempC)}°C"
                SummaryCell("Bat", batText)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "LAST ${recent.size} QUERIES",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── Query history ────────────────────────────────────────────────
            recent.forEachIndexed { idx, entry ->
                if (entry.type == "SYSTEM") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(Color(0xFF0D47A1).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF1976D2).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("⚙️ SYSTEM COLD START", color = Color(0xFF90CAF9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("${entry.durationMs}ms", color = SilicaWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.responseText, color = Color.Gray, fontSize = 10.sp)
                    }
                    return@forEachIndexed
                }

                val borderColor = when {
                    entry.ragActivated -> Color(0xFF1B5E20).copy(alpha = 0.6f)
                    else -> Color.DarkGray
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Model chip
                        Text(
                            entry.model,
                            color = RescueAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(Color(0xFF2A1800), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${entry.durationMs}ms",
                                color = SilicaWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(8.dp))
                            val genSec = if (entry.tokensPerSec > 0) entry.tokens.toDouble() / entry.tokensPerSec else 0.0
                            val overheadSec = (entry.durationMs / 1000.0) - genSec
                            val overheadStr = if (overheadSec > 0.05) " +${"%.1f".format(overheadSec)}s" else ""
                            Text(
                                "${"%.1f".format(entry.tokensPerSec)} t/s$overheadStr",
                                color = Color(0xFF9E9E9E),
                                fontSize = 10.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.queryPreview.ifBlank { "(empty)" },
                        color = SilicaWhite,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    if (entry.chunkTitle.isNotBlank()) {
                        val numChunks = try { org.json.JSONArray(entry.chunksJson).length() } catch (_: Exception) { 0 }
                        val chunksText = if (numChunks > 0) "($numChunks chunks)" else ""
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "📍 ${entry.chunkTitle} $chunksText",
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (entry.responseText.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            entry.responseText.take(120).let { if (entry.responseText.length > 120) "$it…" else it },
                            color = Color(0xFF9E9E9E),
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ragText = if (entry.ragActivated) {
                            val simPct = ((1.0 - entry.ragScore / 2.0) * 100.0).coerceIn(0.0, 100.0)
                            "📚 ${"%.0f".format(simPct)}% sim"
                        } else {
                            "💬 No RAG"
                        }
                        Text(
                            ragText,
                            color = if (entry.ragActivated) Color(0xFF81C784) else Color(0xFF757575),
                            fontSize = 10.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            entry.tempCelsius?.let { t ->
                                val tempColor = when {
                                    t < 38.0 -> Color(0xFF4CAF50)
                                    t < 45.0 -> RescueAmber
                                    else -> Color.Red
                                }
                                Text(
                                    "${"%.1f".format(t)}°C",
                                    color = tempColor,
                                    fontSize = 10.sp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                formatTs(entry.tsMs),
                                color = Color(0xFF616161),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = SilicaWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.Gray, fontSize = 9.sp)
    }
}

private fun formatTs(tsMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tsMs))
