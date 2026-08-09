# Change Log

## Unreleased

- Deliver events queued before presenter startup to every active event collector.
- Stop the event pump when the presenter fails, and clean up a recomposer left by a failed first
  composition.
- Preserve the original startup failure as the cause of later `state` access errors.
- Add `awaitFailure` to the test harness and cover the remaining harness contracts.
- Include ViewModel and payload types in queue overflow messages without logging payload values.

## [0.4.0] - 2026-08-03

- Put an effect back in the buffer when a lifecycle cancellation catches it mid-handoff.
  Delivery used to lose that effect.
- Build with Kotlin 2.4.10. Consumers now need Kotlin 2.3 or newer.
- Update coroutines to 1.11.0 and turbine to 1.2.1.

## [0.3.1] - 2026-08-01

- Throw a clear error when `state` is first read after the ViewModel is cleared. The failure
  used to surface deep inside Molecule with nothing pointing at the cause. The startup race
  itself is cashapp/molecule#760 and stays upstream.
- Add regression tests for startup and collection races.
- Create GitHub releases from tag pushes.

## [0.3.0] - 2026-07-31

- Add `CollectEventsOf`, which collects one event type from the presenter's event stream.
- Lower the Compose runtime dependency to 1.9.1, the floor Molecule already sets.
- Test the remaining production contracts: synchronous first state, typed collection through
  the real channel, effect completion on clear, and effect overflow.

## [0.2.0] - 2026-07-31

- Add `LaunchedEffectNotNull` overloads for one, two, and three nullable values.
- Depend directly on Compose runtime 1.10.4 instead of exporting the Compose BOM.
- Set the documented minimums to minSdk 23 and Kotlin 2.1.
- Add tests for production event and effect wiring and Compose effects inside presenters.

## [0.1.2] - 2026-07-31

- Run the molecule on `Dispatchers.Main` to fix cashapp/molecule#465 without
  `SnapshotNotifier.External`.
- Raise minSdk to 23.

## [0.1.1] - 2026-07-31

- Upgrade Molecule to 2.2.0.
- Remove `SnapshotNotifier.External`, which caused Compose UI invalidation problems when used
  with `Main.immediate` (cashapp/molecule#465).

## [0.1.0] - 2026-07-31

Initial release.
