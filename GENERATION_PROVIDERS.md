# Generation providers

The SDK has one strict extension contract and one real transport adapter.

`AvatarGenerationProvider` is responsible for capability declaration, health checks, cancellable generation, truthful phase reporting, and returning only validated assets. `GenerationManager` selects a provider by capability and serializes heavy jobs.

`LocalHttpGenerationProvider` is intended for a real local worker on Termux, ComfyUI, Linux, or a workstation. It does not contain a model and cannot claim generation by itself. The worker must expose the documented `/health`, `/v1/generate`, `/v1/jobs/{id}`, and `/v1/assets/{id}` endpoints. A ComfyUI integration should translate the request into a pinned workflow and return a real GLB produced by an installed open-source model.

Pure Android/ARM64 generation is not declared READY by default. Most current high-quality image-to-3D and text-to-3D models require desktop-class memory/storage and are better run in a local worker; Android remains the offline renderer, library, validator, and customization host. A future mobile provider may be added only after a real ONNX/TFLite model, memory profile, and device test are available.
