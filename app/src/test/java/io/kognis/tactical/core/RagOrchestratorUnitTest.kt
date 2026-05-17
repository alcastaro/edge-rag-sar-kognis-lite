package io.kognis.tactical.core

import org.junit.Assert.*
import org.junit.Test

/**
 * RagOrchestratorUnitTest — pure-function unit tests, no device required.
 *
 * Tests the extracted pure logic components of the RAG pipeline:
 *   - RagTextUtils: text normalization, tokenization, stopword filtering.
 *   - RagPromptBuilder: generation of system prompts based on verbosity/language/model.
 *
 * Covers (audit §3.2 P1 — RagOrchestratorTokenizerTest):
 *   - normalizeText: accent stripping, punctuation removal, lowercase
 *   - tokenize: min-length filter, deduplication, stopword effect
 *   - verbosityRule: correct string per level/language
 *   - safetyRules: positive (not negative) instructions for 1.2B
 */
class RagOrchestratorUnitTest {

    // ── RagTextUtils ─────────────────────────────────────────────────────────────

    @Test
    fun `normalizeText strips accents and converts to lowercase`() {
        val input = "Árbol de búsqueda en río"
        val result = RagTextUtils.normalizeText(input)
        assertFalse("Should strip á", result.contains('á'))
        assertFalse("Should strip ú", result.contains('ú'))
        assertFalse("Should strip í", result.contains('í'))
        assertTrue("Should contain 'arbol'", result.contains("arbol"))
        assertTrue("Should contain 'busqueda'", result.contains("busqueda"))
    }

    @Test
    fun `normalizeText removes punctuation`() {
        val result = RagTextUtils.normalizeText("¿Cómo? ¡Actúa! (campo), zona: norte.")
        assertFalse("No ¿", result.contains('¿'))
        assertFalse("No ¡", result.contains('¡'))
        assertFalse("No ()", result.contains('('))
        assertFalse("No ,", result.contains(','))
    }

    @Test
    fun `tokenize filters short tokens and stopwords`() {
        val tokens = RagTextUtils.tokenize("el protocolo MARCH de la brigada")
        // "el", "de", "la" are stopwords/short
        assertFalse("'el' should be filtered", tokens.contains("el"))
        assertFalse("'de' should be filtered", tokens.contains("de"))
        assertFalse("'la' should be filtered", tokens.contains("la"))
        assertTrue("'protocolo' should be kept", tokens.contains("protocolo"))
        assertTrue("'march' should be kept (normalized)", tokens.contains("march"))
        assertTrue("'brigada' should be kept", tokens.contains("brigada"))
    }

    @Test
    fun `tokenize deduplicates tokens`() {
        val tokens = RagTextUtils.tokenize("MARCH march MARCH hemorragia hemorragia")
        val marchCount = tokens.count { it == "march" }
        val hemorCount = tokens.count { it == "hemorragia" }
        assertEquals("march should appear once", 1, marchCount)
        assertEquals("hemorragia should appear once", 1, hemorCount)
    }

    @Test
    fun `tokenize handles empty string`() {
        val tokens = RagTextUtils.tokenize("")
        assertTrue(tokens.isEmpty())
    }

    // ── RagPromptBuilder ─────────────────────────────────────────────────────────

    @Test
    fun `system prompt Std ES uses positive instructions and no marker references`() {
        val prompt = RagPromptBuilder.buildSystemPromptStd("ESTANDAR", en = false)
        assertFalse("No 'NUNCA'", prompt.contains("NUNCA"))
        assertFalse("No 'STOP'", prompt.contains("STOP"))
        // S21: removed literal marker references that small models parroted
        assertFalse("No literal '[KB]' marker", prompt.contains("[CONTEXTO"))
        assertFalse("No literal '[MAP]' marker", prompt.contains("[SITUACIÓN"))
        assertTrue("Should contain 'Reglas:'", prompt.contains("Reglas:"))
        assertTrue("Should contain 'Usa solo'", prompt.contains("Usa solo"))
        assertTrue("Should contain no-info fallback", prompt.contains("No tengo esa información"))
    }

    @Test
    fun `system prompt Std EN uses positive instructions and no marker references`() {
        val prompt = RagPromptBuilder.buildSystemPromptStd("ESTANDAR", en = true)
        assertFalse("No 'NEVER'", prompt.contains("NEVER"))
        assertFalse("No 'STOP'", prompt.contains("STOP"))
        assertFalse("No literal '[KB]' marker", prompt.contains("[TACTICAL"))
        assertFalse("No literal '[MAP]' marker", prompt.contains("ON MAP]"))
        assertTrue("Should contain 'Rules:'", prompt.contains("Rules:"))
        assertTrue("Should contain 'Use only'", prompt.contains("Use only"))
        assertTrue("Should contain no-info fallback", prompt.contains("I don't have that information"))
    }

    @Test
    fun `system prompt 1B2 ES is compact with fallback`() {
        val prompt = RagPromptBuilder.buildSystemPrompt1B2("ESTANDAR", en = false)
        assertTrue("1B2 ES brand", prompt.contains("Kognis Lite"))
        assertTrue("1B2 ES contains fallback", prompt.contains("No tengo esa información"))
        assertFalse("No literal markers", prompt.contains("[CONTEXTO"))
        assertFalse("No 'NUNCA'", prompt.contains("NUNCA"))
        // Compact: should be substantially shorter than Std
        val std = RagPromptBuilder.buildSystemPromptStd("ESTANDAR", en = false)
        assertTrue("1B2 prompt should be shorter than Std", prompt.length < std.length)
    }

    @Test
    fun `system prompt 1B2 EN is compact with fallback`() {
        val prompt = RagPromptBuilder.buildSystemPrompt1B2("ESTANDAR", en = true)
        assertTrue("1B2 EN brand", prompt.contains("Kognis Lite"))
        assertTrue("1B2 EN contains fallback", prompt.contains("I don't have that information"))
        assertFalse("No literal markers", prompt.contains("[TACTICAL"))
        assertFalse("No 'NEVER'", prompt.contains("NEVER"))
    }

    @Test
    fun `verbosityRule TACTICO ES is short`() {
        val rule = RagPromptBuilder.verbosityRule("TACTICO", en = false, compact = false)
        assertTrue(rule.contains("3"))
        assertFalse("Should not mention 'numbered steps'", rule.contains("numbered"))
    }

    @Test
    fun `verbosityRule DETALLADO EN mentions steps`() {
        val rule = RagPromptBuilder.verbosityRule("DETALLADO", en = true, compact = false)
        assertTrue(rule.contains("step") || rule.contains("source") || rule.contains("complete"))
    }

    // ── IDF + BM25 ───────────────────────────────────────────────────────────────

    @Test
    fun `idfScore rare term ranks higher than common term`() {
        val n = 1000
        val rareIdf   = RagTextUtils.idfScore(n, docsWithTerm = 3)   // "cricotirotomia" in 3 docs
        val commonIdf = RagTextUtils.idfScore(n, docsWithTerm = 500)  // "protocolo" in 500 docs
        assertTrue("Rare term must have higher IDF", rareIdf > commonIdf)
        assertTrue("IDF always positive (smoothed)", rareIdf > 0.0)
        // When df == N: log((N+1)/(N+1)) = log(1) = 0.0 — term in every doc scores zero (OK)
        assertEquals("IDF is zero when term appears in all docs", 0.0, RagTextUtils.idfScore(n, n), 1e-9)
    }

    @Test
    fun `idfScore zero docs with term returns max IDF`() {
        // Term never seen in KB (query-only token) — df=0 → maximum possible IDF
        val n = 100
        val neverSeenIdf = RagTextUtils.idfScore(n, docsWithTerm = 0)
        val seenOnceIdf  = RagTextUtils.idfScore(n, docsWithTerm = 1)
        assertTrue("Unseen term has highest IDF", neverSeenIdf > seenOnceIdf)
    }

    @Test
    fun `tokenize produces identical tokens from KB chunk and equivalent query`() {
        // Index is built by tokenizing chunk text. Query is also tokenized.
        // If the same surface form appears in both, they must produce the same token.
        val chunkText  = "Protocolo TCCC: torniquete hemostático aplicación"
        val queryText  = "protocolo tccc torniquete hemostatico aplicacion"
        val chunkTokens = RagTextUtils.tokenize(chunkText).toSet()
        val queryTokens = RagTextUtils.tokenize(queryText).toSet()
        // All normalized query tokens should appear in chunk tokens
        assertTrue("'protocolo' must match in both", chunkTokens.contains("protocolo"))
        assertTrue("'torniquete' must match in both", chunkTokens.contains("torniquete"))
        assertEquals("Same tokens from equivalent text", chunkTokens, queryTokens)
    }

    @Test
    fun `tokenize excludes Spanish stopwords that inflate BM25 scores`() {
        // These are high-frequency words that would match every document unfairly
        val noisyQuery = "¿cómo se aplica el protocolo de la brigada?"
        val tokens = RagTextUtils.tokenize(noisyQuery)
        listOf("como", "sel", "la", "del", "los").forEach { word ->
            assertFalse("'$word' must NOT appear in tokens", tokens.contains(word))
        }
        assertTrue("Content word 'protocolo' kept", tokens.contains("protocolo"))
        assertTrue("Content word 'brigada' kept", tokens.contains("brigada"))
    }

    @Test
    fun `buildSystemPrompt routes correctly based on parameters`() {
        // Radio mode overriding
        val radioEn = RagPromptBuilder.buildSystemPrompt("ESTANDAR", "en", "350M", radioMode = true)
        assertTrue("Radio brand", radioEn.contains("Kognis Lite (field radio mode)"))

        // 1B2 selection — compact form, contains brand + fallback
        val model1b2 = RagPromptBuilder.buildSystemPrompt("ESTANDAR", "es", "1B2", radioMode = false)
        assertTrue("1B2 brand", model1b2.contains("Kognis Lite"))
        assertTrue("1B2 fallback", model1b2.contains("No tengo esa información"))

        // 350M selection — minimal compact prompt
        val model350 = RagPromptBuilder.buildSystemPrompt("ESTANDAR", "es", "350M", radioMode = false)
        assertTrue("350M brand", model350.contains("Kognis Lite"))
        assertTrue("350M fallback", model350.contains("No tengo esa información"))
        assertTrue("350M is most compact", model350.length < model1b2.length)
    }
}
