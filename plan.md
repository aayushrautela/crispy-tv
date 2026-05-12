# Plan: Remove `provider` / `providerId` From Runtime Media Identity

## 1. Objective and root cause

### Objective

Remove the obsolete media identity concept of `provider` and `providerId` from the Crispy rewrite stack now that TMDB is the only supported metadata provider. Runtime media identity should be expressed through the canonical backend identifiers already used by the product:

- `mediaKey` as the primary stable media identity.
- `mediaType` / `type` / normalized catalog type as the media kind.
- `tmdbId` when available as the TMDB-specific numeric identifier.

The implementation should make Android Library, Home, Calendar, watch-history-related runtime card surfaces, contracts, and any relevant server payloads agree on this identity model without requiring redundant provider fields.

### Exact root cause

Android runtime-card parsing currently treats `provider` and `providerId` as required fields on `RuntimeMediaCard`.

Known failing path:

- Android surfaces such as Library, Home, and Calendar call backend runtime-card endpoints.
- The server response represents each media item using `mediaKey` and `mediaType` / type-oriented fields, and may include `tmdbId`.
- The response does **not** include `provider` or `providerId` because these fields are no longer meaningful in a TMDB-only system.
- Android `CrispyBackendClient.RuntimeMediaCard` still declares `provider` and `providerId` as required non-null strings.
- `parseRuntimeMediaCard` rejects payloads where those fields are missing and throws/fails with:

```text
Runtime media card is missing required fields
```

Search does not fail because it uses `BackendMetadataItem`, where `provider` and `providerId` are already optional. Runtime cards are therefore stricter than search results even though they represent the same backend identity direction.

## 2. Scope

### In scope

#### Android client repository: `/home/aayush/Downloads/crispy-rewrite`

Primary scope is Android app/backend parsing and all Android consumers of runtime media identity:

- Remove required `provider` / `providerId` from `RuntimeMediaCard` and parser requirements.
- Remove or deprecate provider fields from other app-level DTOs where they are merely identity duplication and not import-provider connection state.
- Keep `mediaKey` as the canonical identifier throughout Home, Library, Calendar, Watch History, metadata title detail, and runtime action flows.
- Keep `tmdbId` as optional TMDB-specific metadata.
- Update tests and contract fixtures/schemas if runtime card schemas currently require provider fields.

Important distinction: do **not** remove unrelated import-provider/auth connection concepts without a separate product decision. Types such as Trakt/Simkl import provider connection state may still be valid because they are external account sync providers, not metadata identity providers.

#### Contracts: `/home/aayush/Downloads/crispy-rewrite/contracts`

Relevant if contracts or schemas model runtime/media identity fields with `provider` / `providerId`:

- Update `contracts/SPEC.md` to state that metadata identity is canonicalized by `mediaKey`, not by provider/providerId.
- Update schemas and fixtures to remove required provider identity fields where applicable.
- Version/bump contract version only if existing contract behavior or fixture shape changes.

#### Swift ContractRunner: `/home/aayush/Downloads/crispy-rewrite/ios/ContractRunner`

Relevant if Swift contract code parses or asserts provider/providerId for shared contract suites:

- Mirror contract identity changes in Swift parsing and expected-output generation.
- Keep Kotlin and Swift deterministic behavior aligned.

#### Server repository: `/home/aayush/Downloads/crispy server`

Relevant because Android should align to the actual backend API shape. The prior diagnosis indicates the server already omits `provider` / `providerId` from runtime-card responses and uses `mediaKey` / `mediaType`.

Server work should be limited to documentation/schema cleanup unless code still emits or stores provider/providerId as canonical media identity:

- Verify runtime-card response DTOs/OpenAPI/docs use `mediaKey`, `mediaType`, and optional `tmdbId`.
- Remove provider/providerId from server response contracts if they are still documented as required.
- Preserve backward-compatible reads for existing stored data if any old rows/documents contain provider/providerId.

### Out of scope

- Reintroducing multi-provider metadata support.
- Replacing `mediaKey` with raw `tmdbId` everywhere.
- Removing external import/sync provider concepts such as Trakt/Simkl account state unless they are specifically metadata identity fields.
- Large UI redesigns unrelated to identity parsing.
- Production code changes in this planning step.

## 3. Detailed file-by-file implementation plan

> Paths are based on the current repo and prior diagnosis. Validate exact call sites before implementation with targeted search for `RuntimeMediaCard`, `parseRuntimeMediaCard`, `providerId`, `provider`, `mediaKey`, and `tmdbId`.

### 3.1 Android app backend DTOs

#### `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt`

Likely symbols:

- `data class BackendMetadataItem`
- `data class RuntimeMediaCard`
- Other metadata/runtime DTOs around detail, library, calendar, watch state, and action responses.

Planned changes:

1. Update `RuntimeMediaCard`:

   Current problematic model likely includes required fields similar to:

   ```kotlin
   data class RuntimeMediaCard(
       val mediaKey: String,
       val mediaType: String,
       val provider: String,
       val providerId: String,
       ...
   )
   ```

   Target shape:

   ```kotlin
   data class RuntimeMediaCard(
       val mediaKey: String,
       val mediaType: String,
       val tmdbId: Int?,
       ...
   )
   ```

   Remove `provider` and `providerId` from constructor and all required property usage.

2. Keep or add optional `tmdbId: Int?` if runtime-card responses include it and consumers need direct TMDB identity.

3. Review nearby DTOs that are metadata identity models and currently include optional/required `provider` and `providerId`, for example:

   - `BackendMetadataItem` has optional provider fields today. Recommended short-term change: remove Android usage of these fields first, then remove the fields entirely if backend search payload/schema no longer includes them.
   - Detail/review/watch-state response DTOs that expose `provider` / `providerId` should be classified:
     - Metadata identity duplication: remove or make ignored/optional.
     - External import provider state: keep.

4. Do **not** remove `ProviderState`, `ImportProvider`, connection provider fields, or Trakt/Simkl provider concepts unless a later audit confirms they are only metadata identity. Those are different domain concepts.

Expected result:

- `RuntimeMediaCard` no longer encodes obsolete provider identity.
- Runtime endpoint parsing can succeed with server responses containing only `mediaKey`, `mediaType`, and related display fields.

### 3.2 Android app backend parsers

#### `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`

Likely symbols:

- `parseMetadataItems`
- `parseMetadataItem`
- `parseRuntimeMediaCard`
- runtime response parsers that call `parseRuntimeMediaCard`, including home/library/calendar/watch-history card response parsers.

Planned changes:

1. Update `parseRuntimeMediaCard` required-field validation.

   Remove `provider` and `providerId` from the required-field gate. Required fields should be limited to the actual runtime-card minimum, likely:

   - `mediaKey`
   - `mediaType` or canonical type field used by server
   - `title` / display title if currently required by UI
   - artwork fields only when the existing `requireBackdrop` flag demands them

2. Parse optional `tmdbId` robustly:

   - Accept integer JSON values.
   - If current backend sometimes serializes IDs as strings, consider a helper that handles numeric strings safely.
   - Treat missing/blank/unparseable `tmdbId` as `null`, not fatal.

3. Normalize identity fields early:

   - `mediaKey = json.optString("mediaKey").trim()` and fail only if blank.
   - `mediaType = json.optString("mediaType", json.optString("type")).trim()` or use the repo’s existing helper if present.
   - Avoid constructing fallback provider/providerId from `tmdbId`; that preserves the obsolete concept under another name.

4. Update `parseMetadataItem` only if the product decision is to remove provider/providerId from search DTOs too:

   - Because search currently works, this is not the urgent crash fix.
   - Recommended implementation order is to first stop requiring provider fields in runtime cards, then remove optional search DTO fields once all search call sites are confirmed not to need them.

5. Update parser error messages to reflect the new identity contract. Example:

   ```text
   Runtime media card is missing required identity/display fields
   ```

   Avoid mentioning provider/providerId.

Expected result:

- Runtime-card parsing accepts current server payloads.
- Missing provider/providerId no longer causes Library/Home/Calendar failure.

### 3.3 Android search mapping

#### `android/app/src/main/java/com/crispy/tv/search/BackendSearchRepository.kt`

Likely symbol:

- `internal fun CrispyBackendClient.BackendMetadataItem.toCatalogItem(defaultGenre: String? = null): SearchCatalogItem?`

Planned changes:

1. Confirm `toCatalogItem` uses `mediaKey` as the identity and does not require provider/providerId.
2. If `SearchCatalogItem` still has provider/providerId fields, decide whether they are UI/API leftovers:

   - If unused: remove from `SearchCatalogItem` and mapping.
   - If used only for analytics/debug labels: replace with `tmdbId` or omit.

3. Keep search behavior unchanged otherwise. Search already works because provider/providerId are optional in `BackendMetadataItem`; avoid introducing regressions.

Expected result:

- Search remains functional and becomes consistent with runtime-card identity.

### 3.4 Android Home recommendations/runtime card consumers

#### `android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`

Likely symbols:

- `private fun CrispyBackendClient.RuntimeMediaCard.toCatalogItem(): HomeCatalogItem?`
- `private fun CrispyBackendClient.RuntimeMediaCard.normalizedCatalogType(): String`

Planned changes:

1. Remove any references to `RuntimeMediaCard.provider` or `RuntimeMediaCard.providerId`.
2. Ensure `toCatalogItem()` uses:

   - `mediaKey` for `contentId` / stable ID.
   - normalized `mediaType` for catalog type.
   - `tmdbId` only as optional metadata.

3. Ensure `normalizedCatalogType()` does not infer type from provider/providerId. It should use `mediaType`, `type`, or an existing canonical type helper.

4. Confirm fallback behavior for missing artwork remains controlled by `requireBackdrop` in parser and does not depend on provider identity.

Expected result:

- Home recommendation rows render from runtime-card responses without provider/providerId.

### 3.5 Android watch history / library runtime consumers

#### `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`

Likely symbol:

- `private fun CrispyBackendClient.RuntimeMediaCard.toTitleMediaKey(): String`

Planned changes:

1. Confirm `toTitleMediaKey()` returns or derives from `mediaKey`, not provider/providerId.
2. Remove provider/providerId references if present.
3. Preserve existing handling for episode-level media keys if the backend distinguishes:

   - title/show/movie `mediaKey`
   - season/episode media keys
   - related show media key on episode cards

4. Ensure watch-state, continue-watching, and library action calls continue to pass `mediaKey` to backend endpoints.

Expected result:

- Library and watch-history surfaces identify titles by `mediaKey` and no longer require provider fields.

### 3.6 Android metadata/detail surfaces

Potential files to audit:

- `android/app/src/main/java/com/crispy/tv/metadata/BackendEpisodeListProvider.kt`
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendMetadataApi.kt`
- Any detail screen/view model that accepts `contentId`, `mediaKey`, `provider`, `providerId`, or `tmdbId`.

Planned changes:

1. Keep detail and episode APIs keyed by `mediaKey`.
2. Remove any navigation arguments that carry provider/providerId solely for metadata identity.
3. If old deep links or saved state contain provider/providerId, ignore those fields and resolve through `mediaKey` when available.
4. If a legacy path only has `providerId` and no `mediaKey`, provide a transitional fallback only if necessary:

   - For TMDB movie: `tmdb:movie:<id>` or the repo’s canonical mediaKey format if defined in contracts.
   - For TMDB show: `tmdb:tv:<id>` or canonical equivalent.

   This fallback should be explicitly temporary and covered by tests if implemented.

Expected result:

- Detail and episode loading use the same canonical identity as runtime cards.

### 3.7 Android model/domain classes outside backend package

Potential search targets:

- `providerId` in app UI/model classes.
- `provider` in catalog/search/watchlist/rating classes.
- `tmdbId` and `mediaKey` mapping helpers.

Planned changes:

1. Classify every occurrence of provider/providerId into one of three buckets:

   - **Metadata identity**: remove/replace with `mediaKey` and optional `tmdbId`.
   - **External account/sync provider**: keep. Examples may include Trakt, Simkl, import connection provider state, scrobble policy provider.
   - **Contract compatibility/legacy parsing**: make optional and ignored, then remove in a later contract version if public.

2. Remove only metadata identity fields in this workstream.
3. Avoid renaming external-provider concepts to prevent accidental churn.

Expected result:

- The codebase no longer requires provider/providerId to identify TMDB media, while still preserving unrelated provider concepts.

## 4. Contract, schema, fixture, and versioning implications

### Contract direction

Update the source of truth to state:

- Canonical media identity is `mediaKey`.
- `tmdbId` is optional provider-specific metadata, not the primary identifier.
- `provider` / `providerId` are not required for metadata title identity and should not appear in new runtime-card fixtures unless explicitly marked as legacy ignored fields.
- Since only TMDB is supported, clients must not require a provider discriminator to parse runtime media cards.

### Files to inspect/update

#### `contracts/SPEC.md`

Planned edits if provider/providerId are mentioned as metadata identity:

1. Replace language requiring provider/providerId with mediaKey-based identity.
2. Clarify canonical mediaKey format if already defined by suites such as `media_ids` or `id_prefixes`.
3. Add a note that legacy provider/providerId fields, if seen in payloads, are ignored by clients.

#### `contracts/schemas/**/*.json`

Planned edits:

1. Locate schemas for runtime cards, metadata titles, search results, library, calendar, home/recommendations, continue watching, storage, and media IDs.
2. Remove `provider` / `providerId` from `required` arrays where they represent metadata identity.
3. Optionally remove the properties entirely if no longer part of the public payload.
4. Keep schemas for import/sync provider state intact.

#### `contracts/fixtures/**/*.json`

Planned edits:

1. Remove provider/providerId from runtime-card fixtures where they are present only to satisfy Android’s old parser.
2. Add at least one fixture that intentionally omits provider/providerId to prevent regression.
3. Ensure fixtures include `mediaKey`, media type, title/display fields, and optional `tmdbId`.
4. Preserve canonical ordering and deterministic expected output.

### Contract versioning

Versioning depends on what the current contracts say:

- If `provider` / `providerId` are currently required in contract schemas or expected outputs, removing them is a contract shape change. Bump `contract_version` according to `contracts/SPEC.md` rules and update Kotlin and Swift contract runners together.
- If contracts already use `mediaKey` as canonical identity and only Android app runtime parsing is stale, no contract version bump may be needed. Add regression fixtures/tests without bumping only if allowed by existing versioning policy.
- If backward-compatible optional legacy parsing is retained while required output stays the same, document it as compatibility behavior without changing semantic contract version unless fixture/schema shape changes.

## 5. Test updates and exact commands

### Android unit/contract tests to add or update

1. Add parser-level regression coverage for runtime cards without provider/providerId.

   Suggested test intent:

   - Given a runtime-card JSON object with `mediaKey`, `mediaType`, title, artwork as required by the parser mode, and `tmdbId`, but no `provider` or `providerId`.
   - When `parseRuntimeMediaCard` is called.
   - Then parsing succeeds and the resulting `RuntimeMediaCard.mediaKey` and `mediaType` are populated.

2. Add surface-level tests if existing test harnesses are available:

   - Home recommendation payload without provider/providerId maps to `HomeCatalogItem`.
   - Library/watch-history payload without provider/providerId maps to title media key.
   - Calendar payload without provider/providerId maps to expected event/card model.

3. Update any snapshot/golden tests expecting provider/providerId fields.

4. Update contract tests if schemas/fixtures change.

### Swift ContractRunner tests

If contracts change, update matching Swift tests and parsing logic, then run SwiftPM tests.

### Server tests

In `/home/aayush/Downloads/crispy server`, if server schema/docs/DTOs are changed:

- Add or update API response tests to assert runtime-card responses are valid without provider/providerId.
- Confirm no server endpoint still documents these fields as required metadata identity.

Use the server repo’s own agent guide/package scripts if present.

### Exact commands from `AGENTS.md`

Run from `/home/aayush/Downloads/crispy-rewrite` after implementation:

```sh
python3 scripts/validate_contracts.py
gradle :android:contract-tests:test
```

If Swift contract logic or fixtures are touched:

```sh
swift test --package-path ios/ContractRunner
```

For Android app parser/UI related changes:

```sh
gradle :android:app:testDebugUnitTest
```

For core domain changes, if any:

```sh
gradle :android:core-domain:test
```

For compile/lint confidence after app DTO changes:

```sh
gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug
gradle :android:app:lintDebug
gradle :android:tv:lintDebug
```

If contract behavior changes and a targeted contract test is enough during iteration:

```sh
gradle :android:contract-tests:test --tests com.crispy.tv.contracts.PlayerMachineContractTest
```

Replace the class filter with the specific contract test class for the changed suite if not `PlayerMachineContractTest`.

## 6. Migration and backward-compatibility notes

### Existing persisted data

The product should treat `mediaKey` as the durable identifier. Migration behavior should be:

1. Existing rows/documents with `mediaKey` continue to work unchanged.
2. Existing rows/documents with `tmdbId` continue to expose it as optional metadata.
3. Existing rows/documents with `provider` / `providerId` should not break reads, but those fields should no longer be required or used as primary identity.
4. New writes should not include provider/providerId for metadata identity.

### Media key compatibility

Confirm the canonical `mediaKey` format before implementing fallbacks. If contracts define formats in `media_ids` or `id_prefixes`, use those exactly.

Potential compatibility strategy:

- Preferred: require stored/runtime records to have `mediaKey`.
- Transitional fallback only if old local data lacks `mediaKey`:
  - If `provider == "tmdb"`, `providerId` is present, and type is known, derive the canonical TMDB mediaKey.
  - Do not derive if type is ambiguous.
  - Log or surface a non-fatal migration warning if the app has an internal diagnostics path.

### `tmdbId`

`tmdbId` should remain optional because:

- Some payloads may use `mediaKey` without raw TMDB ID.
- Some item types may not have a direct title-level TMDB ID in a specific response context.
- `mediaKey` is still the stable app/backend identity.

Use `tmdbId` for display/debug/API calls only where explicitly needed. Do not use it as a replacement primary key unless the endpoint specifically requires raw TMDB ID.

### API backward compatibility

For a transition period, parsers may tolerate provider/providerId in responses but should ignore them. Server may continue to accept provider/providerId in request bodies only if older clients send them, but new clients should send `mediaKey`.

## 7. Risks and acceptance criteria

### Risks

1. **Conflating metadata provider with import provider**

   Removing every `provider` occurrence blindly could break Trakt/Simkl import connection state or scrobble policy logic. Mitigation: classify occurrences before editing.

2. **Implicit provider/providerId usage in navigation or saved state**

   Detail screens or deep links may still pass provider/providerId. Mitigation: keep backward-compatible optional read paths and prefer `mediaKey`.

3. **Contract drift between Android and Swift**

   If schemas/fixtures change but Swift ContractRunner is not updated, CI parity fails. Mitigation: update Kotlin and Swift contract implementations together and run both contract test suites.

4. **Server/client schema mismatch**

   Android may be fixed while server docs/schemas still claim provider/providerId are required, or vice versa. Mitigation: verify server response DTOs/docs and contract schemas.

5. **Legacy data without mediaKey**

   Very old local data may only have provider/providerId. Mitigation: add a constrained TMDB-only fallback if such data exists; otherwise document that `mediaKey` is required.

6. **Overusing `tmdbId` as primary identity**

   Replacing provider/providerId with `tmdbId` everywhere could lose type information and collide between movie/show IDs. Mitigation: primary identity remains `mediaKey`; `tmdbId` is optional metadata.

### Acceptance criteria

1. Android runtime-card parser accepts payloads that include `mediaKey` and media type but omit `provider` and `providerId`.
2. Library, Home, and Calendar no longer fail with `Runtime media card is missing required fields` for current server responses.
3. Search behavior remains unchanged or improves, with no new requirement for provider/providerId.
4. New Android code paths use `mediaKey` as canonical identity and optional `tmdbId` only where appropriate.
5. Metadata identity DTOs no longer require provider/providerId.
6. External account provider concepts such as Trakt/Simkl import state remain intact.
7. Contracts/schemas/fixtures, Kotlin contract tests, and Swift ContractRunner are updated together if contract shape changes.
8. Required validation/test commands pass:

   ```sh
   python3 scripts/validate_contracts.py
   gradle :android:contract-tests:test
   gradle :android:app:testDebugUnitTest
   ```

   Plus, if Swift contracts are touched:

   ```sh
   swift test --package-path ios/ContractRunner
   ```

9. Compile/lint confidence commands pass before release:

   ```sh
   gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug
   gradle :android:app:lintDebug
   gradle :android:tv:lintDebug
   ```

## 8. Recommended order of implementation

### Phase 1: Confirm current API and classify provider usages

1. Inspect Android `CrispyBackendClient.kt` and `CrispyBackendParsers.kt` around runtime-card DTOs and parsers.
2. Inspect Android consumers of `RuntimeMediaCard`:
   - `RecommendationCatalogService.kt`
   - `BackendWatchHistoryService.kt`
   - library/calendar services or repositories discovered by search
3. Classify `provider` / `providerId` occurrences into:
   - metadata identity to remove
   - external provider/import/sync to keep
   - legacy compatibility to tolerate but ignore
4. Inspect server runtime-card DTOs/docs in `/home/aayush/Downloads/crispy server` to confirm current payload shape.

### Phase 2: Fix Android runtime-card parsing and DTOs

1. Remove `provider` / `providerId` from `RuntimeMediaCard`.
2. Update `parseRuntimeMediaCard` to require only `mediaKey`, media type, and existing display/artwork requirements.
3. Parse `tmdbId` optionally.
4. Update all compile errors from removed properties by replacing identity usage with `mediaKey` and type usage with normalized media type.
5. Add parser regression tests for cards without provider/providerId.

This phase should resolve the observed Library/Home/Calendar crash.

### Phase 3: Clean Android metadata identity leftovers

1. Remove or ignore provider/providerId in `BackendMetadataItem` and search mapping if no longer needed.
2. Update navigation/detail/watchlist/rating models to pass `mediaKey` only.
3. Keep compatibility for old saved state where necessary.
4. Add tests for search/detail/watch-state identity behavior if existing harnesses make this practical.

### Phase 4: Update contracts and Swift if required

1. Update `contracts/SPEC.md` to document mediaKey-first identity.
2. Update schemas to stop requiring provider/providerId for metadata identity.
3. Update fixtures to omit provider/providerId in at least one runtime-card case.
4. Bump `contract_version` if fixture/schema semantics require it.
5. Update Kotlin contract tests and Swift ContractRunner to match.
6. Run contract validation and both contract test suites.

### Phase 5: Server cleanup if required

1. In `/home/aayush/Downloads/crispy server`, remove provider/providerId from runtime-card API docs/schema if still present.
2. Ensure backend response serializers do not emit provider/providerId for new runtime-card payloads.
3. Keep server request/read compatibility for old clients/data where cheap and safe.
4. Run the server repo’s tests and schema validation commands.

### Phase 6: Full validation

Run from `/home/aayush/Downloads/crispy-rewrite`:

```sh
python3 scripts/validate_contracts.py
gradle :android:contract-tests:test
gradle :android:app:testDebugUnitTest
```

If Swift was touched:

```sh
swift test --package-path ios/ContractRunner
```

Before merging/release:

```sh
gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug
gradle :android:app:lintDebug
gradle :android:tv:lintDebug
```

## 9. Implementation notes

- Prefer removing the obsolete fields from strongly typed DTOs over keeping nullable placeholders indefinitely. Strong types should reflect the new product model.
- During the migration window, parsers may tolerate extra provider/providerId JSON fields but should not require or propagate them.
- Do not construct fake values such as `provider = "tmdb"` and `providerId = tmdbId.toString()` just to satisfy old models. That hides the migration and keeps obsolete identity alive.
- Keep the language precise in code review: `provider` can still mean external sync/import provider, but it should no longer mean metadata identity provider.
- Use compile errors after removing `RuntimeMediaCard.provider` / `providerId` as a guide to find remaining stale runtime-card call sites.
