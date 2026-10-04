# Build

The sandbox now has JDK 21, Android SDK platform 35, build-tools 35.0.0, platform-tools/adb 37.0.1, and Gradle wrapper 8.9.

The checked-in `local.properties` points to the current sandbox SDK. On another machine, replace its `sdk.dir` with that machine's SDK path; do not commit a developer-specific path in a shared repository.

```bash
export ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
./gradlew :avatar-sdk:test
./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease
python3 worker/test_unavailable_state.py
```

The current build produced Debug APK, Release APK, and Release AAB. Filament native libraries emitted a standard strip warning and were packaged unstripped; this is not treated as a build failure.

No physical Android device is connected in this environment, so installation, ARM64 execution, rendering, frame rate, memory, generation, and process-death recovery remain UNVERIFIED. A device run must record `adb shell dumpsys meminfo`, a frame-time/FPS trace, import/render/export results, and recovery results before those matrix cells can become TESTED.
