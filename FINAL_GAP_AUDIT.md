# Aaa Final Zero-Trust Gap Audit

Audit date: 2026-10-05. The repository is a buildable Android GLB/avatar foundation with truthful local-worker boundaries. It is not yet an end-to-end AI media studio.

## Current evidence

The sandbox has verified Gradle tests, debug/release APK builds, a release AAB build, SDK unit tests, Python syntax checks, and Worker unavailable-state contracts. No Android device is connected. No Hunyuan3D, TripoSR, ComfyUI workflow, image model, Wan 2.2, LTX-2, audio model, or lip-sync backend is installed.

The new studio persistence layer is real at the repository/Room contract level: Room version 3 adds projects, scenes, and reference metadata; reference imports stream through controlled app storage, compute SHA-256, reject oversized files, sanitize names, prevent duplicate checksums, and never execute imported code. Android runtime import and restart recovery remain unverified.

## Status matrix

| Area | State | Evidence / limitation |
|---|---|---|
| GLB validation | REAL + VERIFIED | Structural validator tests pass |
| GLB library | REAL + UNVERIFIED | File/Room implementation exists; no device test |
| Filament renderer | REAL + UNVERIFIED | Compiles against Filament 1.56; no device |
| Project persistence | REAL + VERIFIED contract | Room v3 entities, CRUD repository, migration contract test; Android restart unverified |
| Scene persistence | REAL + VERIFIED contract | Stable project relation, JSON validation, save/load/duplicate/delete; renderer integration unverified |
| Reference import | REAL + VERIFIED contract | Streamed copy, SHA-256, path/name controls, duplicate detection, size cap |
| Reference previews | PARTIAL | Bounded text preview metadata; image/video dimensions and thumbnails not implemented |
| Reference-provider integration | UNAVAILABLE | Capability contracts exist; no installed image/video provider |
| 3D generation | NOT_INSTALLED | Worker boundaries exist, no runtime/weights |
| Image generation/editing | NOT_INSTALLED | Provider contract and unavailable endpoint only |
| Video generation | NOT_INSTALLED | Wan/LTX health and unavailable endpoint only |
| Audio/talking avatar | UNAVAILABLE | Contracts only; no backend |
| UI projects/scenes/references | MISSING | Existing UI is GLB import/export and adult-mode only; no dead controls were added |
| Worker project/reference API | NOT IMPLEMENTED | Android repository is the current persistence boundary; HTTP endpoints are not needed for offline local CRUD |
| Resource governor | REAL + VERIFIED contract | Device behavior unverified |
| Model manager | REAL + VERIFIED contract | Large-model installation not exercised |
| Adult mode | REAL + VERIFIED policy | Provider/model adult capability remains unavailable |
| CI | REAL + VERIFIED | Lightweight Gradle/Worker tests; no model downloads |
| Release signing | BLOCKED | No signing credentials |

## DISCONNECTED / INCOMPLETE FEATURES

### Feature: Project/scene/reference UI

**STATUS:** MISSING. **WHAT EXISTS:** SDK repository and Room records. **WHAT IS MISSING:** project list, scene editor, reference browser, import/preview/attach screens. **DEPENDENCY/BLOCKER:** Android UI work and lifecycle testing. **SMALLEST FIX:** add one screen backed directly by `StudioRepository`, starting with project list and reference import.

### Feature: Scene rendering

**STATUS:** PARTIAL. **WHAT EXISTS:** persisted scene JSON fields can hold future avatar, camera, lighting, animation, audio, and reference IDs. **WHAT IS MISSING:** renderer binding and validation against actual asset IDs. **DEPENDENCY/BLOCKER:** Filament device verification and asset relationship model. **SMALLEST FIX:** add a scene validator that reports missing avatar/reference IDs before attempting render.

### Feature: Image/video reference preview

**STATUS:** PARTIAL. **WHAT EXISTS:** secure file storage and metadata records. **WHAT IS MISSING:** image dimensions/thumbnails, video duration/dimensions/playback. **DEPENDENCY/BLOCKER:** Android media APIs and device tests. **SMALLEST FIX:** add bounded metadata extraction without loading whole files.

### Feature: Reference-to-provider input

**STATUS:** API EXISTS, PROVIDER MISSING. **WHAT EXISTS:** media capability models and GLB image-input boundary. **WHAT IS MISSING:** provider capability negotiation and rejection before submission for image/audio/video references. **DEPENDENCY/BLOCKER:** installed provider manifests/workflows. **SMALLEST FIX:** define a shared reference compatibility check and return `REFERENCE_TYPE_UNSUPPORTED`.

### Feature: 3D generation

**STATUS:** PROVIDER EXISTS, MODEL/BACKEND UNAVAILABLE. **WHAT EXISTS:** ComfyUI/Hunyuan3D/TripoSR bridge paths and truthful health. **WHAT IS MISSING:** installed runtime and valid generated output. **DEPENDENCY/BLOCKER:** explicit user-installed models and suitable hardware. **SMALLEST FIX:** test one installed image-to-3D backend on a workstation.

### Feature: Image generation/editing

**STATUS:** CONTRACT EXISTS, BACKEND UNAVAILABLE. **WHAT EXISTS:** Kotlin media contracts and Worker unavailable endpoint. **WHAT IS MISSING:** workflow registration, output validation, image asset persistence. **DEPENDENCY/BLOCKER:** local ComfyUI or another open model. **SMALLEST FIX:** add a pinned lightweight workflow only after a real backend is installed.

### Feature: Video/audio/talking avatar

**STATUS:** NOT INSTALLED / UNAVAILABLE. **WHAT EXISTS:** contracts and truthful failure endpoints. **WHAT IS MISSING:** backend adapters, real jobs, output validators, playback/export. **DEPENDENCY/BLOCKER:** Wan/LTX/audio/lip-sync runtimes and hardware. **SMALLEST FIX:** integrate one explicitly installed provider end-to-end.

### Feature: Unified job history

**STATUS:** PARTIAL. **WHAT EXISTS:** avatar generation jobs and common media job models. **WHAT IS MISSING:** one Room job schema for image/video/audio/render/export and persistent retry/resume. **DEPENDENCY/BLOCKER:** migration design and lifecycle tests. **SMALLEST FIX:** add a generalized job table without changing the existing avatar job table until migration tests cover both.

### Feature: Android restart/recovery

**STATUS:** DEVICE UNVERIFIED. **WHAT EXISTS:** file and Room persistence. **WHAT IS MISSING:** process-death/restart instrumentation and background job recovery. **DEPENDENCY/BLOCKER:** connected device/emulator. **SMALLEST FIX:** run install/import/restart/reopen tests on an Android target.

## HIGHLY RECOMMENDED UPGRADES

| Priority | Recommendation | Decision |
|---|---|---|
| P0 | Android project/reference UI backed by repository | IMPLEMENT NOW |
| P0 | Reference compatibility validation | IMPLEMENT NOW |
| P0 | Generalized persistent job history | DEFER until UI/job lifecycle is defined |
| P0 | Device install/launch/restart test | BLOCKED by no device |
| P1 | Image/video metadata extraction | IMPLEMENT NOW after repository tests |
| P1 | Backend capability/model manifest discovery | DEFER until a real workflow is installed |
| P1 | Crash recovery and resumable jobs | DEFER; current heavy backends are unavailable |
| P1 | Storage quota and cleanup | DEFER until media categories produce outputs |
| P1 | Diagnostics/backend dashboard | DEFER until a screen can show real health |
| P2 | Non-destructive timeline/editor | DEFER; no media output pipeline |
| P2 | Thumbnails, OCR, waveform, search | DEFER until media imports are exposed in UI |
| P3 | Commercial providers, cloud sync, advanced compositing | DEFER; not required for local-first core |

## RECOMMENDED NEXT BUILD ORDER

1. Add project/scene/reference Android screens backed by `StudioRepository`; verifiable without model downloads.
2. Add reference compatibility checks and explicit provider capability errors; verifiable without models.
3. Add image/video metadata extraction and bounded previews; verifiable with small fixture files.
4. Add Room integration tests using an Android test database for migration and restart behavior; no models required.
5. Add unified Room job history with retry/cancel state transitions; no models required.
6. Add a real ComfyUI workflow manifest registry and validation; verifiable against fixture manifests.
7. Connect one explicitly installed local image workflow; requires external backend but no CI model download.
8. Add provider-backed asset persistence and project/scene attachments; requires step 7.
9. Connect one local video provider (LTX-2 or Wan 2.2 based on available hardware); requires model installation.
10. Add real media playback/export validation and device verification; requires Android device and real media output.

## Final disconnected-feature rule

The repository must continue to distinguish architecture from connected backend, installed model, inference verification, and device verification. No unavailable feature should receive a fake preview, progress bar, generated asset, or `READY` status.
