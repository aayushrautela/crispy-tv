# Crispy Client Architecture

## Status
This document defines the target architecture for the Android and iOS clients.

The core decision is simple:

- The Crispy backend API stores and serves the canonical user state.
- Local storage exists only as a cache, latency optimization, and offline fallback.
- The clients must not talk to Trakt or Simkl directly, including auth, token storage, SDK usage, or provider-specific mutation logic.
- Provider integration, normalization, sync, and conflict resolution belong to the backend.
- The backend may sync to Trakt or Simkl immediately, asynchronously, or not at all depending on product policy. That does not change the ownership model: the Crispy backend remains the source of truth.

## Why This Exists
The current app mixes several responsibilities in the client:

- remote canonical watch state
- provider-specific state
- local watch history
- local playback progress
- provider auth state
- provider-specific branching in UI and service code

That creates split-brain behavior. Different screens can render different answers for the same user state depending on which path they call.

This document defines a backend-first architecture that removes that ambiguity.

## Architecture Principles
- Backend-first: all user media state comes from the Crispy API.
- Single writer: the backend is the only authority for watched, watchlist, rating, continue watching, provider library, and provider auth state.
- Cache, not authority: local data may be shown when remote data is unavailable, but it never becomes an independent truth.
- Pending intent, not authority: optimistic client state is allowed as a temporary UX overlay, but it never becomes a second source of truth.
- Explicit freshness: every cached read must know whether it is fresh, stale, or remote-backed.
- No provider logic in clients: the app may render provider labels returned by backend data, but it must not contain Trakt or Simkl business logic.
- Thin viewmodels: viewmodels coordinate UI state only. They do not assemble backend calls, cache policies, and reconciliation logic themselves.
- Shared domain rules: deterministic business rules stay in `android/core-domain` and `ios/ContractRunner` when they are local and contract-driven.
- Profile-scoped storage: all cached user data is scoped by the active Crispy profile.
- Durable intent: offline-capable writes must survive process death, app restarts, and transient network failure.

## Source Of Truth Model

| Data | Source of truth | Local role |
| --- | --- | --- |
| Session and active profile | Supabase session + Crispy backend | cached credentials/session metadata |
| Home, search, discover, details | Crispy backend | response cache for latency |
| Watched state | Crispy backend | cached snapshot |
| Watchlist | Crispy backend | cached snapshot |
| Ratings | Crispy backend | cached snapshot |
| Continue watching | Crispy backend | cached snapshot and temporary local playback resume |
| Provider library | Crispy backend | cached snapshot |
| Provider connection state | Crispy backend | cached snapshot |
| Playback position during active playback | local device first, then flushed to backend | transient write buffer + cached resume position |
| App settings | local device | source of truth |

The important rule is that the server stores the canonical history and user-media state. If the backend chooses to mirror some of that state into Trakt or Simkl, that is a backend side effect, not a second client-facing truth.

## What Stays Local
The following data is allowed to live locally as primary data because it is device-specific or latency-sensitive:

- playback position during an active session
- pending mutations waiting for network
- image and metadata caches
- app settings and feature flags cached from backend
- temporary offline snapshots of backend-owned data

Important rule:

- local playback progress is not a parallel watch-history system
- local watch data must eventually reconcile into backend-owned watch state

## Consistency And UX Model
To make the app feel fast without introducing split-brain behavior, every backend-owned feature should work with three layers of state:

- canonical snapshot: the latest authoritative backend response
- cached snapshot: the last stored canonical snapshot with freshness metadata
- pending intent overlay: local optimistic state for writes that have not been confirmed yet

Rules:

- The UI may render the pending intent overlay on top of a canonical or cached snapshot so taps feel immediate.
- The pending intent overlay must be profile-scoped and durable when the feature supports offline writes.
- The pending intent overlay must be discarded or reconciled as soon as the backend returns the authoritative snapshot.
- The pending intent overlay must never be read as an independent truth by other features.
- If cached content exists, refreshes must not blank the screen.
- If a mutation fails permanently, revert only the affected field and present a retry affordance.

## What Must Be Removed From Clients
- direct Trakt API usage
- direct Simkl API usage
- provider-specific auth exchange in the app except for launching a backend-managed URL or deep link
- Trakt or Simkl token storage on device
- provider SDKs or API clients in user-state flows
- provider-specific sync rules in the app
- provider-specific retry and reconciliation rules in the app
- nullable `source` semantics where `null` means multiple different things
- duplicated source enums with different meanings across layers
- screen-level logic that decides whether local or provider or canonical backend data should win

The client can still show provider-origin metadata when the backend returns it. It must not own provider integration behavior.

## Recommended Pattern
Use a backend-first repository pattern with local caches and explicit fetch policies.

Each feature reads through a repository that owns:

- remote datasource
- local cache datasource
- mapping to domain models
- cache freshness policy
- optimistic update rules if needed

Each viewmodel depends on use cases, not raw services.

### Layers
1. Presentation
2. Application
3. Domain
4. Data
5. Infrastructure

### Presentation
- Compose screens and SwiftUI/UIKit screens
- ViewModels / observable state objects
- No direct backend client calls
- No direct cache reads
- No provider-specific branching

### Application
- Use cases that express app actions and reads
- Examples: `LoadHome`, `LoadDetails`, `LoadUserMediaState`, `LoadContinueWatching`, `UpdateWatchStatus`, `SavePlaybackProgress`
- Combines repositories when a screen needs multiple backend-backed resources

### Domain
- Shared app models with stable semantics
- Contract-driven logic that must behave the same on Android and iOS
- No HTTP, database, Android, or iOS framework code

### Data
- Repositories
- Remote datasources for backend APIs
- Local datasources for caches
- DTO to domain mappers
- Freshness and invalidation rules

### Infrastructure
- HTTP clients
- Supabase session plumbing
- disk caches
- database
- serialization
- logging

## Dependency Rule
Allowed dependencies go inward only.

- Presentation depends on Application and Domain.
- Application depends on Domain and Data abstractions.
- Data depends on Domain and Infrastructure.
- Domain depends on nothing platform-specific.

The backend client and cache implementations must live at the data/infrastructure edge, not in viewmodels.

## Composition Root
Use constructor injection with one composition root per app.

Android:

- Replace ad hoc global service access with a single app graph.
- `PlaybackDependencies` should shrink into a composition root or be replaced by one.
- ViewModel factories receive use cases or repositories, not raw backend clients plus several helper services.

iOS:

- Mirror the same dependency graph in an `AppContainer`.
- Swift views and view models should receive repositories/use cases, not create networking objects directly.

No DI framework is required. A simple composition root is enough.

## Core Repositories
These are the client-facing repository boundaries the app should converge on.

### `SessionRepository`
Responsibilities:

- current signed-in session
- active profile
- session refresh
- logout handling

### `CatalogRepository`
Responsibilities:

- home sections
- search
- discover
- title details
- person details
- calendar data

Behavior:

- cache-first for warm UI when appropriate
- remote refresh in background
- no provider logic

### `UserMediaRepository`
Responsibilities:

- watched state
- watchlist state
- ratings
- continue watching

Behavior:

- reads from local cache, revalidates from backend
- writes always target backend first or queue a backend mutation
- updates cache from backend responses
- does not call Trakt or Simkl directly

### `LibraryRepository`
Responsibilities:

- backend-normalized provider library data
- foldering, pagination, and library cache policy

Behavior:

- reads from local cache, revalidates from backend
- exposes normalized library models rather than provider-specific service behavior

### `ProviderConnectionRepository`
Responsibilities:

- provider connection status as backend-owned state
- launching backend-managed connect URLs when required
- disconnect requests

Behavior:

- does not store provider tokens on device
- does not talk to provider APIs directly

### `PlaybackRepository`
Responsibilities:

- local playback progress
- flush progress events to backend
- resume position for active playback
- clear local progress when backend confirms item is completed or dismissed

Behavior:

- local-first for hot playback writes
- backend sync in background
- exposes a resolved resume state that prefers backend-backed snapshot when available

### `SettingsRepository`
Responsibilities:

- device-local settings
- feature flags cached from backend if needed

## Target Use Cases
These use cases should become the API that viewmodels consume.

```kotlin
interface LoadUserMediaState {
    suspend operator fun invoke(id: MediaId): Resource<UserMediaState>
}

interface LoadContinueWatching {
    suspend operator fun invoke(policy: FetchPolicy): Resource<List<ContinueWatchingItem>>
}

interface LoadProviderLibrary {
    suspend operator fun invoke(policy: FetchPolicy): Resource<List<LibraryItem>>
}

interface LoadProviderConnections {
    suspend operator fun invoke(policy: FetchPolicy): Resource<ProviderConnectionState>
}

interface UpdateWatchStatus {
    suspend operator fun invoke(command: UpdateWatchStatusCommand): MutationResult<UserMediaState>
}

interface SavePlaybackProgress {
    suspend operator fun invoke(command: PlaybackProgressCommand)
}
```

The important part is not the exact names. The important part is that viewmodels stop depending on large multi-purpose services.

## Fetch And Cache Policy
Every repository read should use an explicit fetch policy.

Recommended policies:

- `RemoteOnly`
- `CacheOnly`
- `CacheThenRemote`
- `RemoteWithCacheFallback`

Recommended result wrapper:

```kotlin
data class Resource<T>(
    val data: T?,
    val source: DataSource,
    val freshness: Freshness,
    val errorMessage: String? = null,
)

enum class DataSource {
    REMOTE,
    CACHE,
}

enum class Freshness {
    FRESH,
    STALE,
}
```

That allows the UI to say:

- showing latest data
- showing cached data
- showing cached data while retrying

without inventing parallel state systems.

`Resource<T>` is the repository-facing contract. Presentation code should project it into a screen state that can distinguish initial load, cached content while refreshing, empty, and blocking error.

```kotlin
sealed interface ScreenState<out T> {
    data object InitialLoading : ScreenState<Nothing>

    data class Content<T>(
        val data: T,
        val freshness: Freshness,
        val isRefreshing: Boolean,
        val issue: NonBlockingIssue? = null,
    ) : ScreenState<T>

    data class Empty(
        val isRefreshing: Boolean,
        val issue: NonBlockingIssue? = null,
    ) : ScreenState<Nothing>

    data class BlockingError(
        val message: String,
        val canRetry: Boolean,
    ) : ScreenState<Nothing>
}
```

Presentation rules:

- If cached content exists, keep rendering it during refresh.
- Use full-screen errors only when there is no renderable content.
- Prefer subtle inline stale or retry messaging over disruptive global error states.

## Mutation Model
Backend-owned writes must be explicit, idempotent, and reconcilable.

Required behavior:

- Every mutation carries a client-generated idempotency key such as `clientMutationId`.
- Every mutation is scoped to the active profile.
- Every mutation records `submittedAt` and may include a `baseRevision` when conflict detection matters.
- The backend response returns the updated canonical snapshot when possible, not only an acknowledgment.
- If downstream provider sync is asynchronous, the backend may also return normalized sync metadata, but the client must not infer truth from provider-specific booleans.

Example shape:

```kotlin
data class PendingMutation(
    val clientMutationId: String,
    val profileId: String,
    val entityKey: String,
    val kind: MutationKind,
    val submittedAtEpochMs: Long,
    val baseRevision: String? = null,
    val retryCount: Int = 0,
)
```

## Offline And Retry Behavior
Good UX requires the app to preserve user intent even when connectivity is poor.

Rules:

- Offline-capable mutations are stored in a profile-scoped on-device database.
- The mutation queue survives process death and app restarts.
- The queue is replayed automatically when connectivity returns.
- Mutations move through explicit states such as `PENDING`, `IN_FLIGHT`, `RETRY_SCHEDULED`, `FAILED_REQUIRES_ACTION`, and `CONFIRMED`.
- Watchlist, watched, and rating changes may be optimistic immediately.
- Provider connect or disconnect should not pretend to succeed before the backend confirms it.
- Playback progress is local-first and aggressively buffered, then flushed to the backend.
- Transient failures should keep optimistic state visible and retry silently first.
- Permanent failures should revert only the affected field and expose a targeted retry action.

## Caching Rules
- Cache backend responses by profile.
- Invalidate user caches on profile switch or logout.
- Store timestamps with every cached payload.
- Use stale-while-revalidate for home, details, library, and continue watching.
- Use memory cache for the current screen and disk cache for warm startup.
- Do not synthesize provider-specific state locally.
- Do not store independent local watch history once backend-backed watch state exists.

Recommended freshness targets:

- home, discover, and details metadata: `CacheThenRemote`, fresh for minutes, stale usable for hours or days
- user media state: `RemoteWithCacheFallback`, fresh for seconds, stale usable briefly while refresh happens
- continue watching: backend snapshot fresh for seconds, with local playback overlay allowed during active sessions
- provider connection state: short freshness window and force refresh on settings entry when needed
- feature flags: cached at startup with a background refresh

## Conflict Resolution
The backend owns conflict resolution for backend-backed state. The client should send enough context for the backend to resolve safely, but it should not reimplement server conflict policy.

Recommended backend rules:

- watchlist: latest successful user mutation wins
- ratings: latest successful rating wins
- watched state: explicit unwatched should beat an older queued watched mutation from a stale revision
- playback progress: highest valid progress timestamp or completion event wins within the same playback identity
- continue watching: derived by backend from canonical state, never authored independently by screens
- dismissing or removing a continue-watching item should beat older ordinary progress pings

If a mutation conflicts with newer server data, the backend should either merge deterministically or reject with a typed conflict plus the latest canonical snapshot.

## Playback And Continue Watching
Playback is the one area where local-first writes are justified.

Rules:

- During playback, progress writes go to local storage immediately.
- A background worker batches and sends progress events to the backend.
- The backend produces canonical continue watching and watched state.
- The client may use local progress as a temporary fallback only when backend state is unavailable.
- When backend state arrives, it replaces the fallback snapshot.
- Completion thresholds and reconciliation rules should be centralized in one place, not spread across screens.

This avoids the current problem where local completion and backend completion can both mark items watched in different paths.

## Provider Policy
Providers are backend connectors, not client architecture concepts.

Client rules:

- The app does not authenticate with Trakt or Simkl directly.
- The app does not call provider APIs directly.
- The app does not store provider access tokens or refresh tokens.
- The app does not contain provider-specific mutation flows.
- The app does not choose between Trakt and Simkl as separate data sources for truth.
- The app may launch a backend-managed provider connect URL or deep link, but the backend owns the OAuth exchange and resulting credentials.
- If provider attribution is useful for UI, the backend returns normalized fields like `providerLabel`, `providerStatus`, or `origin`.

Backend rules:

- The Crispy backend stores canonical watched history, watchlist, ratings, continue watching, provider connection state, and other user-media state.
- If the product wants Trakt or Simkl sync, the backend performs that work after or alongside the canonical Crispy write.
- Provider outages or delayed sync must not change client data ownership. The Crispy backend remains authoritative even if downstream sync is temporarily unhealthy.

This means types like `WatchProvider` should become passive display metadata or disappear from most client-facing APIs.

## Shared Contracts
`contracts/SPEC.md` remains important, but its role is specific.

- It is the source of truth for deterministic local rules.
- It is not the source of truth for user account state.
- If the backend owns user-media state, the client should consume normalized backend responses and apply only presentation-safe deterministic rules locally.

Examples of logic that can stay contract-driven:

- ranking rules
- media id normalization
- stream selection heuristics
- player state machines

Examples of logic that should move to backend-owned contracts or API responses:

- watched state truth
- provider library truth
- continue watching truth
- provider auth truth

## Recommended Package Direction
The exact folder names can vary, but the dependency shape should look like this.

```text
android/app/src/main/java/com/crispy/tv/
  app/
    AppGraph.kt
  feature/
    home/
    details/
    library/
    search/
    settings/
    player/
  domain/
    model/
    usecase/
    repository/
  data/
    remote/
      backend/
    local/
      cache/
      database/
      prefs/
    repository/
    mapper/
  infra/
    network/
    auth/
    logging/
```

Keep `android/core-domain` for platform-independent rules that are shared with Swift contract runners.

In this shape, `domain/repository` defines interfaces and `data/repository` contains their implementations.

## What To Refactor First In This Repo
1. Break up `BackendWatchHistoryService`.
2. Replace `WatchProvider?` and nullable-source semantics with explicit domain use cases.
3. Move backend and cache orchestration out of viewmodels like `DetailsViewModel` and `PlayerSessionViewModel`.
4. Add a durable profile-scoped mutation queue for offline-capable backend writes.
5. Collapse duplicate source enums into one domain model, with backend DTO mapping at the edge.
6. Replace local watch-history truth with backend-backed `UserMediaRepository` plus a dedicated `PlaybackRepository` for transient progress.
7. Split backend-normalized library and provider connection concerns out of the watch-history service shape.
8. Remove client-owned provider branching from library and continue-watching flows.
9. Reduce `PlaybackDependencies` to a composition root instead of a global mutable service locator.

## Migration Plan
### Phase 1: Define stable repository interfaces
- Introduce `SessionRepository`, `CatalogRepository`, `UserMediaRepository`, `LibraryRepository`, `ProviderConnectionRepository`, and `PlaybackRepository`.
- Keep existing implementations behind those interfaces.
- Stop new code from depending on `BackendWatchHistoryService` directly.

### Phase 2: Move screen orchestration into use cases
- Home, Details, Library, and Player screens should depend on use cases.
- Viewmodels stop calling backend clients and mixed-purpose services directly.

### Phase 3: Introduce durable mutation handling
- Add a profile-scoped on-device mutation queue.
- Make backend-owned mutations idempotent and snapshot-returning.
- Render optimistic state as a pending-intent overlay rather than a second source of truth.

### Phase 4: Separate playback progress from user-media truth
- Keep local playback progress in a dedicated repository.
- Remove local watch history as a competing state model.
- Make continue watching and watched state backend-owned.

### Phase 5: Remove direct provider logic from clients
- Delete client-side Trakt and Simkl flows.
- Replace provider-specific branching with backend-normalized APIs.
- Keep only display metadata if the product still wants provider attribution.

### Phase 6: Harden caches and screen-state behavior
- Move critical cached data to a proper on-device database.
- Add profile scoping, timestamps, invalidation, and stale markers.
- Standardize screen states so cached content remains visible during refresh and transient failures.

### Phase 7: Align Android and iOS
- Mirror repository interfaces and domain models.
- Keep deterministic contract-driven logic shared in behavior, even if implementations are platform-native.

## Guardrails
- No new viewmodel may call `CrispyBackendClient` directly.
- No new viewmodel may read SharedPreferences or local stores directly.
- No new client code may add direct provider integrations.
- No new client code may store Trakt or Simkl credentials.
- No feature API may use `null` to mean both canonical and auto-selected source.
- Every backend-backed cache must record profile scope and freshness.
- Every backend-owned mutation must define optimistic behavior, retry behavior, and reconciliation behavior.
- Every offline-capable mutation must be durable across restart.

## Definition Of Done For The Target State
The architecture is in the intended state when all of the following are true:

- every user-facing screen reads user state through repositories/use cases
- the backend is authoritative for watched, watchlist, rating, continue watching, provider library, and provider auth state
- local storage is only cache or transient playback state
- optimistic UI exists only as a pending-intent overlay that reconciles back to backend snapshots
- viewmodels no longer assemble remote plus local plus provider reconciliation rules themselves
- Trakt and Simkl are implementation details of the backend, not client architecture concepts
- the clients never call provider APIs or store provider credentials
- Android and iOS follow the same data ownership rules
