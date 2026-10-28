# Navidrome Integration Plan

## Status and delivery strategy

- **Status:** Phase 0 and Phase 1 are merged (PRs #1, #2). Phase 2 is merged (PRs #3, #4). Phase 3 is in review (PR #5).
- **Goal:** Add native Navidrome support to BitChord without replacing its existing playback engine.
- **Delivery:** Small, reviewable pull requests, starting with authenticated search and playback.
- **Principle:** Navidrome is a first-class source, not a custom addon. Preserve existing source behavior and avoid unrelated refactors.

## 1. Goals

1. Let users add, test, edit, enable/disable, and remove one or more Navidrome servers.
2. Search a server's collection and map results to BitChord's existing song model.
3. Play tracks through the existing source-resolution and Media3 playback path.
4. Keep server and track identities isolated, including when IDs collide across servers.
5. Store credentials securely and prevent secrets leaking into logs, exports, or persistent track URLs.
6. Add deterministic automated tests for API behavior, mapping, errors, and source isolation.
7. Add artist/album/library browsing as a separate follow-up capability.

### Non-goals for initial support

- Replacing the audio engine or redesigning the shared `Song` model without demonstrated need.
- Treating Navidrome as a generic addon or changing the addon protocol.
- Shipping playlists, favorites, ratings, scrobbling, offline playback, or transcoding controls in the first PR.
- Claiming lossless or bit-perfect playback solely from a source capability flag.
- Requiring server-side modifications.
- Supporting the TV, Wear OS, or web clients. Scope is the phone app (`app`) only. Other modules must still compile if they consume shared code touched by this work (see Phase 0), but they get no Navidrome UI or behavior.
- Custom CA trust, self-signed certificate handling, or certificate pinning in the first milestone (revisit in Phase 3).
- Routing Navidrome traffic through the `backend` module; the phone app talks to the server directly, which is required for LAN servers.

## 2. Existing architecture and integration points

The targeted audit identified these areas; re-check them on the current target branch before coding, because implementation details may change.

- `app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt` — source types, labels, priorities, capability flags, and exhaustive `when` expressions.
- `app/src/main/java/com/music/bitchord/data/sources/MusicSource.kt` — source health, search, and stream contract.
- `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt` — source construction, configuration, and source-specific track identity.
- `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt` — resolution of BitChord source track URIs.
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` — existing Media3 playback and HTTP data-source path.
- `app/src/main/java/com/music/bitchord/auth/AuthStore.kt` and `EncryptedPrefs.kt` — existing credential-storage infrastructure; verify per-source keying, cleanup, migration, and backup behavior.
- `app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt` — source-management UI; add a dedicated Navidrome editor rather than reusing the addon editor.
- `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt` — shared song model; map metadata into existing fields first.

Scope is the phone app only. `shared` is touched only as needed for `SourceKind`/models and must keep the other modules compiling.

The project already uses Ktor, kotlinx.serialization, Media3/ExoPlayer, and an OkHttp-backed playback data source. Reuse existing dependencies and conventions unless testing demonstrates a gap.

## 3. Proposed architecture

### Native source

Add `SourceKind.NAVIDROME` and a `NavidromeSource` implementing `MusicSource`. Keep API calls and Subsonic response parsing in a small client, separate from Compose UI state.

Suggested files (adapt to current project conventions):

- `app/src/main/java/com/music/bitchord/data/sources/NavidromeSource.kt`
- `app/src/main/java/com/music/bitchord/data/sources/navidrome/NavidromeClient.kt`
- `app/src/main/java/com/music/bitchord/data/sources/navidrome/NavidromeModels.kt`
- `app/src/test/.../NavidromeClientTest.kt`
- `app/src/test/.../NavidromeSourceTest.kt`

The client should centralize URL construction, authentication, response parsing, timeouts, and error translation. The source class should translate domain requests and results.

### Source identity

- Keep a stable BitChord source configuration ID for each server.
- Treat server track IDs as opaque strings.
- Retain the registry's source-scoped identity mechanism.
- A track from server A must always resolve against server A, even if server B has the same track ID.
- Disabling, editing, or deleting a source must not silently redirect queued tracks to another source.
- Add regression tests for duplicate IDs, URI construction, and resolution.

### Optional library browsing

The current `MusicSource` contract is oriented around health, search, and streaming. Do not force all existing sources to implement Navidrome-specific library operations. For the browsing phase, introduce a separate optional interface, tentatively `BrowsableMusicLibrary`, for:

- Listing artists and albums, with pagination where supported.
- Listing tracks for an album.
- Retrieving artwork references.
- Representing paging, empty results, loading, and failures.

Other source implementations should remain compatible without stub methods for features they do not support.

## 4. Authentication, networking, and security

### Server configuration

The editor should accept a base URL, username, password, and optional display label.

- Normalize URLs consistently and support reverse-proxy path prefixes; do not assume the server is hosted at `/`.
- Accept HTTPS by default. Permit HTTP only for explicitly configured trusted local environments when needed; never silently downgrade HTTPS.
- Android blocks cleartext traffic by default. Decision (Phase 0): enable cleartext app-wide and enforce HTTPS by default in app code, with a per-server HTTP opt-in and visible warning, so LAN servers work.
- Self-signed certificates: TLS failures surface as a distinct, actionable error. Custom CA or pinning support is a non-goal for the first milestone.
- Reject malformed URLs and unsupported schemes with actionable, non-secret messages.
- Never put a password in the base URL.
- Support multiple independent server configurations.

### Subsonic-compatible authentication

Use the Navidrome-supported Subsonic-compatible API. **Primary mode: token + salt (`u`, `t`, `s`)**, with `f=json` and a fixed client name (`c`) and API version (`v`). Confirm against current docs whether Navidrome's newer API-key authentication is worth supporting as an alternative; if so, make it an explicit per-server choice, not a fallback. Note that token auth means the server holds the plaintext password, so the app should recommend a dedicated account. For token authentication, calculate the request token according to the API specification using the password and a fresh cryptographically random salt for each request.

- Keep password, token, and salt handling inside the API/auth layer.
- Never log credentials, tokens, salts, or URLs containing authentication parameters.
- Redact sensitive query parameters from errors and diagnostics.
- Use HTTPS over untrusted networks.
- Never persist reusable authentication tokens in media URLs or track identities.
- **Stream auth approach (decide in Phase 0).** Subsonic has no header-based auth, so `stream` and cover-art requests carry credentials as query parameters. Persistent track URIs hold only the source ID and opaque track ID. Credentials are added at resolve time (or by an OkHttp interceptor in the playback data source) with a fresh salt per request, so Media3 retries and seeks never reuse a stale or persisted token. Resolved URLs must never be logged or stored in queue/history state.
- Confirm supported API versions and response formats during connection testing.
- If legacy authentication is necessary, document the security trade-off and do not silently fall back to a weaker mode.

### Credential storage

Store credentials separately from ordinary source metadata, keyed by the stable source configuration ID. Reuse `AuthStore` / `EncryptedPrefs` only after confirming their behavior for multiple server credentials, deletion, key invalidation, and exports.

- Never store passwords as plaintext in `SourceConfig` or ordinary preferences.
- Remove credentials and related secret material when a server is deleted.
- Exclude credentials from exports, debug dumps, logs, and crash diagnostics.
- Handle encryption-key invalidation safely and provide a re-authentication path.
- Avoid logging raw server errors if they might echo authentication data.

### HTTP and error handling

Use bounded timeouts, honor coroutine cancellation, and avoid unbounded retries. Retry only safe operations and only for transient failures. Parse API-level error responses as well as HTTP status codes.

Provide useful error categories for invalid URL, network failure, TLS failure, authentication failure, unsupported API/response, rate limiting, temporary server error, missing track, and malformed data. User-facing errors should explain the next action without secrets or stack traces.

## 5. Implementation phases

### Phase 0 — Verify contracts and test setup

1. Read current source, registry, resolver, playback, auth, UI, and test code.
2. Read repository-specific instructions and identify required CI/build commands.
3. Trace how source track IDs become source URIs and resolve to `SourceStream`.
4. Verify how `SourceStream` URL, headers, MIME type, and audio metadata reach the playback data source, and choose the stream-auth approach described in Section 4.
5. Confirm current HTTP-client, coroutine, serialization, error, and test conventions.
   - Decide the cleartext-HTTP policy (Section 4) and check how `SourceKind` priorities rank Navidrome against existing sources, including fallback behavior when a user's own library has a match.
   - Check whether the app has a settings export or backup and what it includes.
   - Audit other modules (`sharedUi`, `tv`, `wearos`, `web`, `native`) only for compile breakage from adding `SourceKind.NAVIDROME` or changing shared code (for example exhaustive `when` expressions). Do not add Navidrome features there.
6. Establish baseline tests/build results before making changes.

**Acceptance:** Implementation is based on current contracts; existing source behavior is understood; any playback pipeline change is justified by a failing test or a documented compatibility gap.

### Phase 0 findings and decisions

Audit of `main` at `6e93ebbd`. Read-only.

**Baseline:** `./gradlew testDevDebugUnitTest` on `6c2be95f` (`main` plus this document): BUILD SUCCESSFUL, 1,118 tests in 101 classes, 0 failures, 0 errors, 1 skipped (2m 49s, JDK 17, NDK 27.0.12077973, CMake 3.22.1). Lint, static analysis and `assembleDevDebug` were not run. Note `compileSdk` is 37 (`app/build.gradle.kts`), not 36 as `CONTRIBUTING.md` says.

**Decisions**

- **Cleartext HTTP:** allowed for LAN servers. Android's network security config cannot match private IP ranges, so cleartext is enabled app-wide (`usesCleartextTraffic`) and the app enforces the policy: HTTPS by default, HTTP only after an explicit per-server opt-in with a visible warning, never a silent downgrade.
- **Password storage:** a separate encrypted entry keyed by the source config ID, not a field in `SourceConfig`. Delete it when the source is removed. Do not store it in the plain-text fallback of `EncryptedPrefs`; if only the plain fallback is available, ask the user to re-enter the password rather than persisting it there.
- **Delivery:** work on a branch and open a PR rather than pushing to `main`.

**Findings**

1. `SourceKind` is referenced only inside `app`; `shared`, `sharedUi`, `tv` and `wearos` do not use it. Adding `NAVIDROME` requires editing the exhaustive `when` in `SourceRegistry.build()` and the icon `when` in `SourcesScreen.kt`. Other uses are `==` checks.
2. Playback resolves `bitchord://source?s=<configId>&t=<trackId>` at open time via `ResolvingDataSource` and `SourceResolver`. A fresh salt per open fits this path, and persisted URIs never need credentials. `AudioCache` keys source tracks as `<configId>|<trackId>` (plus tag suffixes), never by URL, so salts cannot fragment or leak into the cache. Still to verify: `StreamChoice` caches resolved `SourceStream`s in memory, so a salted URL could go stale on retry.
3. `SourceConfig` is stored whole in `EncryptedPrefs` (`bitchord_sources`). The encrypted file and its plain fallback are excluded from auto-backup (`backup_rules.xml`, `data_extraction_rules.xml`), and `AppSettings.exportPrefs()` reads a different file. `EncryptedPrefs` falls back to a plain file if the Keystore is unusable, which is why passwords get their own entry (see decisions).
4. The manifest has `usesCleartextTraffic="false"` and there is no network security config.
5. `SourceHealth.Rejected` versus `Unreachable` already matches the auth-failure versus connectivity-failure split.
6. `StreamRequest.Capped` maps to `maxBitRate` and `Lossless` to `format=raw`. `canServeLossless` can be true for Navidrome.
7. User-added kinds share `rank = 0`, and `reorderAddons` keys on `kind.isUserAdded`, so a rank-0 Navidrome kind reorders with addons unchanged. `identify(url)` can only produce `ADDON` or `CUSTOM_MODULE` by probing for a manifest or index, so Navidrome needs its own editor and save path. `SourceRegistry.tidied()` only normalizes `ADDON` URLs, so Navidrome needs its own normalization. `PlayerConnection.kt:638` checks for `ADDON` and has not been read yet. Still to decide: `worthPrefetching` for Navidrome.
8. User-facing search has three modes (`SearchSource.YOUTUBE`, `LIBRARY`, `ADDONS`), and `MainViewModel.runAddonSearch` queries only `AddonSource` instances. Navidrome would otherwise only play as a substitute for a YouTube track, so Phase 1 widens `runAddonSearch` to sources with `kind.isUserAdded`.
9. Tests are JVM unit tests under `app/src/test`; `AddonSourceTest.kt` is the closest template. Command: `./gradlew testDevDebugUnitTest`.
10. `CONTRIBUTING.md` says to branch from the active version branch. Only `origin/main` exists (latest tag `v1.8.1`), so this work branches from `main`; confirm with maintainers if a version branch is expected.

### Phase 1 — Native connection, search, and playback

1. Add `NAVIDROME` to `SourceKind` and update all exhaustive branches and source metadata.
2. Add a typed API client with centralized authentication and URL construction.
3. Implement connection testing using the Subsonic-compatible ping endpoint.
4. Implement `NavidromeSource.health()` and map errors to meaningful states.
5. Implement search using `search3` (ID3-based, paginated via `songCount`/`songOffset`), requesting songs only in this phase.
6. Map title, artist, album, duration, track number, and available artwork metadata to existing song fields.
7. Implement stream resolution through the server's stream endpoint, scoped to the configured server.
8. Apply authentication to stream requests using the approach chosen in Phase 0. Modify `PlaybackService` only if a test proves the current path insufficient. Use server defaults for format and bitrate, and verify seeking works (consider `estimateContentLength`).
9. Store credentials securely per source.
10. Add a dedicated add/edit Navidrome flow in the source-management screen, including connection test, enable/disable, and delete.
11. Add deterministic tests for authentication, parsing, mapping, error cases, and source isolation.

**Acceptance criteria**

- A user can add a server and validate its connection.
- Authentication failures are distinguishable from connectivity failures.
- Search returns expected tracks mapped to existing song models.
- A selected track plays through the existing playback engine.
- Authentication is applied correctly without leaking credentials into persistent track identity or logs.
- Identical track IDs on two servers remain isolated.
- Existing source types still compile and pass relevant regression tests.
- Passwords are absent from ordinary source configuration.

**Out of scope:** artist/album browsing, playlists, favorites, ratings, scrobbling, offline mode, and transcoding controls.

**Status: done** (see Phase 1 outcome).

### Phase 1 outcome

Merged in PR #2 (`301595e3` on `main`). Full suite: 1,157 tests, 0 failures, 1 skipped; `assembleDevDebug` builds.

Delivered as planned, with these specifics and deviations:

- **Cleartext:** `usesCleartextTraffic` is `true` app-wide. An address without a scheme is taken as https; `http://` must be typed explicitly and the editor warns while it is in use. This is the implementation of the per-server opt-in.
- **Credentials:** `NavidromeCredentials` is a separate encrypted file (`bitchord_navidrome`), excluded from backups, deleted with the source, and refuses to write to the plain-text fallback. The client is built per call from the stored login, so an edited password applies without rebuilding the source.
- **Search:** the Search tab's Addons mode now includes Navidrome. Artwork is deliberately absent from search rows (see Phase 2).
- **Strings:** new UI strings are translated in every locale file because `LocaleStringsTest` requires it. The translations are machine-written and unreviewed.

**Known gaps carried forward**

- Not verified on a device or against a real Navidrome server; unit tests only.
- Lint and static analysis were not run.
- A missing track is not detected before playback (no `getSong` round trip).
- Source health is re-probed when the URL changes, not when only the password changes.
- Self-signed certificates surface as a TLS error; no custom CA support.

### Phase 2 — Browse artists, albums, tracks, and artwork

1. Add the optional library-browsing interface without expanding the mandatory source contract.
2. Implement artist and album listing with paging or bounded loading.
3. Implement album-track retrieval with stable ordering.
4. Load artwork using supported cover-art endpoints or safely constructed URLs.
5. Ensure artwork requests use the correct server and authorization where required.
6. Scope artwork cache identity by server/source to prevent cross-server collisions.
7. Add artist → album → track navigation, reusing existing UI components where practical.
8. Include loading, empty, disconnected, error, and retry states.

**Status:** Implemented in PRs #3 (browsing interface and API) and #4 (UI and artwork), pending review and device testing.

- Entry point: a "Server libraries" group on the Sources screen lists each enabled Navidrome server. Navigation is three routes on the app's single back stack: library (Artists / Albums), artist, album.
- Artwork: `NavidromeArt(configId, ref, size)` is the image model, not a URL. A Coil fetcher builds the authenticated URL at fetch time and the cache key is `navidrome:<configId>:<ref>:<size>`. Known limitation: covers are cached in memory only, not on disk, so they are re-downloaded after a process restart.
- Search rows still have no artwork.
- Not verified on a device or emulator: scrolling and prefetch behaviour, layouts and the artwork fetcher have only compiled and been unit-tested at the paging and cache-key level.

**Acceptance criteria:** Users can browse artists and albums, open an album, and play its tracks. Large libraries are not loaded all at once. Artwork comes from the correct server. Sources without browsing support behave unchanged.

### Phase 3 — Lifecycle and UX hardening

1. Improve multiple-server labels and connection-state presentation.
2. Test editing a URL or credential without leaving stale secrets or misrouting queued tracks.
3. Verify enable/disable/delete operations update registry state safely.
4. Define behavior for temporarily unreachable servers and any cached display data.
5. Add caching only if consistent with existing app patterns and bounded by clear invalidation rules.
6. Check accessibility, localization, and small-screen layouts.

**Acceptance criteria:** Servers are distinguishable; source lifecycle actions never redirect tracks to another server; connectivity problems are recoverable; existing source-management flows still work.

### Phase 3 outcome

Implemented in PR #5. These are the defined behaviours:

| Situation | Behaviour |
|---|---|
| Server unreachable | Sources row shows Unreachable (a refusal of credentials shows Rejected instead). Search returns nothing for that server and logs the reason. Browsing shows the error with Try Again. A queued track from it fails at playback, because the stream URL is built locally; it is **not** replaced by another source. |
| Server disabled | Off means off: nothing is contacted. Browsing and cover art refuse it. A queued track from it is skipped, and no other source (including a lossless upgrade from another server) answers for it. |
| Address edited | The server is re-keyed under a new config id, keeping its place in the order and its on/off state. Track ids are only meaningful to the server that issued them, so queued tracks from the old id are never sent to the new server by id; they are re-matched by title, artist and duration like any removed source. The old login is deleted. |
| Username, password or label edited | Same id. The stored login is replaced, and the Sources screen re-checks the server (a password change alters no config field, so the login store has its own revision counter). |
| Server deleted | Its login is deleted. Queued tracks are re-matched by metadata, never by id. |
| Duplicate ids on two servers | Each track resolves against its own server (tested through the resolver, not only the source). |

Also: a server with no label is named by host plus any port and path, so two servers on one host are distinguishable. The album header cover scales down on narrow screens and list rows are exposed as buttons.

**No caching was added.** Library lists live in memory while their screen is open and are re-fetched on return. Covers are cached in memory by server and reference (see Phase 2).

**Not done**

- No accessibility audit with TalkBack or large font scales on a device; only the changes above.
- Error text that originates in the client (for example "Wrong username or password") is English-only; the surrounding UI strings are translated.
- Two servers given the same explicit label are not flagged.
- A deleted server's queued tracks are re-matched across other sources, including YouTube, because a deleted config no longer says what kind it was.
- Still unverified on a device or against a real server.

### Phase 4 — Optional future features

Scope each separately with its own design and tests:

- Playlists: list/play first; add mutations only if needed.
- Favorites and ratings: define read/write semantics, permissions, and conflict behavior.
- Scrobbling: avoid duplicate submissions if BitChord already scrobbles.
- Transcoding: expose only capabilities that can be reliably established.
- Offline playback: define download lifecycle, storage limits, authorization, and cleanup.
- Advanced search: filters, paging, and richer result categories.
- Server capability detection: use documented capability/version behavior rather than guessing from URL.

## 6. Test plan

Use the repository's established framework. Prefer a mocked HTTP engine or local test server. Unit tests must not require a live Navidrome server or real credentials.

### API client

- URL normalization, base path prefixes, and query construction.
- Required parameters and response format.
- Fresh random salt and correct token calculation.
- Successful/failed ping and authentication.
- Empty, partial, malformed, and unexpected responses.
- HTTP errors, timeouts, cancellation, and transient server errors.
- Redaction of credentials and auth parameters in logs/errors.
- Non-ASCII metadata and missing optional fields.

### Source and mapping

- Map artist, album, title, duration, track number, artwork reference, and stable ID where available.
- Missing optional metadata does not crash mapping.
- IDs remain opaque strings.
- Source-specific URI creation and resolution.
- Same ID on two servers stays isolated.
- Disabled/deleted sources do not resolve through another source.
- Search limits and empty results are respected.

### Playback integration

- Stream resolution produces the expected server URL with a fresh salt per request, and persisted track URIs contain no credentials.
- Cleartext HTTP is allowed or rejected per the chosen policy; TLS failures map to the distinct error.
- Authentication is provided using the API's required mechanism, not embedded in persistent media identity.
- Existing Media3 data-source handling accepts the stream.
- Queue playback, track switching, and failure recovery work.
- Missing tracks and unavailable servers fail cleanly.

### Browsing phase

- Artist/album pagination and album-track ordering.
- Empty libraries and empty albums.
- Artwork request handling and per-server cache identity.
- Loading, retry, and disconnected states.

### Regression and CI

- Run focused tests first, then the full prescribed test/build suite.
- Build affected Android targets/flavors.
- Run required formatting, static analysis, and lint checks.
- Confirm existing sources compile after enum/interface changes.
- If manual UI verification is waived or not performed, state that honestly.
- Record the exact tested commit SHA and CI results in the PR review/merge decision.

## 7. Suggested PR breakdown

1. **PR 1 — Native Navidrome source, secure credentials, search, and playback.** Keep this focused on a working end-to-end first milestone.
2. **PR 2 — Optional library-browsing interface and Navidrome artist/album/track APIs.**
3. **PR 3 — Artist/album browsing UI and artwork support.**
4. **PR 4 — Multi-server lifecycle, error handling, caching, and accessibility polish.**
5. **Future PRs —** Separate changes for playlists, favorites, ratings, scrobbling, offline support, or other optional features.

Follow current repository contribution rules if they prescribe a different PR breakdown. Do not bundle speculative future features into the first PR.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Track IDs collide across servers or source kinds | Keep source-scoped identity and add collision regression tests. |
| Authentication leaks into logs, URLs, or exports | Centralize auth, redact sensitive values, and test error/logging paths. |
| Reverse-proxy subpaths are mishandled | Normalize base URLs carefully and test path-prefixed deployments. |
| Stream requests require unsupported headers | Verify the `SourceStream` → resolver → Media3 path before changing playback code. |
| Server/API versions vary | Parse defensively and test optional/missing fields against documented behavior. |
| Large libraries cause slow UI or memory pressure | Use pagination and bounded requests. |
| Artwork cache mixes servers | Include source/server identity in cache keys. |
| Encryption key becomes unavailable | Handle invalidation safely and require re-authentication rather than crashing. |
| Core source interface changes break existing providers | Keep browsing as an optional capability. |
| Lossless capability is overstated | Report only quality supported by actual stream/server metadata. |

## 9. Documentation and references

Use official Navidrome documentation as the protocol source of truth:

- [Navidrome Subsonic API documentation](https://github.com/navidrome/website/blob/master/content/en/docs/developers/subsonic-api.md)
- [Navidrome authentication documentation](https://navidrome.org/docs/usage/integration/authentication/)

During implementation, confirm endpoint names, authentication requirements, response formats, and API-version constraints against current documentation. Do not assume every optional Subsonic feature is supported identically by every server version.

## 10. Definition of done

- [ ] User can configure and validate a Navidrome server.
- [ ] Credentials are stored securely and excluded from logs, exports, and persistent track identities.
- [ ] Search results map correctly into existing BitChord song models.
- [ ] A selected result plays through the existing playback engine.
- [ ] Multiple servers remain isolated when track IDs overlap.
- [ ] Errors are useful, safe, and recoverable.
- [ ] Automated tests cover authentication, parsing, mapping, source identity, and stream resolution.
- [ ] Existing sources and playback behavior pass regression tests.
- [ ] Other modules still compile after shared changes (no new features there).
- [ ] Required CI/build checks pass on the exact PR head SHA.
- [ ] Any waived or unperformed manual testing is reported honestly.
- [ ] PR description records scope, tests, limitations, and follow-up work.

**First milestone:** Complete Phase 0 and Phase 1 only. Begin library browsing after authenticated search and playback are stable.
