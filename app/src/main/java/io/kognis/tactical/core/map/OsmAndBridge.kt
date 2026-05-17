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

    private fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
