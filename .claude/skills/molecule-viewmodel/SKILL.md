---
name: molecule-viewmodel
description: Use when writing or testing ViewModels built on the molecule-viewmodel library (MoleculeViewModel, UiFactory, the test { } harness). Covers presenter rules, effects, and test idioms.
---

# molecule-viewmodel

A ViewModel base class for Molecule. Screen logic is a `@Composable` function. State comes out
as a `StateFlow`, one-off effects come out of a channel, and tests drive the presenter directly.

Dependencies:

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.2.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.2.0")
```

Needs minSdk 23, Kotlin 2.1 or newer, the Compose compiler plugin on the consuming module, kotlinx-coroutines-test
for tests, and `testOptions { unitTests.isReturnDefaultValues = true }`.

## Writing a ViewModel

```kotlin
class CounterViewModel : MoleculeViewModel<CounterEvent, CounterState, CounterEffect>() {

    @Composable
    override fun present(events: Flow<CounterEvent>): CounterState {
        var count by remember { mutableStateOf(0) }

        CollectEvents(events) { event ->
            when (event) {
                CounterEvent.Increment -> count++
                CounterEvent.Share -> emitEffect(CounterEffect.OpenShareSheet(count))
            }
        }

        return CounterState(count)
    }
}
```

Rules:

- Collect `events` with `CollectEvents`, unconditionally. Never put a collector behind an `if`
  or inside a keyed `LaunchedEffect`: events sent while no collector is active are dropped.
  Multiple collectors are fine. All of them receive every event.
- Write snapshot state from event handlers and effects only, never in the composition body. An
  unconditional write in the body recomposes forever.
- Call `emitEffect` from handlers and effects only, never in the composition body.
- In broad catches inside presenter effects (`catch (t: Throwable)`), rethrow
  `CancellationException` before mapping to an error state. A keyed effect restart cancels the
  old coroutine at its suspension point, and swallowing that writes a spurious failure.
- `rememberSaveable` silently does nothing in a presenter (there is no saveable registry). Use
  `SavedStateHandle` for process-death state.
- If a screen has no effects, use `Nothing` as the Effect type.
- `@HiltViewModel` goes on the subclass. For screens with arguments, use
  `@HiltViewModel(assistedFactory = ...)` with `@AssistedInject`.

## Wiring into UI

```kotlin
val vm: CounterViewModel = hiltViewModel()
UiFactory(
    presenter = vm,
    onEffect = { effect -> /* navigate, snackbar, ... */ },
) { state, onEvent ->
    CounterScreen(state, onEvent)
}
```

`UiFactory` collects state with the lifecycle and delivers effects while at least STARTED.
Effects buffer while the screen is stopped. Pass `effectsMinActiveState = Lifecycle.State.RESUMED`
to wait out navigation transitions. Collect `effects` from exactly one place. Two concurrent
collectors silently split the stream.

## Testing

```kotlin
@Test
fun increment() = runTest {
    CounterViewModel().test {
        assertThat(awaitState()).isEqualTo(CounterState(0))
        sendEvent(CounterEvent.Increment)
        assertThat(awaitState()).isEqualTo(CounterState(1))
    }
}
```

- Drive events with `sendEvent`, never `vm.onEvent`. `onEvent` feeds the production channel,
  which the harness does not read. After 50 buffered sends it throws.
- `sendEvent` is synchronous: everything the event triggers has run when it returns, so assert
  on the next line. Work behind a `delay` or another dispatcher is the exception. Step past it
  with `awaitState()`.
- `awaitState()` returns the next distinct model. Consecutive equal models are filtered, the
  same stream a `StateFlow` gives the UI.
- The block is strict: any state or effect emitted but never asserted fails the test. Use
  `skipStates(n)` for states you don't care about. There is no predicate-based skip.
- `expectNoStateChanges()` and `expectNoEffects()` are valid immediately after `sendEvent`.
- Tests run on the JVM with plain `runTest`. No Robolectric, no dispatcher setup, no main
  looper.
- Construct a fresh ViewModel per `test { }` block. Channels are per-instance and leftovers
  leak across blocks.
