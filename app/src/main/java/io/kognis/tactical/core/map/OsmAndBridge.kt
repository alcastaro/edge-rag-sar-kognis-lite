package io.kognis.tactical.core.map

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Drop a single marker in OsmAnd from a [LocationJsonExtractor.Location].
 *
 * Uses the standard Android `geo:` URI intent + package targeting — no AIDL,
 * no service bind, no parcelable surface. Trade-off vs OsmAnd AIDL: we can't
 * query OsmAnd's state or add multiple markers in one shot, but for the
 * Kognis use case (LLM emits a single LOCATION_JSON per response → drop one
 * pin) Intent is sufficient and an order of magnitude less code/risk.
 *
 * Resolution order:
 *   1. OsmAnd Plus (`net.osmand.plus`) — preferred; the NGO-grade build
 *   2. OsmAnd free (`net.osmand`)
 *   3. Any installed handler for `geo:` (Google Maps, Maps.me, …)
 *   4. caller falls back to in-app [MapFallbackView] (osmdroid)
 *
 * Network: this issues no network I/O of its own. Whatever OsmAnd does with
 * the marker is its own business — Zero-Signal still holds because OsmAnd
 * is a separate process with its own offline maps (.obf files).
 */
object OsmAndBridge {

    private const val TAG = "OsmAndBridge"

    private const val PKG_OSMAND_PLUS  = "net.osmand.plus"
    private const val PKG_OSMAND_FREE  = "net.osmand"
    private const val PKG_GOOGLE_MAPS  = "com.google.android.apps.maps"

    /** True if any OsmAnd build is installed (Plus or Free). */
    fun isInstalled(context: Context): Boolean =
        isPackageInstalled(context, PKG_OSMAND_PLUS) ||
            isPackageInstalled(context, PKG_OSMAND_FREE)

    /** True if Google Maps is installed. */
    fun isGoogleMapsInstalled(context: Context): Boolean =
        isPackageInstalled(context, PKG_GOOGLE_MAPS)

    /**
     * Attempt to open the marker in OsmAnd. Returns true if an OsmAnd build
     * accepted the intent; false otherwise (caller should fall back to
     * [MapFallbackView]).
     */
    fun openInOsmAnd(context: Context, location: LocationJsonExtractor.Location): Boolean {
        val uri = buildGeoUri(location)
        val pkg = when {
            isPackageInstalled(context, PKG_OSMAND_PLUS) -> PKG_OSMAND_PLUS
            isPackageInstalled(context, PKG_OSMAND_FREE) -> PKG_OSMAND_FREE
            else -> return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Dropped marker in $pkg at ${location.lat},${location.lon}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start $pkg: ${e.message}")
            false
        }
    }

    /**
     * Open the location in Google Maps using the same geo: URI that OsmAnd uses.
     * Returns true if Google Maps accepted the intent.
     */
    fun openInGoogleMaps(context: Context, location: LocationJsonExtractor.Location): Boolean {
        if (!isGoogleMapsInstalled(context)) return false
        val uri = buildGeoUri(location)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PKG_GOOGLE_MAPS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Opened in Google Maps at ${location.lat},${location.lon}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Google Maps launch failed: ${e.message}")
            false
        }
    }

    /**
     * Build the `geo:lat,lon?z=15&q=lat,lon(label)` URI per the OGP geo: scheme.
     * OsmAnd parses `?q=lat,lon(label)` and drops a labeled marker at the
     * point; the `z=` zoom hint is honored by most apps including Google Maps.
     */
    @JvmStatic
    internal fun buildGeoUri(location: LocationJsonExtractor.Location): Uri {
        // Note: Uri.Builder URL-encodes the query value (including the parens),
        // which OsmAnd handles fine on parse. Constructing the string by hand
        // keeps the encoding contract explicit.
        val lat = location.lat
        val lon = location.lon
        val labelEnc = Uri.encode(location.label)
        return Uri.parse("geo:$lat,$lon?z=15&q=$lat,$lon($labelEnc)")
    }

    /**
     * Open Google Maps with a multi-marker directions URL: first marker is the
     * origin, remaining markers are stops + final destination. Up to 10 markers
     * (Google Maps URL waypoint limit). Uses the universal `maps.google.com/maps/dir/`
     * URL so it works offline-after-handoff once Google Maps caches the area.
     */
    fun openAllInGoogleMaps(context: Context, markers: List<MarkerStore.Entry>): Boolean {
        if (markers.isEmpty()) return false
        val capped = markers.take(10)
        val path = capped.joinToString("/") { "${it.location.lat},${it.location.lon}" }
        val uri = Uri.parse("https://www.google.com/maps/dir/$path")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Prefer Google Maps app; fall back to browser if not installed.
            if (isGoogleMapsInstalled(context)) setPackage(PKG_GOOGLE_MAPS)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Opened ${capped.size} markers in Google Maps directions")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Multi-marker Google Maps launch failed: ${e.message}")
            false
        }
    }

    /**
     * Open OsmAnd with multiple favourites. OsmAnd's `geo:` URI handler only
     * accepts a single point; for batch handoff we use OsmAnd's GPX import
     * intent — caller must have written a GPX file via [GpxExporter] first.
     *
     * Returns true if intent dispatch succeeded. If OsmAnd isn't installed,
     * returns false (UI should offer the in-app map or Google Maps instead).
     */
    fun openAllInOsmAnd(context: Context, gpxUri: Uri): Boolean {
        if (!isInstalled(context)) return false
        val pkg = if (isPackageInstalled(context, PKG_OSMAND_PLUS)) PKG_OSMAND_PLUS else PKG_OSMAND_FREE
        val intent = Intent(Intent.ACTION_VIEW, gpxUri).apply {
            setPackage(pkg)
            setDataAndType(gpxUri, "application/gpx+xml")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Handed GPX to $pkg")
            true
        } catch (e: Exception) {
            Log.w(TAG, "OsmAnd GPX import launch failed: ${e.message}")
            false
        }
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
