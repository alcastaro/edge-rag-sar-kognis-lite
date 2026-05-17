package io.kognis.tactical

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class KbIngestTest {

    data class ParsedChunk(val title: String, val content: String, val chunkId: String)
    data class ParseResult(val toInsert: List<ParsedChunk>, val skipped: Int, val rejected: Int)

    private fun contentHash(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun parseChunks(jsonArray: JSONArray, existingKeys: Set<String>): ParseResult {
        val toInsert = mutableListOf<ParsedChunk>()
        var skipped = 0; var rejected = 0
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: run { rejected++; continue }
            val title = obj.optString("title", "")
            val content = obj.optString("content", "")
            val chunkId = obj.optString("chunk_id", "").ifBlank { contentHash(content) }
            if (title.isBlank() || content.isBlank()) { rejected++; continue }
            val vectorArr = obj.optJSONArray("vector")
            // vector is optional (null = BM25-only chunk). If present, must be 384-dim.
            if (vectorArr != null && vectorArr.length() != 384) { rejected++; continue }
            if (chunkId in existingKeys) { skipped++; continue }
            toInsert.add(ParsedChunk(title, content, chunkId))
        }
        return ParseResult(toInsert, skipped, rejected)
    }

    private fun makeValidChunk(title: String, content: String, chunkId: String, withVector: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("title", title)
            put("content", content)
            put("chunk_id", chunkId)
            if (withVector) put("vector", JSONArray((1..384).map { 0.1 }))
        }
    }

    @Test
    fun `new chunks are inserted`() {
        val array = JSONArray().apply {
            put(makeValidChunk("T1", "Content one", "id-001"))
            put(makeValidChunk("T2", "Content two", "id-002"))
        }
        val result = parseChunks(array, emptySet())
        assertEquals(2, result.toInsert.size)
        assertEquals(0, result.skipped)
        assertEquals(0, result.rejected)
    }

    @Test
    fun `existing chunkId causes skip`() {
        val array = JSONArray().apply {
            put(makeValidChunk("T1", "Content one", "id-001"))
            put(makeValidChunk("T2", "Content two", "id-002"))
        }
        val result = parseChunks(array, setOf("id-001"))
        assertEquals(1, result.toInsert.size)
        assertEquals(1, result.skipped)
        assertEquals("id-002", result.toInsert[0].chunkId)
    }

    @Test
    fun `missing title rejects chunk`() {
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("title", "")
                put("content", "Some content")
                put("chunk_id", "id-003")
                put("vector", JSONArray((1..384).map { 0.1 }))
            })
        }
        val result = parseChunks(array, emptySet())
        assertEquals(0, result.toInsert.size)
        assertEquals(1, result.rejected)
    }

    @Test
    fun `wrong vector dimensions rejects chunk`() {
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("title", "Valid title")
                put("content", "Valid content")
                put("chunk_id", "id-004")
                put("vector", JSONArray(listOf(0.1, 0.2, 0.3)))
            })
        }
        val result = parseChunks(array, emptySet())
        assertEquals(0, result.toInsert.size)
        assertEquals(1, result.rejected)
    }

    @Test
    fun `chunk without vector is accepted as BM25-only`() {
        val array = JSONArray().apply {
            put(makeValidChunk("TCCC — Torniquete", "Aplica torniquete 5-7cm proximal", "id-col-001", withVector = false))
            put(makeValidChunk("MARCH — Hemorragia", "M: control hemorragia masiva", "id-col-002", withVector = false))
        }
        val result = parseChunks(array, emptySet())
        assertEquals(2, result.toInsert.size)
        assertEquals(0, result.rejected)
    }

    @Test
    fun `mixed KB — vector and no-vector chunks all accepted`() {
        val array = JSONArray().apply {
            put(makeValidChunk("WHO BEC — triage", "Triage content with embedding", "id-who-001", withVector = true))
            put(makeValidChunk("Sphere — WASH", "Protocolo WASH sin vectores", "id-sphere-001", withVector = false))
        }
        val result = parseChunks(array, emptySet())
        assertEquals(2, result.toInsert.size)
        assertEquals(0, result.rejected)
    }

    @Test
    fun `missing chunkId uses content hash`() {
        val content = "Content without explicit id"
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("title", "T5")
                put("content", content)
                put("vector", JSONArray((1..384).map { 0.1 }))
            })
        }
        val result = parseChunks(array, emptySet())
        assertEquals(1, result.toInsert.size)
        assertEquals(contentHash(content), result.toInsert[0].chunkId)
    }

    @Test
    fun `idempotent — second run with same chunks all skipped`() {
        val array = JSONArray().apply {
            put(makeValidChunk("T1", "Content one", "id-001"))
            put(makeValidChunk("T2", "Content two", "id-002"))
        }
        val first = parseChunks(array, emptySet())
        val afterFirst = first.toInsert.map { it.chunkId }.toSet()
        val second = parseChunks(array, afterFirst)
        assertEquals(0, second.toInsert.size)
        assertEquals(2, second.skipped)
    }
}
