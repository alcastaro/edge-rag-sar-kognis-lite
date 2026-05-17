# Kognis Lite SAR — Gemma 4 Good Hackathon Submission

> **One-line:** Offline, on-device humanitarian SAR field assistant powered by Gemma 4 E2B via LiteRT-LM. Voice in → agentic routing → grounded answer + map action → voice out. Zero network.

## Submission links

| Asset | URL |
|-------|-----|
| 🎥 **Demo video (3 min)** | _(YouTube link added before submission)_ |
| 📦 **Public code** | https://github.com/alcastaro/kjognis-lite (branch `kognis_lite`) |
| 📄 **Writeup** | [`KAGGLE_WRITEUP.md`](./KAGGLE_WRITEUP.md) |
| 📓 **Notebook** | [`notebooks/kognis_lite_sar_demo.ipynb`](./notebooks/kognis_lite_sar_demo.ipynb) |
| 📱 **Live demo (APK + install instructions)** | See [Quickstart](#quickstart) below |

## What it is

A single Android APK that runs **Gemma 4 E2B locally** on Snapdragon 8 Elite (Adreno 750 GPU) via Google AI Edge LiteRT-LM 0.11.0, with:

- **Hybrid RAG** (HNSW + BM25, RRF fusion) over an INSARAG / UNDAC humanitarian-field corpus (1,153 chunks, `multilingual-e5-small` embeddings)
- **Voice in** (Android SpeechRecognizer, on-device) → **Voice out** (TextToSpeech) — hands-free field operation
- **Vision agent** (ML Kit Latin OCR, bundled ~3 MB) for medication-label identification → routed back through RAG for dosage lookup
- **Function-calling pattern** via structured-output parsing — model emits `LOCATION_JSON: {...}`, app dispatches to OsmAnd / Google Maps / osmdroid map action
- **Pre-LLM intent router** (`QueryPreprocessor`) — extracts coordinates / GPS phrases / SAR-type keywords and bypasses the LLM when deterministic action is enough
- **Live map**: osmdroid offline tiles, 8 INSARAG-aligned marker types (V/+/!/C/X/M/B/W), live GPS puck with tracking toggle, route polyline + total km, tap-to-mark long-press, per-marker delete
- **Separate-process AIDL isolation** — `:field_core` process owns the LiteRT runtime; UI process survives native crashes
- **Bilingual** (ES + EN) across UI, prompts, embeddings, voice in/out

## Tracks targeted

| Track | Why we fit |
|-------|-----------|
| **LiteRT Special Technology** ($10k) | Production-grade Gemma 4 LiteRT-LM 0.11.0 integration — custom KV-cache hash invalidation, per-query system prompt switching without reset, function-calling shim, AIDL process isolation, thermal/throughput instrumentation. See [LiteRT engineering depth](./KAGGLE_WRITEUP.md#litert-engineering-depth-gemma-4-e2b-on-device-runtime) in the writeup. |
| **Global Resilience Impact** ($10k) | Offline edge-based disaster response. Climate-driven SAR framing. ~28× less energy per query than cloud. ICRC-aligned data sovereignty for sensitive operational data. |
| **Main Track** | Function-calling agentic loop with five cooperating on-device components. Real working APK on a real phone. |

## Quickstart

```bash
# 1. Clone
git clone https://github.com/alcastaro/kjognis-lite.git
cd kjognis-lite

# 2. Build (Android Studio JDK 21; macOS shown)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug

# 3. Install on phone (Snapdragon 8 Gen 3 / 8 Elite recommended; 12 GB RAM)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Sideload the Gemma 4 E2B model (~2.3 GB, .litertlm format)
adb push <path-to>/gemma-4-E2B-it.litertlm /sdcard/gemma-4-E2B-it.litertlm
adb shell "cat /sdcard/gemma-4-E2B-it.litertlm | run-as io.kognis.lite.sar dd of=/data/user/0/io.kognis.lite.sar/files/models/gemma-4-E2B-it.litertlm bs=4194304"

# 5. Launch
adb shell am start -n "io.kognis.lite.sar/io.kognis.tactical.MainActivity"

# 6. (optional, for the demo) Pre-cache map tiles for Hispaniola
./scripts/precache_tiles.sh
```

## Demo flow (matches the 3-min video)

1. **02:14 local. M6.8 earthquake. Hispaniola. Cell network down.** Open the app; airplane mode is on.
2. **Voice query:** tap mic → say "protocolo MARCH para hemorragia masiva." On-device transcript → Gemma 4 streams a 3-sentence MARCH protocol answer in ~6 s, grounded in the INSARAG corpus.
3. **Mark víctima at GPS:** "agrega víctima atrapada en mi ubicación, sector B planta 2." `QueryPreprocessor` detects GPS intent + SAR type `VICTIM`. Marker drops instantly with live device coords — no LLM round-trip required.
4. **Open map. Route mode on.** Polyline GPS → markers, total km computed live.
5. **Identify medication.** Open camera → snap a blister pack → ML Kit OCR extracts the label → query routed to RAG (force mode) → grounded dosage answer.
6. **Export.** JSON marker set → share intent → ready to hand to the incoming team.
7. **Voice out on.** TTS speaks the answer. Phone never leaves the responder's pocket if they don't want it to.

12 seconds from voice to actionable triage decision. Zero network. ~0.15 Wh per query.

## What's honest about this submission

This is a working APK, not a mock-up. We measure things and they are not always flattering:

- Sustained throughput ~14 tok/s under thermal throttle on S24 Ultra. Cold start ~22 tok/s.
- Peak temperature 75.9 °C during the 50-question stress eval. Field deployment claim is bounded to the SoC family tested.
- LiteRT-LM 0.11.0 does NOT yet expose a `ToolProvider` interface for Gemma 4 E2B. The `LOCATION_JSON` parsing path is our substitute. When the native API lands, it's a single class change.
- Gemma 4 E2B's native vision/audio modalities are not yet exposed in the Kotlin runtime. We use ML Kit OCR and SpeechRecognizer as faithful agentic-tool substitutes today.
- The notebook uses `gemma-3-4b-it` cloud as a reproducible stand-in. The APK is what runs Gemma 4 E2B on-device — see the video for the real path.

All of this is disclosed in the writeup. We compete on truth, not polish.

## Architecture

```
operator input (text · voice · camera label)
  ↓
[VoiceInputAgent | VisionAgent]   ← pre-LLM modality tools (offline)
  ↓
QueryPreprocessor                 ← intent router (coord-mark / GPS-mark / knowledge)
  ↓
RagOrchestrator                   ← retrieval strategy (Auto / Always / Never / NoMap)
  ↓
Gemma 4 E2B (LiteRT-LM, GPU)      ← reasoning model
  ↓
LocationJsonExtractor             ← function-call parser → MarkerStore
  ↓
Android map action                ← OsmAnd / Google Maps / osmdroid
  ↓                                  ↓
TtsAgent (voice out)               Marker state + GPS distance → next prompt
```

See [README.md](./README.md) and [KAGGLE_WRITEUP.md](./KAGGLE_WRITEUP.md) for the full agent table and code-level detail.

## License

Apache 2.0 — see [LICENSE](./LICENSE).
