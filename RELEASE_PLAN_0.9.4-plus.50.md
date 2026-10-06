# Nuvio+ 0.9.4-plus.50 — Just Play

Status: implementation plan, 5 October 2026. No release changes implemented by this planning pass.

## Release outcome

Make everyday viewing trustworthy, then deliver one visible payoff: **ask for your next episode, or select it on TV, and actually watch it.** Exact episode, correct profile, saved position, existing source preferences. No successful response that merely means a search screen opened.

This builds on existing Continue Watching, stream selection, pre-scraping and local control. It preserves existing navigation, provider selection and manual playback. It is one feature spanning existing entry points, not a new dashboard or a second playback engine.

Quality target: no known release-blocking defects or regressions in the agreed acceptance matrix. A universal zero-bug guarantee is not a testable promise; failures and untested behavior must remain visible.

## Why this feature

The repeated friction is getting to the right episode: incomplete seasons, delayed metadata, lost next-up items, random viewing contaminating history, and voice requests that stop at search. This release makes stability visible in one everyday action.

Current verified gaps:
- AppCommandBus.kt:70–72 turns PlayMedia into a title search despite receiving content ID, season and episode.
- AiOperatorEngine.kt:146 onward turns a parsed play request into a title search.
- PlaybackAvailability.canStream reports provider capability or embedded streams; capability alone does not establish a resolved working source.
- ContinueWatchingPreScrapeCoordinator already warms links and suppresses work during playback/screensaver. Reuse and preserve that policy.

## The viewing experience

On the focused Continue Watching card and series details, present an explicit action such as **Resume S02E04 · 18:42** or **Play next · S02E05** using the existing visual language. Preserve all current context actions. Respect spoiler settings in text and artwork.

Supported intents:
- “Play my next episode of [show].” Resume a genuinely in-progress episode first; otherwise resolve the next eligible unwatched episode under existing tracking rules.
- “Play [show], season 2, episode 4.” Resolve that exact episode; honor existing resume/start-over behavior.
- Equivalent TV and authenticated local API requests produce the same episode decision for the same profile and state.

Title ambiguity yields a small choice, not a guessed show. Episode ambiguity yields an explanation and existing episode/source controls. A caught-up show remains followed; unknown dates do not become invented premieres. An absent date means unknown, not automatically unaired. Existing real episodes with usable sources must not become inaccessible just because a provider omits dates.

The action displays resolving/starting states and remains cancellable. A fresh cached link can accelerate startup, but must be re-resolved if stale or rejected. Retries are bounded and remain on the exact same episode. Honor quality, language, source and manual-selection preferences. Do not add broad mid-playback automatic failover in this release.

“Started” requires player confirmation, not command acceptance. Local clients receive accepted/resolving/started/failed status with a request identifier or an equivalent backward-compatible contract. Duplicate requests must not launch duplicate sessions. Profile changes and cancellation invalidate obsolete work. Responses must not expose stream credentials or tokens.

If sources fail: “No source found for S02E05” with Retry and Choose source. Do not silently substitute another episode, mark it watched, or report playback success. A returned stream URL is only a candidate; actual startup establishes playback.

Core next-episode selection does not require an LLM. Existing AI may interpret natural language, but deterministic resolution owns identity, progress and playback. Update the repository's HA integration where needed; changes to the deployed household HA/Alexa configuration are a separate rollout step, preserving existing working routes.

## Work sequence

### 1. Establish the release safety net

- Capture the current SHA, signing certificate identity, versionCode, CI state and focused behavior fixtures.
- Cover plus-dev in PR validation and execute relevant tests beyond updater-only filtering; run the full JVM suite in GitHub before release. Report pre-existing failures separately and resolve release-blocking failures rather than hiding them.
- Tie release tag and assets to the exact tested build SHA; retain the requested tag format 0.9.4-plus.50.
- Fail on publication errors and release-note generation errors. Remove the hard-coded success fallback.
- Replace direct upstream merge-and-publish with a reviewable integration path. Avoid changing unrelated upstream issue automation in this release.
- Verify increasing versionCode against current releases at implementation time. Preserve package and signing identity.

### 2. Repair metadata and Continue Watching

- Preserve distinct unnumbered episodes; do not map unknown season/episode to zero. Use safe identity fallback and reject cross-title merges.
- Make provider precedence deterministic while preserving canonical title identity and supplementing episodes from other providers.
- Publish same-count enrichment; serialize/version merge results so late responses cannot restore stale state or override current source selection.
- Preserve current season and focus across enrichment; handle provider timeout and cancellation.
- Remove fabricated playable premieres. Separate known episode, release evidence, provider capability and resolved source state.
- Preserve followed/caught-up shows through transient provider failures and retain profile/dismissal boundaries.
- Keep random/comfort viewing isolated from normal resume and tracking progress.
- Reconcile details and Continue Watching through a shared small decision layer, not a wholesale view-model rewrite.

### 3. Implement Just Play

- Introduce/reuse a single exact-episode resolution coordinator with typed outcomes and request lifecycle.
- Connect current TV actions and local PlayMedia to it; connect parsed play intents after disambiguating identity.
- Reuse existing stream selector, caches and player navigation; retain manual source access.
- Show the resolved episode and resume position before/during startup. Confirm success from player state.
- Keep cached source keys scoped correctly to profile/content/episode/settings, invalidate on relevant changes, and preserve the existing suppression of background work during playback.
- Add the minimal compatible HA integration support and request-status diagnostics needed to use this path.

### 4. Protect adjacent experiences

- Check ordinary trailers and cinema pre-show independently, including trivia input, skip and Show Time handoff; fix reproducible regressions within scope.
- Check queues, random playback, live TV, NAS/direct playback, tracking, audio/subtitles, focus and Back behavior.
- Remove unsafe credential defaults with explicit configuration errors where required; preserve installed-app upgrade signing and functioning provider setup. Do not rotate keys blindly.
- Update README/HANDOVER and the release acceptance ledger to describe the fork accurately.

### 5. Candidate, device acceptance, publication

- Build a signed candidate through GitHub and provide its APK, checksum, built SHA and test results.
- Preserve manual TV installation. No automatic ADB install or interruption of active playback.
- Record actual Shield results after installation and an available test window; do not call pending device checks passes.
- Publish/promote the exact accepted artifact without rebuilding different bytes. Version label alone is not sufficient provenance.
- Keep prior artifacts and settings/export guidance. Do not promise an Android downgrade with adb install -r: lower versionCode may be rejected. Prefer a forward repair with a higher versionCode; any destructive recovery requires separate authorization.

## Acceptance matrix

| Area | Required evidence |
|---|---|
| Identity | Null/zero coordinates, specials, aliases and conflicting provider IDs cannot merge unrelated episodes |
| Enrichment | All deterministic provider completion orders converge; equal-count improvements appear; chosen source/season remains selected |
| Next-up | Real S10E01 can supplement seasons 1–9; partial mid-season data cannot invent a new-season premiere; unknown stays distinct from unaired |
| Entry-point parity | TV and authenticated local commands resolve the same canonical episode/profile/resume decision using identical fixtures |
| Real playback | Exact requested episode starts on Shield; accepted/searching is never reported as started |
| Resume | Coordinator preserves saved position exactly; observed seek position respects the player's established tolerance, recorded in results |
| Failure | Timeout, provider outage, expired link and no source return actionable outcomes without false watch progress |
| Races | Cancel, double press, duplicate request, profile switch and source change cannot start obsolete playback |
| Preferences | Manual source mode, language/quality preferences and spoiler settings survive the new path |
| Isolation | Random sessions, trailers and pre-show do not alter ordinary resume/next-up state |
| Regression | Existing playback/queue/live-TV/NAS controls, subtitles/audio, focus and Back remain usable |
| Performance | Compare cold/warm startup and frame/buffering behavior with baseline on the same device; retain playback-time background suppression |
| Release | Full intended test gates pass; tag SHA equals tested SHA; asset checksum and signing certificate match the acceptance record; upgrade retains data |

Tests must exercise the real decision/integration code, not mirror its implementation. Use controlled completion order and clocks for races; avoid relying on arbitrary repeat counts. Preserve progressive enrichment where useful rather than insisting on exactly one UI publication.

## Delegation and scope control

Codex owns integration and verification. During implementation use 4–5 independent DeepSeek work packages: release integrity; metadata/watch-state repair; exact-play coordinator and integration; regression test design; final review. Stage dependent work after interface agreement. Supply minimal relevant code and no credentials.

Four independent DeepSeek planning reviews informed this document. Accepted: a bounded Just Play slice, shared resolution, explicit failure states, profile/cancellation coverage and exact-SHA release evidence. Rejected: putting CI repairs last; changing the requested tag format; claiming a missing date proves an episode is unaired; treating a returned source as guaranteed playback; promising a simple downgrade; arbitrary 100-run flake claims; invented device specifications. No generated implementation was applied.

Excluded from .50: new recommendation engine, new home layout, new notification system, player-engine replacement, broad automatic mid-stream failover, unrelated HA dashboard redesign. The single added experience is dependable Just Play.

Release completion means the artifact and acceptance record agree. Known failed or pending release gates block the stable designation; the feature is not silently removed to make a green report.
