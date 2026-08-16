# Mauzun

[![build](https://github.com/Raheelnaz/mauzun/actions/workflows/build.yaml/badge.svg?branch=main)](https://github.com/Raheelnaz/mauzun/actions/workflows/build.yaml?query=branch%3Amain)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.raheelnaz/mauzun)](https://central.sonatype.com/artifact/io.github.raheelnaz/mauzun)

Use a [Molecule](https://github.com/cashapp/molecule) presenter as an Android `ViewModel`.

`MauzunViewModel` runs a composable presenter in `viewModelScope`. The screen reads models from
a `StateFlow`, sends events, and receives one-time effects. The test artifact runs the same
presenter directly on the JVM.

Inside the presenter, use `remember`, Flow collection, and Compose effects. Outside it, the screen
sees only the binding contract.

## Quick start

```kotlin
class CounterViewModel : MauzunViewModel<CounterEvent, CounterState, CounterEffect>() {

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
val presenter = mauzunViewModel<CounterViewModel>()

PresenterHost(
    presenter = presenter,
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

## Installation

```kotlin
implementation("io.github.raheelnaz:mauzun:0.11.0")
testImplementation("io.github.raheelnaz:mauzun-test:0.11.0")
```

Hilt users can add the optional adapter:

```kotlin
implementation("io.github.raheelnaz:mauzun-hilt:0.11.0")
```

Metro users can add the optional adapter:

```kotlin
implementation("io.github.raheelnaz:mauzun-metro:0.11.0")
```

The library requires minSdk 23 and Kotlin 2.3 or newer. Apply the Compose compiler plugin to the
module that subclasses `MauzunViewModel`.

`mauzun-api` and `mauzun-compose` arrive transitively with the main
artifact. A UI module that receives a `PresenterBinding` can depend on the Compose host alone:

```kotlin
implementation("io.github.raheelnaz:mauzun-compose:0.11.0")
```

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

```
          events                    models
UI ──────────────────▶ present() ──────────────────▶ UI
                           │
                           └── effects ──▶ onEffect
```

| Type | Direction | Purpose |
| --- | --- | --- |
| Model | Presenter to UI | Everything the screen needs to render |
| Event | UI to presenter | User input such as a tap or retry |
| Effect | Presenter to UI | One-time work such as navigation or a snackbar |

### Models

The molecule starts the first time the binding's `state` is read. `RecompositionMode.Immediate`
produces the first model during that read, so `state.value` is available as soon as the getter
returns. That first composition runs on whichever thread reads `state` first, so make the first
read on Main. Later models are conflated by equality, like any other `StateFlow`.

The ViewModel does not expose `state`, `effects`, or its binding. `mauzunViewModel()` returns a
`PresenterEntry`, and `PresenterHost` reads the entry's binding for you. The entry does not
expose the underlying ViewModel.

At a module boundary, pass only the binding:

```kotlin
// app module
val presenter = mauzunViewModel<ProductViewModel>()
ProductUi(presenter.binding)

// feature UI module
@Composable
fun ProductUi(binding: PresenterBinding<ProductEvent, ProductState, ProductEffect>) {
    PresenterHost(binding, onEffect = ::handleEffect) { state, onEvent ->
        ProductScreen(state, onEvent)
    }
}
```

`ProductUi` can live in a module that depends on `mauzun-compose` instead of the
ViewModel runtime.

### Events

Events sent before the presenter starts wait in the input queue. Once the presenter is running,
events are broadcast to every active collector and are not replayed to collectors added later.

Register event collectors unconditionally:

```kotlin
CollectEvents(events) { event -> handleEvent(event) }
```

Use `CollectEventsOf` when a block only accepts one event type:

```kotlin
CollectEventsOf<CounterEvent.Increment>(events) {
    count++
}
```

Both input and effect queues hold 50 items. A full queue throws instead of suspending or silently
dropping, and the error names the ViewModel and payload types without logging values. Sending
after the ViewModel is cleared does nothing. The exact overflow points are in the guarantees
table.

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
| Event overflow | Throws on the 51st queued send before startup, and stays bounded once running |
| Effects | One collector, buffered while the screen is stopped |
| Effect caught by cancellation | Back in the queue while there is room, behind newer effects |
| Effect overflow | Throws when the 50 slot queue is full |
| First read of an entry's `binding.state` | Composes synchronously on the calling thread |
| Presenter lifecycle | Most active lifecycle among the compositions retrieving the entry |
| After the ViewModel clears | Sends are dropped, the effects flow completes |
| A `CollectEvents` block that throws | Cancellation ends that collector, anything else ends the presenter |

## Writing presenters

Presenter logic uses the same `remember`, `collectAsState`, and Compose effect APIs as UI code. A
few rules keep that state predictable:

- Change snapshot state from a `CollectEvents` block or Compose effect. Writing it unconditionally in
  the composition body causes an endless recomposition loop.
- Call `emitEffect` from a `CollectEvents` block or Compose effect, not from the composition body.
- Keep `CollectEvents` and `CollectEventsOf` out of conditionals. Events are lost while a collector
  is absent.
- Use `Nothing` when a screen has no events or effects.

Use `collectAsStateWhileActive` when a particular Flow should stop collecting while its screen
is below `STARTED`:

```kotlin
val products by repository.products.collectAsStateWhileActive(initialValue = emptyList())
```

Use `collectAsState` for work that should continue for the ViewModel's lifetime. Lifecycle-aware
collection cancels the subscription below its minimum state. A cold upstream stops with that
subscription. A hot producer keeps whatever lifetime and sharing policy created it.

When collection resumes, a cold Flow starts again and a `StateFlow` immediately emits its
current value. Hot streams that do not replay, such as a `SharedFlow` with `replay = 0`, can
miss values emitted while stopped. Room observable queries rerun and emit the database's
current result.

An uncaught `CancellationException` stops only that event collector. Any other uncaught exception
stops the presenter and reaches the uncaught exception handler.

## UI lifecycle

`mauzunViewModel()` observes the calling composition's `LocalLifecycleOwner` and provides it to the
presenter. Hilt and Metro retrieval use the same path.

The composition runs until the ViewModel clears. Moving below `STARTED` only pauses the
opted-in Flows. Everything else, `remember`, `LaunchedEffect`, event handling, carries on. If
the same entry is retrieved by more than one composition, the presenter receives the most
active lifecycle state among them.

With no attached composition, or while every attached owner is below `STARTED`, the presenter
lifecycle is `CREATED`. It reaches `DESTROYED` only when the ViewModel clears.

`PresenterHost` collects models with `collectAsStateWithLifecycle`. Effects are collected with
`repeatOnLifecycle` and default to `Lifecycle.State.STARTED`.

Set `effectsMinActiveState = Lifecycle.State.RESUMED` when an effect must wait until a navigation
transition finishes.

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
`sendEvent`. Calling `viewModel.onEvent` writes to the production queue, which the harness does not
read.

Harness presenters receive a `RESUMED` lifecycle, so `collectAsStateWhileActive` collects with
no extra setup.

## Saved state

`mauzunViewModel()` lets a presenter use `rememberSaveable`. It installs saved state before it
returns the entry, so the first composition can restore values after process recreation.

```kotlin
val presenter = mauzunViewModel<ProductViewModel>()
```

The entry is the only supported production route to the binding, so saved state attaches
before UI code can start the presenter. A `MauzunViewModel` subclass cannot read its own produced
state, including from its constructor, `init` block, or `present` function. An injected
`SavedStateHandle` remains available for application state that does not belong in
`rememberSaveable`.

## Hilt

Hilt is optional. Use `hiltMauzunViewModel()` instead of `hiltViewModel()` to obtain the same
entry while Hilt creates the ViewModel. Constructor injection, assisted injection, scoping, and
`SavedStateHandle` all work the way Hilt users expect.

### Constructor injection

Add `@HiltViewModel` to an ordinary constructor-injected class:

```kotlin
@HiltViewModel
class ProductListViewModel @Inject constructor(
    private val repository: ProductRepository,
) : MauzunViewModel<ProductListEvent, ProductListState, ProductListEffect>() {

    @Composable
    override fun present(events: Flow<ProductListEvent>): ProductListState = TODO()
}
```

Retrieve and render it from the destination:

```kotlin
@Composable
fun ProductListRoute(onOpenProduct: (String) -> Unit) {
    val presenter = hiltMauzunViewModel<ProductListViewModel>()

    PresenterHost(
        presenter = presenter,
        onEffect = { effect ->
            when (effect) {
                is ProductListEffect.OpenProduct -> onOpenProduct(effect.productId)
            }
        },
    ) { state, onEvent ->
        ProductListScreen(state, onEvent)
    }
}
```

The route turns effects into app actions, like pushing the next back stack key.

### SavedStateHandle

Inject a `SavedStateHandle` normally when you want one:

```kotlin
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val savedStateHandle: SavedStateHandle,
) : MauzunViewModel<SearchEvent, SearchState, SearchEffect>() {

    @Composable
    override fun present(events: Flow<SearchEvent>): SearchState = TODO()
}
```

`rememberSaveable` does not need it. Use `SavedStateHandle` for application or navigation state
that should be available outside the Molecule composition. Use `rememberSaveable` for state owned
by the composable presenter.

### Assisted injection

Use assisted injection when the ViewModel needs a runtime argument:

```kotlin
@HiltViewModel(assistedFactory = ProductDetailsViewModel.Factory::class)
class ProductDetailsViewModel @AssistedInject constructor(
    @Assisted private val productId: String,
    private val repository: ProductRepository,
) : MauzunViewModel<ProductDetailsEvent, ProductDetailsState, ProductDetailsEffect>() {

    @AssistedFactory
    interface Factory {
        fun create(productId: String): ProductDetailsViewModel
    }

    @Composable
    override fun present(events: Flow<ProductDetailsEvent>): ProductDetailsState = TODO()
}
```

Pass the assisted value at retrieval:

```kotlin
val presenter = hiltMauzunViewModel<ProductDetailsViewModel, ProductDetailsViewModel.Factory>(
    creationCallback = { factory -> factory.create(productId) },
)
```

Assisted parameters are not saved by Hilt or this adapter. Reconstruct values needed after process
death from navigation state or a `SavedStateHandle`, and persist identifiers rather than object
instances.

### Scoping and keys

`hiltMauzunViewModel()` uses `LocalViewModelStoreOwner` by default. Under Navigation 3's
ViewModelStore decorator that owner is the destination, so the ViewModel lives while its entry is
on the back stack and clears when the entry is removed. Pass `viewModelStoreOwner` for a different
scope, and a `key` when several presenters of one class share an owner.

Presenter unit tests do not need Hilt. Construct the ViewModel with fake dependencies and use the
test harness directly.

## Metro

Metro is optional. Extend MetroX's `ViewModelGraph` and contribute a `MetroViewModelFactory`,
as the
[metrox-viewmodel](https://github.com/ZacSweers/metro/blob/main/metrox-viewmodel/README.md) and
[metrox-viewmodel-compose](https://github.com/ZacSweers/metro/blob/main/metrox-viewmodel-compose/README.md)
READMEs show, then provide the factory at the app root. Retrieval throws without it. A complete
setup lives in
[`MetroCheckGraph.kt`](metro-check/src/main/java/io/github/raheelnaz/mauzun/metrocheck/MetroCheckGraph.kt),
compiled with the real Metro plugin:

```kotlin
CompositionLocalProvider(
    LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
) {
    App()
}
```

Contribute the ViewModel, with an explicit `binding<ViewModel>()` because its immediate
supertype is `MauzunViewModel`:

```kotlin
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding<ViewModel>())
class ProductListViewModel(
    private val repository: ProductRepository,
) : MauzunViewModel<ProductListEvent, ProductListState, ProductListEffect>() {

    @Composable
    override fun present(events: Flow<ProductListEvent>): ProductListState = TODO()
}
```

Retrieve it with `metroMauzunViewModel()` instead of `metroViewModel()`:

```kotlin
val presenter = metroMauzunViewModel<ProductListViewModel>()
```

For a runtime argument, use assisted injection and contribute the factory:

```kotlin
@AssistedInject
class ProductDetailsViewModel(
    @Assisted private val productId: String,
    private val repository: ProductRepository,
) : MauzunViewModel<ProductDetailsEvent, ProductDetailsState, ProductDetailsEffect>() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(productId: String): ProductDetailsViewModel
    }

    @Composable
    override fun present(events: Flow<ProductDetailsEvent>): ProductDetailsState = TODO()
}
```

Metro resolves the factory, and the lambda runs with it as the receiver:

```kotlin
val presenter =
    assistedMetroMauzunViewModel<ProductDetailsViewModel, ProductDetailsViewModel.Factory> {
        create(productId)
    }
```

When the generated factory is already injected into the destination builder, there is nothing
left for Metro to resolve. A Navigation 3 entry provider is one common place for this. Hand the
creation to the core retrieval:

```kotlin
val presenter = mauzunViewModel<ProductDetailsViewModel> {
    detailsFactory.create(productId)
}
```

A `ViewModelAssistedFactory` that builds from `CreationExtras` has its own overload,
`assistedMetroMauzunViewModel<VM>()`.

Saved state and presenter lifecycle attach the same as `mauzunViewModel()`. Assisted parameters
are not saved, so reconstruct them from navigation state and pass identifiers rather than
instances.

## Navigation 3

Include the saveable-state and ViewModelStore decorators so each back-stack entry owns its
ViewModel:

```kotlin
entryDecorators = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)
```

`rememberViewModelStoreNavEntryDecorator` ships in its own artifact:

```kotlin
implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0")
```

Retrieve the presenter inside the entry. No explicit key is needed, since each destination has
its own owner. `rememberNavBackStack` restores its keys after process death when every key is a
`@Serializable` `NavKey`, so a restored key's arguments reach the assisted factory again when
the destination is recreated.

Presenter state remains while its destination stays on the back stack. Navigating to another
screen can pause lifecycle-aware Flow collection without resetting `remember` values or restarting
the presenter. Collection resumes when the destination becomes active again. Use
`rememberSaveable` when values must also survive process death. Removing the destination clears
its ViewModel and presenter state.

The library does not define a navigation API. Navigation can stay in the UI or be handled as an
effect, depending on the application.

## Implementation notes

The molecule runs on `Dispatchers.Main`, not `Main.immediate`, to avoid the invalidation problem
tracked in [cashapp/molecule#465](https://github.com/cashapp/molecule/issues/465). It does not use a
Compose UI frame clock.

The saved-state integration uses a private holder ViewModel. Concrete presenters keep their
no-argument base constructor.

The project is pre-1.0. Minor releases may change the public API. See [CHANGELOG.md](CHANGELOG.md)
before upgrading.

Molecule is maintained by [Cash App](https://github.com/cashapp/molecule). Mauzun provides the
Android ViewModel and testing integration around it, and takes its name from the Urdu for
well-balanced, written موزوں.

## License

    MIT License

    Copyright (c) 2026 Raheel Naz
