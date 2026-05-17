# Kognis Lite — Offline Humanitarian Field Assistant

**Gemma 4 Good Hackathon | Submission Writeup**
*Tracks: Google Global Resilience Challenge · Special Track: LiteRT on Device*

---

## The Problem

When disaster strikes — earthquake, flood, conflict displacement — the first responders who matter most are often the last to have connectivity. NGO field workers, search-and-rescue teams, and community health volunteers operate where mobile data is unreliable or deliberately jammed. Yet they need instant access to clinical protocols, triage guidance, WASH standards, and coordination frameworks the moment a patient arrives or a sector needs to be cleared.

Current solutions fail in one of two ways: they require constant cloud connectivity (useless in the field) or they ship static PDF manuals (impossible to query conversationally in Spanish, French, or Arabic at 2 AM under a tarp).

---

## What Kognis Lite Does

Kognis Lite is an **offline-first, retrieval-augmented AI assistant** for humanitarian field operations. It runs entirely on an Android device — no internet, no server, no data exfiltration — powered by **Gemma 4 E2B via LiteRT-LM 0.11.0** on Android GPU (Adreno 750, Snapdragon 8 Gen 3).

**Core capabilities:**

- **Conversational field queries** in English and Spanish. Ask "How do I apply a tourniquet?" or "¿Cuál es el protocolo MARCH para hemorragia masiva?" — get a concise, source-cited answer in under 2 seconds.
- **Hybrid RAG (BM25 + HNSW + RRF):** The on-device knowledge base uses ObjectBox HNSW for dense semantic search and BM25 for keyword precision, fused with Reciprocal Rank Fusion. This hybrid approach outperforms either method alone, especially for multilingual medical terminology.
- **Automatic waypoint extraction:** When the model identifies a location (triage post, LZ, water point), it emits a structured `LOCATION_JSON` tag. The app parses this and drops a pin on the in-app map (osmdroid, offline tiles) and can push it directly to OsmAnd via the installed navigation app.
- **GPX export:** All session waypoints can be exported as a standard GPX 1.1 file and shared with OsmAnd, Garmin devices, or any field mapping tool.
- **Zero network I/O:** Model inference, embedding, retrieval, and map rendering all happen locally. The app requests no internet permission.

---

## Why Gemma 4?

Gemma 4 E2B (2B parameter instruction-tuned) hits the right point on the device-capability curve:

| Model | tok/s (S24 Ultra, Adreno 750) | RAM | Notes |
|-------|-------------------------------|-----|-------|
| Gemma 4 E2B | 22–31 tok/s | ~3.2 GB | **Primary runtime** |

At 22–31 tokens/second, a typical field response (80–120 tokens) completes in 3–5 seconds — fast enough for emergency use. The multilingual instruction-following is strong enough to handle Spanish medical terminology without fine-tuning.

LiteRT-LM 0.11.0 provides GPU-accelerated inference via Vulkan/OpenCL on the Adreno 750 with no model server, no Python runtime, no network call.

---

## Knowledge Base & RAG Architecture

```
User query (natural language)
      │
      ▼
RagOrchestrator
  ├─ EmbeddingEngine       ONNX runtime, multilingual-e5-small (384-dim)
  │                        Same model as vectorisation pipeline → identical space
  │
  ├─ BM25Searcher          Tokenised index over decrypted chunk content
  │                        Stopword filtering (ES + EN), accent normalisation
  │
  ├─ ObjectBox HNSW        Cosine similarity over 384-dim vectors
  │                        ef=200, M=16, ~4ms for 4000-chunk KB
  │
  └─ RRF fusion (k=60)     top-3 chunks → context block → FieldAssistantService
                            (Gemma 4 via LiteRT-LM)
```

**Corpus sources** (humanitarian open-access):
- Sphere Handbook (WASH, Shelter, Food, Health)
- WHO Emergency Response guidelines
- ICRC First Aid / surgical protocols
- MSF Clinical Guidelines
- OCHA Cluster coordination frameworks
- INSARAG USAR methodology (IEC-certified)

All corpus text is AES-256-GCM encrypted at rest in ObjectBox (Android Keystore key). Decryption only happens inside the sealed `:field_core` service process.

---

## Technical Highlights

### AIDL Service Architecture
The LLM inference runs in a dedicated Android service process (`:field_core`) isolated from the UI process via AIDL IPC (`IFieldCore` / `IFieldCallback`). This means:
- The model stays loaded in memory between queries (no reload overhead)
- The UI process can be killed without interrupting generation
- Other apps can bind to `FieldAssistantService` and issue queries via the AIDL interface

### On-Device Security
- Chunk content encrypted with AES-256-GCM, key stored in Android Hardware Keystore
- No network permission in AndroidManifest
- AIDL service exported=false — only same-UID binding

### Multilingual Pipeline
The vectorisation pipeline (`pipeline/vectorize_corpus.py`) uses `intfloat/multilingual-e5-small` — the same model compiled to ONNX for on-device inference. A corpus chunk embedded on a MacBook and a query embedded on the phone occupy the same vector space, so embeddings generated offline transfer directly to the device.

---

## Reproducibility

The Colab notebook (`notebooks/kognis_lite_sar_demo.ipynb`) reproduces the full RAG pipeline:
1. Load humanitarian seed corpus
2. Embed with `multilingual-e5-small`
3. BM25 + HNSW + RRF retrieval
4. Query Gemma 4 via the Gemma API
5. Parse `LOCATION_JSON` → folium map

No custom infrastructure needed. Run it on a free Colab T4.

---

## Impact

The target users are the ~450,000 aid workers deployed annually by OCHA-coordinated responses, MSF, ICRC, and national Red Cross/Crescent societies — many of them in areas with no reliable connectivity. Beyond NGO workers, the same architecture applies to:

- **Community health workers** in rural clinics (no internet, basic Android phones)
- **Disaster response volunteers** who receive a 2-day training before deployment
- **Search and rescue teams** operating in communications-blackout zones

Kognis Lite requires no subscription, no cloud account, and works on mid-range Android devices (Snapdragon 7-series and up with LiteRT GPU delegate). The knowledge base can be updated offline via a USB-transferred JSON file.

---

## License

Apache 2.0 — see `LICENSE`.

---

*Built with Gemma 4 · LiteRT-LM 0.11.0 · ObjectBox HNSW · multilingual-e5-small · osmdroid*
