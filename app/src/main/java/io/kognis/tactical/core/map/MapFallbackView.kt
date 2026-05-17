package io.kognis.tactical.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
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
    val deviceLatLon = remember { lastKnownLatLon(context) }

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

    // Pre-build colored icons once per CotType (avoids recreating on every recomposition).
    val cotIcons = remember(context) {
        MarkerStore.CotType.entries.associateWith { type ->
            makeCotMarkerIcon(context, type.colorArgb, type.symbol)
        }
    }

    // Re-apply markers whenever the list changes.
    LaunchedEffect(markers) {
        mapView.overlays.clear()
        if (markers.isEmpty()) {
            // Center on GPS if available, otherwise world view.
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
                },
            )
        }
        // Include GPS position in bounding box so user sees self + markers
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

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

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

        // My Location button — bottom-right (Google Maps convention)
        // Tap: center on GPS. Long-tap not needed — center is enough; marking is done via button above.
        if (deviceLatLon != null) {
            IconButton(
                onClick = {
                    mapView.controller.animateTo(GeoPoint(deviceLatLon.first, deviceLatLon.second))
                    mapView.controller.setZoom(15.0)
                    onMarkMyLocation?.invoke(deviceLatLon.first, deviceLatLon.second)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 28.dp)
                    .background(Color(0xFF1A1A1A), androidx.compose.foundation.shape.CircleShape)
                    .size(40.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "My location", tint = io.kognis.tactical.ui.theme.RescueAmber, modifier = Modifier.size(20.dp))
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
    }
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
