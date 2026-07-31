# Change Log

## [0.2.0] - 2026-07-31

- Add `LaunchedEffectNotNull`: a `LaunchedEffect` for one to three nullable values that runs
  only while all of them are non-null, handing them to the block already checked.
- Stop exporting the Compose BOM. The library depends on Compose runtime 1.10.4 directly, so
  apps own their own BOM. Consuming needs minSdk 23 and Kotlin 2.1 or newer.
- Cover the production wiring with tests: pre-start event delivery, broadcast, effect
  buffering, overflow and teardown, and snapshotFlow, plus DisposableEffect and produceState
  inside presenters.

## [0.1.2] - 2026-07-31

- Run the molecule on `Dispatchers.Main` instead of `viewModelScope`'s `Main.immediate`. The
  deferred dispatch fixes cashapp/molecule#465 without `SnapshotNotifier.External`, so `state`
  no longer needs Compose UI running before its first read.
- Raise minSdk to 23, which Compose runtime and Molecule already require.

## [0.1.1] - 2026-07-31

- Upgrade Molecule to 2.2.0.
- Stop sending snapshot apply notifications from the molecule (`SnapshotNotifier.External`).
  Molecule's own notifier on Main.immediate broke Compose UI invalidation tracking
  (cashapp/molecule#465): stuck scrolling and stalled image fades in consuming apps.

## [0.1.0] - 2026-07-31

Initial release.
