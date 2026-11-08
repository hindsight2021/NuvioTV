<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio+" width="320" />

  <h1>🚀 Nuvio+ (The Couch Potato Edition)</h1>

  <p>
    <b>The unapologetically superior, feature-packed Android TV fork of Nuvio.</b>
    <br />
    <i>Because reaching between couch cushions for the remote is a failure of modern engineering.</i>
  </p>

  <p>
    <a href="https://github.com/hindsight2021/NuvioTV/releases/latest">📦 Latest Releases</a> •
    <a href="HANDOVER.md">🛠 Architecture & Handover</a> •
    <a href="https://github.com/NuvioMedia/NuvioTV"> upstream Vanilla Nuvio</a>
  </p>

</div>

---

## 🛋️ Why Nuvio+? (An Honest Manifesto)

Look, vanilla Nuvio is great. It's clean. It's polite. It respects your screen time.

**Nuvio+ does not respect your screen time.** Nuvio+ is engineered with a singular, uncompromising vision: to turn your living room into an autonomous entertainment temple where human thumb movement is considered an obsolete design flaw.

If you believe that binge-watching should require roughly the same amount of physical exertion as a coma, welcome home.

---

## ✨ Superpowers That Vanilla Left On The Table

### ✈️ 1. Autopilot Mode (AI-Powered Laziness)
Why should an app stop playing when an episode ends? What are you supposed to do, *think* about what to watch next?
- **Seamless Continuous Playback**: When your show finishes its latest release, Autopilot seamlessly scans your library and Continue Watching row, queues up the next hottest release or smart sequel, auto-selects a fast stream, and plays it.
- **Gemini AI Curation**: Toggle into AI Curated Mode. Built-in intelligence analyzes narrative tone, genre pacing, and release recency so you don't get violently whiplashed from *The Bear* straight into *Peppa Pig*.
- **Post-Play Countdown**: A slick, TV-optimized overlay gives you a countdown timer with 1-click **Play Now** and **Cancel** controls if you somehow decide you need to go to sleep.

### 🧩 2. Metadata Diplomacy (`MetaMerger`)
Have you ever waited for a new season premiere, only to find that Cinemeta thinks the season doesn't exist, TMDB is stuck in 2021, and Trakt gave up on life?
- Nuvio+ refuses to participate in addon drama.
- **Superset Aggregation**: Our custom metadata pipeline forcibly gathers Cinemeta, TMDB, Cyberflix, and Trakt into a room and merges their episode listings into a unified, conflict-free superset.
- **Automatic Premiere Ingestion**: As soon as *any* provider knows an episode exists, it pops right into your Continue Watching row ready to scrape.

### 💡 3. Home Assistant Cinema Mode
Your TV shouldn't just be a screen; it should be the brain of your home theater:
- **Automatic Ambient Dims**: When you start a movie in the evening, Nuvio+ signals Home Assistant to gently dim your living room lights over a velvety 7-second fade.
- **Lego-Prevention Lighting**: Pause or stop the movie, and the lights gently rise so you don't break your toes navigating to the kitchen for popcorn.
- **Live Climate Badges**: Indoor temperature and local weather sit right in the TV header, so you can scientifically confirm why you're freezing under your blanket.

### 🛡️ 4. Anti-Freeze Hardware Decoder Armor
Tired of AV1 and HEVC hardware decoders having an existential crisis at the end of a video and locking up your TV with a black screen?
- We baked in upstream ExoPlayer end-of-stream overrun guards. When the audio finishes, the decoder yields cleanly. No stutters, no frozen frames, no rebooting your Shield TV.

### 👯 5. Zero-Risk Side-by-Side Coexistence
We use package ID `com.nuvio.tv.plus`.
- Installing Nuvio+ will **never** overwrite or break your vanilla Nuvio install.
- Keep them side-by-side. Use vanilla on days you feel like exercising your remote-clicking finger, and Nuvio+ for the other 364 days of the year.

---

## 🎮 Android TV D-Pad Masterclass

Nuvio+ was built by people who actually use Android TV remotes in the dark:
- **No Focus Traps**: Every card, overlay, pill, and button has deterministic D-pad focus routes.
- **Instant Scrubbing**: Hold Left or Right on the remote to fluidly fast-forward at accelerating rates without stuttering the audio engine.
- **No Unwanted PiP Surprises**: Clean, immersive fullscreen playback that stays fullscreen.

---

## 📥 Installation

1. Grab the latest `app-full-arm64-v8a-release.apk` from the **[Releases](https://github.com/hindsight2021/NuvioTV/releases)** page.
2. Sideload via your favorite tool (Send Files to TV, ADB, Downloader, or Local NAS).
3. If using ADB:
   ```bash
   adb install -r app-full-arm64-v8a-release.apk
   ```
4. Sit back, put the remote out of arm's reach, and let Nuvio+ take the wheel.

---

## 🛠️ Building from Source

```bash
# Clone the Plus fork
git clone -b plus-dev https://github.com/hindsight2021/NuvioTV.git
cd NuvioTV

# Build the release APK
./gradlew :app:assembleFullRelease
```

---

<div align="center">
  <sub>Built with ❤️, excessive caffeine, and a passionate hatred of reaching for the remote.</sub>
</div>
