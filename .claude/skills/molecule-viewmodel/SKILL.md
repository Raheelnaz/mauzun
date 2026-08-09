---
name: molecule-viewmodel
description: Write and test presenters built with molecule-viewmodel.
---

# molecule-viewmodel

`MoleculeViewModel` runs a composable presenter and exposes its models as a `StateFlow`.

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.5.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.5.0")
```

The library requires minSdk 23, Kotlin 2.3 or newer, and the Compose compiler plugin. JVM tests
also need `kotlinx-coroutines-test` and the following Android setting:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

## Presenter

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

- Register `CollectEvents` and `CollectEventsOf` unconditionally. The event stream does not replay
  items to a collector added after startup.
- Multiple event collectors are supported. Every active collector receives each event.
- Change snapshot state from an event handler or Compose effect. Do not write it unconditionally in
  the composition body.
- Call `emitEffect` from an event handler or Compose effect.
- Use `Nothing` when the presenter has no events or effects.
- Use `SavedStateHandle` for process-death state. There is no saveable state registry, so
  `rememberSaveable` behaves like `remember`.

For broad catches inside presenter coroutines, preserve cancellation before handling the failure:

```kotlin
try {
    repository.refresh()
} catch (failure: Throwable) {
    currentCoroutineContext().ensureActive()
    error = failure.toUiError()
}
```

An uncaught `CancellationException` ends only that coroutine. Any other uncaught exception ends the
presenter and reaches the uncaught exception handler.

## UI

```kotlin
val viewModel: CounterViewModel = hiltViewModel()

PresenterHost(
    presenter = viewModel,
    onEffect = ::handleEffect,
) { state, onEvent ->
    CounterScreen(state, onEvent)
}
```

`PresenterHost` collects models with the lifecycle and collects effects while the lifecycle is at least
`STARTED`. Set `effectsMinActiveState` to `RESUMED` when effects must wait for navigation
transitions. Values below `CREATED` are rejected.

Effects have one consumer. Do not collect the effect flow from multiple places.

Hilt is optional. Put `@HiltViewModel` on the concrete class. Use an assisted factory for runtime
screen arguments.

## Tests

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

- Create a new ViewModel for each `test` block.
- Send events with `sendEvent`, not `viewModel.onEvent`. The harness owns a separate event stream.
- `sendEvent` finishes immediate presenter work before returning. Delayed or re-dispatched work is
  still asynchronous.
- `awaitState` returns the next distinct model.
- `awaitEffect` returns the next effect.
- `expectNoStateChanges` and `expectNoEffects` inspect what is available now; they do not wait.
- `skipStates` skips distinct models.
- `awaitFailure` returns the exception that ended the presenter.
- The test fails if a model or effect remains unconsumed when the block returns.
