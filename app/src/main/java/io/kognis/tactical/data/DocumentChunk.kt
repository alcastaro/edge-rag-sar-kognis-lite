package io.kognis.tactical.data

import io.kognis.tactical.core.ChunkEncryptor
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

@Entity
data class DocumentChunk(
    @Id var id: Long = 0,
    var title: String = "",
    var content: String = "",
    var chunkId: String? = null,
    var sourcePage: String? = null,

    // HNSW index for vector similarity search. Dimensions set to 384 for MiniLM-L12-v2
    @HnswIndex(dimensions = 384)
    var vector: FloatArray? = null
) {
    // Required for ObjectBox no-arg constructor
    constructor() : this(0, "", "", null, null, null)
}

/** Returns a copy with decrypted title/content. No-op if fields are not encrypted. */
fun DocumentChunk.decrypted(): DocumentChunk {
    if (!ChunkEncryptor.isEncrypted(title) && !ChunkEncryptor.isEncrypted(content)) return this
    return copy(
        title = ChunkEncryptor.decrypt(title),
        content = ChunkEncryptor.decrypt(content)
    )
}
