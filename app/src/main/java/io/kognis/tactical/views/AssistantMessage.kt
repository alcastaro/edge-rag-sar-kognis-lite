package io.kognis.tactical.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import io.kognis.tactical.R
import io.kognis.tactical.core.map.LocationJsonExtractor
import io.kognis.tactical.core.map.MapFallbackViewMulti
import io.kognis.tactical.core.map.MarkerStore
import io.kognis.tactical.core.map.OsmAndBridge
import io.kognis.tactical.core.PerformanceLogger
import org.json.JSONObject

@Composable
fun AssistantMessage(
    text: String,
    reasoningText: String?,
    ragInfo: String? = null,
    generationStats: String? = null,
    cotAuditJson: String? = null,
    feedbackRating: String? = null,
    onFeedback: ((String) -> Unit)? = null,
) {
    val reasoningText = reasoningText?.trim()
    val clipboardManager: androidx.compose.ui.platform.ClipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    var showRagSheet by remember { mutableStateOf(false) }
    var showCotSheet by remember { mutableStateOf(false) }
    var showMapSheet by remember { mutableStateOf(false) }
    var showMapFallback by remember { mutableStateOf(false) }
    var localRating by remember(feedbackRating) { mutableStateOf(feedbackRating) }

    // Parse LOCATION_JSON tag from the LLM response. If present, the tag is
    // stripped from the displayed text (kept clean) and a "Ver en mapa" button
    // surfaces below the RAG/CoT badges.
    val mapLocation = remember(text) { LocationJsonExtractor.extract(text) }
    val displayText = remember(text, mapLocation) {
        if (mapLocation != null) LocationJsonExtractor.stripTag(text) else text
    }

    val cotData = remember(cotAuditJson) {
        if (cotAuditJson != null) {
            try {
                val json = JSONObject(cotAuditJson)
                val arr = json.optJSONArray("entities")
                val entities = if (arr != null) {
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        CotEntityDisplay(
                            uid = e.optString("uid", ""),
                            cotCode = e.optString("cotCode", ""),
                            typeLabel = e.optString("typeLabel", ""),
                            lat = e.optDouble("lat", 0.0),
                            lon = e.optDouble("lon", 0.0),
                            detectionSource = e.optString("detectionSource", ""),
                            sent = e.optBoolean("sent", true),
                            cotXml = e.optString("cotXml", "")
                        )
                    }
                } else emptyList()
                val gpsAvailable = json.optBoolean("gpsAvailable", false)
                CotDisplayData(
                    count = json.optInt("count", 0),
                    destination = json.optString("destination", ""),
                    transport = json.optString("transport", ""),
                    gpsAvailable = gpsAvailable,
                    deviceLat = if (gpsAvailable) json.optDouble("deviceLat") else null,
                    deviceLon = if (gpsAvailable) json.optDouble("deviceLon") else null,
                    entities = entities
                )
            } catch (e: Exception) { null }
        } else null
    }

    // Parse RAG metadata
    val ragData = remember(ragInfo) {
        if (ragInfo != null) {
            try {
                val json = JSONObject(ragInfo)
                val chunksArray = json.optJSONArray("chunks")
                val allChunks: List<RagChunkDisplay> = if (chunksArray != null) {
                    (0 until chunksArray.length()).map { i ->
                        val c = chunksArray.getJSONObject(i)
                        RagChunkDisplay(
                            title = c.optString("title", ""),
                            content = c.optString("content", ""),
                            score = c.optDouble("score", -1.0),
                            sourcePage = c.optString("source_page", "").ifBlank { null }
                        )
                    }
                } else emptyList()
                RagDisplayData(
                    activated = json.optBoolean("ragActivated", false),
                    score = json.optDouble("score", -1.0),
                    threshold = json.optDouble("threshold", 0.4),
                    chunkTitle = json.optString("chunkTitle", ""),
                    chunkContent = json.optString("chunkContent", ""),
                    embeddingMode = json.optString("embeddingMode", "?"),
                    allChunks = allChunks
                )
            } catch (e: Exception) { null }
        } else null
    }

    Row(modifier = Modifier.padding(start = 12.dp, end = 32.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(1.0f).testTag("AssistantMessageView"), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Color(0xFF1E1E1E), shape = androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.8f), shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚡",
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.Start) {
            if (!reasoningText.isNullOrEmpty()) {
                Text(text = reasoningText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(4.dp))
            }
            MarkdownText(text = displayText, modifier = Modifier.testTag("AssistantMessageViewText"), color = io.kognis.tactical.ui.theme.SilicaWhite)

            // "Ver en mapa" button — appears when the LLM emitted a LOCATION_JSON tag.
            // Opens a bottom sheet letting the user pick osmdroid / OsmAnd / Google Maps.
            if (mapLocation != null) {
                Spacer(modifier = Modifier.height(6.dp))
                MapButton(location = mapLocation, onClick = { showMapSheet = true })
            }

            // RAG audit badge
            if (ragData != null) {
                Spacer(modifier = Modifier.height(6.dp))
                RagBadge(ragData = ragData, onClick = { showRagSheet = true })
            }

            // Fase 7a: CoT audit badge (clickable)
            if (cotData != null) {
                Spacer(modifier = Modifier.height(4.dp))
                CotBadge(cotData = cotData, onClick = { showCotSheet = true })
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (generationStats != null) {
                    val stats = try {
                        val statsObj = JSONObject(generationStats)
                        val tokens = statsObj.optInt("tokens", 0)
                        val tps = statsObj.optDouble("tps", 0.0)
                        val overheadMs = statsObj.optLong("overhead_ms", 0L)
                        val overheadStr = if (overheadMs > 0) " +${"%.1f".format(overheadMs / 1000.0)}s" else ""
                        "$tokens tok · ${"%.1f".format(tps)} t/s$overheadStr"
                    } catch (e: Exception) {
                        null
                    }
                    if (stats != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stats,
                            color = Color.DarkGray,
                            fontSize = 10.sp,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (onFeedback != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "👍",
                        fontSize = 14.sp,
                        color = if (localRating == "up") Color(0xFF81C784) else Color.Gray.copy(alpha = 0.4f),
                        modifier = Modifier
                            .clickable {
                                if (localRating != "up") {
                                    localRating = "up"
                                    onFeedback("up")
                                }
                            }
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "👎",
                        fontSize = 14.sp,
                        color = if (localRating == "down") Color(0xFFEF5350) else Color.Gray.copy(alpha = 0.4f),
                        modifier = Modifier
                            .clickable {
                                if (localRating != "down") {
                                    localRating = "down"
                                    onFeedback("down")
                                }
                            }
                            .padding(4.dp)
                    )
                }
            }
        }
    }

    // RAG Sources BottomSheet
    if (showRagSheet && ragData != null) {
        RagSourcesSheet(ragData = ragData, onDismiss = { showRagSheet = false })
    }

    // CoT Audit BottomSheet
    if (showCotSheet && cotData != null) {
        CotAuditSheet(cotData = cotData, onDismiss = { showCotSheet = false })
    }

    // Map picker bottom sheet — user chooses between in-app map, OsmAnd, or Google Maps.
    if (showMapSheet && mapLocation != null) {
        MapOptionSheet(
            location = mapLocation,
            onDismiss = { showMapSheet = false },
            onAddMarker = { source, cotType ->
                MarkerStore.add(
                    MarkerStore.Entry(location = mapLocation, source = source, queryPreview = "", cotType = cotType)
                )
                PerformanceLogger.addMarkerToLastQuery(
                    lat = mapLocation.lat,
                    lon = mapLocation.lon,
                    label = mapLocation.label,
                    source = source.key,
                )
            },
            onShowInApp = { showMapFallback = true },
        )
    }

    // Full-screen in-app map fallback (all session markers).
    if (showMapFallback && mapLocation != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showMapFallback = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapFallbackViewMulti(
                    markers = MarkerStore.markers,
                    modifier = Modifier.fillMaxSize(),
                    onClear = { MarkerStore.clear() },
                )
            }
        }
    }
}

@Composable
private fun MapButton(location: LocationJsonExtractor.Location, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "📍",
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Ver en mapa · ${location.label}",
            color = io.kognis.tactical.ui.theme.RescueAmber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapOptionSheet(
    location: LocationJsonExtractor.Location,
    onDismiss: () -> Unit,
    onAddMarker: (MarkerStore.Source, MarkerStore.CotType) -> Unit,
    onShowInApp: () -> Unit,
) {
    val context = LocalContext.current
    // Infer marker type from LLM-provided type field, fallback to COMMAND
    val inferredType = runCatching { MarkerStore.CotType.valueOf(location.markerType) }
        .getOrDefault(MarkerStore.CotType.COMMAND)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = io.kognis.tactical.ui.theme.MachinedGraphite,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            Text(
                "📍 ${location.label}",
                color = io.kognis.tactical.ui.theme.RescueAmber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "[${inferredType.symbol}] ${inferredType.label}  ·  ${"%.5f".format(location.lat)}, ${"%.5f".format(location.lon)}",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(bottom = 12.dp))

            // Built-in map (osmdroid — always available)
            MapOptionRow(icon = "🗺️", label = "Built-in map", subtitle = "osmdroid · offline") {
                onAddMarker(MarkerStore.Source.OSMDROID, inferredType)
                onShowInApp()
                onDismiss()
            }

            // OsmAnd
            val osmInstalled = OsmAndBridge.isInstalled(context)
            MapOptionRow(
                icon = "🧭", label = "OsmAnd",
                subtitle = if (osmInstalled) "Open in OsmAnd" else "Not installed",
                enabled = osmInstalled,
            ) {
                val plusInstalled = runCatching { context.packageManager.getPackageInfo("net.osmand.plus", 0); true }.getOrDefault(false)
                OsmAndBridge.openInOsmAnd(context, location)
                onAddMarker(if (plusInstalled) MarkerStore.Source.OSMAND_PLUS else MarkerStore.Source.OSMAND_FREE, inferredType)
                onDismiss()
            }

            // Google Maps
            val gmapsInstalled = OsmAndBridge.isGoogleMapsInstalled(context)
            MapOptionRow(
                icon = "📍", label = "Google Maps",
                subtitle = if (gmapsInstalled) "Open in Google Maps" else "Not installed",
                enabled = gmapsInstalled,
            ) {
                OsmAndBridge.openInGoogleMaps(context, location)
                onAddMarker(MarkerStore.Source.OSMDROID, inferredType)
                onDismiss()
            }
        }
    }
}

@Composable
private fun MapOptionRow(
    icon: String,
    label: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val textColor = if (enabled) io.kognis.tactical.ui.theme.SilicaWhite else Color.DarkGray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(36.dp))
        Column {
            Text(label, color = textColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        if (enabled) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Inline badge showing RAG status. Clickable to open the sources panel.
 */
@Composable
private fun RagBadge(ragData: RagDisplayData, onClick: () -> Unit) {
    val bgColor = if (ragData.activated) {
        Color(0xFF1B5E20).copy(alpha = 0.3f) // dark green
    } else {
        Color(0xFF424242).copy(alpha = 0.3f) // dark gray
    }
    val textColor = if (ragData.activated) {
        Color(0xFF81C784) // green
    } else {
        Color(0xFF9E9E9E) // gray
    }
    val icon = if (ragData.activated) "📚" else "💬"
    
    val scoreLabel = when {
        ragData.activated && ragData.embeddingMode == "ONNX" -> {
            val simPct = ((1.0 - ragData.score / 2.0) * 100.0).coerceIn(0.0, 100.0)
            "📚 ${"%.0f".format(simPct)}% sim"
        }
        ragData.activated && ragData.embeddingMode == "TEXT" ->
            "📚 ${"%.0f".format(ragData.score * 100)}% match"
        else -> stringResource(R.string.rag_off)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = scoreLabel,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        if (ragData.activated) {
            Text(
                text = stringResource(R.string.view_sources),
                color = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CotBadge(cotData: CotDisplayData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pluralStringResource(R.plurals.cot_badge_markers, cotData.count, cotData.count),
            color = Color(0xFF64B5F6),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.cot_view_xml_badge),
            color = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.7f),
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CotAuditSheet(cotData: CotDisplayData, onDismiss: () -> Unit) {
    var expandedEntityIndex by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.cot_audit_title),
                color = io.kognis.tactical.ui.theme.RescueAmber,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            val countColor = if (cotData.count > 0) Color(0xFF81C784) else Color(0xFFEF5350)
            Text(
                pluralStringResource(R.plurals.cot_markers_sent, cotData.count, cotData.count),
                color = countColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            MetricRow(stringResource(R.string.cot_destination), "${cotData.destination} · ${cotData.transport}")
            if (cotData.gpsAvailable && cotData.deviceLat != null && cotData.deviceLon != null) {
                MetricRow("GPS", "✅ lat=${"%.4f".format(cotData.deviceLat)}, lon=${"%.4f".format(cotData.deviceLon)}")
            } else {
                MetricRow("GPS", stringResource(R.string.cot_no_gps))
            }
            if (cotData.entities.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                cotData.entities.forEachIndexed { idx, entity ->
                    val isExpanded = expandedEntityIndex == idx
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (idx > 0) 12.dp else 0.dp)
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${entity.typeLabel} · ${entity.cotCode}",
                                color = io.kognis.tactical.ui.theme.RescueAmber,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (entity.sent) "✓" else stringResource(R.string.cot_failed),
                                color = if (entity.sent) Color(0xFF81C784) else Color(0xFFEF5350),
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        MetricRow("Coords", "lat=${"%.4f".format(entity.lat)}, lon=${"%.4f".format(entity.lon)}")
                        MetricRow("UID", entity.uid.ifBlank { "—" })
                        MetricRow(stringResource(R.string.cot_source), entity.detectionSource)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedEntityIndex = if (isExpanded) null else idx }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isExpanded) stringResource(R.string.cot_hide_xml) else stringResource(R.string.cot_view_xml),
                                color = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                        if (isExpanded && entity.cotXml.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    entity.cotXml,
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * BottomSheet showing ALL retrieved RAG chunks for full audit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RagSourcesSheet(ragData: RagDisplayData, onDismiss: () -> Unit) {
    var expandedChunkIndex by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = io.kognis.tactical.ui.theme.MachinedGraphite
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.rag_audit),
                color = io.kognis.tactical.ui.theme.RescueAmber,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Status badge
            val statusColor = if (ragData.activated) Color(0xFF81C784) else Color(0xFFEF5350)
            val statusText = if (ragData.activated) stringResource(R.string.rag_yes) else stringResource(R.string.rag_no)
            Text(
                statusText,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Metrics
            val isOnnxMode = ragData.embeddingMode == "ONNX"
            MetricRow(stringResource(R.string.search_engine), if (isOnnxMode) stringResource(R.string.engine_onnx) else stringResource(R.string.engine_text))
            if (isOnnxMode) {
                val simPct = ((1.0 - ragData.score / 2.0) * 100.0).coerceIn(0.0, 100.0)
                MetricRow(stringResource(R.string.hnsw_score), "${"%.0f".format(simPct)}% sim (${"%.3f".format(ragData.score)} dist · ${stringResource(R.string.lower_better)})")
                MetricRow(stringResource(R.string.activation_thresh), "< ${"%.2f".format(ragData.threshold)}")
                MetricRow(stringResource(R.string.result), if (ragData.activated) stringResource(R.string.res_act_dist) else stringResource(R.string.res_inact_dist))
            } else {
                MetricRow(stringResource(R.string.keyword_match), "${"%.1f".format(ragData.score * 100)}% — ${stringResource(R.string.higher_better)}")
                MetricRow(stringResource(R.string.activation_thresh), "> ${"%.0f".format(ragData.threshold * 100)}%")
                MetricRow(stringResource(R.string.result), if (ragData.activated) stringResource(R.string.res_act_match) else stringResource(R.string.res_inact_match))
            }

            if (ragData.activated && ragData.allChunks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.retrieved_chunks, ragData.allChunks.size),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ragData.allChunks.forEachIndexed { idx, chunk ->
                    val isExpanded = expandedChunkIndex == idx
                    val scoreLabel = if (isOnnxMode)
                        "${"%.0f".format(((1.0 - chunk.score / 2.0) * 100.0).coerceIn(0.0, 100.0))}% sim"
                    else
                        "${"%.0f".format(chunk.score * 100)}% match"

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (idx > 0) 12.dp else 0.dp)
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        // Score badge
                        Text(
                            scoreLabel,
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        // Full content always visible
                        if (chunk.content.isNotBlank()) {
                            Text(
                                chunk.content,
                                color = io.kognis.tactical.ui.theme.SilicaWhite,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        // Tap to reveal source metadata
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedChunkIndex = if (isExpanded) null else idx }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isExpanded) "▲ Ocultar fuente" else "▼ Ver fuente",
                                color = io.kognis.tactical.ui.theme.RescueAmber.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                        if (isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                MetricRow("Manual", chunk.title)
                                MetricRow("Página", chunk.sourcePage ?: "—")
                                MetricRow("Chunk", "#${idx + 1}")
                            }
                        }
                    }
                }
            } else if (ragData.activated && ragData.chunkTitle.isNotBlank()) {
                // Fallback for older messages serialized without allChunks
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "«${ragData.chunkTitle}»",
                    color = io.kognis.tactical.ui.theme.RescueAmber,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (ragData.chunkContent.isNotBlank()) {
                    Text(
                        ragData.chunkContent,
                        color = io.kognis.tactical.ui.theme.SilicaWhite,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 15,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.no_chunks_match),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            color = Color.LightGray,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.4f).padding(end = 8.dp)
        )
        Text(
            value,
            color = io.kognis.tactical.ui.theme.SilicaWhite,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}

private data class RagChunkDisplay(
    val title: String,
    val content: String,
    val score: Double,
    val sourcePage: String? = null
)

/**
 * Parsed RAG metadata for display.
 */
private data class RagDisplayData(
    val activated: Boolean,
    val score: Double,
    val threshold: Double,
    val chunkTitle: String,
    val chunkContent: String,
    val embeddingMode: String,
    val allChunks: List<RagChunkDisplay> = emptyList()
)

private data class CotDisplayData(
    val count: Int,
    val destination: String,
    val transport: String,
    val gpsAvailable: Boolean,
    val deviceLat: Double?,
    val deviceLon: Double?,
    val entities: List<CotEntityDisplay>
)

private data class CotEntityDisplay(
    val uid: String,
    val cotCode: String,
    val typeLabel: String,
    val lat: Double,
    val lon: Double,
    val detectionSource: String,
    val sent: Boolean,
    val cotXml: String
)

@Preview
@Composable
fun AssistantMessagePreview() {
    AssistantMessage("Hello world!", null)
}

@Preview
@Composable
fun AssistantMessageWithRagPreview() {
    AssistantMessage(
        "Aplica presión directa sobre la herida...",
        null,
        """{"ragActivated":true,"score":0.1234,"threshold":0.40,"chunkTitle":"Control de Hemorragias","chunkContent":"Aplicar presión directa con gasa estéril...","embeddingMode":"ONNX"}"""
    )
}
