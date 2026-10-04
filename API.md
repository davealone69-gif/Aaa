# API

## Basic library API

```kotlin
val engine = AvatarEngine.initialize(context)
val asset = engine.importGlb(uri, "My avatar")
val loaded = engine.load(asset.metadata.id)
engine.listAvatars()
engine.exportGlb(asset.metadata.id, outputUri)
engine.delete(asset.metadata.id)
```

For rendering, add `AvatarView` to a layout and call `loadGlb(asset.glb)`. It loads actual GLB geometry through Filament; it does not use a 2-D preview.

## `AvatarGenerationProvider`

`AvatarGenerationProvider` is a provider plug-in contract. A provider declares a stable `id`, human-readable `name`, and exact `capabilities`. `status()` must perform a real health/model check and return `READY`, `UNAVAILABLE`, `NOT_INSTALLED`, `INSUFFICIENT_RESOURCES`, or another truthful state. `generate()` returns a `GenerationHandle` containing a cancellable coroutine result and a `StateFlow<GenerationProgress>`.

A provider may emit an indeterminate progress value (`fraction = null`) when the underlying engine does not expose progress. It must not invent percentages. The result may be `success` only after an actual GLB file exists and `GlbValidator.validate()` succeeds. Failures must remain failures and should include the worker/model error and a recovery message.

## Included real provider: `LocalHttpGenerationProvider`

This adapter connects the SDK to a local Termux process, ComfyUI bridge, workstation, or other LAN worker. It requires:

```text
GET  /health
POST /v1/generate       -> {"jobId":"..."}
GET  /v1/jobs/{jobId}   -> {"phase":"GENERATING", "progress":0.4, "message":"..."}
GET  /v1/assets/{assetId} -> application/gltf-binary
```

The SDK sends `prompt`, `kind`, `quality`, optional `seed`, and optional base64 image data. It polls the job, downloads the completed GLB, validates it locally, and only then returns `AvatarAsset`. Cancellation cancels the polling coroutine and does not report completion.

## Content policy boundary

`ContentPolicyValidator` runs before the HTTP request is sent. Adult mode is off by default and requires `ageGateConfirmed`. Requests containing explicit minor/underage terms or an age below 18 are rejected. Reference photos require both `photoIsSelfOwned` and `photoConsentConfirmed`; reference-photo generation is disabled when adult mode is enabled. These checks are local SDK policy checks, not a claim that a remote model can infer age or identity reliably.

This is an adapter, not a fake generator: a compatible server and a real open-source model must be installed separately. A missing server or model is exposed as unavailable.

## Provider selection

`GenerationManager` checks capabilities and serializes heavy generation jobs with a `Mutex`, preventing several large models from loading simultaneously. If no provider is installed for a request, it returns an explicit failure rather than silently falling back to a placeholder.
