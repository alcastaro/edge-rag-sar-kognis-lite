# Edge RAG System with Geospatial Capabilities for Search and Rescue

**Offline Gemma 4 RAG on Android for SAR + geospatial OsmAnd / Google Maps integration — 94.7 % retrieval accuracy, 22–31 tok/s on Snapdragon 8 Gen 3.**

*The Gemma 4 Good Hackathon · Hackathon Writeup · May 18, 2026*

---

## The Problem

Field operations happen where connectivity doesn't. Search-and-rescue coordinators, disaster relief teams, and emergency responders operate in areas with no cell coverage, degraded networks, or communication blackouts. Cloud-dependent AI tools fail exactly when they're needed most.

## What We Built

**Kognis Lite SAR** — an edge-native AI assistant for field teams, running **Gemma 4 E2B entirely on an Android device**, with **zero internet required**. Not a single byte leaves the phone during operation.

19 cooperating agents/tools (voice in/out, vision-OCR, hybrid RAG, pre-LLM intent router, marker store, map handoff, adaptive-learning orchestrator) interact through a single multi-turn conversation managed in a separate `:field_core` AIDL process for resilience.

## How It Works

**On-device inference stack:**

- **Gemma 4 E2B** via Google's **LiteRT-LM 0.11.0** on Adreno 750 GPU — 22–31 tok/s warm inference
- **Hybrid RAG** (ObjectBox HNSW + BM25 keyword, fused with Reciprocal Rank Fusion) over **1175 chunks** indexing UNDAC Field Handbook (2022), INSARAG Guidelines (Vol II Manuals A/B/C), USAR Field Manual, and the MARCH-TECC-TCCC-KATT pocket guide. 384-dim multilingual-e5-small embeddings
- **Geospatial subsystem** — three map backends with graceful fallback: **osmdroid** (in-app), **OsmAnd** (AIDL bridge + Intent), **Google Maps** (`geo:` Intent). 8 INSARAG-aligned marker types, polyline routing, JSON / GPX export to incoming teams via Android share sheet
- **Pre-LLM intent router** (`QueryPreprocessor`) — catches coordinate / marker / SAR-type intent in 80 ms so map actions never wait on the LLM and coordinates never hallucinate
- **Vision agent** — ML Kit on-device OCR (3 MB bundled) → drug-label text → routed back through the same RAG pipeline for grounded medication info
- **Adaptive learning subsystem** (Sprint S28) — Hermes-inspired 5-section prompt + 4 sealed skill calls (`quiz_user`, `show_example`, `mark_mastery`, `review_past_misses`). 6 corpus-grounded curriculum modules led by **UNDAC mission cycle** (789 corpus chunks). Per-topic mastery EMA + cross-session fact promotion via ObjectBox

**Key technical challenges solved:**

- Gemma 4's sliding-window attention requires special KV-cache handling. We targeted LiteRT-LM 0.11.0's OpenCL backend on Adreno GPU — a path not available in generic ONNX runtimes
- Locale-safe numeric formatting across the entire prompt + UI stack (a single comma-vs-dot mismatch caused a 10× distance-display bug we tracked down)
- ObjectBox cursor lifecycle hardening for the 50-query eval path (`Query.use{}` on every search)
- Native-handle race between in-flight inference and user-tap "Stop" (`cancelAndJoin` on coroutine reassignment)
- Per-turn `mapModeOverride` so vision and training queries don't trigger map-marker hallucinations
- Separate-process AIDL isolation — native crashes don't kill the UI process; it auto-rebinds

## Verified Results

| Metric | Value |
|--------|-------|
| Gemma 4 E2B warm inference | 22–31 tok/s |
| RAG hit rate | 94.7 % |
| Cold start | ~6.2 s |
| Multi-turn context window | 8 turns |
| Corpus size | 1175 chunks · 384-dim E5-small embeddings |
| Test device | Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750) |
| Operating mode | Airplane mode — 0 bytes outbound |
| Energy per query (vs cloud LLM) | ~0.15 Wh vs ~4.3 Wh — 28× lower |

Validated across the 50-question SAR eval, 13 GIS mapping orders, and live adaptive-training sessions.

## Impact

SAR teams lose critical minutes during operations looking up SOPs, terrain data, drug interactions, and resource inventories. **Kognis Lite SAR brings AI-assisted decision support to the field** — in the Andes, a hurricane-hit coast, a forest fire perimeter — with no cloud dependency. The same stack works on any mid-range Android device with an Adreno GPU, making it accessible to teams in low-resource settings.

Beyond field assistance, the **adaptive-learning mode** turns the same model + corpus + RAG into an INSARAG / UNDAC training tool — instructors load a curriculum JSON, responders study protocols offline at their own pace, the agent quizzes and tracks mastery without sending a byte off the device.

UN OCHA coordinates ~450,000 humanitarian aid workers per year. A 1 % adoption rate translates to ~4,500 responders with on-demand triage, protocol, and geospatial support at the moments when the cloud isn't there.

**Open source under Apache 2.0** so any humanitarian / SAR organisation can adopt and extend.

## What's honest

- **Notebook uses cloud Gemma stand-in** for reproducibility — LiteRT-LM is Android-native, doesn't run in Kaggle Notebook. Disclosed at notebook cell 0. The APK is what runs Gemma 4 E2B on-device — see the video
- **No native Gemma 4 multimodal yet** — LiteRT-LM 0.11.0 Kotlin API doesn't expose Gemma 4's vision/audio modality. We use ML Kit OCR + Android SpeechRecognizer + TextToSpeech as faithful agentic-tool substitutes
- **Function-calling = structured-output parsing**, not native tool-calling (`LOCATION_JSON:` / `SKILL:` sentinels parsed by a brace-counting JSON extractor). Behavioural contract is the same; swap to native `ToolProvider` is one class when LiteRT-LM exposes it
- **Single-device validation** — all metrics from S24 Ultra. Performance on lower-end SoCs is bounded by this claim, not generalized

## Submission links

- **Code (submission repo):** https://github.com/alcastaro/edge-rag-sar-kognis-lite
- **APK (~272 MB, install via `adb install -r`):** https://github.com/alcastaro/kjognis-lite/releases/tag/v1.0-hackathon
- **Demo video playlist (3 min):** https://www.youtube.com/playlist?list=PLcAvstgQ4zAGQY4mAhssH_2O0q8L4y5n_
- **Notebook:** `notebooks/kognis_lite_sar_demo.ipynb`

## Authors

- **Alberto Castillo Aroca** — Creator · [github.com/alcastaro](https://github.com/alcastaro)
- **Juana Casique** — [github.com/juanacasique](https://github.com/juanacasique)

**Tracks:** Main Track · Impact Track · Special Technology Track

## License

Apache 2.0 — see `LICENSE`.

---

*Built with Gemma 4 · LiteRT-LM 0.11.0 · ObjectBox HNSW · multilingual-e5-small · osmdroid · OsmAnd · ML Kit · Android TextToSpeech*
