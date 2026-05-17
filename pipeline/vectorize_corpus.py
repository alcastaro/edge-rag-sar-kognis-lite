#!/usr/bin/env python3
"""
Vectorize a curated humanitarian corpus into the JSON format expected by
KnowledgeBaseLoader.kt (title, content, chunk_id, vector[384]).

Embedding model: intfloat/multilingual-e5-small (matches the ONNX model
shipped in app/src/main/assets/models/, so on-device queries land in the
same vector space).

Usage:
    pipeline/venv/bin/python pipeline/vectorize_corpus.py \
        --input  corpus/seed/humanitarian_seed.json \
        --output app/src/main/assets/humanitarian_base.json
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from sentence_transformers import SentenceTransformer


MODEL_NAME = "intfloat/multilingual-e5-small"
EMBED_DIM = 384


def chunk_id_for(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]


def expand_seed_to_entries(seed: list[dict]) -> list[dict]:
    """One ES entry + one EN entry per seed item."""
    entries: list[dict] = []
    for item in seed:
        source = item["source"]
        section = item["section"]
        for lang in ("es", "en"):
            title = item[f"title_{lang}"]
            content = item[f"content_{lang}"]
            full_title = f"{source} — {section} ({lang.upper()}): {title}"
            entries.append({
                "lang": lang,
                "title": full_title,
                "content": content,
                "chunk_id": chunk_id_for(content),
            })
    return entries


def embed_entries(entries: list[dict], model: SentenceTransformer) -> list[dict]:
    """multilingual-e5 expects 'passage: ' prefix for documents."""
    texts = [f"passage: {e['content']}" for e in entries]
    vectors = model.encode(
        texts,
        batch_size=8,
        show_progress_bar=True,
        normalize_embeddings=True,
    )
    if vectors.shape[1] != EMBED_DIM:
        sys.exit(f"unexpected embedding dim: {vectors.shape[1]} != {EMBED_DIM}")
    out = []
    for e, v in zip(entries, vectors):
        out.append({
            "title": e["title"],
            "content": e["content"],
            "chunk_id": e["chunk_id"],
            "vector": [float(x) for x in v.tolist()],
        })
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    seed_path = Path(args.input)
    out_path = Path(args.output)

    with seed_path.open() as f:
        seed = json.load(f)

    entries = expand_seed_to_entries(seed)
    print(f"loaded {len(seed)} seed items → {len(entries)} bilingual entries")

    print(f"loading model: {MODEL_NAME}")
    model = SentenceTransformer(MODEL_NAME)
    print(f"model loaded, encoding {len(entries)} passages")

    final = embed_entries(entries, model)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(final, f, ensure_ascii=False, indent=2)

    print(f"wrote {len(final)} chunks → {out_path}")


if __name__ == "__main__":
    main()
