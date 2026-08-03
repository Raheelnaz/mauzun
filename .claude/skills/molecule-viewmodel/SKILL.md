---
name: molecule-viewmodel
description: Instructions for writing and testing molecule-viewmodel presenters.
---

# molecule-viewmodel

A ViewModel base class for Molecule. Screen logic lives in a `@Composable` presenter, state is
exposed as a `StateFlow`, and tests call the presenter directly.

Dependencies:

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.4.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.4.0")
```

Requires minSdk 23, Kotlin 2.3 or newer, and the Compose compiler plugin. Tests also need
`kotlinx-coroutines-test` and `testOptions { unitTests.isReturnDefaultValues = true }`.

## Writing a ViewModel

```kotlin
class CounterViewModel : MoleculeViewModel<CounterEvent, CounterState, CounterEffect>() {

    @Composable
    override fun present(events: Flow<CounterEvent>): CounterState {
        var count by remember { mutableIntStateOf(0) }

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

- Always collect `events` with `CollectEvents`. Do not put the collector behind an `if` or
  inside a keyed `LaunchedEffect`; events are lost while the collector is absent. Multiple
  collectors are supported and each receives every event.
- Write snapshot state from event handlers and effects only, never in the composition body. An
  unconditional write in the body recomposes forever.
- Call `emitEffect` from handlers and effects only, never in the composition body.
- In broad catches inside presenter effects (`catch (t: Throwable)`), rethrow
  `CancellationException` before mapping to an error state. A keyed effect restart cancels the
  old coroutine where it suspended, and swallowing that shows an error that never happened.
- `rememberSaveable` falls back to `remember` because the presenter has no saveable registry.
  Use `SavedStateHandle` for process-death state.
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

`UiFactory` collects state with the lifecycle and effects while at least STARTED. Effects stay
buffered while the screen is stopped, and one caught mid-handoff by a lifecycle cancellation
goes back in the buffer. Use `effectsMinActiveState = Lifecycle.State.RESUMED` to wait for
navigation transitions. Collect `effects` from one place; concurrent collectors split the
channel.

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
- `sendEvent` is synchronous, so the next line can assert its result. Work behind a `delay` or
  another dispatcher is the exception; wait for that work with `awaitState()`.
- `awaitState()` returns the next distinct model, matching the production `StateFlow`.
- Unasserted states and effects fail the test. Use `skipStates(n)` for states you do not need.
- `expectNoStateChanges()` and `expectNoEffects()` are valid immediately after `sendEvent`.
- Tests run on the JVM with plain `runTest`. No Robolectric, no dispatcher setup, no main
  looper.
- Construct a fresh ViewModel for each `test { }` block.
