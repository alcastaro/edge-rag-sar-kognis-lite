# Training Mode — Adaptive SAR Learning on Gemma 4

> Multi-tool adaptive-learning agent for Search-and-Rescue training. Runs entirely on-device on Gemma 4 E2B via LiteRT-LM.

## Why this exists

Field-assistant work and training-the-next-responder share the same corpus, the same protocols, and the same agentic pipeline. Kognis Lite already has all the pieces — system-prompt builder, multi-turn conversation lifecycle, RAG, voice in/out, function-calling-style structured output. Training mode reuses these to deliver personalized SAR education that adapts to each learner's mastery and pace.

The educator angle: instructors load a custom curriculum JSON; responders study INSARAG / UNDAC / MARCH protocols offline at their own pace; the agent quizzes, explains, and tracks mastery without ever sending a byte off the device.

## Architecture

```
operator input (text · voice · camera)
  ↓
[VoiceInputAgent | VisionAgent]
  ↓
LearningSessionRouter                  ← chooses normal RAG vs training path
  ↓                                    ↓
QueryPreprocessor (RAG path)           LearningOrchestrator (training path)
  ↓                                    ↓
RagOrchestrator                        LearningPromptBuilder
  ↓                                    ↓ (Hermes-inspired 4-section prompt)
Gemma 4 E2B (LiteRT-LM)                Gemma 4 E2B (same model, different prompt)
  ↓                                    ↓
LocationJsonExtractor                  SkillCallExtractor
  ↓                                    ↓ (parses SKILL: {…} tags)
MarkerStore / map action               LearningStore / quiz UI / mastery update
```

## The four learning skills

The model invokes these by emitting a final-line `SKILL: {...}` JSON tag. Sealed Kotlin registry — no dynamic code, no execution sandbox.

| Skill | What the model emits | What the app does |
|-------|----------------------|--------------------|
| `show_example` | `{"name":"show_example","topic":"MARCH"}` | Fetches a matching case study from the curriculum + RAG; renders it as a CaseStudyCard. |
| `quiz_user` | `{"name":"quiz_user","topic":"...","options":[…],"correct_index":N}` | Renders a Compose MultipleChoiceCard; learner taps an answer; correctness recorded as a `LearningFact`. |
| `review_past_misses` | `{"name":"review_past_misses","limit":3}` | Surfaces the last N topics with mastery < 0.5 and prepends them to the next prompt. |
| `mark_mastery` | `{"name":"mark_mastery","topic":"...","score":0.0..1.0,"rationale":"..."}` | Updates `TopicMastery` with exponential moving average; the next prompt's learner-model section reflects the change. |

## Storage — ObjectBox entities

Eight new entities under `data/learning/`. All reads are ~0.1 ms (B-tree). A custom token inverted-index on `LearningFact` replaces SQLite FTS5 — same recall semantics at half the latency for our fact volume.

- `LearningSession` — one session bound to a curriculum
- `LearningTurn` — raw conversation turn
- `LearningSummary` — rolling, session, and cross-session summaries
- `LearningFact` — subject/predicate/object/confidence with supersession chain
- `LearningPreference` — KV (tone, pace, language, etc.)
- `TopicMastery` — per-topic 0.0–1.0 with decay
- `SkillInvocation` — audit log
- `CurriculumModule` — cached parse of the curriculum JSON

## Curriculum JSON

Bundled at `assets/curriculum_sar.json`. Optional SAF import path lets instructors push custom curricula. Schema:

```json
{
  "id": "insarag-iec-2024",
  "title": "...",
  "language": "es",
  "modules": [
    {
      "id": "march",
      "topic": "MARCH protocol",
      "difficulty": "intermediate",
      "case_studies": [{ "id": "march-01", "text": "...", "key_points": [...] }],
      "quiz_seeds":   [{ "prompt": "...", "options": [...], "correct_index": 1 }]
    }
  ]
}
```

## The prompt that drives it

`LearningPromptBuilder` reconstructs the system prompt every turn from four sections:

1. **Identity** — static tone + language preference
2. **Learner model** — top-5 mastery + recent misses + pace (rebuilt from `TopicMastery` + `LearningFact`)
3. **Session context** — latest rolling summary + last 6 raw turns
4. **Skill catalog** — JSON function-call shapes the model may emit

Rolling summary is regenerated every 8 turns by a `LearningDeriverWorker` (WorkManager). On session close, facts with confidence ≥ 0.7 are promoted from session-scoped to cross-session, so the next session starts with what the learner already knew.

## What it inherits from existing infrastructure

| Existing piece | How training mode reuses it |
|----------------|------------------------------|
| `Conversation` KV-cache lifecycle | Same — slidng window unchanged. |
| `RagOrchestrator.evaluate(ragMode=…)` | Threaded with a `trainingMode` flag. |
| `RagPromptBuilder` | New `trainingMode` branch delegates to `LearningPromptBuilder`. |
| `SecurePrefs` | Used for non-structured preference fallback. |
| ObjectBox `MyObjectBox` | Same `BoxStore` instance, new entities added. |
| AIDL `IFieldCore` | Three new methods added; no breaking changes. |
| Voice in / Voice out | Same agents drive training too. |
| Vision agent | Future: identify image of medical equipment, route to curriculum module. |

## Curriculum design — corpus-grounded modules

Modules in `assets/curriculum_sar.json` are ordered by corpus support. The CURRICULUM CONTEXT picker surfaces the first module by default for new learners and falls back to lowest-mastery topics for returning learners.

| # | Module | Corpus chunks | Anchor score (max) | Difficulty | Notes |
|---|--------|---------------|--------------------|------------|-------|
| 1 | **UNDAC mission cycle** | 789 | 0.953 | beginner | Primary demo example. 3 phases (pre/on/end), OSOCC + RDC stand-up. Fully grounded in UNDAC Field Handbook (2022). |
| 2 | INSARAG team classification | 22 (high-quality) | 0.920 | beginner | Light / Medium / Heavy USAR structural-capability matrix. Grounded in INSARAG Guidelines Vol II Manual A. |
| 3 | START triage | 213 | strong | beginner | 4-colour multi-casualty classification. |
| 4 | Hazard classification | 244 | strong | beginner | Maps to Kognis 8-marker hazard taxonomy. |
| 5 | Radio protocols (SITREP / LSAR) | 214 | strong | beginner | INSARAG-standard ordering. |
| 6 | MARCH protocol | 0 direct | n/a | intermediate | Trauma protocol — limited direct corpus coverage. Module retained for trauma drills but the model leans on Gemma 4 baseline knowledge rather than RAG. See module summary disclaimer. |

**Why UNDAC mission cycle is the primary demo example:**

- 789 corpus chunks (highest coverage of any single SAR topic in the knowledge base)
- Highest-quality anchor scores in the corpus (top 5 chunks all > 0.92)
- Maps directly to the public UNDAC Field Handbook (2022) — judges can verify every fact against an open document
- Concrete operational structure (3 phases × OSOCC/RDC × A&A function outputs by phase) gives the model rich, well-bounded material for adaptive teaching
- Pairs naturally with the marker / map subsystems already in the app (OSOCC location, sectorisation, RDC at airport)

**MARCH protocol notice:** the on-device corpus has zero chunks directly about MARCH. The module is retained because trauma drills are a useful teaching context, but the model will pull from Gemma 4's baseline trauma knowledge, not RAG. For fully-grounded sessions prefer UNDAC mission cycle or INSARAG team classification.

## Adaptive teaching flow (S28+)

System prompt rules (see `LearningPromptBuilder.kt`) enforce:

- **R1** Always advance — short acks ("ok", "yes", "continue") trigger the NEXT chunk, never a recap
- **R2** Banned filler openers (specific phrases like "I see you are ready…", "Since we were just starting…")
- **R3** Substance first — 3–6 sentences of concrete content per turn, no meta-talk
- **R4** Anti-repetition — check last 3 assistant turns; rewrite if content/opener/example duplicates
- **R5** Quiz answer feedback + escalation — `[QUIZ ANSWER]` tag triggers 1-sentence feedback + next chunk + difficulty escalation
- **R6** Default flow = explanation + inline `**Quick check:**` text question (cheap, fast verification)
- **R7** Formal `SKILL: quiz_user` card ONLY when learner explicitly asks ("quiz me", "test me") or after 2+ inline checks on the current topic
- **R8** `What next?` block (3 numbered next-move options) on non-quiz, non-inline-check turns
- **R9** Adaptive topic rotation — after 3 turns on the same topic with rising mastery, pivot to a low-mastery topic from CURRICULUM CONTEXT sibling list
- **R10** Use CURRICULUM CONTEXT — paraphrase real case studies and quiz seeds; do not invent material outside that context

## Demo flow

1. Gear menu → **Start training**. Curriculum loads from `assets/curriculum_sar.json`.
2. Learner says or types: "Teach me the UNDAC mission cycle."
3. Gemma 4 E2B reads the learner model (empty on first turn) + UNDAC module + delivers the 3-phase overview inline, anchored on real corpus chunks. Closes with `**Quick check:** Which phase covers OSOCC stand-up?`.
4. Learner replies: "On-mission." Model gives 1-sentence feedback, delivers next chunk on OSOCC vs RDC roles, closes with a new Quick check.
5. After 2 inline checks the learner says "quiz me." Model emits `SKILL: quiz_user` with a 4-option card pulled from the UNDAC module's quiz seeds. QuizCard renders.
6. Learner taps wrong option. App writes a `LearningFact` (correct=false) AND sends a synthetic `[QUIZ ANSWER]` message back to the model with topic + selected option + correct option + result. Next prompt's R5 path triggers: 1-sentence feedback explaining WHY the correct answer is correct + next chunk + escalated quiz.
7. After 3 turns on UNDAC with rising mastery, R9 fires: model announces "You've got UNDAC fundamentals — let's move to INSARAG team classification" and pivots.
8. After 8 turns: WorkManager runs `LearningDeriverWorker` → rolling summary + facts extracted.
9. Learner ends session. Cross-session promotion fires. Next time, the session opens with `review_past_misses` surfacing the previously-failed step.

## What this is NOT

- **Not a code-execution agent.** No skill in the registry runs code. Skills are app-side UI actions only.
- **Not cloud-backed.** No sync, no analytics, no telemetry. All learner state stays on the phone.
- **Not multi-user.** v1 is single-learner-per-device. Multi-profile is a v2 concern.
- **Not OS-level multitasking.** A training session and a normal field-assistant query do not coexist within the same chat — the user toggles via the gear menu.

## References

- **Hermes Agent** (Nous Research) — layered prompt assembly + FTS-backed session recall
- **Honcho** (Plastic Labs) — `Workspace → Peer → Session → Message` storage model + deriver pipeline for summaries/facts
- INSARAG IEC Guidelines Vol. II (A/B/C) — corpus source for case studies
- UNDAC Field Handbook — corpus source for SAR coordination protocols
