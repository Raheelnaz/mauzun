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

## Introduction

[Molecule](https://github.com/cashapp/molecule) runs Compose without UI, so presenter logic can
use `remember`, snapshot state, and `LaunchedEffect` in place of `combine`, `stateIn`, and
friends. Molecule deliberately stops there: it hands back a `StateFlow` and leaves the Android
questions open. Where does the molecule run? How do click events reach it? How does a
navigation effect fire exactly once? What does a test look like?

This library is one set of answers, for apps already built on `ViewModel`:

- The molecule runs in `viewModelScope` under `RecompositionMode.Immediate`, started lazily on
  first use. The first composition is synchronous, so `state` always has a value, and
  recomposition follows data changes rather than display frames.
- Events enter through a buffered channel and broadcast to every collector in the presenter.
  Events sent before the presenter is composing wait for it. Fifty unconsumed events crashes
  rather than dropping input. Events after the ViewModel is cleared are dropped.
- Effects are a channel, not a SharedFlow. They buffer while the UI is stopped, and each
  effect reaches a single consumer.
- Tests run the same Immediate clock as production, so the presenter recomposes identically in
  both.

The molecule runs on `Dispatchers.Main` rather than `viewModelScope`'s `Main.immediate`.
Snapshot notifications sent inline from a write observer corrupt Compose UI's invalidation
tracking (cashapp/molecule#465). Deferred dispatch sends them after the write phase, and the
molecule needs nothing from Compose UI to keep recomposing.

Molecule is [Jake Wharton](https://jakewharton.com/)'s work. This library is the ViewModel
wiring around it, nothing more.

## Usage

`UiFactory` hosts a presenter. It collects state with the lifecycle and delivers effects only
while the screen is at least STARTED, so a stopped screen buffers effects instead of navigating
while invisible. It behaves the same under Navigation 2 and Navigation 3. Both hold an
animating destination at STARTED, so effects can fire during the animation. Pass
`effectsMinActiveState = Lifecycle.State.RESUMED` to wait for it to settle.

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

The same screen under Navigation 3. The ViewModelStore decorator scopes each ViewModel to its
back-stack entry. Without it, `hiltViewModel()` resolves against the activity and every entry
shares one instance.

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
    override fun present(events: Flow<ProductEvent>): ProductState {
        // a presenter like any other, with productId in scope
    }
}
```

```kotlin
val vm = hiltViewModel<ProductViewModel, ProductViewModel.Factory>(
    creationCallback = { factory -> factory.create(productId) },
)
```

## Writing presenters

**Collect `events` with `CollectEvents`.**

```kotlin
CollectEvents(events) { event -> ... }
```

It collects for the lifetime of the composition, and its handler scope can `launch` work that
outlives a single event. Position in the body doesn't matter, conditions do: a collector behind
an `if` has windows with nobody listening, and events sent in a window go nowhere. Same for
rolling your own collector inside a keyed `LaunchedEffect`, which restarts when the key
changes. Multiple collectors are fine, events broadcast to all of them. A collector added late
misses events sent before it started.

**Change state inside handlers and effects, not in the body.**

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

`sendEvent` is synchronous. By the time it returns the presenter has handled the event, so the
next line can assert. `awaitState` returns distinct models, the same stream a `StateFlow` gives
the UI.

Anything the presenter emitted that the test didn't assert fails the test.

Drive the presenter with `sendEvent`. Calling `vm.onEvent` in a test feeds the production
channel, which the harness doesn't read.

## Download

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel:0.1.2")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.1.2")
```

Requires minSdk 23 and Kotlin 2.1 or newer. Presenters are `@Composable`, so the module that subclasses
`MoleculeViewModel` needs the Compose compiler plugin. Unit tests need
`kotlinx-coroutines-test`, and Compose logs through `android.util.Log` on some paths, so:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

Working with a coding agent? The library's rules ship as a skill at
`.claude/skills/molecule-viewmodel/SKILL.md`. The format works with Claude Code, Codex, Copilot,
Cursor, and friends: copy the folder into your project's skills directory, or point your
AGENTS.md at the file.

## License

    MIT License

    Copyright (c) 2026 Raheel Naz
