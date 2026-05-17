package io.kognis.tactical.core

/**
 * RagTextUtils — extracted pure-function utilities from RagOrchestrator.
 *
 * These are the text normalization and tokenization functions used by BM25
 * text search. Extracted to enable unit testing without requiring a device,
 * ModelRunner, or EmbeddingEngine.
 *
 * @see RagOrchestrator (which delegates to these functions)
 */
object RagTextUtils {

    /**
     * Normalize text for search: lowercase, strip accents, remove punctuation.
     * Must produce identical output to RagOrchestrator.normalizeText().
     */
    fun normalizeText(input: String): String = input.lowercase()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i')
        .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u')
        .replace(Regex("[¿¡?!.,;:()'\"«»\\-]"), " ")

    /**
     * Tokenize a query into meaningful search tokens.
     * Removes Spanish stopwords and short words.
     */
    fun tokenize(text: String): List<String> {
        val stopwords = setOf(
            // Articles
            "el", "la", "los", "las", "un", "una", "unos", "unas",
            "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas",
            // Prepositions
            "de", "del", "en", "con", "por", "para", "sin", "sobre", "entre", "ante", "bajo",
            // Conjunctions
            "que", "y", "o", "ni", "pero", "sino", "porque", "como",
            // Pronouns
            "se", "le", "les", "me", "mi", "tu", "yo", "sus", "su", "al", "lo", "nos",
            // Common verbs (conjugated)
            "es", "son", "esta", "estan", "han", "has", "hay", "ser", "tener",
            "hace", "hacer", "tiene", "tienen", "haber", "sido", "sea",
            // Question words (with and without accent)
            "cual", "cuales", "como", "cuando", "donde", "quien", "que",
            // Other
            "mas", "muy", "bien", "si", "no", "ya", "otro", "otros",
            "todo", "toda", "todos", "todas", "aqui", "alli",
            // English stopwords
            "the", "of", "in", "to", "and", "or", "is", "are", "was",
            "with", "for", "on", "at", "from", "by", "an", "be",
            "this", "that", "it", "its", "do", "not"
        )
        // Normalize: lowercase + remove accents for matching
        return normalizeText(text)
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in stopwords }
            .distinct()
    }

    /**
     * Smoothed IDF: log((N+1) / (df+1)).
     * Returns a positive value even when df == N (every doc has the term).
     */
    @androidx.annotation.VisibleForTesting
    internal fun idfScore(numDocs: Int, docsWithTerm: Int): Double =
        Math.log((numDocs + 1.0) / (docsWithTerm + 1))
}
