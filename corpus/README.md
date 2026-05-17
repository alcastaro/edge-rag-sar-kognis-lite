# Kognis Lite — Corpus Pipeline

## Directory layout

```
corpus/
  seed/                          ← raw source chunks (manually curated, in git)
    humanitarian_seed.json       ← 31 Sphere/humanitarian chunks, bilingual (ES+EN), no vectors
  dist/                          ← processed + embedded corpus ready for app (in git)
    manuales_base_v6.json        ← 1,153 chunks · v5 schema · 384-dim vectors · KB_VERSION=6
```

## What the app uses

`app/src/main/assets/manuales_base.json` — loaded by `KnowledgeBaseLoader` via `ASSET_PATH`.

This file is gitignored (avoids 16 MB duplicate in the tree). The canonical tracked copy is `corpus/dist/manuales_base_v6.json`. They are identical.

**To restore the assets copy after a fresh clone:**
```bash
cp corpus/dist/manuales_base_v6.json app/src/main/assets/manuales_base.json
```

## Current corpus (v6 — 2026-05-16)

| Field | Value |
|-------|-------|
| Chunks | 1,153 |
| Schema | v5: `id`, `question`, `answer`, `source_doc`, `source_page`, `source_text`, `vector[384]` |
| Embedding | multilingual-e5-small (384-dim) |
| KB_VERSION | 6 (bumped when `source_page` field was added to `DocumentChunk`) |
| Source docs | INSARAG Guidelines Vol II (A, B, C) · UC Handbook 2022 · UNDAC Handbook |

## Schema v5 — field reference

```json
{
  "id": "unique_chunk_id",
  "question": "trigger question for this chunk",
  "answer": "main content text",
  "source_doc": "INSARAG-Guidelines-V2-Manual-B-Operations.pdf",
  "source_page": 47,
  "source_text": "optional verbatim extract",
  "vector": [0.012, -0.034, ...]
}
```

## Adding new sources

1. Place raw chunks in `corpus/seed/` (no vectors needed at this stage)
2. Run embedding pipeline → produces `corpus/dist/manuales_base_vN.json`
3. Copy to assets: `cp corpus/dist/manuales_base_vN.json app/src/main/assets/manuales_base.json`
4. Bump `KB_VERSION` in `app/src/main/java/io/kognis/tactical/data/KnowledgeBaseLoader.kt`
5. Build and deploy — ObjectBox wipes and re-ingests automatically on first launch

## Scope

- **Public corpus only** in this repo. Private/extended corpora are not included in this repository.
