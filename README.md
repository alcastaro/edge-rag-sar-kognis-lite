# Kognis Lite — Offline Humanitarian Field Assistant

> **AI assistant for civil search-and-rescue, civil protection, and emergency medical responders operating in areas without connectivity.**

Built for the **Gemma 4 Good Hackathon** — Global Resilience track + LiteRT Special.

---

## What is Kognis Lite?

Kognis Lite is an Android assistant that runs **100% offline** on a mid-to-high-end consumer phone. No internet, no servers, no telemetry. It is designed to help first responders consult evacuation protocols, triage guidelines, and humanitarian doctrine in environments where cellular coverage is unreliable or absent.

It uses **Google's Gemma 4 E2B** (via LiteRT-LM) running on the device GPU, with a local hybrid RAG pipeline (BM25 + ONNX vector search) over a public corpus of humanitarian standards (Sphere, OCHA, WHO, MSF, ICRC). Locations referenced in the model's answer can be opened directly in OsmAnd or in the in-app osmdroid fallback, and exported as GPX so they can be shared via Bluetooth / WhatsApp / file transfer.

---

## Project Status — v1.0-hackathon

| Component | State |
|---|---|
| Gemma 4 E2B via LiteRT-LM 0.11.0 (GPU) | ✅ |
| RAG over humanitarian corpus (BM25 + ONNX HNSW + RRF) | ✅ |
| OsmAnd integration (`geo:` Intent + AIDL marker push) | ✅ |
| GPX export for marker sharing | ✅ |
| osmdroid fallback (when OsmAnd not installed) | ✅ |
| Zero-Signal mode (`INTERNET` permission disabled by default) | ✅ |
| Multi-turn memory with cross-reset summarizer | ✅ |
| Thermal monitor (sysfs) | ✅ |
| Performance dashboard | ✅ |
| Dark OLED UI (Jetpack Compose) | ✅ |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                       KOGNIS LITE (Android)                          │
│                                                                      │
│  MainActivity.kt — Jetpack Compose UI                                │
│    ├─ AssistantMessage → LocationJsonExtractor → MapButton           │
│    ├─ OsmAndBridge — geo: Intent + AIDL marker push                  │
│    ├─ MarkerStore — session-wide marker accumulator                  │
│    ├─ MapFallbackView — osmdroid Compose multi-marker BBox           │
│    └─ GpxExporter — write /sdcard/Download/kognis_markers.gpx        │
│                                                                      │
│  FieldAssistantService.kt — background LLM + RAG                     │
│    ├─ LiteRtModelRunner → Gemma 4 E2B (LiteRT-LM, Adreno GPU)        │
│    ├─ RagOrchestrator                                                │
│    │    ├─ COORD_PATTERN bypass (explicit lat/lon → skip retrieval)  │
│    │    ├─ EmbeddingEngine (ONNX multilingual-e5-small)              │
│    │    ├─ ObjectBox HNSW (nearestNeighbors, 384 dims)               │
│    │    ├─ ConversationSummarizer (≤150-token cross-reset memory)    │
│    │    └─ RagPromptBuilder (verbosity + mapMode appendix)           │
│    └─ ThermalGovernor + PerformanceLogger                            │
└──────────────────────────────────────────────────────────────────────┘
```

Everything is local. No outbound network calls during inference.

---

## Model

| Name in UI | File | Runtime | tok/s\* | Multi-turn |
|---|---|---|---|---|
| Gemma 4 E2B | `gemma4-e2b-it-int8.litertlm` | LiteRT-LM GPU | 22–31 | 8 turns |

\*Measured on Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3 + Adreno 750), airplane mode, 19 realistic queries.

The model is **not** bundled in the APK (Google AI Edge license + size). It is sideloaded once to the app's private data directory via `scripts/sideload_model.sh`. See **Setup** below.

---

## Map Integration

When the model's answer references a geographic point, it appends a single trailing line:

```
LOCATION_JSON: {"lat": 9.7489, "lon": -83.7534, "label": "Hospital San Juan de Dios"}
```

`LocationJsonExtractor` parses the tag, removes it from the visible text, and shows an amber **"📍 Open in map"** button under the message. Tapping the button:

1. Opens **OsmAnd Plus / OsmAnd Free** via `geo:` Intent → marker drops at the location.
2. If OsmAnd Plus is installed, an AIDL bridge can also push markers programmatically (multi-marker push, no user intent).
3. If OsmAnd is not installed → in-app **osmdroid** map (BBox-fitting, OSM tiles).
4. All markers accumulate in `MarkerStore` and are visible from the toolbar map icon.

**Sharing markers**: from the markers screen, tap *Export GPX* → writes `kognis_markers.gpx` to `/sdcard/Download/`. Share with another responder via Bluetooth / WhatsApp / file transfer.

---

## Knowledge Base — INSARAG / UNDAC Humanitarian Corpus

The shipped knowledge base is built from open INSARAG and UN OCHA sources, processed with **Kognis Forge** (Gemma 4 on MacBook Pro M1 Max — Q&A pair generation + embedding).

### Corpus Validation Report (Kognis Forge)

```
╔══════════════════════════════════════════════╗
║   KOGNIS FORGE — Validation Report          ║
╠══════════════════════════════════════════════╣
║  Total Q&A pairs:         1153               ║
║  Avg Anchorage Score:     0.88               ║
║  Low Anchorage (<0.7):   0 (0%)         ✅   ║
╠══════════════════════════════════════════════╣
║  By Document:                                ║
║    › 1823826E_web_pages.pdf         773 pairs║
║    › INSARAG-Guidelines-V2-Manual-B 12 pairs ║
║    › INSARAG20Guidelines20Vol20II2C 135 pairs║
║    › INSARAG20Guidelines20Vol20II2C 14 pairs ║
║    › UC_Handbook_2022-2.pdf         72 pairs ║
║    › UC_Handbook_2022-3.pdf         74 pairs ║
║    › UC_Handbook_2022.pdf           73 pairs ║
╚══════════════════════════════════════════════╝
```

*Anchorage score = semantic similarity between Q and A, used to filter hallucinated pairs. 0% below threshold = high-quality corpus.*

### Source Documents

| Document | Source | License |
|---|---|---|
| INSARAG Guidelines Vol I & II (2015, 2020 revisions) | insarag.org | Public (UN) |
| UNDAC Handbook 2018 — UC Handbook 2022 (Parts 1–3) | unocha.org | Public (UN) |

### Corpus Schema (v5)

```json
{
  "id":          "sha256:<hex>",
  "question":    "What is the minimum daily water requirement per person in a camp?",
  "answer":      "Sphere standards require at least 15 litres per person per day...",
  "source_doc":  "1823826E_web_pages.pdf",
  "source_page": 42,
  "vector":      [384 floats — multilingual-e5-small, normalised],
  "anclaje_score": 0.894
}
```

`manuales_base.json` (16 MB) is **not tracked in git** (public repo) because it embeds derived question–answer pairs that are proprietary to the Kognis pipeline. The pipeline to regenerate it from public source PDFs is in `pipeline/vectorize_corpus.py`.

See `corpus/SOURCES.md` for full attribution.

---

## Required Assets

```
app/src/main/assets/
├── humanitarian_base.json          ← Vectorized humanitarian corpus
├── models/
│   └── multilingual-e5-small.onnx  ← ONNX INT8 embeddings (113 MB)
└── tokenizer/
    └── tokenizer.json              ← BPE vocab (16 MB)
```

The Gemma 4 model itself is **not** in the repo or the APK — see **Setup** for sideloading.

---

## Project Structure — Lite vs Full

This is **kognis-lite-sar** — the public, hackathon version. It uses only Gemma 4 models via LiteRT-LM. The full version (`Kognis-app`) includes additional model backends and private corpora and is not public.

| | kognis-lite-sar (this repo) | Kognis-app (private) |
|---|---|---|
| Model | Gemma 4 E2B only (LiteRT-LM) | Gemma 4 + additional backends |
| Corpus | INSARAG / UNDAC public docs | INSARAG + private regional docs |
| License | Apache 2.0 | Proprietary |
| Folder | `/Users/alcastaro/Documents/Kognis-Tak/kognis-lite-sar` | `/Users/alcastaro/Documents/Kognis-Tak/Kognis-app` |

---

## Setup (build + run on device)

### 1. Prerequisites

- Android Studio Ladybug or newer
- NDK 26.3.11579264 (only required if you re-enable native code)
- A physical Android ARM64 device, ≥ 8 GB RAM (validated: Samsung Galaxy S24 Ultra, Android 16)

### 2. Get Gemma 4 E2B (`.litertlm`)

Download `gemma4-e2b-it-int8.litertlm` (≈ 2.4 GB) from Google AI Edge / Kaggle Models (Gemma 4 LiteRT distribution). Place in `/sdcard/Download/` on the device.

### 3. Sideload the model

The app (`applicationId = io.kognis.lite.sar`) stores models in its private data dir. For a debug build, sideload via on-device pipe:

```bash
# Push model to external storage first
adb -s <SERIAL> push gemma-4-E2B-it.litertlm /sdcard/

# Copy into app's private data dir (requires debuggable APK)
adb -s <SERIAL> shell "cat /sdcard/gemma-4-E2B-it.litertlm | run-as io.kognis.lite.sar sh -c 'mkdir -p /data/user/0/io.kognis.lite.sar/files/models && dd of=/data/user/0/io.kognis.lite.sar/files/models/gemma-4-E2B-it.litertlm bs=4194304'"
```

**Note:** The model filename must be exactly `gemma-4-E2B-it.litertlm`. Validated with `gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm` from Google AI Edge Gallery.

### 4. Build and install

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Important:** The app's `applicationId` is `io.kognis.lite.sar` (namespace is `io.kognis.tactical`). Launch with:

```bash
adb shell am start -n "io.kognis.lite.sar/io.kognis.tactical.MainActivity"
```

### 5. Verify (logcat)

```bash
adb logcat FieldAssistant:D KnowledgeBaseLoader:I EmbeddingEngine:D RagOrchestrator:D
```

Expected startup sequence (validated on Samsung Galaxy S24 Ultra):

```
KnowledgeBaseLoader: ✅ KB loaded: 1153 chunks (1153 with vectors) in 1.6s
EmbeddingEngine:     === EmbeddingEngine: ONNX REAL MODE ACTIVE ===
RagOrchestrator:     Decrypted 1153 chunks; index: 7572 terms, IDF ready
FieldAssistant:      === initializeCore COMPLETE in 19486ms ===
```

---

## Reproducibility (Colab Notebook)

`notebooks/kognis_lite_sar_demo.ipynb` reproduces the corpus build, the RAG pipeline, and a 10-query hit-rate benchmark on a free Kaggle / Colab T4 instance. No Android device required to run the notebook.

---

## Hardware Target

| Device | Support | Notes |
|---|---|---|
| Samsung Galaxy S24 Ultra (SM-S928B) | ✅ Primary | Android 16, 12 GB RAM, Snapdragon 8 Gen 3 |
| Samsung Galaxy S23 / S24 | ✅ Compatible | Same ARM64 architecture |
| Google Pixel 8 Pro | ✅ Compatible | Tensor G3 |
| Android 12+, ARM64, 8 GB+ RAM | ⚠️ Untested | Should work |
| x86 emulator | ❌ Not supported | LiteRT-LM requires ARM64 native |

---

## Zero-Signal — No Connectivity Required

Kognis Lite does not require nor use the internet after the initial model sideload. All inference — LLM, embeddings, vector search, maps — happens on the device.

Verify the absence of the `INTERNET` permission in the compiled APK:

```bash
aapt dump badging app-release.apk | grep INTERNET
# No output expected (only osmdroid tile cache may request it transiently)
```

---

## Roadmap

- **v1.0-hackathon** (this submission) — Gemma 4 E2B + RAG humanitarian + OsmAnd + GPX export
- **v1.1** — Expanded corpus (multilingual), function calling via LiteRT `ToolProvider`, encrypted KB at rest
- **v1.2** — Field validation with first-responder partners

---

## Credits

- **LLM Runtime (GPU):** [LiteRT-LM](https://ai.google.dev/edge/litert) 0.11.0 — Google AI Edge
- **Model:** [Gemma 4 E2B](https://deepmind.google/technologies/gemma/) — Google DeepMind (INT8 LiteRT)
- **Embeddings:** [intfloat/multilingual-e5-small](https://huggingface.co/intfloat/multilingual-e5-small) via ONNX Runtime
- **Vector DB:** [ObjectBox](https://objectbox.io) HNSW
- **Offline Map (fallback):** [osmdroid](https://github.com/osmdroid/osmdroid) 6.1.20 + OSM tiles
- **External map app:** [OsmAnd](https://osmand.net) (GPLv3, used via Intent + AIDL inter-process)
- **UI:** Jetpack Compose, Material 3

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Note that OsmAnd is GPLv3 and is only invoked via inter-process Intent / AIDL (no linking), so it does not affect the Apache 2.0 status of this codebase. OpenStreetMap tiles are © OpenStreetMap contributors (ODbL), which is honored in the osmdroid view attribution.
