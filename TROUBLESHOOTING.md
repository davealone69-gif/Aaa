# Troubleshooting

- `Invalid GLB magic`: choose a binary `.glb`, not an arbitrary file or HTML download.
- `GLB length field ...`: the file was truncated or modified; re-copy it.
- Filament cannot load asset: inspect the GLB JSON, buffers, images, and material references.
- No generator available: expected until a real local/worker provider is installed.

Errors are surfaced to the caller; no success state is fabricated.
