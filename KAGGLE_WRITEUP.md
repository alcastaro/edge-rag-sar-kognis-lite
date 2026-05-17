# Kognis Lite — Offline Humanitarian Field Assistant

**Gemma 4 Good Hackathon · Submission Writeup**
*Targeted tracks: LiteRT Special Technology · Global Resilience Impact · Future of Education Impact*

---

## The problem

When disaster strikes — earthquake, flood, conflict displacement — the first responders who matter most are often the last to have connectivity. INSARAG search-and-rescue teams, UNDAC coordinators, and community medics operate on disabled infrastructure: cell towers down, satcom shared by ten, PDFs on USB sticks. Cloud LLMs are useless in this scenario. Yet these responders need instant, grounded answers on protocols (MARCH, START triage, LZ selection, radio SITREP), and they need to record the rapidly evolving geography of an incident.

## What Kognis Lite is

A single Android APK running **Gemma 4 E2B locally on Snapdragon 8 Elite (Adreno 750 GPU) via Google AI Edge LiteRT-LM 0.11.0**. Zero network, zero data exfiltration, ~0.15 Wh per query.

**19 cooperating agents and tools** turn operator input (text, voice, camera) into deterministic field actions:

- **Hybrid RAG** (ObjectBox HNSW + BM25 RRF) over a 1,153-chunk INSARAG / UNDAC corpus with multilingual-e5-small 384-dim embeddings.
- **Voice in** (Android SpeechRecognizer, on-device) + **voice out** (TextToSpeech). Hands-free field operation, gloves on.
- **Vision agent** (ML Kit Latin OCR, bundled ~3 MB) reads medication labels, routes extracted text back through RAG for dosage lookup.
- **Map agent**: live GPS puck, route polyline + total km, 8 INSARAG-aligned SAR marker types (V/M/H/C/X/M/B/W), tap-to-mark, OsmAnd / Google Maps / osmdroid handoff.
- **Adaptive learning agent**: Hermes-inspired 5-section system prompt, 4 sealed skills (`show_example`, `quiz_user`, `review_past_misses`, `mark_mastery`), per-topic mastery with EMA, cross-session fact promotion.
- **Function-calling pattern** via structured sentinel tokens (`LOCATION_JSON:` and `SKILL:`), parsed by a brace-counting JSON extractor.

## Why Gemma 4

Gemma 4 E2B is the right point on the device-capability curve. On-device benchmarks (Samsung Galaxy S24 Ultra, sustained 50-question eval):

| Metric | Value |
|--------|-------|
| Tokens/sec (cold) | ~22 |
| Tokens/sec (sustained, thermal-throttled) | ~14 |
| RAG hit rate | 96% |
| Hallucination resistance | 100% (forced-RAG mode) |
| Peak temperature | ~77 °C |
| Per-query energy | ~0.15 Wh (vs ~4.3 Wh cloud — 28× reduction) |

Multilingual ES + EN instruction-following is strong enough to handle Spanish field terminology with no fine-tuning. The model file path is `gemma-4-E2B-it.litertlm`, sideloaded onto `/data/user/0/io.kognis.lite.sar/files/models/`.

## LiteRT engineering depth

This is not a Hello-World LiteRT integration. We built production-grade Gemma 4 lifecycle for long sessions on thermally-constrained devices:

1. **Custom `Conversation` lifecycle with KV-cache hash invalidation.** `RagOrchestrator.getOrCreateConversation()` hashes the system prompt; reuses the native handle when unchanged; closes the previous Conversation before allocating a new one (prevents native leak across hundred-turn sessions).
2. **Five distinct prompt modes** (`Auto` / `Siempre` / `Desactivado` / `NoMap` / `Training`) routed through the same Conversation lifecycle, with per-turn `mapModeOverride` so vision and training queries don't trigger map-marker hallucinations.
3. **Sliding-window resets** bounded by `maxTurns`; emits a callback the UI surfaces when memory wraps.
4. **Per-query system-prompt switching without reset.** When the pre-LLM agent (`QueryPreprocessor`) has already placed a marker deterministically, a one-line system-note is appended to the user message instead of rewriting the system prompt. KV cache survives.
5. **Function-calling shim.** LiteRT-LM 0.11.0 does not yet expose `ToolProvider` for Gemma 4 E2B; the structured-output contract (`LOCATION_JSON:` / `SKILL:`) is a faithful substitute. When the native API lands, swapping is a single class change.
6. **Separate-process AIDL isolation.** The LiteRT runtime lives in `:field_core`, a dedicated Android process. Native crashes don't kill the UI process — it auto-rebinds.
7. **Thermal + tok/s instrumentation** exported as JSON by the in-app eval runner (`EvalRunner.kt`, 50 questions; `GisEvalRunner.kt`, 13 mapping orders).

## Reproducibility

- **Code:** https://github.com/alcastaro/kjognis-lite (branch `kognis_lite`, Apache 2.0)
- **APK (~272 MB, install via `adb install -r`):** https://github.com/alcastaro/kjognis-lite/releases/tag/v1.0-hackathon
- **Notebook companion** (RAG pipeline reproducible without an Android device — uses `gemma-3-4b-it` cloud API as honestly-disclosed stand-in for the LiteRT runtime): `notebooks/kognis_lite_sar_demo.ipynb`
- **Demo video:** YouTube link added on final submission
- **Hardware tested:** Samsung Galaxy S24 Ultra (Snapdragon 8 Elite, 12 GB RAM, Android 16)

## Why this matters (the Good)

The "Good" in Gemma 4 Good is the humanitarian thesis: AI deployed where it actually helps. UN OCHA coordinates ~450,000 humanitarian aid workers per year. UNHCR reports 117 million displaced people as of 2026, ~one-third in low-connectivity zones. A 1% adoption rate translates to ~4,500 responders with on-demand triage, protocol, and geospatial support at the moments when the cloud isn't there.

**Why offline matters.** Patient location, casualty counts, evacuation routes, and operational coordinates are exactly the data classes the ICRC *Data Protection in Humanitarian Action Handbook* (2nd ed., 2020) flags as sensitive operational data. Cloud LLM inference creates audit trails, cross-jurisdiction data flows, and third-party uptime dependencies. Kognis Lite processes all of this on the responder's device.

**Climate angle.** SAR work is increasingly climate-driven: hurricanes (Maria 2017, Beryl 2024), megafloods (Pakistan 2022, Libya 2023), wildfires (Lahaina 2023), heat-dome casualty events (Europe 2023). The responder population is growing precisely as the infrastructure they depend on becomes more fragile. Kognis Lite targets that growing, infrastructure-constrained population — at 28× less per-query energy than cloud.

## Kognis Lite vs cloud LLM

|  | Kognis Lite | Cloud LLM (GPT/Gemini/Claude) |
|---|---|---|
| Works on disabled infra | Yes — fully offline | No — requires cell or satcom |
| Latency to first token | ~1.5 s on Adreno 750 | 0.8–3 s + network |
| Throughput | 14–22 tok/s sustained | 30–60 tok/s when connected |
| Data leaves device | Never | Always |
| Cost per query | 0 (battery only) | $0.001–$0.05 |
| Energy per query | ~0.15 Wh | ~4.3 Wh (incl. datacenter) |
| Geospatial output | Native (LOCATION_JSON tool) | Plain-text coordinates |
| ICRC humanitarian-data alignment | Yes | Requires DPA + audit |

## Field scenario — earthquake response

**02:14 local, M6.8, Hispaniola. Cell network down.** Responder team A2 reaches a collapsed three-story residential. Voice query, gloves on: *"protocolo MARCH para hemorragia masiva por aplastamiento."* On-device transcript → `QueryPreprocessor` classifies as a knowledge request → `RagOrchestrator` runs HNSW+BM25 RRF against the corpus → Gemma 4 streams a three-sentence MARCH protocol with tourniquet placement, hemostatic packing, and evacuation-triage thresholds. Total: ~6 s.

Then: *"agrega víctima atrapada en mi ubicación con etiqueta sector B planta 2."* `QueryPreprocessor` detects GPS intent + SAR type `VICTIM`. Marker placed instantly with live device coords — no LLM round-trip. Medic taps export-JSON, share-intent hands the marker set off to the incoming team via Bluetooth/local drop. **Twelve seconds from voice to actionable triage decision plus synchronized map state with the next team, zero network egress.**

## Field scenario — training the next responder

Same operational tempo, different role. Medic-in-training opens the app, gear menu → Start SAR training. *"Teach me MARCH protocol."* Gemma 4 reads the curriculum module (bundled `assets/curriculum_sar.json`, 5 INSARAG-aligned modules) embedded in the system prompt, writes a case study inline ("M6.8 earthquake. Adult male, crush injury under slab. Pulse 130, RR 28. Distal pulse absent. 4 hours entrapment."), then emits `SKILL: quiz_user` with a real question + four options.

Learner taps wrong option. App writes a `LearningFact` with confidence 0.3. Next turn, the Hermes-style system prompt's `LEARNER MODEL` section flags the topic as low-mastery; the agent surfaces it for review. Across sessions, facts with confidence ≥ 0.7 are promoted to cross-session memory — so the second session starts knowing what the first session covered.

This is multi-tool adaptive learning that empowers the educator (custom curriculum via SAF import) and adapts to the individual (per-topic mastery EMA, pace preference, language detection per turn). The track description matches what we built, literally.

## What's honest

- **Notebook uses `gemma-3-4b-it` cloud API as stand-in** for reproducibility — LiteRT-LM is Android-native, doesn't run in Kaggle Notebook environment. Disclosed at notebook cell 0. The APK is what runs Gemma 4 E2B on-device — see the video.
- **No native Gemma 4 multimodal yet** — LiteRT-LM 0.11.0 Kotlin API doesn't expose Gemma 4's vision/audio modality. We use ML Kit OCR + Android SpeechRecognizer + TextToSpeech as faithful agentic-tool substitutes. When LiteRT-LM exposes the native path, swap is a single class change.
- **Single-device validation** — all metrics from S24 Ultra (Snapdragon 8 Elite). Performance on lower-end SoCs is bounded by this claim, not generalized.
- **Function-calling is structured-output parsing, not native tool-calling** — same behavioral contract, different protocol, until LiteRT-LM exposes `ToolProvider` for Gemma 4.

## License

Apache 2.0 — see `LICENSE`.

---

*Built with Gemma 4 · LiteRT-LM 0.11.0 · ObjectBox HNSW · multilingual-e5-small · osmdroid · ML Kit · Android TextToSpeech*
