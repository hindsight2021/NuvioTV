# Plus release audit: Continue Watching and its data dependencies

Each published Plus release is compared with the preceding published Plus release. The scope includes Home, air-state and dismissal stores, enrichment persistence, date parsing, launcher sync, watch progress and metadata repositories. The table records source changes, not device acceptance. Missing release numbers were not present in the published inventory.

| Release | Relevant commits | Changes |
|---|---:|---|
| 0.9.2-plus.1 | 0 |  |
| 0.9.2-plus.2 | 1 | b54909403 feat: multi-provider AI assistant, random episode context menu, and magical sound |
| 0.9.2-plus.3 | 1 | e9329a824 fix(test): provide mockk aiManager in SearchViewModel unit tests and default parameter |
| 0.9.2-plus.4 | 3 | d44c8af34 feat(ui): add random/channel actions to Continue Watching context dialog and fix Episode Options Overlay cutoff<br>b3da831f7 chore(sync): merge upstream/dev into plus-dev<br>0a2e2f870 Merge pull request #3519 from ieno/fix/subtitle-own-headers-redirect-scope |
| 0.9.2-plus.5 | 1 | 1313b9f44 fix(random-play): fix random episode selection, add home clock with date, fix dialog cutoff (v0.9.2-plus.5) |
| 0.9.2-plus.6 | 0 |  |
| 0.9.2-plus.7 | 1 | a4684c01d feat: chic AI review with TTS, 4D cinema lighting, cinema pre-show, enhanced intro skipping, and thematic channels (v0.9.2-plus.7) |
| 0.9.2-plus.8 | 2 | 012728018 fix: resolve MetaDetailsScreen and HomeScreen compilation errors (v0.9.2-plus.7)<br>7841bdbbb fix: resolve ChicReviewDialog invocation and scope in HomeScreen (v0.9.2-plus.7) |
| 0.9.2-plus.9 | 0 |  |
| 0.9.2-plus.10 | 2 | d2e7dbe59 chore(sync): merge upstream/dev into plus-dev<br>2cef93b65 fix(home): stop grid focus snapping back after See all |
| 0.9.2-plus.11 | 1 | dd1e969c3 feat: separate pages for movies/TV shows, curated library tiers, B&W launcher banner, AI curation hook (v0.9.2-plus.11) |
| 0.9.2-plus.12 | 2 | 9e615ce8c feat: center tab pill, sidebar drawer backup, fix shuffle playback bug & guard CW pollution (v0.9.2-plus.12)<br>d62dce263 fix(import): add missing android.util.Log import in HomeViewModel |
| 0.9.2-plus.13 | 1 | b85fbb97d feat: high-fidelity Chic TTS voice personas & cinematic animated backgrounds (v0.9.2-plus.13) |
| 0.9.2-plus.14 | 1 | fe59fefd7 feat: Canadian Bell Fibe live TV guide, Apple TV remote click sounds & landscape hero new episode badges (v0.9.2-plus.14) |
| 0.9.2-plus.15 | 2 | 08ac76e14 Fix Bell Fibe live TV tuning & add hero carousel above continue watching in modern landscape (v0.9.2-plus.15)<br>8b2629a76 fix(compile): fix modern flow combine, extractYearText type, surface color, and player context |
| 0.9.4-plus.16 | 1 | 849b50723 Fix duplicate NuvioTheme import in ModernHomeRowsList |
| 0.9.4-plus.17 | 0 |  |
| 0.9.4-plus.18 | 1 | 9570a6c00 feat(player/livetv/hero): playback acceleration, hero focus bridge, native IPTV & in-player mini guide (v0.9.4-plus.18) |
| 0.9.4-plus.19 | 0 |  |
| 0.9.4-plus.20 | 0 |  |
| 0.9.4-plus.21 | 0 |  |
| 0.9.4-plus.22 | 1 | 1abc33d7b fix(sprint-21): resolve screensaver black screen, top pill focus trap, sidebar scrolling, and HA integration |
| 0.9.4-plus.23 | 2 | eb3bc0210 feat(nas): sprint 22 local nas media discovery, zero-debrid playback, and ha voice fix<br>281629cc2 feat(cw): instant launch continue watching pre-scraper and sprint 21 fixes |
| 0.9.4-plus.24 | 1 | aab564f74 fix: resolve compile errors in LocalMedia repository, scanner, search, and settings for Sprint 22 |
| 0.9.4-plus.25 | 0 |  |
| 0.9.4-plus.26 | 1 | 8d267927d feat: search priority, tv catalog curation, default genres, and remote sound controls (v0.9.4-plus.26) |
| 0.9.4-plus.27 | 1 | 8ea4e8975 release(v0.9.4-plus.27): voice playback, search history, AI channels, screensaver pause fix, stutter RCA, and TMDB season expansion |
| 0.9.4-plus.28 | 1 | b82282dde feat: align with upstream v0.9.5-beta..v1.1.0-beta.1 (v0.9.4-plus.28) |
| 0.9.4-plus.29 | 0 |  |
| 0.9.4-plus.30 | 4 | 36db5e8c7 fix: correct ContentType.UNKNOWN in pipeline and restrict release build to Shield arm64-v8a<br>1d3eb31ae fix: resolve compile errors in EpisodeOptionsOverlay, HomeViewModel, and HomeViewModelCatalogPipeline<br>3e4a30776 feat(sprint-30): content freshness, CW hero banner fix, progressive grid, and extended intro skipping<br>f4f8a377e feat(home): power landscape hero banner with Simkl trending and auto-rotation |
| 0.9.4-plus.31 | 0 |  |
| 0.9.4-plus.32 | 0 |  |
| 0.9.4-plus.33 | 1 | 6c5508e2e fix(player/cw): complete playback at 85%, prevent teardown race, and fix new episode discovery for caught-up series (v0.9.4-plus.33) |
| 0.9.4-plus.34 | 1 | 1aaff62ce fix(continue-watching): resolve missing new episodes by fixing DataStore deadline lock, freshness checks, and cache invalidation |
| 0.9.4-plus.35 | 1 | ea6b1f4e2 fix(continue-watching): prioritize release alert seeds, fix older seeds positive cache drop, and prevent shrink truncation (v0.9.4-plus.35) |
| 0.9.4-plus.36 | 1 | 0e28d30eb fix(continue-watching): preserve cached older next-up items and prioritize recent release alerts (v0.9.4-plus.36) |
| 0.9.4-plus.37 | 1 | 9ed228e57 fix(continue-watching): restore missing closing brace in async older seeds flow |
| 0.9.4-plus.38 | 0 |  |
| 0.9.4-plus.39 | 0 |  |
| 0.9.4-plus.40 | 0 |  |
| 0.9.4-plus.44 | 2 | a9787d674 fix: cast details photos, tmdb catalog ratings, youtube trailer black screen, and next-up missing seasons (v0.9.4-plus.44)<br>e879fe166 fix(player): suppress trakt, simkl, and continue watching tracking for random episodes (v0.9.4-plus.43) |
| 0.9.4-plus.45 | 2 | fcb2d4b40 fix: multiple hotfixes (cast photos, tmdb rating, youtube trailers, next-up season synthesis) (v0.9.4-plus.45)<br>433fe8f7a fix(build): resolve kotlin compiler errors from coil3 migration and variable renaming (v0.9.4-plus.44) |
| 0.9.4-plus.46 | 1 | 87cdd51d1 feat: multi-source metadata superset merging & on-screen source switching (v0.9.4-plus.46) |
| 0.9.4-plus.47 | 3 | 7429605c0 fix(compile): use primary.imdbId and contentId in candidateIds for CW<br>bdbdf18d8 feat: complete Just Play integration, multi-source continue watching merging, and full release unit test fixes (v0.9.4-plus.50)<br>929846419 fix(compile): resolve candidate meta addons suspend, flow imports, and theme tokens |
| 0.9.4-plus.50 | 1 | 6b78101ee fix: guard CW metadata aliases and prepare plus.50 |
| 0.9.4-plus.51 | 1 | d0f0cb881 fix(cw): prevent unannounced future season premieres from appearing in continue watching |
| 0.9.4-plus.52 | 0 |  |
| 0.9.4-plus.53 | 0 |  |
| 0.9.4-plus.54 | 0 |  |
| 0.9.4-plus.55 | 0 |  |
| 0.9.4-plus.56 | 0 |  |
| 0.9.4-plus.57 | 0 |  |
| 0.9.4-plus.60 | 1 | 1bd660ef4 feat(localmedia): add Shield/NAS folder picker, Kodi/Jellyfin metadata parsing, and Home rows (0.9.4-plus.60) |
| 0.9.4-plus.61 | 0 |  |
| 0.9.4-plus.62 | 0 |  |
| 0.9.4-plus.63 | 1 | e155fe72c fix(continue-watching): resolve skipping newly aired episodes and refresh locked series (0.9.4-plus.63) |
| 0.9.4-plus.64 | 1 | c1939c97c fix(continue-watching): resolve ongoing show airings and prevent pre-air leaks |
| 0.9.4-plus.65 | 2 | 70710d0de fix(air-date): default postAirBufferHours to 0L for exact timestamp tests while preserving 1h post-broadcast buffer for Continue Watching<br>771f63fe8 fix(continue-watching): prioritize ongoing seeds, prevent overlay clobbering, and enforce post-playback buffer (0.9.4-plus.65) |
| 0.9.4-plus.66 | 1 | bcc599368 feat(poster): add Posters+ and custom artwork provider support (v0.9.4-plus.66) |
| 0.9.4-plus.67 | 1 | 64005d94e fix: eliminate duplicate show name on Posters+ artwork and add textless badges-only option |
| 0.9.4-plus.68 | 1 | 4df8a29dd perf(ui): eliminate scrolling and loading jank on TV poster rows (0.9.4-plus.68) |
| 0.9.4-plus.69 | 1 | b0fb5cc67 fix(continue-watching): resolve multi-seed ID fragmentation, partial metadata handling, and Cinemeta 404 aborts (0.9.4-plus.69) |
| 0.9.4-plus.70 | 1 | 7a7ef5b38 fix(continue-watching): eliminate premature partial metadata drop and auto-cluster show sibling IDs by title (0.9.4-plus.70) |
| 0.9.4-plus.71 | 1 | 0c02ec8f2 perf(continue-watching): eliminate main-thread freezes, throttle older seed discovery, and stop TMDB 401 spam (0.9.4-plus.71) |
| 0.9.4-plus.72 | 1 | bb13b2efd feat(ui): streamline context menus, add Trakt/Simkl watchlist dialog, and add temporary play next queue (0.9.4-plus.72) |
| 0.9.4-plus.73 | 0 |  |
| 0.9.4-plus.74 | 1 | 941b02c59 feat(perf): streamline boot time, catalog pagination, caching and search TTS controls (v0.9.4-plus.74) |
| 0.9.4-plus.75 | 1 | e862bf699 fix(continue-watching): ensure newly released episodes appear immediately in CW across timezones and cold starts (v0.9.4-plus.75) |
| 0.9.4-plus.76 | 2 | 9a1e27d5b fix(airing-dates): align plain date parsing with UTC midnight contract<br>a1101e2cb fix(continue-watching): event-driven revalidation and timezone-aware air dates (v0.9.4-plus.76) |
| 0.9.4-plus.77 | 1 | 9989c098d fix(continue-watching): resolve cold-start Catch-22 with persisted next air timestamp boost (0.9.4-plus.77) |
| 0.9.4-plus.78 | 0 |  |
| 0.9.4-plus.79 | 1 | 81f3278a8 fix(cw): decouple series air state from fully-watched badges to maintain release alert boost on cold start (plus.79) |
