# molecule-viewmodel

[![build](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml/badge.svg)](https://github.com/Raheelnaz/molecule-viewmodel/actions/workflows/build.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.raheelnaz/molecule-viewmodel)](https://central.sonatype.com/artifact/io.github.raheelnaz/molecule-viewmodel)

Use a [Molecule](https://github.com/cashapp/molecule) presenter as an Android `ViewModel`.

`MoleculeViewModel` runs a composable presenter in `viewModelScope`. The screen reads models from
a `StateFlow`, sends events, and receives one-time effects. The test artifact runs the same
presenter directly on the JVM.

Inside the presenter, use `remember`, `collectAsState`, and Compose effects. Outside it, the screen
sees only the binding contract.

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
val presenter = moleculeViewModel<CounterViewModel>()

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
implementation("io.github.raheelnaz:molecule-viewmodel:0.9.0")
testImplementation("io.github.raheelnaz:molecule-viewmodel-test:0.9.0")
```

Hilt users can add the optional adapter:

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel-hilt:0.9.0")
```

Metro users can add the optional adapter:

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel-metro:0.9.0")
```

The library requires minSdk 23 and Kotlin 2.3 or newer. Apply the Compose compiler plugin to the
module that subclasses `MoleculeViewModel`.

`molecule-viewmodel-api` and `molecule-viewmodel-compose` arrive transitively with the main
artifact. A UI module that receives a `PresenterBinding` can depend on the Compose host alone:

```kotlin
implementation("io.github.raheelnaz:molecule-viewmodel-compose:0.9.0")
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

The ViewModel does not expose `state`, `effects`, or its binding. `moleculeViewModel()` returns a
`PresenterEntry`, and `PresenterHost` reads the entry's binding for you. The entry does not
expose the underlying ViewModel.

At a module boundary, pass only the binding:

```kotlin
// app module
val presenter = moleculeViewModel<ProductViewModel>()
ProductUi(presenter.binding)

// feature UI module
@Composable
fun ProductUi(binding: PresenterBinding<ProductEvent, ProductState, ProductEffect>) {
    PresenterHost(binding, onEffect = ::handleEffect) { state, onEvent ->
        ProductScreen(state, onEvent)
    }
}
```

`ProductUi` can live in a module that depends on `molecule-viewmodel-compose` instead of the
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

`PresenterHost` controls collection in the UI. It does not pause the presenter itself. The molecule
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
`sendEvent`. Calling `viewModel.onEvent` writes to the production queue, which the harness does not
read.

## Saved state

`moleculeViewModel()` lets a presenter use `rememberSaveable`. It installs saved state before it
returns the entry, so the first composition can restore values after process recreation.

```kotlin
val presenter = moleculeViewModel<ProductViewModel>()
```

The entry is the only supported production route to the binding. Saved state therefore attaches
before UI code can start the presenter. A `MoleculeViewModel` subclass cannot read its own produced
state, including from its constructor, `init` block, or `present` function. An injected
`SavedStateHandle` remains available for application state that does not belong in
`rememberSaveable`.

## Hilt

Hilt is optional. Use `hiltMoleculeViewModel()` instead of `hiltViewModel()` to obtain the same
entry while Hilt creates the ViewModel. Constructor injection, assisted injection, scoping, and
`SavedStateHandle` all work the way Hilt users expect.

### Constructor injection

Add `@HiltViewModel` to an ordinary constructor-injected class:

```kotlin
@HiltViewModel
class ProductListViewModel @Inject constructor(
    private val repository: ProductRepository,
) : MoleculeViewModel<ProductListEvent, ProductListState, ProductListEffect>() {

    @Composable
    override fun present(events: Flow<ProductListEvent>): ProductListState = TODO()
}
```

Retrieve and render it from the destination:

```kotlin
@Composable
fun ProductListRoute(onOpenProduct: (String) -> Unit) {
    val presenter = hiltMoleculeViewModel<ProductListViewModel>()

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
) : MoleculeViewModel<SearchEvent, SearchState, SearchEffect>() {

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
) : MoleculeViewModel<ProductDetailsEvent, ProductDetailsState, ProductDetailsEffect>() {

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
val presenter = hiltMoleculeViewModel<ProductDetailsViewModel, ProductDetailsViewModel.Factory>(
    creationCallback = { factory -> factory.create(productId) },
)
```

Assisted parameters are not saved by Hilt or this adapter. Reconstruct values needed after process
death from navigation state or a `SavedStateHandle`, and persist identifiers rather than object
instances.

### Scoping and keys

`hiltMoleculeViewModel()` uses `LocalViewModelStoreOwner` by default. Under Navigation 3's
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
[`MetroCheckGraph.kt`](metro-check/src/main/java/io/github/raheelnaz/molecule/metrocheck/MetroCheckGraph.kt),
compiled with the real Metro plugin:

```kotlin
CompositionLocalProvider(
    LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
) {
    App()
}
```

Contribute the ViewModel, with an explicit `binding<ViewModel>()` because its immediate
supertype is `MoleculeViewModel`:

```kotlin
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding<ViewModel>())
class ProductListViewModel(
    private val repository: ProductRepository,
) : MoleculeViewModel<ProductListEvent, ProductListState, ProductListEffect>() {

    @Composable
    override fun present(events: Flow<ProductListEvent>): ProductListState = TODO()
}
```

Retrieve it with `metroMoleculeViewModel()` instead of `metroViewModel()`:

```kotlin
val presenter = metroMoleculeViewModel<ProductListViewModel>()
```

For a runtime argument, use assisted injection and contribute the factory:

```kotlin
@AssistedInject
class ProductDetailsViewModel(
    @Assisted private val productId: String,
    private val repository: ProductRepository,
) : MoleculeViewModel<ProductDetailsEvent, ProductDetailsState, ProductDetailsEffect>() {

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
    assistedMetroMoleculeViewModel<ProductDetailsViewModel, ProductDetailsViewModel.Factory> {
        create(productId)
    }
```

When the generated factory is already injected into the destination builder, there is nothing
left for Metro to resolve. A Navigation 3 entry provider is one common place for this. Hand the
creation to the core retrieval:

```kotlin
val presenter = moleculeViewModel<ProductDetailsViewModel> {
    detailsFactory.create(productId)
}
```

A `ViewModelAssistedFactory` that builds from `CreationExtras` has its own overload,
`assistedMetroMoleculeViewModel<VM>()`.

Saved state attaches the same as `moleculeViewModel()`. Assisted parameters are not saved, so
reconstruct them from navigation state and pass identifiers rather than instances.

## Navigation 3

Include the saveable-state and ViewModelStore decorators so each back-stack entry owns its
ViewModel:

```kotlin
entryDecorators = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)
```

Retrieve the presenter inside the entry. No explicit key is needed, since each destination has
its own owner. `rememberNavBackStack` restores its keys after process death, so a restored key's
arguments reach the assisted factory again when the destination is recreated.

Presenter state remains while its destination stays on the back stack. Navigating to another
screen and returning does not reset `remember` values. Use `rememberSaveable` when those values
must also survive process death. Removing the destination clears its ViewModel and presenter
state.

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

Molecule is maintained by [Cash App](https://github.com/cashapp/molecule). This project provides
the Android ViewModel and testing integration around it.

## License

    MIT License

    Copyright (c) 2026 Raheel Naz
