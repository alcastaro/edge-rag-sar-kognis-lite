package io.kognis.tactical.core.map

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports [MarkerStore] entries to a GPX 1.1 file and surfaces a share intent.
 *
 * GPX is the field standard for waypoint exchange (OsmAnd, Locus, Garmin, Maps.me).
 * OsmAnd opens GPX intents directly — no AIDL binding required for import.
 *
 * Usage:
 *   val uri = GpxExporter.export(context)          // write file
 *   context.startActivity(GpxExporter.shareIntent(context, uri))  // share
 */
object GpxExporter {

    private const val TAG = "GpxExporter"
    private const val AUTHORITY_SUFFIX = ".provider"

    data class ExportResult(
        val file: File,
        val markerCount: Int,
    )

    /**
     * Write current [MarkerStore] markers to a GPX file in the app's external
     * files dir (no storage permission needed on API 29+).
     *
     * Returns null and logs error if the store is empty or write fails.
     */
    fun export(context: Context): ExportResult? {
        val markers = MarkerStore.markers
        if (markers.isEmpty()) {
            Log.w(TAG, "No markers to export")
            return null
        }

        val dir = File(context.getExternalFilesDir(null), "gpx").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "kognis_$ts.gpx")

        return try {
            file.bufferedWriter().use { w ->
                w.write(buildGpx(markers))
            }
            Log.i(TAG, "GPX exported: ${file.absolutePath} (${markers.size} waypoints)")
            ExportResult(file, markers.size)
        } catch (e: Exception) {
            Log.e(TAG, "GPX export failed: ${e.message}", e)
            null
        }
    }

    /**
     * Build a share [Intent] for the exported file.
     * Targets OsmAnd Plus first, then OsmAnd Free, then falls back to
     * a generic chooser so any GPX-capable app can open it.
     */
    fun shareIntent(context: Context, result: ExportResult): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            result.file
        )

        val osmandPkg = when {
            isInstalled(context, "net.osmand.plus") -> "net.osmand.plus"
            isInstalled(context, "net.osmand")      -> "net.osmand"
            else -> null
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (osmandPkg != null) setPackage(osmandPkg)
        }

        return if (osmandPkg != null) {
            viewIntent
        } else {
            Intent.createChooser(viewIntent, "Open GPX with…")
        }
    }

    /**
     * Build a standard GPX 1.1 document from the marker list.
     * Each marker becomes a `<wpt>` element with name and description.
     */
    private fun buildGpx(markers: List<MarkerStore.Entry>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val created = iso.format(Date())
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Kognis Lite"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>Kognis Lite session markers</name>
    <time>$created</time>
  </metadata>
""")
        for (entry in markers) {
            val lat = entry.location.lat
            val lon = entry.location.lon
            val name = escapeXml(entry.location.label)
            val desc = escapeXml(entry.queryPreview.take(200))
            val time = iso.format(Date(entry.timestampMs))
            sb.append("""  <wpt lat="$lat" lon="$lon">
    <name>$name</name>
    <desc>$desc</desc>
    <time>$time</time>
  </wpt>
""")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
}
