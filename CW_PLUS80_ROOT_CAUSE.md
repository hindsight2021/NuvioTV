# Continue Watching release discovery: Plus 80

Investigation date: 29 October 2026. Baseline: `81f3278a8`, Plus 79, versionCode 1137.

## Observed incident

ADB on Shield `192.168.1.242:5555` confirmed the installed version is Plus 79. Its process 10845 logged Scrubs `tt40197357:2:6` at 11:28:05.092 and South Park `tt0121955:29:4` at 11:28:05.173. Those messages prove resolution, not publication or rendered visibility. The production package is not debuggable; `run-as` correctly refused private-file access. UIAutomator could not obtain an idle state on the animated Home screen; screenshots remain usable.

The GitHub release inventory and comparison of every published Plus release are recorded in `CW_PLUS_RELEASE_AUDIT_2026-10-29.md`. Gaps in release numbers are preserved rather than inventing releases.

## Root causes in the source

1. **Badge state controlled metadata invalidation.** `buildNextUpItem` discarded both repository and CW caches whenever `isSeriesValidationFresh` was false. That method deliberately returns false when a show lacks a fully-watched badge. Discovering an aired unwatched episode removes that badge. Consequently even positive cache hits were repeatedly converted into network work. This also undermined the cached-older-seed fast path and increased time spent resolving the library.
2. **Resolution did not imply publication.** Foreground publication waited for 2–8 successful resolutions; older discovery waited for six successes or completion of the whole scan. A single newly released show could wait behind unrelated slow providers. Plus 71 increased the older success threshold from three to six and introduced a fixed older-seed cap; later increases to the cap did not establish eventual coverage.
3. **Snapshot workers outlived their input.** `launch` inside the non-receiver `collectLatest` action inherited the enclosing pipeline scope. Background badge/discovery work could therefore survive replacement of a progress snapshot. Several snapshot writes explicitly used `viewModelScope`, extending their lifetime further. Older work could mutate the current list and persistence after newer input arrived.
4. **A deadline was not a scheduler.** Plus 75–79 improved air dates, deadline tiers, persisted priority, and badge/air-state separation. Home still had no clock-driven CW refresh. A new release changes eligibility without changing watch progress. Computing a deadline alone did not wake discovery, and CW metadata summaries had no independent expiry.
5. **Cold-start ordering was racy.** The initial pipeline started before the persisted air-state load. A first seed selection could therefore run without the persisted priority that Plus 77/79 depended on. Profile changes similarly started the new pipeline before loading its badge/air state.

These are source-confirmed failure paths. The old build did not log its published list, so assigning the October 29 disappearance exclusively to one race would exceed the available evidence.

## Plus 80 changes

- Load persisted air state before starting initial/profile pipelines.
- Give each collected snapshot a coroutine scope; cancellation joins its badge, discovery, and snapshot-write children before the replacement runs.
- Publish each resolved next-up immediately and persist partial snapshots. Remove the fixed older scan cap while retaining sequential background resolution.
- Replace badge-driven per-lookup cache destruction with independent metadata revalidation: check while Home is resumed, retry due/unknown entries after five minutes, and refresh metadata after fifteen minutes even when the provider supplied no useful schedule. Home lifecycle polling does not run during player playback.
- Invalidate matching metadata, badge, positive/negative resolution, and repository entries together. Preserve visible cards and disk snapshots until resolution finishes. Force due discovery even if an old badge deadline remains fresh.
- Bound pending refresh state to active seeds and clear it on conclusive resolution.
- Log actual published next-up IDs/coordinates, not just successful builds, so future diagnosis can distinguish resolution from presentation.

## Delegated review

Four independent DeepSeek roles reviewed implementation, architecture, tests, and reliability. Initial reasoner calls exhausted their output allowance without a usable proposal and were rerun with the chat model. Accepted proposals: immediate publication, structured cancellation, independent refresh, targeted invalidation, coroutine-ordering and repository-boundary tests. Rejected proposals included reversing release-alert time comparisons, coupling freshness to every non-watched series, restoring fixed scan caps, flushing stale work in `NonCancellable`, fuzzy ID invalidation, and test code using nonexistent APIs or reflection to replace final dependencies. Codex inspected and corrected the proposals before integration.

## Acceptance

Acceptance must distinguish source/unit evidence, signed build, installed APK, actual published list, rendered cards, and cold-start persistence. Build-only success or a `built contentId=...` message cannot close this incident. Final build/install/device results are recorded after execution.
