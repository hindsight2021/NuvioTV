# Nuvio+ project assessment and primary handoff

Review date: 5 October 2026. Reviewer: Codex, lead orchestrator.

## Verdict
Nuvio+ has a compelling identity: a personal home-theatre environment combining playback, discovery, random channels, local media, voice control, Home Assistant, and cinema presentation. Its weakest point is confidence in changes. Feature growth has outpaced release verification and the consistency of metadata/watch-state decisions. Stabilization is the highest-value next release.

I can act as primary for this project: own architecture, triage, delegation, integration, and evidence-based release decisions. This document establishes the reviewed baseline; it does not imply unattended monitoring or deployment.

## Scope and verified baseline
- Read HANDOVER.md, current build configuration, all 43 published fork release records returned by GitHub, recent implementation history, release/PR/upstream-sync workflows, metadata merger and tests, detail source-selection logic, next-up synthesis, and selected pre-show/integration material.
- Reviewed the complete published Nuvio+ release sequence at release-note level; inspected latest implementation in greater depth. This is not an exhaustive audit of every historical diff or every inherited upstream tag.
- Working branch: plus-dev. Working tree initially clean. Local and live remote branch HEAD: 9298464192e80eb6b6eaa56bd264729a66ec8c82.
- Latest published fork release: 0.9.4-plus.46, versionCode 1104, published 5 October at 18:41 Halifax time. Full flavor package: com.nuvio.tv.plus. Latest asset is arm64-v8a only, 87,053,117 bytes.
- GitHub successful build: https://github.com/hindsight2021/NuvioTV/actions/runs/37373910445 . Release: https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.46 . Release asset SHA-256 recorded by GitHub: b9cffb4f2c16049cfc1f0ba2d69b85d3303de0b23e51b0171949265988010069.
- Ran existing Python release-tool suite locally: 19 tests passed. Inventoried 222 files under app/src/test; this is a file count, not a passing-test count.
- Did not rebuild Android, install an APK, interrupt playback, or run on-device acceptance. Build success establishes compilation and the selected CI tests, not user-visible correctness.

## Release evolution
| Era | What changed | Assessment |
|---|---|---|
| 0.9.2-plus.1–6 | Coexistence, local media, random/playlist channels, AI providers, home clock/weather, fork updater | Strong personal-TV foundation; several immediate compile/UI repairs |
| 0.9.2-plus.7–10 | Chic reviews/TTS, cinema pre-show, lighting, backgrounds, startup branding | Clear identity; presentation increases lifecycle/audio complexity |
| 0.9.2-plus.11–15 | Movie/TV separation, curation, voice personas, Bell Fibe guide, hero carousel | Discovery and living-room experience become central; shuffle/watch-history isolation already needs repair |
| 0.9.4-plus.16–20 | Upstream integration, tracking repair, IPTV, calendar, hardware/audio tuning | Valuable upstream reuse plus substantial player integration risk |
| 0.9.4-plus.21–24 | Screensaver, embedded control server, HA, pre-scraping, NAS discovery | Strong ecosystem differentiator; focus, screensaver and playback-interference fixes follow |
| 0.9.4-plus.25–29 | Authentication, search curation, voice/channels, upstream alignment, debrid selection and performance | Important operational improvements; .28 notes report upstream 1.1.0-beta.1 alignment despite 0.9.4 fork numbering |
| 0.9.4-plus.30–36 | Hero/freshness/grid work, completion policy, repeated Continue Watching repairs | The recurring weak spot is state and freshness reconciliation, not simply missing artwork |
| 0.9.4-plus.37–40 | Interactive cinema pre-show, trivia enrichment/fallback, upcoming trailers and handoff | Attractive signature feature with a concentrated repair tail |
| .41–43 commits | Trivia input/trailer handling, regular trailer restoration, random-session tracking suppression | Present in Git history; no standalone published release records in the returned inventory |
| 0.9.4-plus.44–45 | Cast/rating/trailer/next-season repairs and compile correction | Useful repairs, but next-season synthesis introduces a new correctness risk |
| 0.9.4-plus.46 | Multi-provider episode merging and visible source selection | Correct strategic direction; concurrency, identity and publication details need hardening |

Version gaps are not proof of lost work. There are 43 published releases across 46 Plus increment labels, with .41–43 represented by commits rather than separate published entries.

## The good
1. **A coherent differentiator.** Random channels, queues, home automation, voice control and cinema pre-show fit how this household watches TV. They are more valuable together than as isolated options.
2. **Substantial inherited engineering.** Kotlin/Compose, repositories, domain models, Media3/native playback integrations, tracking providers and a large existing test tree are a credible foundation.
3. **The .46 approach addresses an actual provider disagreement.** A tracker reporting S10E01 should supplement a catalog reporting seasons 1–9. A merger and source selector are better foundations than assuming one provider is complete.
4. **There is existing recovery-oriented work.** History records debrid file selection repairs, reduced background work during playback, watch-history suppression for random sessions and preservation of rich cast data.
5. **Deployment boundaries are clear.** Separate application package, fork-targeted updater, GitHub compilation, manual TV installation. I will retain the handover's no-automatic-TV-deployment instruction.
6. **Trivia already exists.** MoviePreShowService includes generation, response parsing, shuffled choices and procedural fallback. The handover's 'continue trivia' task should mean finishing and validating this implementation, not starting a duplicate system.

## The bad
1. **CI coverage is much narrower than the repository suggests.** android-release.yml:210 executes only com.nuvio.tv.updater.* tests. The PR build does the same and is triggered for PRs into dev, not plus-dev. New MetaMerger tests exist but are not executed by that release command.
2. **Fixes repeatedly require compile fixes or adjacent regressions.** The .37–46 sequence includes multiple follow-up compile repairs and regular-trailer restoration after pre-show changes. This is evidence that integration validation arrives too late, not evidence that every published APK is broken.
3. **Core responsibilities remain concentrated.** Continue Watching and detail-view-model files each contain thousands of lines. Provider fetching, freshness, enrichment and UI state compete in large flows, making behavior hard to isolate.
4. **Documentation misidentifies the product.** README still directs users to upstream downloads and upstream build identity. HANDOVER is much more relevant but lacks a verified acceptance ledger, and docs/ is ignored by Git. Fork version numbers also obscure the actual upstream baseline.
5. **Hardware scope narrowed.** Earlier releases contain five assets; current releases contain one arm64 APK. This suits the Shield but should not be mistaken for broad device-release coverage.

## The ugly: concrete findings
### P1: release tag and built source disagree — verified remotely
The .46 tag resolves to 87cdd51d123c7662042adddd614e70c12aff0bb9. The successful build used 9298464192e80eb6b6eaa56bd264729a66ec8c82, the subsequent compilation fix. android-release.yml sets RELEASE_TARGET to current_bump, while checkout/build uses the dispatched commit. Someone checking out the release tag misses the compile correction included in the build source. Repair the pipeline to tag the exact built SHA; handle already-published tags deliberately rather than silently moving them.

### P1: guessed premieres are marked available — verified code, device impact untested
HomeViewModelContinueWatching.kt:2447–2469 synthesizes next-season episode 1 when the seed is the final known episode and releaseInfo ends in '-'. It assigns released=null and available=true. An ongoing series at the last indexed mid-season episode can therefore be offered a guessed next season; the code has no positive premiere evidence in this fallback. Preserve the followed show, but distinguish unknown/upcoming from confirmed released/playable content.

### P1: release failures can look successful — verified code
The final gh release create command falls back to a successful echo on any failure. There is no actual CLI recovery step after that echo in the workflow. Separately, notes-generation failure substitutes a fixed historical feature list and writes a success message into its quality report. .46's release does exist; these are pipeline defects, not a claim that this release is missing.

### P2: .46 merging can discard distinct unnumbered videos — verified algorithm
MetaMerger.kt:60 onward uses season ?: 0 and episode ?: 0 as identity. Multiple differently identified videos with missing coordinates collapse onto the same key; null also collides with explicit zero. Use canonical episode coordinates when known, otherwise preserve distinct provider video identities. The six current merger tests do not cover missing coordinates.

### P2: equal-size metadata improvements do not update the merged display — verified condition
MetaDetailsViewModel.kt:1145 onward refreshes displayed merged metadata only when video count increases or current metadata is null. A provider supplying missing images, dates or descriptions for the same episode count updates the cached merged object but does not automatically replace the displayed metadata. Compare meaningful content/state revisions, not just counts.

### P2: source merging has concurrency and precedence risks — code-supported, not reproduced
Each parallel provider completion starts a merge plus potentially suspending enrichment. These passes can finish out of order and overwrite enrichedMergedMeta. The selected-source decision is read before the atomic state update. Serialize publication and evaluate selection against the state being updated. Also, 'largest non-stub episode list' is a completeness heuristic, not proof that that provider should own canonical identity/artwork. Explicit deterministic source priorities and provenance are needed.

### P2: upstream synchronization bypasses review — verified workflow configuration
upstream-sync.yml merges upstream/dev, pushes plus-dev directly, and dispatches publish. This is configured behavior, not proof the scheduled workflow recently ran. It gives upstream changes a path to publication without a human-reviewed integration branch or broad behavioral suite, and does not itself ensure a new fork version.

### Credential hygiene requires a focused follow-up
Build configuration contains literal fallback signing passwords and provider credential material. Values are intentionally omitted here. The local signing keystore is ignored and not currently tracked; I found no basis to claim the private signing key was published or compromised. Remove unnecessary credential defaults, verify provider ownership/usage rules, and audit actual historical exposure before deciding what needs rotation. Do not casually rotate the signing identity and break installed-app upgrades.

## Latest release disposition
.46 is a promising integration milestone with a successfully produced published artifact. It is not yet a verified stable baseline. The immediate acceptance matrix should cover:
- Catalog seasons 1–9 plus tracker S10E01, including slow/failed providers.
- Same-size enrichment, missing episode numbers, conflicting IDs and specials.
- Switching source and season while requests finish; leaving and reopening details.
- Mid-season caught-up shows, genuinely new seasons, canceled shows and unknown release dates.
- Continue Watching versus direct detail playback; normal movie trailers versus pre-show trailers.
- Trivia remote input, skip/show-time transition, random playback history isolation.
- Return from playback, resume position, audio/passthrough and background-work pressure on the Shield.
These are proposed acceptance checks, not completed passes.

## Future and order of work
**First: establish release trust.** Exact-SHA tags, fail on publication failure, truthful notes, plus-dev CI, relevant domain/view-model tests, and a small recorded Shield acceptance checklist. Publish a stabilization increment only after those gates.

**Second: one trustworthy episode model.** Separate show/episode identity, artwork preference, tracker progress, release evidence and stream availability. Keep provider provenance. Add the handover's preferred metadata provider as a policy with fallback, rather than allowing an absolute lock to hide legitimate new episodes. Reuse the same reconciliation policy for details and Continue Watching.

**Third: finish cinema mode.** Make trivia, trailers, prefetch and show-time handoff one predictable lifecycle, with cancellation, bounded waits and verified fallback behavior. Protect ordinary playback/trailers from cinema-only transport changes.

**Then: develop the strongest product opportunities.** Reliability-informed stream fallback with preserved position; a 'pick something tonight' flow using existing taste/history; dependable personal channels with isolated watch history; and well-defined local voice/HA playback contracts. Existing implementations must be inventoried before adding overlapping features. These are recommendations, not approved implementation scope.

## Primary working agreement
Codex owns architecture, triage, integration review and release evidence. For coding work, use 3–5 independent DeepSeek calls as requested, then inspect actual changes and validate before accepting them. Maintain a small durable project ledger identifying built SHA, release tag, APK, tests, known issues and device acceptance. Continue on plus-dev, preserve user changes, build through GitHub, and leave TV installation manual unless directly authorized otherwise.

For this review, three DeepSeek calls covered metadata correctness, metadata concurrency and release reliability. Accepted after inspection: missing-coordinate collisions, merge publication/selection risks, swallowed release errors and misleading fallback notes. Rejected or narrowed: definite cross-title contamination (item identity is fixed in this view model), the claim that the final coroutineScope continuation races unfinished children (structured concurrency waits), and the assertion that empty-video sources never enrich fields (blank-field enrichment is present). No proposed code was integrated.

## Published-release ledger
Every entry below was retrieved from the fork's GitHub Releases API during this review. Notes document release intent; they do not independently verify behavior.

### 0.9.2-plus.1

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.1

Assets: 5.

### Nuvio+ 0.9.2 (plus.1) Release

Custom fork of Nuvio TV with custom features and coexistence with the official app.

#### Features Included:
- **Side-by-Side Coexistence**: Package name com.nuvio.tv.plus (Nuvio+), runs alongside official Nuvio on Nvidia Shield.
- **Official Backend Sync**: Uses official Supabase backend (https://api.nuvio.tv) so existing Nuvio profiles work directly.
- **Local NAS / Network Storage Playback**: Scans /storage and mounted SMB/NFS shares.
- **Smart Channels & Play Random**: Long-press context actions to play random episode or create continuous shuffle / binge channels.
- **1-Click Trakt / Simkl Watchlist Toggle**: Quickly add/remove shows from your watchlist.
- **Playlist Queue Manager**: Play Next and custom queued playlist manager with save/load capability.
- **Modern Startup Chime**: Ambient shimmer chime on cold launch.
- **Automated Upstream Sync**: Automatic sync with upstream Nuvio releases and OTA updates.

### 0.9.2-plus.2

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.2

Assets: 5.

### Improvements & Fixes
- Updated meta.id and isInLibrary in MetaDetailsContent (@hindsight2021)
- Multi-provider AI assistant, random episode context menu, and magical sound (@hindsight2021)


### 0.9.2-plus.3

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.3

Assets: 5.

### Improvements & Fixes
- Fixed imports, tts shutdown alias, and settings row components (@hindsight2021)
- Fixed nullable string mismatches and smart casting in Search and Home screens (@hindsight2021)
- Provide mockk aiManager in SearchViewModel unit tests and default parameter (@hindsight2021)
- Updated Gemini to gemini-3.6-flash, add dynamic model fallback, auto-migration, and custom model entry (@hindsight2021)


### 0.9.2-plus.4

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.4

Assets: 5.

### Improvements & Fixes
- Added haze license attribution (@tapframe)
- Restored IntroDB-first skip provider priority (@Laskco)
- Fixed Keyboard Appearing When Selecting Recent Searches (@haveAnIssue)
- Follow-up: Extend Content-Based Text Direction Fix (@haveAnIssue)
- Fixed player treat truncated mkv tails as end of input (@halibiram)
- Fixed player follow nested mkv seekhead so exoplayer can seek (@halibiram)
- Player match next episode prompt buttons to overlay style (@halibiram)
- Fixed Player: keep stream credentials off other subtitle hosts, Player: keep subtitle-supplied credentials off other hosts, and anime skip segment season mapping and Indonesian subtitle categorization (@ieno, @skoruppa)
- Improved upstream/dev into plus-dev (@github-actions[bot])
- Added random/channel actions to Continue Watching context dialog and fixed Episode Options Overlay cutoff (@hindsight2021)

### Localization
- Some very minor changes to the Dutch translation (@scheperr)


### 0.9.2-plus.5

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.5

Assets: 5.

### Improvements & Fixes
- Fixed random episode selection, add home clock with date, fix dialog cutoff (v0.9.2-plus.5) (@hindsight2021)


### 0.9.2-plus.6

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.6

Assets: 5.

### Improvements & Fixes
- HA weather & temperature widget, B&W Android TV logo/banner, and updater fork lock (@hindsight2021)


### 0.9.2-plus.7

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.7

Assets: 5.

### Improvements & Fixes
- Restored standard candidate filter in ReleaseSelector while keeping plus support (@hindsight2021)
- Chic AI review with TTS, 4D cinema lighting, cinema pre-show, enhanced intro skipping, and thematic channels (v0.9.2-plus.7) (@hindsight2021)


### 0.9.2-plus.8

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.8

Assets: 5.

### Improvements & Fixes
- Fixed ChicReviewDialog invocation and scope in HomeScreen (v0.9.2-plus.7) (@hindsight2021)
- Fixed MetaDetailsScreen and HomeScreen compilation errors (v0.9.2-plus.7) (@hindsight2021)
- Scandinavian minimalist clock and ambient weather layout (v0.9.2-plus.8) (@hindsight2021)


### 0.9.2-plus.9

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.9

Assets: 5.

### Improvements & Fixes
- Luxury home theatre splash screen and Crystalline Zen startup sound (v0.9.2-plus.9) (@hindsight2021)


### 0.9.2-plus.10

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.10

Assets: 5.

### Improvements & Fixes
- Stop Grid focus jumping after returning from See all (@Laskco)
- Fixed FFmpeg downmix distortion and buffer growth (@kernexshadow)
- Fixed Player: don't save previous episode position after MPV switch (@ieno)
- Fixed Player: route media-reload subtitles through the subtitle download path (@ieno)
- Keep FragmentActivity in ProGuard rules for CloudStream extensions (NUVIO-TV-445) (@halibiram)
- Fixed collection sort order and stream list pagination for large results (@skoruppa)
- Added TVDB anime ID preference to avoid per-season IMDB splits (@skoruppa)
- Improved upstream/dev into plus-dev (@github-actions[bot])
- Prevented startup chime early cut off by retaining strong player reference and proper AudioAttributes (v0.9.2-plus.10) (@hindsight2021)

### Localization
- Translation improvements and adjustments for Slovak language (@mmsw91)


### 0.9.2-plus.11

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.11

Assets: 5.

### Improvements & Fixes
- Separate pages for movies/TV shows, curated library tiers, B&W launcher banner, AI curation hook (v0.9.2-plus.11) (@hindsight2021)


### 0.9.2-plus.12

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.12

Assets: 5.

### Improvements & Fixes
- Added missing android.util.Log import in HomeViewModel (@hindsight2021)
- Fixed center tab pill, sidebar drawer backup, fix shuffle playback bug & guard Continue Watching pollution (v0.9.2-plus.12) (@hindsight2021)


### 0.9.2-plus.13

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.13

Assets: 5.

### Improvements & Fixes
- Fixed flow combine, HomeTab param, and synthesized Video fields (@hindsight2021)
- High-fidelity Chic TTS voice personas & cinematic animated backgrounds (v0.9.2-plus.13) (@hindsight2021)


### 0.9.2-plus.14

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.14

Assets: 5.

### Improvements & Fixes
- Pass animatedBackgroundMode into HeroCarouselSlide and MetaDetailsContent (@hindsight2021)
- Canadian Bell Fibe live TV guide, Apple TV remote click sounds & landscape hero new episode badges (v0.9.2-plus.14) (@hindsight2021)


### 0.9.2-plus.15

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.2-plus.15

Assets: 5.

### Improvements & Fixes
- Fixed modern flow combine, extractYearText type, surface color, and player context (@hindsight2021)
- Fixed flow combine in MainActivity, braces in NuvioApplication and NuvioNavHost (@hindsight2021)
- Fixed Bell Fibe live TV tuning & add hero carousel above continue watching in modern landscape (v0.9.2-plus.15) (@hindsight2021)


### 0.9.4-plus.16

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.16

Assets: 5.

### Improvements & Fixes
- Updated theme accent for continue watching progress (@tapframe)
- Added rotten tomatoes status icons (@tapframe)
- Limit anime ID preference to entries with anime-specific IDs (@skoruppa)
- Fixed scraper type matching and unaired next-episode card (@skoruppa)
- Updated add-on imdbid as fallback for non-IMDB content enrichment (@skoruppa)
- Allowed turning subtitles off by long pressing the selected track (@ram130)
- I18n(vi): update latest missing strings (@blueocean2308)
- Added IntroDB movie segments and independent external forwarding (@Laskco)
- Fixed duplicate NuvioTheme import in ModernHomeRowsList (@hindsight2021)

### Localization
- Updated Turkish translations (@halibiram)
- Added Polish translations (@Laskco)


### 0.9.4-plus.17

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.17

Assets: 5.

### Improvements & Fixes
- Restored animeSkipSettingsDataStore dependency in SkipIntroRepository (@hindsight2021)
- Hide unaired card when recommendations active, fix focus and double-back after return (@skoruppa)
- I18n(vi): update latest missing strings (@blueocean2308)
- Improved upstream/dev into plus-dev (@github-actions[bot])
- Fixed binge-group next episode hijack and restore Continue Watching progress tracking (@hindsight2021)

### Localization
- Updated Latin American Spanish localization strings (@omavel)


### 0.9.4-plus.18

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.18

Assets: 5.

### Improvements & Fixes
- Playback acceleration, hero focus bridge, native IPTV & in-player mini guide (v0.9.4-plus.18) (@hindsight2021)


### 0.9.4-plus.19

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.19

Assets: 5.

### Improvements & Fixes
- Fixed compilation errors in metadata and stream controller (@hindsight2021)
- Added Simkl-powered TV & movie upcoming calendar (v0.9.4-plus.19) (@hindsight2021)


### 0.9.4-plus.20

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.20

Assets: 5.

### Improvements & Fixes
- Fixed Password Input Direction in RTL Layout (@haveAnIssue)
- Fixed Text Direction for Mixed Languages (@haveAnIssue)
- Improved upstream/dev into plus-dev (@github-actions[bot])
- Audio passthrough fix, hardware tuner, stream filter & end-credits pill (v0.9.4-plus.20) (@hindsight2021)


### 0.9.4-plus.21

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.21

Assets: 5.

### Improvements & Fixes
- Escape apostrophes in strings.xml for AAPT compliance (@hindsight2021)
- Fixed Kotlin compilation references for v0.9.4-plus.20 (@hindsight2021)
- Cinematic 4K HDR screensaver, Home Assistant integration & remote control server (v0.9.4-plus.21) (@hindsight2021)


### 0.9.4-plus.22

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.22

Assets: 5.

### Improvements & Fixes
- Fixed references for trailerPlayerPool, toggle lambda, and Tv icon (@hindsight2021)
- Removed HomeAssistantWeatherService constructor injection in AmbientWeatherContext (@hindsight2021)
- Fixed screensaver black screen, top pill focus trap, sidebar scrolling, and HA integration (@hindsight2021)


### 0.9.4-plus.23

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.23

Assets: 5.

### Improvements & Fixes
- Updated recordFailure instead of recordPlayback in AmbientCoordinator (@hindsight2021)
- Instant launch continue watching pre-scraper and sprint 21 fixes (@hindsight2021)
- Provide secondary @Inject constructor without CoroutineDispatcher parameter (@hindsight2021)
- Sprint 22 local nas media discovery, zero-debrid playback, and ha voice fix (@hindsight2021)


### 0.9.4-plus.24

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.24

Assets: 5.

### Improvements & Fixes
- Fixed compile errors in LocalMedia repository, scanner, search, and settings for Sprint 22 (@hindsight2021)
- Fixed stuck debrid stream hang and improve singleTop intent delivery (@hindsight2021)
- Prevented screensaver during playback, activate IntroDB skip intro, stabilize auto-tuning (@hindsight2021)


### 0.9.4-plus.25

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.25

Assets: 5.

### Improvements & Fixes
- Pass effectiveImdbId to enhancedIntroDetector (@hindsight2021)
- Configure official Trakt and Simkl credentials for TV device authentication (@hindsight2021)


### 0.9.4-plus.26

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.26

Assets: 5.

### Improvements & Fixes
- Search priority, tv catalog curation, default genres, and remote sound controls (v0.9.4-plus.26) (@hindsight2021)


### 0.9.4-plus.27

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.27

Assets: 5.

### Improvements & Fixes
- Updated ClickSoundProfileDialog in NetworkSettingsScreen (@hindsight2021)
- Release(v0.9.4-plus.27): voice playback, search history, AI channels, screensaver pause fix, stutter RCA, and TMDB season expansion (@hindsight2021)


### 0.9.4-plus.28

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.28

Assets: 5.

### Improvements & Fixes
- Added missing imports for fillMaxHeight and scroll in ThematicChannelDialog (@hindsight2021)
- Align with upstream v0.9.5-beta..v1.1.0-beta.1 (v0.9.4-plus.28) (@hindsight2021)


### 0.9.4-plus.29

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.29

Assets: 5.

### Improvements & Fixes
- Restored playerSurfaceShape definition in PlayerScreen (@hindsight2021)
- Return to details on Back after a finished episode (@hindsight2021)
- Select requested episode files (@hindsight2021)
- Eliminate GC pauses by suppressing pre-scraping and trimming image cache during playback (@hindsight2021)


### 0.9.4-plus.30

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.30

Assets: 1.

### Improvements & Fixes
- Added missing return in OpenSubtitlesHasher readChunkSum (@hindsight2021)
- Removed duplicate onCleared in PlayerViewModel (@hindsight2021)
- Added Titanium Lotus Bloom as new startup splash logo (@hindsight2021)
- Power landscape hero banner with Simkl trending and auto-rotation (@hindsight2021)
- Release v0.9.4-plus.30 (sprint 30) (@hindsight2021)


### 0.9.4-plus.31

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.31

Assets: 1.

### Improvements & Fixes
- Content freshness, Continue Watching hero banner fix, progressive grid, and extended intro skipping (@hindsight2021)
- Fixed compile errors in EpisodeOptionsOverlay, HomeViewModel, and HomeViewModelCatalogPipeline (@hindsight2021)
- Fixed ContentType.UNKNOWN in pipeline and restrict release build to Shield arm64-v8a (@hindsight2021)
- Suppress periodic sync during playback, optimize watched items reconciliation, and close response bodies (@hindsight2021)


### 0.9.4-plus.32

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.32

Assets: 1.

### Improvements & Fixes
- Restored random episode, playlist queue, and channel actions in Continue Watching and Episode Options dialogs (v0.9.4-plus.32) (@hindsight2021)


### 0.9.4-plus.33

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.33

Assets: 1.

### Improvements & Fixes
- Complete playback at 85%, prevent teardown race, and fixed new episode discovery for caught-up series (v0.9.4-plus.33) (@hindsight2021)


### 0.9.4-plus.34

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.34

Assets: 1.

### Improvements & Fixes
- Fixed missing new episodes by fixing DataStore deadline lock, freshness checks, and cache invalidation (@hindsight2021)


### 0.9.4-plus.35

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.35

Assets: 1.

### Improvements & Fixes
- Prioritize release alert seeds, fix older seeds positive cache drop, and prevent shrink truncation (v0.9.4-plus.35) (@hindsight2021)


### 0.9.4-plus.36

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.36

Assets: 1.

### Improvements & Fixes
- Preserved cached older next-up items and prioritize recent release alerts (v0.9.4-plus.36) (@hindsight2021)


### 0.9.4-plus.37

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.37

Assets: 1.

### Improvements & Fixes
- Restored missing closing brace in async older seeds flow (@hindsight2021)
- Realistic theatrical cinema pre-show with custom audio, interactive trivia, and trailer controls (v0.9.4-plus.37) (@hindsight2021)


### 0.9.4-plus.38

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.38

Assets: 1.

### Improvements & Fixes
- Updated coil3 for AsyncImage and pass trailerService to MetaDetailsContent (@hindsight2021)
- Seamless trivia generation with background prefetch, rich film metadata, option shuffling, and resilient fallback (v0.9.4-plus.38) (@hindsight2021)


### 0.9.4-plus.39

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.39

Assets: 1.

### Improvements & Fixes
- Pass meta.cast directly as List to loadPreShow (@hindsight2021)
- Upcoming theatrical trailers, trailer skipping stability, and Show Time handoff (v0.9.4-plus.39) (@hindsight2021)


### 0.9.4-plus.40

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.40

Assets: 1.

### Improvements & Fixes
- Route onPreScrapeStreams callback to MetaDetailsContent (@hindsight2021)
- Curated upcoming theatrical trailers fallback, seamless trailer skip transitions, and strict movie exclusion (v0.9.4-plus.40) (@hindsight2021)


### 0.9.4-plus.44

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.44

Assets: 1.

### Improvements & Fixes
- Cast details photos, tmdb catalog ratings, youtube trailer black screen, and next-up missing seasons (v0.9.4-plus.44) (@hindsight2021)


### 0.9.4-plus.45

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.45

Assets: 1.

### Improvements & Fixes
- Fixed kotlin compiler errors from coil3 migration and variable renaming (v0.9.4-plus.44) (@hindsight2021)
- Multiple hotfixes (cast photos, tmdb rating, youtube trailers, next-up season synthesis) (v0.9.4-plus.45) (@hindsight2021)


### 0.9.4-plus.46

https://github.com/hindsight2021/NuvioTV/releases/tag/0.9.4-plus.46

Assets: 1.

### Improvements & Fixes
- Multi-source metadata superset merging & on-screen source switching (v0.9.4-plus.46) (@hindsight2021)

