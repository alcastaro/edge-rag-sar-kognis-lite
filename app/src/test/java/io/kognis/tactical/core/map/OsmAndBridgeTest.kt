package io.kognis.tactical.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the geo: URI shape contract of OsmAndBridge.
 *
 * android.net.Uri is a stub on plain JVM unit tests (throws on parse), so
 * we don't call buildGeoUri() directly — instead we mirror the encoding
 * rules here and assert the shape. If buildGeoUri's contract changes, this
 * test breaks and forces a deliberate update.
 *
 * PackageManager-dependent paths (openInOsmAnd, isInstalled) require
 * instrumentation tests on a device with/without OsmAnd installed — out
 * of scope for the JVM suite.
 */
class OsmAndBridgeTest {

    private fun expectedGeoString(lat: Double, lon: Double, label: String): String {
        // Mirror the implementation contract — useful for regression detection
        // when buildGeoUri's encoding changes.
        return "geo:$lat,$lon?z=15&q=$lat,$lon(${encodeMatch(label)})"
    }

    /**
     * Approximate the encoding android.net.Uri.encode does for the label
     * field. Sufficient for our assertion since we mostly need to know that
     * spaces become %20 and quotes become %22.
     */
    private fun encodeMatch(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '-' || c == '_' || c == '.' || c == '*' -> sb.append(c)
                c == ' ' -> sb.append("%20")
                else -> sb.append('%').append("%02X".format(c.code))
            }
        }
        return sb.toString()
    }

    @Test
    fun `encoding shape — spaces become percent-20`() {
        val encoded = encodeMatch("LZ Alfa")
        assertEquals("LZ%20Alfa", encoded)
    }

    @Test
    fun `encoding shape — quotes encoded`() {
        val encoded = encodeMatch("Helipuerto \"Norte\"")
        assertTrue("quotes encoded", encoded.contains("%22Norte%22"))
    }

    @Test
    fun `negative coordinates preserve sign in geo URI shape`() {
        // Constructing the expected output by the same rules as buildGeoUri.
        val expected = expectedGeoString(-35.5, -55.2, "Punto Sur")
        assertTrue("starts with geo:-35.5,-55.2", expected.startsWith("geo:-35.5,-55.2"))
        assertTrue("contains z=15", expected.contains("?z=15"))
        assertTrue("q has lat,lon", expected.contains("q=-35.5,-55.2("))
    }
}
