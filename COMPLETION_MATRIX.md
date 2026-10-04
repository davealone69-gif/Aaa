# Hard completion matrix

Statuses are conservative and evidence-based. `REAL + VERIFIED` means an executable test or build passed in this sandbox; it does not imply physical-device verification.

| Capability | Status | Evidence / limitation |
|---|---|---|
| GLB import | REAL + UNVERIFIED | File import implementation exists; no Android device/import integration run |
| GLB validation | REAL + VERIFIED | Tests cover valid GLB, missing file, magic, declared length, JSON and structural references |
| Filament rendering | REAL + UNVERIFIED | Filament 1.56.0 API compiles; no connected device |
| `/health` | REAL + VERIFIED | Independent ComfyUI, Hunyuan3D shape, Hunyuan3D texture, and TripoSR states tested |
| `/v1/generate` | REAL + VERIFIED boundary | Creates jobs and returns a failed job with `3D_BACKEND_UNAVAILABLE` when no backend is ready |
| `/v1/jobs` | REAL + VERIFIED | Job failure transition tested |
| Job cancellation | REAL + UNVERIFIED | Authenticated `DELETE /v1/jobs/{jobId}` marks jobs CANCELLED and prevents later completion; physical upstream interruption is unverified |
| `/v1/assets` | REAL + UNVERIFIED | Streams validated assets; no real model asset available |
| Provider selection | REAL + VERIFIED | Image requests select IMAGE_TO_3D/IMAGE_TO_IMAGE_3D; text requires genuine TEXT_TO_3D |
| ComfyUI Bridge | REAL + UNVERIFIED | Executable API-format path; ComfyUI/workflow not installed |
| Hunyuan3D Shape | REAL + UNVERIFIED | Independent health and bridge path; exact server/version not installed |
| Hunyuan3D Texture | NOT_INSTALLED | Separate texture endpoint is not configured |
| TripoSR Bridge | REAL + UNVERIFIED | Real subprocess path; runtime/weights not installed |
| Text → image → 3D | NOT_INSTALLED | No pinned text-to-image workflow/model |
| Image → 3D | NOT_INSTALLED | No Hunyuan3D or TripoSR runtime/weights |
| PBR/texturing | REAL + UNVERIFIED | Boundary exists; no texture model installed |
| Rigging | UNSUPPORTED | No real rigging tool installed |
| Blendshapes | UNSUPPORTED | No ARKit-52-producing pipeline installed |
| Animation | PARTIAL | Filament GLB loading compiles; discovery/playback UI and device verification pending |
| Room persistence | REAL + UNVERIFIED | Room entities, DAOs, version-2 migration, asset/job repository, and GenerationManager integration compile; no physical Android migration run |
| Model manager | REAL + VERIFIED | SHA-256 registry/download/insufficient-RAM tests pass; HTTPS resume integration remains unverified |
| Resource governor | REAL + VERIFIED | RAM, storage, CPU cores and Android thermal status implemented; device behavior unverified |
| Content policy | REAL + VERIFIED | SDK validation runs before network transmission |
| Adult mode infrastructure | REAL + VERIFIED | Separate app-private setting, explicit 18+ enable dialog, disable action, SDK and Worker policy tests |
| Adult provider capability | UNSUPPORTED / UNVERIFIED | No configured backend reports verified adult capability; Worker rejects adult jobs unless explicitly verified by provider configuration |
| Adult reference photos | UNSUPPORTED | Disabled in adult mode; normal-mode photos require ownership and consent flags |
| APK | REAL + VERIFIED | Debug and unsigned Release APK build successfully |
| AAB | REAL + VERIFIED | Release AAB builds successfully |
| ARM64 packaging | REAL + VERIFIED | APK contains `arm64-v8a` Filament native libraries; runtime unverified |
| Installation | UNVERIFIED | `adb devices` has no connected device |
| Generation | UNVERIFIED | No real model backend installed |
| Rendering | UNVERIFIED | No Android device connected |
| Recovery | UNVERIFIED | Process-death instrumentation pending |
| Release signing | BLOCKED | No signing credentials were provided; release APK is explicitly unsigned |
