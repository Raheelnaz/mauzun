# molecule-viewmodel

[![build](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml/badge.svg)](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.raheelnaz/molecule-viewmodel)](https://central.sonatype.com/artifact/io.github.raheelnaz/molecule-viewmodel)

Write a ViewModel's logic as a `@Composable` function.

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

Tests run on the JVM. No Robolectric, no dispatcher setup.

## Why this exists

[Molecule](https://github.com/cashapp/molecule) runs Compose without UI, so presenter logic can
use `remember`, snapshot state, and `LaunchedEffect` in place of `combine`, `stateIn`, and
friends. It does not provide Android ViewModel integration or an event/effect API. I kept
rebuilding that glue, so this library packages it:

- The molecule starts lazily in `viewModelScope` with `RecompositionMode.Immediate`. Its first
  model is available synchronously.
- Events wait in a buffered channel until the presenter starts, then broadcast to every event
  collector in it. The buffer throws on overflow instead of dropping an event.
- Effects also use a buffered channel. They wait while the UI is stopped and go to one
  collector.
- The test artifact runs presenters with the same recomposition mode used in production.

The molecule uses `Dispatchers.Main`, not `viewModelScope`'s `Main.immediate`. Deferring snapshot
notifications until after the current write avoids the invalidation bug in
[cashapp/molecule#465](https://github.com/cashapp/molecule/issues/465). No Compose UI frame clock
is involved.

Molecule is [Jake Wharton](https://jakewharton.com/)'s work. This library supplies the Android
ViewModel integration.

## Usage

`UiFactory` collects state with the lifecycle. It collects effects while the screen is at least
STARTED, which leaves them buffered while the screen is stopped. Use
`effectsMinActiveState = Lifecycle.State.RESUMED` if navigation effects must wait until a
transition finishes.

```kotlin
composable("counter") {
    val vm: CounterViewModel = hiltViewModel()
    UiFactory(
        presenter = vm,
        onEffect = { effect ->
            when (effect) {
                is CounterEffect.OpenShareSheet -> shareSheet.open(effect.count)
            }
        },
    ) { state, onEvent ->
        CounterScreen(state, onEvent)
    }
}
```

With Navigation 3, include the ViewModelStore decorator so each back-stack entry gets its own
ViewModel:

```kotlin
NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    ),
    entryProvider = entryProvider {
        entry<CounterKey> {
            val vm: CounterViewModel = hiltViewModel()
            UiFactory(
                presenter = vm,
                onEffect = { effect ->
                    when (effect) {
                        is CounterEffect.OpenShareSheet -> shareSheet.open(effect.count)
                    }
                },
            ) { state, onEvent ->
                CounterScreen(state, onEvent)
            }
        }
    },
)
```

Hilt is optional. `MoleculeViewModel` is a plain `ViewModel`. For a screen that takes an
argument, assisted injection works the usual way:

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
val vm = hiltViewModel<ProductViewModel, ProductViewModel.Factory>(
    creationCallback = { factory -> factory.create(productId) },
)
```

## Writing presenters

Use `CollectEvents` for event handling:

```kotlin
CollectEvents(events) { event -> ... }
```

The collector lives as long as the presenter composition. Do not put it behind an `if` or use a
keyed `LaunchedEffect` as an event collector; events sent while that collector is absent are
lost. Multiple `CollectEvents` calls are supported and each receives every event.

Change state from an event handler or effect, not directly in the composition body:

```kotlin
var count by remember { mutableStateOf(0) }
count++                                  // recomposes forever
CollectEvents(events) { count++ }        // fine
```

## Testing

Everything happens inside `test { }`:

```kotlin
vm.test {
    sendEvent(event)        // deliver an event and everything it triggers
    awaitState()            // the next distinct model
    awaitEffect()           // the next effect
    expectNoStateChanges()  // assert the model didn't change
    expectNoEffects()       // assert nothing fired
    skipStates(2)           // jump past states you don't care about
}
```

`sendEvent` is synchronous. When it returns, the presenter has handled the event. `awaitState`
returns only distinct models, matching the production `StateFlow`.

Anything the presenter emitted that the test didn't assert fails the test.

Drive the presenter with `sendEvent`. Calling `vm.onEvent` in a test feeds the production
channel, which the harness doesn't read.

## Download

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.3.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.3.0")
```

Requires minSdk 23 and Kotlin 2.1 or newer. The module that subclasses `MoleculeViewModel` needs
the Compose compiler plugin. Unit tests need `kotlinx-coroutines-test`. Compose also calls
`android.util.Log` on some JVM test paths, so enable default Android return values:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

## License

    MIT License

    Copyright (c) 2026 Raheel Naz
