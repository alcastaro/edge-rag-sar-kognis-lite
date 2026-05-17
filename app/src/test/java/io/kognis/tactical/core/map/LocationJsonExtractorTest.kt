package io.kognis.tactical.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationJsonExtractorTest {

    @Test
    fun `extracts standard tag at end of response`() {
        val text = """
            Punto de evacuación recomendado: helipuerto Alfa.
            LOCATION_JSON: {"lat": 4.651, "lon": -74.055, "label": "LZ Alfa"}
        """.trimIndent()
        val loc = LocationJsonExtractor.extract(text)
        assertNotNull(loc)
        assertEquals(4.651, loc!!.lat, 1e-6)
        assertEquals(-74.055, loc.lon, 1e-6)
        assertEquals("LZ Alfa", loc.label)
    }

    @Test
    fun `extracts tag with mixed case keyword`() {
        val text = "Procede al norte.\nlocation_json: {\"lat\": 18.4, \"lon\": -69.9, \"label\": \"x\"}"
        assertNotNull(LocationJsonExtractor.extract(text))
    }

    @Test
    fun `last tag wins when multiple appear`() {
        val text = """
            Primer intento: LOCATION_JSON: {"lat": 1.0, "lon": 1.0, "label": "old"}
            Punto correcto: LOCATION_JSON: {"lat": 4.5, "lon": -74.0, "label": "new"}
        """.trimIndent()
        val loc = LocationJsonExtractor.extract(text)!!
        assertEquals("new", loc.label)
        assertEquals(4.5, loc.lat, 1e-6)
    }

    @Test
    fun `returns null when no tag`() {
        assertNull(LocationJsonExtractor.extract("Respuesta sin coordenadas."))
        assertNull(LocationJsonExtractor.extract(""))
    }

    @Test
    fun `returns null on malformed JSON`() {
        assertNull(LocationJsonExtractor.extract("LOCATION_JSON: {bad json}"))
        assertNull(LocationJsonExtractor.extract("LOCATION_JSON: {\"lat\": 1.0}")) // missing lon
        assertNull(LocationJsonExtractor.extract("LOCATION_JSON: {\"lon\": 1.0}")) // missing lat
    }

    @Test
    fun `rejects out-of-range coordinates`() {
        assertNull(LocationJsonExtractor.extract(
            "LOCATION_JSON: {\"lat\": 999.0, \"lon\": 0.0, \"label\": \"hallucination\"}",
        ))
        assertNull(LocationJsonExtractor.extract(
            "LOCATION_JSON: {\"lat\": 0.0, \"lon\": -999.0, \"label\": \"hallucination\"}",
        ))
    }

    @Test
    fun `accepts boundary coordinates`() {
        val north = LocationJsonExtractor.extract(
            "LOCATION_JSON: {\"lat\": 90.0, \"lon\": 180.0, \"label\": \"pole\"}",
        )!!
        assertEquals(90.0, north.lat, 1e-9)
        assertEquals(180.0, north.lon, 1e-9)
    }

    @Test
    fun `default label when blank`() {
        val loc = LocationJsonExtractor.extract(
            "LOCATION_JSON: {\"lat\": 4.0, \"lon\": -74.0, \"label\": \"\"}",
        )!!
        assertEquals("Marker", loc.label)
    }

    @Test
    fun `label length capped at 80`() {
        val longLabel = "x".repeat(200)
        val loc = LocationJsonExtractor.extract(
            "LOCATION_JSON: {\"lat\": 4.0, \"lon\": -74.0, \"label\": \"$longLabel\"}",
        )!!
        assertEquals(80, loc.label.length)
    }

    @Test
    fun `stripTag removes tag from display text`() {
        val text = "Helipuerto Alfa. LOCATION_JSON: {\"lat\": 4.5, \"lon\": -74.0, \"label\": \"x\"}"
        val stripped = LocationJsonExtractor.stripTag(text)
        assertEquals("Helipuerto Alfa.", stripped)
    }

    @Test
    fun `stripTag removes multiple tags`() {
        val text = """
            LOCATION_JSON: {"lat": 1.0, "lon": 1.0, "label": "a"}
            middle
            LOCATION_JSON: {"lat": 2.0, "lon": 2.0, "label": "b"}
        """.trimIndent()
        val stripped = LocationJsonExtractor.stripTag(text)
        assertEquals(false, stripped.contains("LOCATION_JSON"))
        assertEquals(true, stripped.contains("middle"))
    }
}
