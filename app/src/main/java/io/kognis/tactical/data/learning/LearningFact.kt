package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * A fact extracted from a conversation — subject/predicate/object/confidence + a
 * tokenized search index that replaces SQLite FTS5 at lower latency.
 *
 * `supersededBy` lets a newer fact override an older one without deletion (useful
 * for mastery updates where the history matters for the audit trail).
 *
 * `tokensIdx` is a space-joined lowercase normalized concatenation of `subject` +
 * `objectStr`. ObjectBox `contains` queries on indexed strings are ~1 ms for our
 * expected scale (<1k facts/user).
 */
@Entity
data class LearningFact(
    @Id var id: Long = 0,
    @Index var sessionId: Long = 0,
    @Index var subject: String = "",
    var predicate: String = "",
    var objectStr: String = "",
    var confidence: Double = 0.5,
    @Index var tokensIdx: String = "",
    var supersededBy: Long = 0L,
    var crossSession: Boolean = false,
    var ts: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, 0, "", "", "", 0.5, "", 0L, false, 0L)
    val isCurrent: Boolean get() = supersededBy == 0L
}

/** Lowercase ASCII-fold tokenizer used to populate `LearningFact.tokensIdx`. */
fun computeFactTokens(subject: String, objectStr: String): String {
    val raw = "$subject $objectStr".lowercase()
    val noDiacritics = raw.replace(Regex("[áàäâ]"), "a")
        .replace(Regex("[éèëê]"), "e")
        .replace(Regex("[íìïî]"), "i")
        .replace(Regex("[óòöô]"), "o")
        .replace(Regex("[úùüû]"), "u")
        .replace("ñ", "n")
    return noDiacritics.split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 }
        .joinToString(" ")
}
