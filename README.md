# Avatar Engine

A local-first Android SDK for real glTF 2.0 / GLB assets.

## Verified in the sandbox

The sandbox now has JDK 21, Gradle 8.9, Android SDK platform 35, build-tools 35.0.0, and adb. `:avatar-sdk:test`, Debug APK, Release APK, and Release AAB all build successfully. Filament 1.56.0 API integration compiles, the sample app has real GLB import/preview/export controls, and the Python Worker has an executable truthful-unavailable black-box test.

## Current status: NOT COMPLETE

No physical Android device is connected, so installation, ARM64 execution, Filament rendering, frame rate, memory, process-death recovery, and device generation remain UNVERIFIED. No ComfyUI, Hunyuan3D, or TripoSR model/workflow is installed in this sandbox, so real generation and PBR texturing remain UNVERIFIED. Room migrations, model installation/checksum management, parametric avatar generation, rigging, ARKit blendshapes, LOD generation, and animation controls are not yet complete.

The project does not use fake generation, fake progress, placeholder GLBs, fabricated worker responses, or hidden paid cloud dependencies.

See `COMPLETION_MATRIX.md` and `VERIFICATION_REPORT.md` for the hard status matrix. See `worker/README.md` and `BRIDGING.md` for exact local model integration.
