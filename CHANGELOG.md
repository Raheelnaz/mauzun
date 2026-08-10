# Change Log

## [Unreleased]

- `moleculeViewModel()` and `hiltMoleculeViewModel()` return a `PresenterEntry` instead of the
  ViewModel. The entry exposes only the binding, so nothing outside the presenter can reach
  state or effects. Retrieval keeps its one type argument.
- Add `molecule-viewmodel-compose`. Its `PresenterHost` takes a binding and depends only on the
  contract and lifecycle Compose, so a feature UI module can skip the ViewModel runtime.
- `PresenterHost` takes the entry or the binding. The ViewModel overload and the
  `presenterBinding` property are gone.
- Remove the overload that attached saved state to an already-created ViewModel. DI adapters
  create entries through `moleculePresenterEntry` behind an opt-in annotation.
- Calling `moleculeViewModel()` from `present()` fails before anything touches the
  ViewModelStore.

## [0.7.0] - 2026-08-10

- New molecule-viewmodel-api artifact with the `PresenterBinding` contract: state, effects,
  onEvent, and nothing Android. A UI module can depend on it alone.
- `MoleculeViewModel` no longer exposes `state` or `effects`. Screens read them through
  `presenterBinding`, one instance per ViewModel. A subclass that read its own state has to
  switch to the binding too. `onEvent` stays public, so `init { onEvent(Load) }` keeps working.
- `MoleculePresenter` is gone. `PresenterHost` takes either the ViewModel or its
  `PresenterBinding`.
- The late attachment error names the init block case.

## [0.6.0] - 2026-08-10

- Add `moleculeViewModel()` and the optional `molecule-viewmodel-hilt` adapter. Both install a
  saveable state registry before the presenter starts, so `rememberSaveable` survives process
  recreation without requiring a `SavedStateHandle` constructor in the base class.
- With the registry attached, `rememberSaveable` of a value Android cannot save throws instead
  of silently acting like `remember`.

## [0.5.1] - 2026-08-09

- Move Turbine to an implementation dependency of molecule-viewmodel-test. Tests that use
  Turbine directly now declare their own dependency.
- Cover PresenterHost with lifecycle tests and the runtime with stress tests.
- Document the delivery guarantees in one table, with the first-read threading rule and the
  bounded overflow contract.

## [0.5.0] - 2026-08-09

- Rename `UiFactory` to `PresenterHost`.
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
