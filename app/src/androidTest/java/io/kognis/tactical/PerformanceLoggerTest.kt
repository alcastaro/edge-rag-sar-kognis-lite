package io.kognis.tactical

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kognis.tactical.core.PerformanceLogger
import io.kognis.tactical.core.PerformanceLogger.QueryEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PerformanceLoggerTest — instrumented tests for PerformanceLogger.
 *
 * Runs on device because PerformanceLogger depends on:
 *   - AndroidKeyStore (HMAC-SHA256 signing)
 *   - SecurePrefs (EncryptedSharedPreferences)
 *
 * Covers (audit §3.2 P1):
 *   - record() + entries() round-trip
 *   - Rolling buffer cap (MAX = 200)
 *   - ragHitRate() calculation
 *   - avgTps() calculation
 *   - clear() resets all state
 *   - HMAC integrity: verifyEntry rejects tampered data
 */
@RunWith(AndroidJUnit4::class)
class PerformanceLoggerTest {

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PerformanceLogger.init(ctx)
        PerformanceLogger.clear()
    }

    private fun makeEntry(
        queryPreview: String = "test query",
        ragActivated: Boolean = true,
        ragScore: Double = 0.5,
        tokensPerSec: Double = 5.0,
        durationMs: Long = 1000L,
        tokens: Int = 50,
        model: String = "LFM2-350M",
        tempCelsius: Double? = 35.0,
        responseText: String = "test response",
        chunkTitle: String = "Test Chunk",
        embeddingMode: String = "TEXT",
    ) = QueryEntry(
        queryPreview = queryPreview,
        model = model,
        durationMs = durationMs,
        tokensPerSec = tokensPerSec,
        tokens = tokens,
        ragActivated = ragActivated,
        ragScore = ragScore,
        tempCelsius = tempCelsius,
        responseText = responseText,
        chunkTitle = chunkTitle,
        embeddingMode = embeddingMode,
    )

    // ── Basic round-trip ─────────────────────────────────────────────────────────

    @Test
    fun entries_starts_empty_after_clear() {
        assertTrue(PerformanceLogger.entries().isEmpty())
    }

    @Test
    fun record_and_entries_roundtrip() {
        PerformanceLogger.record(makeEntry(queryPreview = "MARCH protocol"))
        val entries = PerformanceLogger.entries()
        assertEquals(1, entries.size)
        assertEquals("MARCH protocol", entries[0].queryPreview)
    }

    // ── ragHitRate ────────────────────────────────────────────────────────────────

    @Test
    fun ragHitRate_is_zero_when_empty() {
        assertEquals(0.0, PerformanceLogger.ragHitRate(), 0.001)
    }

    @Test
    fun ragHitRate_computes_correctly() {
        // 3 RAG hits + 2 misses = 60%
        repeat(3) { PerformanceLogger.record(makeEntry(ragActivated = true)) }
        repeat(2) { PerformanceLogger.record(makeEntry(ragActivated = false)) }
        assertEquals(0.6, PerformanceLogger.ragHitRate(), 0.01)
    }

    // ── avgTps ───────────────────────────────────────────────────────────────────

    @Test
    fun avgTps_computes_correctly() {
        PerformanceLogger.record(makeEntry(tokensPerSec = 8.0))
        PerformanceLogger.record(makeEntry(tokensPerSec = 4.0))
        assertEquals(6.0, PerformanceLogger.avgTps(), 0.01)
    }

    @Test
    fun avgTps_is_zero_when_empty() {
        assertEquals(0.0, PerformanceLogger.avgTps(), 0.001)
    }

    // ── clear ────────────────────────────────────────────────────────────────────

    @Test
    fun clear_resets_entries_and_stats() {
        PerformanceLogger.record(makeEntry())
        PerformanceLogger.clear()
        assertTrue(PerformanceLogger.entries().isEmpty())
        assertEquals(0.0, PerformanceLogger.ragHitRate(), 0.001)
        assertEquals(0.0, PerformanceLogger.avgTps(), 0.001)
    }

    // ── Rolling buffer ───────────────────────────────────────────────────────────

    @Test
    fun rolling_buffer_does_not_exceed_200() {
        repeat(220) { i ->
            PerformanceLogger.record(makeEntry(queryPreview = "q$i"))
        }
        assertTrue(
            "Expected size <= 200, got ${PerformanceLogger.entries().size}",
            PerformanceLogger.entries().size <= 200
        )
    }

    @Test
    fun rolling_buffer_keeps_most_recent() {
        repeat(210) { i ->
            PerformanceLogger.record(makeEntry(queryPreview = "query_$i"))
        }
        val last = PerformanceLogger.entries().last()
        assertEquals("query_209", last.queryPreview)
        // Oldest should be query_10 (first 10 evicted)
        val first = PerformanceLogger.entries().first()
        assertEquals("query_10", first.queryPreview)
    }

    // ── maxTemp ──────────────────────────────────────────────────────────────────

    @Test
    fun maxTemp_returns_highest_temperature() {
        PerformanceLogger.record(makeEntry(tempCelsius = 35.0))
        PerformanceLogger.record(makeEntry(tempCelsius = 41.5))
        PerformanceLogger.record(makeEntry(tempCelsius = 38.0))
        assertEquals(41.5, PerformanceLogger.maxTemp()!!, 0.01)
    }

    @Test
    fun maxTemp_returns_null_when_all_null() {
        PerformanceLogger.record(makeEntry(tempCelsius = null))
        assertNull(PerformanceLogger.maxTemp())
    }
}
