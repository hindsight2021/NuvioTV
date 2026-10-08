# NuvioTV Development Handover & Architecture Guide

> **Target Audience**: AI Coding Assistants (Codex, Antigravity, DeepSeek) & Developers collaborating on NuvioTV.

---

## 1. Project Overview & Environment

**NuvioTV** is a Kotlin-based Android TV media streaming client built with Jetpack Compose for TV. It consumes the Stremio Addon Protocol (v3), enriched with TMDB, Trakt, Simkl, and MDBList.

* **Repository**: `https://github.com/hindsight2021/NuvioTV`
* **Active Working Branch**: `plus-dev` (All development, hotfixes, and CI/CD builds occur on `plus-dev`).
* **Active Package Name**: `com.nuvio.tv.plus` (The Plus build flavor; note that the base application ID in `build.gradle.kts` is `com.nuvio.tv`, suffixed in release/plus variants).
* **Target Platforms**: Android TV / Google TV (minSdk 24, targetSdk 36).

---

## 2. Coding Subagent Protocol (Mandatory)

Per user configuration rules:
* **DeepSeek Coder** is the **DEFAULT** engine for tactical coding tasks, boilerplate generation, algorithm implementation, mechanical refactoring, and completions.
* The orchestrating AI (Antigravity/Codex) acts as **lead architect, orchestrator, and QA reviewer**.
* **Delegation CLI Path**:
  ```powershell
  python "C:\Users\mboud\.gemini\config\skills\deepseek-coder\scripts\deepseek-cli.py" generate "<PROMPT>" -l <LANGUAGE> [-c "<CONTEXT>"]
  python "C:\Users\mboud\.gemini\config\skills\deepseek-coder\scripts\deepseek-cli.py" edit "<CODE_OR_PATH>" -i "<INSTRUCTION>" -l <LANGUAGE>
  python "C:\Users\mboud\.gemini\config\skills\deepseek-coder\scripts\deepseek-cli.py" complete "<CODE_OR_PATH>" -l <LANGUAGE>
  python "C:\Users\mboud\.gemini\config\skills\deepseek-coder\scripts\deepseek-cli.py" explain "<CODE_OR_PATH>" [-q "<QUESTION>"]
  ```

---

## 3. Hardware, Testing & ADB Deployment Guide

The user tests builds directly on a physical Android TV device over the local network.

### Device Specifications
* **IP Address**: `192.168.1.242:5555`
* **Architecture**: `arm64-v8a`

### Step-by-Step ADB Workflow
1. **Connect to the Android TV**:
   ```powershell
   adb connect 192.168.1.242:5555
   ```
2. **Verify Connection**:
   ```powershell
   adb devices
   # Output should show: 192.168.1.242:5555    device
   ```
3. **Install Compiled APK**:
   ```powershell
   adb -s 192.168.1.242:5555 install -r path/to/app-full-arm64-v8a-release.apk
   ```
4. **Launch Application**:
   ```powershell
   adb -s 192.168.1.242:5555 shell monkey -p com.nuvio.tv.plus -c android.intent.category.LAUNCHER 1
   ```
5. **Inspect Live Logs (Filtering for Nuvio)**:
   ```powershell
   adb -s 192.168.1.242:5555 logcat -s NuvioTV:* *:E
   ```

---

## 4. Compilation & CI/CD Pipeline

The local Windows workstation lacks a local Android SDK installation. **Compilation is always executed via GitHub Actions**.

### Triggering a Release Build
1. Commit and push all changes to the `plus-dev` branch:
   ```powershell
   git add -A
   git commit -m "feat/fix: <description> (vX.X.X-plus.XX)"
   git push origin plus-dev
   ```
2. Dispatch a signed candidate build and full unit suite:
   ```powershell
   gh workflow run android-release.yml --ref plus-dev -f mode=candidate
   ```
3. Monitor the build run:
   ```powershell
   gh run list --workflow android-release.yml --limit 1
   gh run watch <run-id>
   ```
4. Download the generated APK artifact:
   ```powershell
   gh run download <run-id> -n nuviotv-<version>-full-release -D tmp_apk
   # or:
   gh run download <run-id> -D tmp_apk
   ```

> **IMPORTANT**: The user subsequently authorized ADB installation of plus.50 on Shield. That installation is complete. Future device installs remain task-specific; check the latest instruction before interrupting playback. Publish an accepted candidate APK without rebuilding if its exact bytes must be retained.

---

## 5. Recent Architecture Changes & Bugfixes

### A. Metadata Multi-Source Superset Merging & Source Switching (v0.9.4-plus.46)
* **Problem**: Different Stremio catalog addons (Better Posters / RPDB, MyTrakt, Cyberflix, Cinemeta) frequently return conflicting or partial metadata. For example:
  - Better Posters' cache only had Seasons 1–9 for a show.
  - MyTrakt returned a tracker stub with *only* Season 10 Episode 1.
  - If a user launched from one, the other seasons vanished.
* **Solution**:
  - `MetaMerger.kt`: Created domain helper `MetaMerger` that detects tracker stubs (`isStub`) and merges episode lists across all responding addons into a complete **superset** keyed by `(season, episode)`.
  - `MetaRepository.kt` & `MetaRepositoryImpl.kt`: Implemented `getCandidateMetaAddons(type, id)` to identify all installed addons declaring the `meta` resource for a given content type and ID prefix.
  - `MetaDetailsViewModel.kt`: 
    - Queries all candidate meta addons concurrently in the background.
    - Default view is set to `All (Merged)` (`selectedMetaSourceId = "merged"`), automatically uniting all episodes so newly premiered episodes (like S10E01) are always available.
    - Populates `availableMetaSources: List<MetaSource>`.
  - `MetaSourceSelector.kt`: Created TV-focused Compose pill bar displayed above `SeasonTabs` allowing the user to seamlessly toggle between `All (Merged)` and specific installed providers (Cinemeta, Better Posters, MyTrakt).

### B. Continue Watching Next-Up Missing Seasons (v0.9.4-plus.45)
* **Problem**: When a user finished the last episode of Season 9 and Season 10 premiered yesterday, TMDB returned 404 for Season 10 because it hadn't indexed it yet. Nuvio received `nextVideo = null` and erroneously dropped the series from Continue Watching.
* **Solution**: In `HomeViewModelContinueWatching.kt` (`resolveNextUpItemAsync`), added smart fallback synthesis: if a show is ongoing (`meta.releaseInfo?.endsWith("-")`) and the user has watched the final known episode, Nuvio synthesizes the next logical premiere (`Season + 1, Episode 1`) into Continue Watching so scrapers (Torrentio) can search for it immediately.

### C. YouTube Trailers Black Screen (v0.9.4-plus.45)
* **Problem**: A custom `YoutubeChunkedDataSourceFactory` wrapper introduced during a previous trivia update broke ExoPlayer's ability to resolve stream content lengths, causing black screens. Faulty `remember` keys in `TrailerPlayer.kt` also caused infinite pool re-acquisitions.
* **Solution**: Removed the chunked data source factory and restored standard ExoPlayer `DefaultMediaSourceFactory(context)`. Fixed `remember` keys.

### D. Cast Photos Race Condition in Continue Watching (v0.9.4-plus.45)
* **Problem**: Launching playback straight from Continue Watching triggered concurrent enrichment from TMDB (has photos) and Cinemeta (no photos). Cinemeta finished second and wiped out TMDB photos.
* **Solution**: In `PlayerRuntimeControllerMetadata.kt`, updated `applyMetaDetails` to preserve existing cast members if they already contain photos (`hasRichCast`).

### E. Catalog Hero IMDB & TMDB Ratings (v0.9.4-plus.45)
* **Problem**: The catalog grid displayed both ratings, but the Hero Carousel items at the top dropped `tmdbRating`.
* **Solution**: Mapped `tmdbRating` in `enrichHeroItemsAsync` in `HomeViewModelPresentationPipeline.kt`.

---

## 6. Key Files & Reference Map

| Component | File Path |
|---|---|
| **Multi-Source Domain Merger** | `app/src/main/java/com/nuvio/tv/domain/model/MetaMerger.kt` |
| **Meta Source Model** | `app/src/main/java/com/nuvio/tv/domain/model/MetaMerger.kt` (`MetaSource`) |
| **Source Selector UI** | `app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaSourceSelector.kt` |
| **Detail Screen ViewModel** | `app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaDetailsViewModel.kt` |
| **Detail Screen UI** | `app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaDetailsScreen.kt` |
| **Meta Repository** | `app/src/main/java/com/nuvio/tv/data/repository/MetaRepositoryImpl.kt` |
| **Continue Watching Logic** | `app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` |
| **Trailer Player** | `app/src/main/java/com/nuvio/tv/ui/components/TrailerPlayer.kt` |
| **Build Configuration** | `app/build.gradle.kts` |
| **Release CI/CD Workflow** | `.github/workflows/android-release.yml` |

### F. Continue Watching Multi-Source Addon Superset Merging (v0.9.4-plus.50)
* **Problem**: When a new season or episode airs (e.g. *The Traitors Canada* Season 4 Episode 3, where S4E1/S4E2 had already been watched), primary metadata providers like Cinemeta often lag behind and only index up to Season 3. When Nuvio's Continue Watching engine checked the primary addon, `watchedIndex` was `-1`, causing Nuvio to drop the series with `seed-not-found-in-meta`. Meanwhile, other installed addons (such as AIOMetadata under `tmdb:234613`) already had all 10 episodes of Season 4, but Continue Watching never queried them because secondary candidate fetching was gated behind `externalMetaPrefetchEnabled` and only queried the single original content ID without counterpart ID translation.
* **Solution**:
  - In `HomeViewModelContinueWatching.kt` (`findNextUpEpisodeFromMetaSeed`):
    - Removed the `externalMetaPrefetchEnabled` gate so candidate addon supplementing runs for all TV series when `nextVideo == null`.
    - Expanded candidate queries across both primary ID and counterpart IDs (`progress.contentId`, `tmdb:$tmdbId`, `$tmdbId`, `primary.imdbId`).
    - Concurrently queried candidate addons for those IDs and merged them with `MetaMerger.mergeAll(primary, others).toCwSummary()`. In plus.50, cross-ID TMDB aliases are canonicalized only when the returned ID, content type, normalized title, release year, and IMDb are consistent; title alone no longer rewrites identity.
    - If the supplemented metadata has more episodes, `currentMeta` is updated and cached in `cwMetaCache`, cleanly resolving the next real episode (e.g. S4E3) without creating phantom/synthetic episodes.
  - In `resolveMetaForProgress`: added cached TMDB ID lookup to `idCandidates` to prevent single-addon failure from blocking metadata resolution.

---

## 7. Current Status & Next Steps for Codex

-1. **v0.9.4-plus.52 (8 October 2026)**:
   - Changes:
     - Immediate screensaver dismissal and decoder yield across all playback and navigation requests to prevent MediaCodec exhaustion crashes.
     - Home Assistant Cinema Mode automation (dimming strictly for movies after 5 PM with slow 7s transition).
     - Private MCP bridge lighting control tools (`set_light_color` / `set_light`).
     - REST API control body parsing fix in `NuvioControlServer`.
   - Version Details: `versionName = "0.9.4-plus.52"`, `versionCode = 1110`.

0. **v0.9.4-plus.51 compiled (7 October 2026)**:
   - Built on branch `plus-dev` at commit `b48692226` (Workflow run `37613324707`).
   - Features:
     - Calendar default set to TV Shows / Episodes; Movies are opt-in via Category pills.
     - 1-Week Look-Back support ("This Week" vs "Look Back (Past Week)") with immediate access to "Yesterday" for watching 1 day behind.
     - Modernized Calendar UI tailored for Android TV D-pad navigation, day strips with "★ TODAY" and "⟲ YESTERDAY" badges, quick jump card, and responsive layout.
     - Synchronized focus prefetch boundary test to eliminate CI race condition.
   - Compiled APK: `tmp_apk/app-full-arm64-v8a-release-v51.apk` (SHA-256: `995078d4a9d7b56cf2c4295d41a36e38c841bd7acf6f9875992035d3ac10c0da`).
   - Mode: Candidate build ready for **manual installation only**.

0a. **v0.9.4-plus.50 released and installed (6 October 2026)**:
   - Release tag and candidate build SHA: `d9c331ca0d818c6235bfb932c67829ebc732a476`; candidate run `37492621422` passed 1,443 unit tests and assembled the arm64 APK.
   - Published release: `https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.50`. The published APK, candidate APK, and APK pulled from Shield share SHA-256 `1c2dd7553879090d74873a61f323907a6af62f475ff3dd4f48d6d81936b3e223`.
   - Shield `com.nuvio.tv.plus` reports versionCode `1108`, versionName `0.9.4-plus.50`; launch reached `MainActivity` without a fatal crash in the checked log window. Exact-episode playback and resume on Shield still need hands-on acceptance. See `RELEASE_ACCEPTANCE_0.9.4-plus.50.md`.

1. **v0.9.4-plus.47 Hotfix Release (Commit `2c9d7ea60`, Publish Run `37468513740`)**:
   - **Target**: Published directly to GitHub Releases as `0.9.4-plus.47` for Nuvio in-app updater.
   - **Scope**: Continue Watching multi-source candidate merging (Traitors Canada S4 / Great Canadian Baking Show S10E01 fix), full JVM test fixes, and release metadata alignment.
   - **Version Details**: `versionName = "0.9.4-plus.47"`, `versionCode = 1105`.
   - **Codex Independence**: Codex continues work on v50 ("Just Play") separately.
2. **Device Verification on Shield TV (192.168.1.242:5555)**:
   - Update from inside Nuvio via Settings -> Check for Updates.
   - Verify *The Traitors Canada* S4E3 displays in Continue Watching.
   - Verify *The Great Canadian Baking Show* S10E01 displays properly.
3. **Primary Metadata Addon Lock**:
   - Consider adding an optional setting in Layout / Addons allowing users to designate a preferred primary metadata provider.
4. **Movie Trivia Feature**:
   - Proceed with remaining planned features once v50 device acceptance is complete.
