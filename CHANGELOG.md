# Change Log

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
