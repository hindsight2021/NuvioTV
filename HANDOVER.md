# Nuvio+ Development Handover & Architecture Guide

> **Target Audience**: Developers, contributors, and AI assistants collaborating on Nuvio+.

---

## 1. Project Overview & Environment

**Nuvio+** is an enhanced, power-user fork of Nuvio TV, built with Kotlin and Jetpack Compose for Android TV. It interfaces with the Stremio Addon Protocol (v3), enriched with multi-provider metadata aggregation (TMDB, Trakt, Simkl, MDBList), Home Assistant smart home automation, and an autonomous playback engine.

* **Repository**: `https://github.com/hindsight2021/NuvioTV`
* **Active Working Branch**: `plus-dev` (All development, hotfixes, and release pipelines target `plus-dev`).
* **Package Name**: `com.nuvio.tv.plus` (Runs side-by-side with vanilla Nuvio without conflicting).
* **Target Platforms**: Android TV / Google TV / Fire TV (minSdk 24, targetSdk 36, architecture: `arm64-v8a`).

---

## 2. Core Architecture Highlights

### A. Intelligent Autopilot Playback
Nuvio+ includes a dedicated `AutopilotEngine` and `AutopilotDecisionEngine`:
* Automatically queues and transitions to the next episode, sequel, or curated next watch when natural playback completes.
* Evaluates franchise order, air dates, and AI recommendation rationale.
* Interactive post-play countdown overlay with 1-click immediate play, cancel, and seamless navigation.

### B. Multi-Source Metadata Merging (`MetaMerger.kt`)
* Resolves conflicting episode listings across Cinemeta, TMDB, Cyberflix, and Trakt into a unified superset.
* Automatically synthesizes upcoming season premieres into Continue Watching so scrapers find fresh releases immediately.
* On-screen provider pill selector (`All (Merged)` vs individual catalogs).

### C. Home Assistant Smart Home Integration
* Local REST and WebSocket status server built directly into the TV app.
* Cinema mode lighting triggers (dimming on movie playback start, brightening on pause/stop).
* Ambient indoor climate and outdoor weather badges displayed in the TV header.

### D. Enhanced Media3 & Hardware Decoder Resilience
* Automated end-of-stream detection: forces natural completion when buggy hardware decoders finish audio but hang on video EOS markers.
* Parallel range chunking without deep recursion.
* Resilient subtitle synchronization and automated audio stream preference restoration.

---

## 3. Testing & ADB Deployment Guide

To deploy and test builds on an Android TV device over local Wi-Fi / Ethernet:

1. **Connect to the Target Device**:
   ```bash
   adb connect <device-ip>:5555
   adb devices
   ```

2. **Install Compiled APK**:
   ```bash
   adb -s <device-ip>:5555 install -r path/to/app-full-arm64-v8a-release.apk
   ```

3. **Launch the Application**:
   ```bash
   adb -s <device-ip>:5555 shell monkey -p com.nuvio.tv.plus -c android.intent.category.LAUNCHER 1
   ```

4. **Inspect Live Logs**:
   ```bash
   adb -s <device-ip>:5555 logcat -s NuvioTV:* *:E
   ```

---

## 4. Build & CI/CD Pipeline

Builds are compiled and packaged using Gradle:

```bash
# Compile and run unit test suite
./gradlew :app:testFullDebugUnitTest

# Assemble local build
./gradlew :app:assembleFullRelease
```

Automated releases and APK signatures are built on GitHub Actions via the `Build Android TV Release` workflow (`android-release.yml`), triggered via:
```bash
gh workflow run android-release.yml -R hindsight2021/NuvioTV --ref plus-dev -f mode=publish
```
