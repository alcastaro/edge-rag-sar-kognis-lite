package io.kognis.tactical.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.kognis.tactical.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * osmdroid-backed map fallback. Two overloads:
 *   - single [LocationJsonExtractor.Location] — used by inline "Ver en mapa"
 *     when OsmAnd Plus/Free aren't installed.
 *   - List<[MarkerStore.Entry]> — used by the session map screen ("Ver
 *     mapa de marcadores") to render all markers dropped so far.
 *
 * MAPNIK raster tile source; ODbL attribution overlaid in bottom-right as
 * required by the OSM tile license.
 *
 * Offline behavior: osmdroid uses its own tile cache (Android/data/<pkg>/
 * files/osmdroid/tiles). If the device has no internet AND no pre-cached
 * tiles for the region, the map renders gray squares with markers still
 * positioned correctly. The hackathon demo should ship a small pre-
 * populated cache for the demo region (script TODO).
 *
 * Zero-Signal compliance: this composable does NOT issue network I/O on
 * its own. osmdroid only fetches when a tile is missing AND network access
 * is permitted by the OS. Airplane mode keeps everything local.
 */
@Composable
fun MapFallbackView(
    location: LocationJsonExtractor.Location,
    modifier: Modifier = Modifier,
) {
    val single = MarkerStore.Entry(
        location = location,
        source = MarkerStore.Source.OSMDROID,
    )
    MapFallbackViewMulti(markers = listOf(single), modifier = modifier)
}

@SuppressLint("MissingPermission")
private fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { it.latitude to it.longitude }
}

@Composable
fun MapFallbackViewMulti(
    markers: List<MarkerStore.Entry>,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onMarkMyLocation: ((lat: Double, lon: Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Live GPS — updates continuously via LocationListener (see DisposableEffect below).
    // Initial seed = last known fix so bounding box / centering work without waiting.
    var liveGps by remember { mutableStateOf(lastKnownLatLon(context)) }
    var isTracking by remember { mutableStateOf(false) }
    val deviceLatLon = liveGps
    // Tap-to-mark — long-press anywhere on the map opens a type picker for the tapped point.
    var pendingTapLatLon by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Tap-on-marker — show delete confirmation for the tapped index.
    var pendingDeleteIdx by remember { mutableStateOf<Int?>(null) }
    // Route mode — when on, draws polyline + shows total path distance.
    var routeMode by remember { mutableStateOf(false) }
    // Total path distance in km (GPS → m1 → m2 → … → mN); recomputed each marker rebuild.
    var totalPathKm by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        Configuration.getInstance()
            .load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }

    // Tap-to-mark: long-press anywhere on the map opens a SAR type picker dialog
    // populated with the tapped coordinate. Tool invocation pattern — the user
    // selects a marker type, the app drops the marker via MarkerStore.
    LaunchedEffect(mapView) {
        val eventsOverlay = org.osmdroid.views.overlay.MapEventsOverlay(
            object : org.osmdroid.events.MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p ?: return false
                    pendingTapLatLon = p.latitude to p.longitude
                    return true
                }
            },
        )
        mapView.overlays.add(0, eventsOverlay)
    }

    // Live GPS subscription — 2s / 5m updates, both providers, auto-cleanup on dispose.
    // Re-subscribes on each lifecycle ON_RESUME so a runtime permission grant (which
    // dispatches the permission-dialog Activity and resumes us afterward) takes effect
    // immediately without requiring the user to reopen the map.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                liveGps = loc.latitude to loc.longitude
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
        }
        var subscribed = false
        fun trySubscribe() {
            if (subscribed) return
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return  // permission not yet granted; will retry on next RESUME
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                runCatching {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, 2000L, 5f, listener)
                        // Seed liveGps from last-known fix if available.
                        @SuppressLint("MissingPermission")
                        val last = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                        if (last != null && liveGps == null) liveGps = last.latitude to last.longitude
                    }
                }
            }
            subscribed = true
        }
        trySubscribe()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) trySubscribe()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { lm.removeUpdates(listener) }
        }
    }

    // Puck icon (blue dot with white ring) — built once.
    val puckIcon = remember(context) { makePuckIcon(context) }

    // Pre-build colored icons once per CotType (avoids recreating on every recomposition).
    val cotIcons = remember(context) {
        MarkerStore.CotType.entries.associateWith { type ->
            makeCotMarkerIcon(context, type.colorArgb, type.symbol)
        }
    }

    // Re-apply markers whenever the list changes (or route mode toggles).
    LaunchedEffect(markers, routeMode) {
        // Preserve puck across marker rebuilds (added in liveGps LaunchedEffect below).
        // Preserve MapEventsOverlay (long-press handler) — it's not a Marker.
        mapView.overlays.removeAll {
            (it is Marker && it.title != PUCK_TITLE) || it is org.osmdroid.views.overlay.Polyline
        }
        if (markers.isEmpty()) {
            totalPathKm = 0.0
            deviceLatLon?.let {
                mapView.controller.setZoom(14.0)
                mapView.controller.setCenter(GeoPoint(it.first, it.second))
            } ?: run {
                mapView.controller.setZoom(2.0)
                mapView.controller.setCenter(GeoPoint(0.0, 0.0))
            }
            mapView.invalidate()
            return@LaunchedEffect
        }
        val points = markers.map { GeoPoint(it.location.lat, it.location.lon) }
        markers.forEachIndexed { idx, entry ->
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(entry.location.lat, entry.location.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "${idx + 1}. ${entry.location.label}"
                    val distStr = deviceLatLon?.let {
                        GeoUtils.distanceLabel(it.first, it.second, entry.location.lat, entry.location.lon)
                    } ?: ""
                    snippet = buildString {
                        append("[${entry.cotType.symbol}] ${entry.cotType.label}")
                        append(" · ${"%.5f".format(entry.location.lat)}, ${"%.5f".format(entry.location.lon)}")
                        if (distStr.isNotEmpty()) append(" · $distStr")
                    }
                    icon = cotIcons[entry.cotType]
                    setOnMarkerClickListener { m, _ ->
                        // Show InfoWindow on first tap. Also expose delete via Compose dialog.
                        m.showInfoWindow()
                        pendingDeleteIdx = idx
                        true
                    }
                },
            )
        }

        // Route polyline: GPS → m1 → m2 → … → mN. Computes total path km.
        if (routeMode) {
            val routePoints = mutableListOf<GeoPoint>()
            deviceLatLon?.let { routePoints.add(GeoPoint(it.first, it.second)) }
            routePoints.addAll(points)
            if (routePoints.size >= 2) {
                var total = 0.0
                for (i in 0 until routePoints.size - 1) {
                    total += GeoUtils.haversineKm(
                        routePoints[i].latitude, routePoints[i].longitude,
                        routePoints[i + 1].latitude, routePoints[i + 1].longitude,
                    )
                }
                totalPathKm = total
                val poly = org.osmdroid.views.overlay.Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color = android.graphics.Color.argb(220, 255, 193, 7)
                    outlinePaint.strokeWidth = 8f
                }
                mapView.overlays.add(poly)
            } else {
                totalPathKm = 0.0
            }
        } else {
            totalPathKm = 0.0
        }
        val allPoints = if (deviceLatLon != null) {
            points + GeoPoint(deviceLatLon.first, deviceLatLon.second)
        } else {
            points
        }
        if (allPoints.size == 1) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(allPoints.first())
        } else {
            val bbox = BoundingBox.fromGeoPoints(allPoints)
            mapView.post { mapView.zoomToBoundingBox(bbox, true, 80) }
        }
        mapView.invalidate()
    }

    // Puck overlay + tracking — refreshed whenever liveGps updates.
    LaunchedEffect(liveGps, isTracking) {
        val gps = liveGps ?: return@LaunchedEffect
        // Remove old puck (identified by PUCK_TITLE), add fresh one.
        mapView.overlays.removeAll { it is Marker && it.title == PUCK_TITLE }
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(gps.first, gps.second)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = PUCK_TITLE
                snippet = "${"%.5f".format(gps.first)}, ${"%.5f".format(gps.second)}"
                icon = puckIcon
            },
        )
        if (isTracking) {
            mapView.controller.animateTo(GeoPoint(gps.first, gps.second))
        }
        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        // Zoom controls — left side, middle vertically.
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = { mapView.controller.zoomIn() },
                modifier = Modifier
                    .background(Color(0xCC1A1A1A), androidx.compose.foundation.shape.CircleShape)
                    .size(36.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in", tint = io.kognis.tactical.ui.theme.RescueAmber, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { mapView.controller.zoomOut() },
                modifier = Modifier
                    .background(Color(0xCC1A1A1A), androidx.compose.foundation.shape.CircleShape)
                    .size(36.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out", tint = io.kognis.tactical.ui.theme.RescueAmber, modifier = Modifier.size(18.dp))
            }
        }

        // Route mode toggle + total distance display (top-center, only when relevant)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = { routeMode = !routeMode },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = if (routeMode) "Hide route" else "Show route",
                    tint = if (routeMode) io.kognis.tactical.ui.theme.RescueAmber else Color.LightGray,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (routeMode && totalPathKm > 0.0) {
                Text(
                    text = "Route: ${GeoUtils.formatDistance(totalPathKm)} · ${markers.size} stops",
                    color = io.kognis.tactical.ui.theme.RescueAmber,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    text = if (routeMode) "Route mode" else "Show route",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
            }
        }

        // Top-left: compact count + clear row
        if (markers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.map_marker_count, markers.size, markers.size),
                    color = Color.White,
                    fontSize = 11.sp,
                )
                if (onClear != null) {
                    Text(
                        text = stringResource(R.string.map_clear),
                        color = Color(0xFFEF5350),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(onClick = onClear),
                    )
                }
            }
        }

        // Location controls — bottom-right column: [Track toggle] / [Center on me]
        // Always visible; if no GPS fix yet, the tap is a no-op (toast hint).
        run {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // Track toggle — amber when active, animates map on every GPS update.
                IconButton(
                    onClick = {
                        if (liveGps == null) {
                            android.widget.Toast.makeText(context, "Waiting for GPS fix…", android.widget.Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        isTracking = !isTracking
                    },
                    modifier = Modifier
                        .background(
                            if (isTracking) io.kognis.tactical.ui.theme.RescueAmber else Color(0xFF1A1A1A),
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = if (isTracking) "Stop tracking" else "Track my location",
                        tint = if (isTracking) Color.Black else io.kognis.tactical.ui.theme.RescueAmber,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Center on me — one-shot recenter on current GPS fix.
                IconButton(
                    onClick = {
                        val g = liveGps
                        if (g == null) {
                            android.widget.Toast.makeText(context, "Waiting for GPS fix…", android.widget.Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        g.let {
                            mapView.controller.animateTo(GeoPoint(it.first, it.second))
                            mapView.controller.setZoom(16.0)
                        }
                    },
                    modifier = Modifier
                        .background(Color(0xFF1A1A1A), androidx.compose.foundation.shape.CircleShape)
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Center on my location",
                        tint = io.kognis.tactical.ui.theme.RescueAmber,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ODbL attribution — required by tile license.
        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color(0x88000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // Per-marker delete confirmation: triggered by tapping any marker.
        pendingDeleteIdx?.let { idx ->
            val entry = markers.getOrNull(idx)
            if (entry == null) { pendingDeleteIdx = null }
            else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingDeleteIdx = null },
                    containerColor = Color(0xFF1A1A1A),
                    title = { Text("Marker #${idx + 1}: ${entry.location.label}", color = Color.White) },
                    text = {
                        Column {
                            Text("[${entry.cotType.symbol}] ${entry.cotType.label}", color = Color(entry.cotType.colorArgb), fontSize = 13.sp)
                            Text("${"%.5f".format(entry.location.lat)}, ${"%.5f".format(entry.location.lon)}", color = Color.LightGray, fontSize = 11.sp)
                            deviceLatLon?.let {
                                val d = GeoUtils.distanceLabel(it.first, it.second, entry.location.lat, entry.location.lon)
                                Text("Distance from you: $d", color = io.kognis.tactical.ui.theme.RescueAmber, fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            MarkerStore.removeAt(idx)
                            pendingDeleteIdx = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.height(0.dp))
                            Text("Delete", color = Color(0xFFEF5350))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingDeleteIdx = null }) {
                            Text("Close", color = Color.Gray)
                        }
                    },
                )
            }
        }

        // Tap-to-mark picker: triggered by long-press on map; lets operator pick SAR type.
        pendingTapLatLon?.let { (tapLat, tapLon) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingTapLatLon = null },
                containerColor = Color(0xFF1A1A1A),
                title = {
                    Text("Mark this point?", color = Color.White)
                },
                text = {
                    Column {
                        Text(
                            "${"%.5f".format(tapLat)}, ${"%.5f".format(tapLon)}",
                            color = Color(0xFFFFC107),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text("Select marker type:", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        // 4 types per row — quick tap selection
                        val types = MarkerStore.CotType.entries
                        types.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                row.forEach { t ->
                                    androidx.compose.material3.TextButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            MarkerStore.add(
                                                MarkerStore.Entry(
                                                    location = LocationJsonExtractor.Location(tapLat, tapLon, t.label, t.name),
                                                    source = MarkerStore.Source.OSMDROID,
                                                    cotType = t,
                                                    queryPreview = "Tap-to-mark",
                                                    modelName = "MANUAL",
                                                ),
                                            )
                                            pendingTapLatLon = null
                                        },
                                    ) {
                                        Text("[${t.symbol}] ${t.label.take(8)}", color = Color(t.colorArgb), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingTapLatLon = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
            )
        }
    }
}

/** Title used to tag the live-GPS puck so we can find/remove it across overlay rebuilds. */
private const val PUCK_TITLE = "__kognis_gps_puck__"

/** Google-Maps-style puck: white outer ring + accent ring + solid blue center. */
private fun makePuckIcon(context: android.content.Context): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (28 * dp).toInt().coerceAtLeast(28)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val rOuter = size / 2f - (1 * dp)
    val rRing = rOuter - (3 * dp)
    val rDot = rRing - (2 * dp)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val blueDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, 13, 71, 161) // material blue 900
        style = Paint.Style.FILL
    }
    val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, 30, 136, 229) // material blue 600
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, rOuter, white)
    canvas.drawCircle(cx, cy, rRing, blueDark)
    canvas.drawCircle(cx, cy, rDot, blue)
    return BitmapDrawable(context.resources, bitmap)
}

private fun makeCotMarkerIcon(context: android.content.Context, colorArgb: Int, symbol: String): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (44 * dp).toInt().coerceAtLeast(44)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val r = size / 2f - (2 * dp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.FILL
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (2.5f * dp).coerceAtLeast(2.5f)
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = (size * 0.42f)
        isFakeBoldText = true
    }
    canvas.drawCircle(cx, cy, r, fill)
    canvas.drawCircle(cx, cy, r, border)
    // Center text vertically: baseline offset = half ascent
    val textBounds = android.graphics.Rect()
    text.getTextBounds(symbol, 0, symbol.length, textBounds)
    canvas.drawText(symbol, cx, cy - textBounds.exactCenterY(), text)
    return BitmapDrawable(context.resources, bitmap)
}
