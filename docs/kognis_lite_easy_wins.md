# Kognis Lite — Pre-Submission Analysis & Session Changelog
_Last updated: 2026-05-16 (Session S17) — Gemma 4 Good Hackathon (deadline May 18)_

---

## Session S17 — What Was Built

### F10: SAR marker types
**Files:** `MarkerStore.kt`, `AssistantMessage.kt`, `MapFallbackView.kt`, `JsonMarkerExporter.kt`
- Replaced previous marker types with 8 INSARAG-aligned SAR types:
  - VICTIM (V, red), MEDICAL (+ dark red), HAZARD (! amber), COMMAND (C, blue)
  - EXTRACTION (X, green), MISSING (M, orange), BASE (B, brown), WATER (W, light blue)
- Each marker rendered as a circle with letter/symbol drawn in center via Canvas API
- JSON export/import schema updated to carry new type field

### F11: LLM marker type routing
**Files:** `RagPromptBuilder.kt`, `MapOptionSheet.kt`
- `LOCATION_JSON` now includes optional `"type"` field: `{"lat": 18.44, "lon": -69.94, "label": "Medical Post", "type": "MEDICAL"}`
- System prompt extended: LLM taught all 8 SAR types and when to use each
- `MapOptionSheet` infers `SarMarkerType` from LLM output; falls back to COMMAND when field is absent or unrecognised

### F12: Eval runner
**Files:** `MainActivity.kt`, `EvalRunner.kt`, `sar_test.json`
- Gear menu → "Run RAG Eval" launches in-app automated eval
- Runs 50 questions (30 multi-chunk + 20 hallucination probes) from `sar_test.json`
- Each question passes through the full RAG+LLM pipeline; `clearConversation()` called between questions to prevent OOM
- Exports `kognis_eval_YYYYMMDD_HHmmss.json` to external storage
- Progress bar with cancel button displayed during run

### F13: clearConversation AIDL
**Files:** `IFieldCore.aidl`, `FieldAssistantService.kt`, `RagOrchestrator.kt`
- Added `clearConversation()` to `IFieldCore.aidl`
- Implemented in `FieldAssistantService` as `ragOrchestrator.resetConversation()`
- Prevents native OOM crash during back-to-back queries (eval runner + repeated user queries)

### F14: Pre-extraction coordinate router (QueryPreprocessor)
**Files:** `QueryPreprocessor.kt`, `MainActivity.kt`
- App-level intent detection extracts coordinates directly from user text before the query reaches the LLM
- Eliminates coordinate truncation bug (model generating `8.44` instead of `18.44`)
- When marking intent + coords detected: marker placed immediately with exact user coords; `ragMode="NoMap"` suppresses `mapModeAppendix` for that query

### F15: GPS marking support
**Files:** `QueryPreprocessor.kt`, `MainActivity.kt`
- "add marker at my location" / "mi ubicación" / "here" intent detected by `QueryPreprocessor`
- Uses device GPS to place marker immediately — no LLM round-trip required

### F16: GPS distance in marker context
**Files:** `MainActivity.kt`
- `buildQueryWithMarkers()` now includes Haversine distance from device GPS to each session marker
- Format: `1. Medical Post [MEDICAL] lat=18.44850 lon=-69.94177 (~0.3 km NE)`

### F17: Per-query mapMode
**Files:** `RagOrchestrator.kt`, `FieldAssistantService.kt`
- `RagOrchestrator` accepts `ragMode="NoMap"` which skips the `mapModeAppendix` system-prompt appendix for that query
- Reduces LOCATION_JSON false-positive rate from ~92% to near-zero for pure knowledge queries

### F18: "query: " prefix for embeddings
**Files:** `EmbeddingEngine.kt`
- `multilingual-e5-small` requires `"query: "` prefix on search queries and `"passage: "` on indexed documents
- Prefix added in `EmbeddingEngine`; improves semantic similarity scores across all retrieval paths

### F19: Map visual fixes
**Files:** `MapFallbackView.kt`, `MainActivity.kt`
- osmdroid zoom buttons hidden (cleaner field UI)
- `clipToBounds()` on inline map `Box` prevents chat text from overlapping the map widget
- Black background on map `Box` eliminates flash of white during tile load

### F20: Powered by Gemma 4
**Files:** `MainActivity.kt`
- Footer badge changed from "⚡ ZERO SIGNAL ACTIVE" to "Powered by Gemma 4"

---

## Session S16 — What Was Built

### F1: Map Interoperability (bottom sheet picker)
**Files:** `AssistantMessage.kt`, `OsmAndBridge.kt`
- "Ver en mapa" button opens a `MapOptionSheet` (ModalBottomSheet)
- Three options: Built-in map (osmdroid offline), OsmAnd, Google Maps
- Each option: adds marker to `MarkerStore` + opens the selected app
- Google Maps: `geo:lat,lon?q=lat,lon(label)` intent, detects install via package manager
- OsmAnd: existing `OsmAndBridge` intent bridge

### F2: JSON marker export/import (WhatsApp sharing)
**Files:** `JsonMarkerExporter.kt` (new), `file_provider_paths.xml`, `MainActivity.kt`
- Export: writes `kognis_markers_YYYYMMDD_HHmmss.json` to external files dir → `ACTION_SEND` intent
- Import: SAF `OpenDocument` launcher → parses JSON (tolerates object or bare array)
- Schema: `{exported_at, marker_count, markers:[{lat, lon, label, cot_type, timestamp_ms, query_preview, model}]}`
- Map screen header: Share(GPX) · Dataset(JSON) · CloudUpload(import) · Close buttons

### F3: Session markers as RAG context
**Files:** `MainActivity.kt`
- `buildQueryWithMarkers(query)` prepends active markers + Haversine distance to each query
- Capped at 10 markers. Injected as plain text before AIDL call (no protocol change needed)
- Format: `[SESSION MARKERS]\n1. Label — lat, lon (~2.3 km NW)\n...`

### F4: RAG audit dashboard — source/page metadata
**Files:** `DocumentChunk.kt`, `KnowledgeBaseLoader.kt`, `RagResult.kt`, `RagOrchestrator.kt`, `FieldAssistantService.kt`, `AssistantMessage.kt`
- `DocumentChunk` gains `sourcePage: String?` field; KB_VERSION bumped 5→6 (auto re-ingest)
- `RagChunk` carries `sourcePage`; all 3 search paths (vector/hybrid/text) pass it through
- `RagSourcesSheet`: each chunk rendered in bordered card — full content always visible
- Tap "▼ Ver fuente" → expands metadata: Manual (source_doc), Página, Chunk #N
- Content limit raised from 500 → 1500 chars in `RagOrchestrator`

### F5: CoT marker types
**Files:** `MarkerStore.kt`, `AssistantMessage.kt`, `MapFallbackView.kt`, `JsonMarkerExporter.kt`
- `CotType` enum: FRIENDLY (blue), MEDICAL (red), CASUALTY (red), HAZARD (yellow), OBJECTIVE (black), EXTRACTION (green)
- `MarkerStore.Entry` gains `cotType: CotType = CotType.FRIENDLY`
- `MapOptionSheet` shows FilterChip type picker before map app selection
- osmdroid map: each marker gets a colored circle icon matching its CotType
- JSON export/import includes `cot_type` field

### F6: UX improvements
**Files:** `MainActivity.kt`, `MapFallbackView.kt`, `AssistantMessage.kt`
- **Send button always visible** (was hidden when input empty) — gray when disabled, amber when active
- **`+` button removed** — Camera, Gallery, Import corpus, Restore corpus moved into gear (⚙️) menu
- **Fullscreen from toolbar removed** — "Full map" text+icon button overlaid on inline map (top-right)
- **My Location button** on inline map (bottom-right) — `Icons.Default.MyLocation`, animates to GPS coords
- **"1 marcador"/"Limpiar"** hardcoded strings → `pluralStringResource(R.plurals.map_marker_count)` / `stringResource(R.string.map_clear)` — respects device language

### F7: Observability fix
**File:** `MainActivity.kt`
- System Prompt show/hide toggle was broken — column had no scroll, expanding content clipped off screen
- Fix: `verticalScroll(rememberScrollState())` on observability column + `skipPartiallyExpanded = true`

### F8: LOCATION_JSON fixes
**Files:** `RagPromptBuilder.kt`, `LocationJsonExtractor.kt`
- **System prompt rule 1 rewritten**: now catches raw decimal pairs `18.44, -69.94` (was only `"lat X lon Y"` keywords)
- New wording: "ADD/MARK/PIN at [coordinates] = ALWAYS emit LOCATION_JSON, do NOT refuse"
- **`stripTag` regex**: changed `[^{}\n]*` → `[^{}]*` with `DOT_MATCHES_ALL` to handle multi-line JSON
- Added `ORPHAN_TAIL` regex to strip leftover `"key": "value"}` fragments from failed partial matches

### F9: Corpus isolation
**Files:** `corpus/dist/manuales_base_v6.json` (new), `corpus/README.md` (new), `.gitignore`
- `corpus/dist/` now contains the canonical 1153-chunk corpus (16 MB, version-tracked)
- `corpus/README.md` documents schema, restore command, pipeline steps
- `.gitignore` comment updated to note `app/src/main/assets/manuales_base.json` is now in `corpus/dist/`

---

## System Prompt (current — Gemma 4 E2B Standard verbosity English)

```
You are Kognis Lite, an offline humanitarian field assistant.

Rules:
1. Answer directly without preamble.
2. Maximum 3 sentences. Plain text. Lists ≤4 items.
3. Respond in the operator's language.
4. Only use coordinates, doses, references that appear in context.
5. If you don't have the information: "I don't have that information in my humanitarian field knowledge base."

MARKER OUTPUT RULES (strict):
1. When operator says ADD/MARK/PIN/PLACE at decimal coords (any format), output
   LOCATION_JSON with those EXACT coords and requested label. Do NOT refuse.
2. If no numeric coords in query AND no RAG chunk provides them — do NOT emit.
3. Append on VERY LAST LINE: LOCATION_JSON: {"lat": <decimal>, "lon": <decimal>, "label": "<label>"}
4. Decimal degrees only. Minus for south/west.
```

---

## Pending / Still To Do

### CRITICAL (before submission May 18)
- [ ] **Record 3-min demo video** — the #1 deliverable
  - Demo flow: ask water question (RAG fires) → mark location with coordinates (QueryPreprocessor exact coords) → open full map → show 8 SAR marker types → export JSON
- [ ] **Kaggle submission form** — upload notebook + video link

### HIGH priority (2–4 hours)
- [ ] **Tile pre-cache for demo region** — script to pre-populate osmdroid cache so map shows tiles not gray squares in video
  - Location: demo region, zoom 12–17
- [ ] **GitHub public push** — verify gitignore excludes private corpus; push `kognis-lite` branch

### MEDIUM (if time allows)
- [ ] **Camera / medicine image recognition (v1.1)** — Camera menu item grayed, shows waitlist dialog
  - Need: CAMERA permission, TakePicture launcher, Bitmap→LiteRT input, fixed dosage-check prompt (~2-3h)
- [ ] Add "1153 chunks" line to observability panel
- [ ] Rename `update_map` string in EN to "View Markers" (more accurate)

### Thermal / Performance Notes
- **Max temperature observed: 75.9°C** during 50-question eval run on S24 Ultra (Snapdragon 8 Elite)
- Avg TPS dropped from ~21 tok/s (cold) to ~14 tok/s (sustained) under thermal throttling
- `clearConversation()` (F13) between eval questions prevents OOM but does not reduce heat load
- **NNAPI/NPU offload is important for field deployment** — routing embedding inference to the NPU would reduce CPU/GPU thermal load and keep TPS stable during extended use; currently ONNX runs on CPU (NNAPI delegate enabled but compute units not pinned to NPU)

### LOW / Post-hackathon
- [ ] OsmAnd map tile offline packages for field regions
- [ ] Session persistence across process restarts (markers currently ephemeral)
- [ ] NPU compute-unit pinning for EmbeddingEngine to reduce thermal throttling

---

## Current Build State (2026-05-16 S17)
```
KnowledgeBaseLoader: KB loaded: 1153 chunks (1153 with vectors) in 1.3s
EmbeddingEngine:     ONNX REAL MODE ACTIVE (NNAPI=true, 384-dim) — "query: " prefix active
RagOrchestrator:     1153 chunks decrypted; hybrid search ready; per-query ragMode support
FieldAssistant:      initializeCore COMPLETE in 9502ms
QueryPreprocessor:   coordinate extraction + GPS marking active
EvalRunner:          50-question eval (30 multi-chunk + 20 hallucination); exports kognis_eval_*.json
```
- KB_VERSION = 6 (stable, no re-ingest on next launch)
- SAR marker types: 8 INSARAG-aligned types (replaces 6 previous marker types from S16)
- LOCATION_JSON: now carries optional `"type"` field for SAR marker routing
- Eval results (S17): rag_hit_rate=0.96, hallucination_resistance=100%, avg_tps=14.1 tok/s (thermal-throttled), peak_temp=75.9°C
- APK: `app/build/outputs/apk/debug/app-debug.apk` — installed on S24 Ultra

---

## Test Queries for Demo

| # | Query | Expected |
|---|---|---|
| Q1 | `What is the Sphere minimum water per person per day?` | RAG fires · 15 L/day |
| Q2 | `Add a medical post at 18.448527, -69.941771` | LOCATION_JSON emitted · map pin appears |
| Q3 | `Where should I establish a triage area?` (no coords) | Answer only · no LOCATION_JSON |
| Q4 | Open map → tap Full map → Export JSON | Map screen + share intent |
| Q5 | Open RAG audit · tap chunk · Ver fuente | Source doc + page shown |

---

## Submission Checklist

- [x] `io.kognis.lite.sar` installed and running on S24 Ultra
- [x] Gemma 4 E2B loaded (LiteRT-LM, GPU, Adreno 750)
- [x] 1153 INSARAG/UNDAC chunks, KB_VERSION=6, ONNX real mode
- [x] Map interoperability (osmdroid / OsmAnd / Google Maps picker)
- [x] JSON marker export/import (WhatsApp sharing)
- [x] Session markers injected as RAG context with distances
- [x] RAG audit: tappable source metadata (manual, page, chunk #)
- [x] Marker types (6 types with colored map icons) — S16
- [x] Send button always visible
- [x] Gear menu consolidates all actions
- [x] My Location button on inline map
- [x] "Full map" text button on inline map
- [x] LOCATION_JSON system prompt + extractor fixes
- [x] Observability show/hide system prompt working
- [x] Corpus isolated in `corpus/dist/manuales_base_v6.json`
- [x] Apache 2.0 LICENSE in repo
- [x] 8 INSARAG-aligned SAR marker types with Canvas circle rendering (F10)
- [x] LLM marker type routing via LOCATION_JSON `"type"` field (F11)
- [x] In-app 50-question eval runner with JSON export (F12)
- [x] `clearConversation()` AIDL — prevents OOM on back-to-back queries (F13)
- [x] QueryPreprocessor: pre-extraction coordinate router — eliminates truncation bug (F14)
- [x] GPS marking ("at my location" / "here" / "mi ubicación") (F15)
- [x] GPS Haversine distance included in session marker context (F16)
- [x] Per-query `ragMode="NoMap"` — eliminates LOCATION_JSON false positives (F17)
- [x] `"query: "` prefix for e5-small embeddings (F18)
- [x] Map visual fixes: zoom buttons hidden, clipToBounds, black background (F19)
- [x] "Powered by Gemma 4" badge (F20)
- [ ] **Video demo (3 min)** ← CRITICAL — NEXT
- [ ] **Kaggle submission form + notebook upload** ← CRITICAL
- [ ] GitHub public push
