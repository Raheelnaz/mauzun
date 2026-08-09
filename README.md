# molecule-viewmodel

[![build](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml/badge.svg)](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.raheelnaz/molecule-viewmodel)](https://central.sonatype.com/artifact/io.github.raheelnaz/molecule-viewmodel)

Use a [Molecule](https://github.com/cashapp/molecule) presenter as an Android `ViewModel`.

`MoleculeViewModel` runs a composable presenter in `viewModelScope`, exposes its models as a
`StateFlow`, and provides channels for UI events and one-time effects. The test artifact runs the
same presenter directly on the JVM.

Inside the presenter, use `remember`, `collectAsState`, and Compose effects. Outside it, the screen
sees an ordinary ViewModel.

## Quick start

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

Render it from Compose:

```kotlin
val viewModel: CounterViewModel = hiltViewModel()

PresenterHost(
    presenter = viewModel,
    onEffect = { effect ->
        when (effect) {
            is CounterEffect.OpenShareSheet -> shareSheet.open(effect.count)
        }
    },
) { state, onEvent ->
    CounterScreen(state, onEvent)
}
```

Test the presenter without starting Android or replacing `Dispatchers.Main`:

```kotlin
@Test
fun increment() = runTest {
    CounterViewModel().test {
        assertThat(awaitState()).isEqualTo(CounterState(0))

        sendEvent(CounterEvent.Increment)
        assertThat(awaitState()).isEqualTo(CounterState(1))

        sendEvent(CounterEvent.Share)
        assertThat(awaitEffect()).isEqualTo(CounterEffect.OpenShareSheet(1))
    }
}
```

```
          events                    models
UI ──────────────────▶ present() ──────────────────▶ UI
                           │
                           └── effects ──▶ onEffect
```

## Installation

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.5.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.5.0")
```

The library requires minSdk 23 and Kotlin 2.3 or newer. Apply the Compose compiler plugin to the
module that subclasses `MoleculeViewModel`.

Unit tests also need `kotlinx-coroutines-test`. Compose calls `android.util.Log` on some JVM test
paths, so enable default Android return values:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

## Models, events, and effects

| Type | Direction | Purpose |
| --- | --- | --- |
| Model | Presenter to UI | Everything the screen needs to render |
| Event | UI to presenter | User input such as a tap or retry |
| Effect | Presenter to UI | One-time work such as navigation or a snackbar |

### Models

The molecule starts when `state` is first read. `RecompositionMode.Immediate` produces the first
model during that read, so `state.value` is available as soon as the getter returns. That first
composition runs on whichever thread reads `state` first, so make the first read on Main. Later
models are conflated by equality, like any other `StateFlow`.

### Events

Events sent before the presenter starts wait in the input queue. Once the presenter is running,
events are broadcast to every active collector and are not replayed to collectors added later.

Register event collectors unconditionally:

```kotlin
CollectEvents(events) { event -> handleEvent(event) }
```

Use `CollectEventsOf` when a handler only accepts one event type:

```kotlin
CollectEventsOf<CounterEvent.Increment>(events) {
    count++
}
```

Both input and effect queues have a capacity of 50. A running presenter drains events into a
64 slot broadcast buffer, so more than 50 events can be waiting before a send throws. Sending
to a full queue throws with the ViewModel and payload types in the message. Sending after the
ViewModel is cleared does nothing.

### Effects

Effects are delivered to one collector. `PresenterHost` collects them while the UI lifecycle is at
least `STARTED`, so effects remain queued while the screen is stopped.

An effect is considered delivered when `onEffect` starts. If lifecycle cancellation happens after
the channel receives an effect but before `onEffect` starts, the effect returns to the queue when
there is room, behind anything buffered meanwhile.

Effects are not a durable queue. Work that must happen exactly once, a payment or a write,
belongs in the presenter, not in an effect.

Collect effects from one place. Concurrent collectors divide the stream between them.

### Guarantees

| Behavior | Guarantee |
| --- | --- |
| Events before startup | Kept, delivered to every collector once the presenter starts |
| Events while running | Broadcast to every active collector, never replayed |
| Event overflow | Throws after 50 queued, plus 64 in flight once running |
| Effects | One collector, buffered while the screen is stopped |
| Effect caught by cancellation | Back in the queue while there is room, behind newer effects |
| Effect overflow | Throws after 50 unconsumed |
| First read of `state` | Composes synchronously on the calling thread |
| After the ViewModel clears | Sends are dropped, the effects flow completes |
| A handler that throws | Cancellation ends that collector, anything else ends the presenter |

## Writing presenters

Presenter logic uses the same `remember`, `collectAsState`, and Compose effect APIs as UI code. A
few rules keep that state predictable:

- Change snapshot state from an event handler or Compose effect. Writing it unconditionally in
  the composition body causes an endless recomposition loop.
- Call `emitEffect` from an event handler or Compose effect, not from the composition body.
- Keep `CollectEvents` and `CollectEventsOf` out of conditionals. Events are lost while a collector
  is absent.
- Use `Nothing` when a screen has no events or effects.

If a broad catch handles failures inside a presenter coroutine, check for real cancellation first:

```kotlin
try {
    repository.refresh()
} catch (failure: Throwable) {
    currentCoroutineContext().ensureActive()
    error = failure.toUiError()
}
```

`ensureActive()` rethrows when the presenter coroutine was cancelled. It does not rethrow a
`TimeoutCancellationException` caught outside its `withTimeout` block, because the surrounding
event collector is still active.

An uncaught `CancellationException` stops only that event collector. Any other uncaught exception
stops the presenter and reaches the uncaught exception handler.

## UI lifecycle

`PresenterHost` collects models with `collectAsStateWithLifecycle`. Effects are collected with
`repeatOnLifecycle` and default to `Lifecycle.State.STARTED`.

Set `effectsMinActiveState = Lifecycle.State.RESUMED` when an effect must wait until a navigation
transition finishes.

`PresenterHost` controls collection in the UI. It does not pause the presenter itself; the molecule
runs until the ViewModel is cleared.

## Testing

`test` calls the composable presenter directly. Use a new ViewModel for each test.

```kotlin
viewModel.test {
    awaitState()            // Wait for the next distinct model.
    sendEvent(event)        // Send an event and finish immediate presenter work.
    awaitEffect()           // Wait for the next effect.
    expectNoStateChanges()  // Fail if a model is ready now.
    expectNoEffects()       // Fail if an effect is ready now.
    skipStates(2)           // Skip two distinct models.
    awaitFailure()          // Wait for a terminal presenter failure.
}
```

`sendEvent` is synchronous for work that stays on the current dispatcher. Work behind a `delay` or
another dispatcher still finishes asynchronously.

The harness fails when the block returns with an unconsumed model or effect. Drive it with
`sendEvent`; calling `viewModel.onEvent` writes to the production queue, which the harness does not
read.

## Hilt

Hilt is optional. Add `@HiltViewModel` to the concrete class. A screen that takes an argument
uses an assisted factory:

```kotlin
@HiltViewModel(assistedFactory = ProductViewModel.Factory::class)
class ProductViewModel @AssistedInject constructor(
    @Assisted private val productId: String,
    private val repository: ProductRepository,
) : MoleculeViewModel<ProductEvent, ProductState, ProductEffect>() {

    @AssistedFactory
    interface Factory {
        fun create(productId: String): ProductViewModel
    }

    @Composable
    override fun present(events: Flow<ProductEvent>): ProductState = TODO()
}
```

```kotlin
val viewModel = hiltViewModel<ProductViewModel, ProductViewModel.Factory>(
    creationCallback = { factory -> factory.create(productId) },
)
```

## Navigation 3

Include the ViewModelStore decorator so each back-stack entry owns its ViewModel:

```kotlin
entryDecorators = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)
```

The library does not define a navigation API. Navigation can stay in the UI or be handled as an
effect, depending on the application.

## Implementation notes

The molecule runs on `Dispatchers.Main`, not `Main.immediate`, to avoid the invalidation problem
tracked in [cashapp/molecule#465](https://github.com/cashapp/molecule/issues/465). It does not use a
Compose UI frame clock.

`rememberSaveable` falls back to `remember` because the presenter has no saveable state registry.
Use `SavedStateHandle` for state that must survive process death.

The project is pre-1.0. Minor releases may change the public API; see [CHANGELOG.md](CHANGELOG.md)
before upgrading.

Molecule is maintained by [Cash App](https://github.com/cashapp/molecule). This project provides
the Android ViewModel and testing integration around it.

## License

    MIT License

    Copyright (c) 2026 Raheel Naz
