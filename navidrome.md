# Navidrome Integration Plan

## Status and delivery strategy

- **Status:** Phase 0 and Phase 1 are merged (PRs #1, #2). Phases 2 and 3 are merged (PRs #3, #4, #5). A pre-playback missing-track check and an opt-in live test followed (PR #6). **Emulator verification (11.1) has since been run, found one real bug (below Lossless, no Navidrome track would play) and fixed it (PR #7); results are under "Live verification". What remains is in section 11: polish, then the upstream decision.**
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
- Supporting the TV, Wear OS, or web clients. Scope is the phone app (`app`) only. The other modules (`tv`, `wearos`, `web`) get no Navidrome UI or behavior and are not built or tested for this work.
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

Scope is the phone app only. `shared` is touched only as needed for `SourceKind`/models. Keeping the other modules compiling is out of scope (see section 10).

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
- A missing track is not detected before playback (no `getSong` round trip). *Resolved in PR #6.*
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
| Track no longer on the server | `stream` first checks the track with `getSong` (skipped when search or browse has already shown it this session) and treats a definitive "not found" as a miss, so the track is skipped cleanly. Without this the server answers a missing id with HTTP 200 and a JSON error body, which the player would try to decode as audio. If the check itself cannot complete (5 s budget), playback is still attempted. |
| Duplicate ids on two servers | Each track resolves against its own server (tested through the resolver, not only the source). |

Also: a server with no label is named by host plus any port and path, so two servers on one host are distinguishable. The album header cover scales down on narrow screens and list rows are exposed as buttons.

**No caching was added.** Library lists live in memory while their screen is open and are re-fetched on return. Covers are cached in memory by server and reference (see Phase 2).

**Not done**

- No accessibility audit with TalkBack or large font scales on a device; only the changes above.
- Error text that originates in the client (for example "Wrong username or password") is English-only; the surrounding UI strings are translated.
- Two servers given the same explicit label are not flagged.
- A deleted server's queued tracks are re-matched across other sources, including YouTube, because a deleted config no longer says what kind it was.
- Still unverified on a device or against a real server.

### Live verification

`NavidromeLiveTest` (opt-in; skipped unless `NAVIDROME_URL`, `NAVIDROME_USER` and `NAVIDROME_PASSWORD` are set, so it never runs in CI and stores no credential) exercises the real client and source against a real server. Read-only; it prints no credential, token or address. Run it with those three variables set:

`NAVIDROME_URL=... NAVIDROME_USER=... NAVIDROME_PASSWORD=... ./gradlew :app:testDevDebugUnitTest --tests com.music.bitchord.NavidromeLiveTest`

Last run against Navidrome 0.64.2 (Subsonic API 1.16.1) over plain `http://` on a LAN: 15 of 15 passed.

Verified against the real server:

- Ping and health; a wrong password is an authentication failure (not a network one) and does not echo the password; a closed port is a network failure.
- Browsing a library of 735 artists: albums page 50 at a time with no overlap between pages, album tracks in track order with unique keys, an artist's album count matching the server's.
- Search, including punctuation and a Cyrillic name that exists in the library.
- Streams: the original (`format=raw`) is real audio and honours range requests (HTTP 206); a 128 kbps cap returns an Ogg transcode; every open uses a fresh salt and the server accepts each.
- Cover art is an authenticated JPEG.
- A missing track is a clean miss in a few milliseconds; a track the session has not seen streams with its real codec and duration (the check fills these in for a queue restored after a restart).
- Two registrations of one server keep distinct track keys for identical server ids.

**Not covered by the live test** (it needs an emulator or device, and was checked in the emulator run below): the Compose screens, real playback in the player, cover loading through Coil, scroll paging, and that the app's cleartext setting lets an `http://` LAN server connect on Android; JVM tests do not enforce that policy. Single-character searches such as `é` or `&` returned nothing; this is unexplained and may simply be no matches.

#### Emulator run (2026-10-28)

Section 11.1, run on an Android 16 emulator (API 36, `google_apis`, `arm64-v8a`, `android-36-ext19`) on an Apple Silicon Mac. The dev flavour (`com.dev.bitchord`) was built from `eb668507` and pointed at the same Navidrome 0.64.2 server (Subsonic API 1.16.1) over plain `http://` on the LAN. The account was the one in the local env file, not checked to be read-only. A full session log was captured (about 66,000 lines). No address, username or password appears in this document.

Of the 22 steps, 20 passed (some with gaps, noted), 1 failed (step 20) and 1 did not apply (step 3).

| Step | Result | What was seen |
|---|---|---|
| 1 Cleartext connect | PASS | Test over `http://` returned "Connected · Navidrome v0.64.2 API 1.16.1". The manifest setting does take effect. |
| 2 Not-encrypted warning | PASS | Shown for `http://`; gone for `https://`. |
| 3 Cleartext failure in the log | n/a | Step 1 succeeded, so there was no failure to look for. |
| 4 Credential storage | PASS | `bitchord_navidrome.xml` present; no `bitchord_navidrome_plain.xml`. |
| 5 Password not readable | PASS | A literal search of `shared_prefs`, `files` and `databases` found nothing. |
| 6 Remove | PASS | The credential entries were gone and the other 2 of the file's 4 entries (the encryption key set) remained. Removal has no confirmation prompt. |
| 7 Editor errors | PASS | Wrong password: "Wrong username or password". Unresolvable host: "Could not reach the server". `https://` to the HTTP server: "A secure connection could not be established (check the certificate)". None echoed the password. |
| 8 Label and password edits | PASS | A label-only edit with a blank password kept the server authenticating. Saving a wrong password made the Sources row re-check on its own and show "Wrong username or password"; restoring the password recovered it. |
| 9 Address edit | PASS | The server kept its position, label and on/off state. Tracks queued from the old address were not resolved against the new one; the one that played was re-matched and came from YouTube Music (the gap in 11.3). |
| 10 Health line | PASS | Version when reachable; "Wrong username or password" and "Can't reach it right now" otherwise. |
| 11 Server libraries | PASS | The row appears while the server is enabled, disappears when it is switched off and returns when switched on. |
| 12 Browse paging | PASS, partly | Artists and Albums load; scrolling past the first 50 loads more (196+ artists seen to the end of the list) with no freeze. Duplicates and gaps were not checked rigorously. |
| 13 Artist and album pages | PASS | Cover, title, Play, Shuffle and tracks in order. Back goes up one level at a time. |
| 14 Stress | PASS, with notes | Rotation, a 1.5 font scale and light mode: no crash, no clipped text (long titles ellipsise). The Albums list returned to the top after the font-scale change, and in landscape the floating mini-player and tab bar cover rows. A small-screen device was not tried. |
| 15 Server drop mid-browse | PASS | Opened offline: "Could not reach the server" with Try Again, which recovered once the network returned. Cut mid-list: the loaded rows stayed and the message appeared at the end of the list; retry continued it. |
| 16 Playback | PASS, partly | Audio plays; the media notification is present; Next, Previous and Pause work. Seeking and the lock screen were not checked. |
| 17 Play from search | PASS | A result from the Addons search played; the player selected the server's own track id. |
| 18 Resume after a kill | PASS | The queue came back after the app was force-stopped and played again. |
| 19 Server gone | PASS | With the network off the track failed three times, then was skipped; there were no YouTube substitution lines in the log. |
| 20 Quality below Lossless | FAIL, then fixed | See below. |
| 21 Covers | PASS, partly | Covers load and missing ones show the placeholder. Reloading after a restart was not checked. |
| 22 Secrets in logs | PASS | No `getCoverArt`, `/rest/stream`, token or salt matches. The 117 "password" matches all came from the system keyboard (`KeyboardWrapper`, `AndroidIME`, `KeyboardManager`) and `WifiScoreCard`, none from BitChord. The real password appears 0 times in the log. |

**Bug found and fixed: no Navidrome track played below Lossless (step 20).** With Wi-Fi quality on High, Medium or Low, every Navidrome track failed with "No enabled source could serve …", and after four in a row the queue stopped. Lossless worked and the server was reachable throughout. `AudioQuality.permits` let a source answer below Lossless only if it could not serve lossless (High) or was YouTube (Medium, Low). Navidrome is declared `canServeLossless`, so it was filtered out of `SourceRegistry.activeForPlayback`, and because a Navidrome track is never answered by another source, nothing played. The capped branch in `NavidromeSource.stream` (`maxBitRate`) was unreachable, which the unit tests could not show because they never set a ceiling. The fix permits Navidrome on every rung, as YouTube is; addon kinds are unchanged. New tests: a table test for `permits` and a resolver test that runs a Navidrome track under High, Medium and Low (a 64 kbps `maxBitRate` on Low, the raw file otherwise). Without the fix, three of the five new tests fail. After the fix the `app` module's unit tests ran 1,209 with 16 skipped (the live tests) and 0 failed. The fix was not re-run on the emulator, so a transcoded stream on Low has been checked by the resolver test and not by ear.

**Still not verified:** seeking and the lock screen; an `https://` server with a real certificate; a small-screen device; whether covers reload after a restart; duplicate and gap checking of the paged lists; TalkBack; a physical device (Keystore behaviour in particular).

**Setup correction.** `avdmanager` could not read the device definitions here and fell back to weak defaults (`hw.gpu.enabled=no`, 2 GB of RAM), so the first boot rendered everything on the CPU and Android raised "isn't responding" dialogs on the system UI and on BitChord, and dropped typed characters. Setting `hw.gpu.enabled=yes`, `hw.gpu.mode=host` and `hw.ramSize=4096` and cold-booting fixed it. The system image is about 1.9 GB. Section 11.1 now says so.

### Phase 4 — Optional future features

Scope each separately with its own design and tests:

- Playlists: list/play first; add mutations only if needed.
- Favorites and ratings: define read/write semantics, permissions, and conflict behavior.
- Scrobbling: avoid duplicate submissions if BitChord already scrobbles.
- Transcoding: expose only capabilities that can be reliably established.
- Offline playback: define download lifecycle, storage limits, authorization, and cleanup.
- Advanced search: filters, paging, and richer result categories.
- Server capability detection: use documented capability/version behavior rather than guessing from URL.

#### 4.1 Playlists (read-only): design

Status: implemented on branch `navidrome-playlists`. Read-only: no create, rename, add or remove, and no write-back to the server. Local results: `./gradlew testDevDebugUnitTest` BUILD SUCCESSFUL (1,245 tests in the suite, 0 failures, 0 errors, 16 skipped: the opt-in live tests, which were not run); `assembleDevDebug` BUILD SUCCESSFUL. Not run: the emulator or a device, and the live server. The queue label and the route title are not unit-tested.

- **API.** `getPlaylists` (no parameters, so the playlists this login can see) returns `playlists.playlist[]` with `id`, `name`, `songCount`, `duration`, `coverArt`. `getPlaylist?id=` returns `playlist.entry[]`, the tracks in the server's order. Both are read-only Subsonic methods already covered by the existing token authentication. A playlist that has been deleted comes back as error 70 and maps to `NotFound`.
- **Mapping.** A playlist maps to `LibraryPlaylist(id, name, trackCount, artworkRef)`, a sibling of `LibraryAlbum`. Its tracks map through the same `toSong` as album tracks, so each track keeps the `<configId>|<trackId>` identity and resolves through this server. Playlist order is kept as the server gives it; album disc/track sorting does not apply.
- **Interface.** `playlists()` and `playlistTracks(id)` are added to the optional `BrowsableMusicLibrary`. Only `NavidromeSource` implements it, so no other source gets a stub.
- **UI entry point.** The existing "Server libraries" flow gains a third segment, Playlists, beside Artists and Albums. Each playlist opens a track page with the album page's layout (Play, Shuffle, tracks in order) through a new `AppRoute.NavidromePlaylist`, so the page is shared code rather than a copy.
- **States.** Loading skeletons; "Nothing here yet" for an empty listing or an empty playlist; errors with Try Again; the server is disabled or removed, which shows the same unavailable message as the other browse screens. Playback of a playlist uses the same queue path as an album, labelled with the playlist name.
- **Isolation.** The route carries the config id, so a playlist is only ever read from the server it was listed from. Playlist ids are opaque and are never sent to another server. Tracks keep their config-scoped identity, so the same track id on two servers stays two tracks. The queue's source label holds the config id and playlist id, as album queues already do; it holds no URL or credential.
- **No new strings.** Reuses `playlists`, `navidrome_empty`, `navidrome_unavailable` and the error strings, so the 18 locales need no change.
- **Tests.** Client parsing of both responses (including malformed and missing fields, and the order kept); source mapping and isolation across two servers with the same ids; the route and queue label; empty and error results.

#### 4.2 Favorites and ratings: design

Status: favorites implemented on branch `navidrome-favorites`; ratings deferred (below). Scope: star and unstar songs on the server, and list the songs the login has starred. Nothing else. Tests: see the outcome note at the end of this section.

- **API.** `getStarred2` (no parameters) returns `starred2.song[]` for this login. `star?id=` and `unstar?id=` take a song id and are idempotent: starring a starred song succeeds. All three are Subsonic methods already covered by the token authentication in section 4, with no new credentials or parameters.
- **Read semantics.** The favorites list is read from the server every time the Favorites segment is opened, in the server's order, never cached. Blank ids are dropped. The list is the server's answer, so a track starred from the Navidrome web UI appears on the next open.
- **Write semantics.** A write is one call for one track. The app does not queue writes, retry them or batch them. The heart shows the new state at once and is rolled back if the server refuses or cannot be reached, so the screen only ever shows a state the server accepted or is still deciding on.
- **Permissions.** Subsonic error 50 (the login may not perform the operation) maps to `NavidromeException.NotAllowed`, with its own message. A read-only login therefore sees the favorites list and a clear refusal on each toggle, never a silent no-op. Any other failure is shown as "Could not update favorites on the server" and rolled back.
- **Conflict behaviour.** Last write wins on the server, and the app does not poll. Starring and unstarring are idempotent, so a duplicate from a retry or a second device cannot produce a wrong state beyond what the user last asked for. A track unstarred elsewhere keeps its heart as it was until the Favorites segment is opened again, and the next toggle then sends the state the user chose, not a guess.
- **UI entry point.** Two surfaces, both in the existing "Server libraries" flow:
  1. A Favorites segment beside Artists, Albums and Playlists. Rows play through the same queue path as an album, labelled "Favorites" with the server id in the queue source (no URL, no credential). Loading, empty ("Nothing here yet"), error with Try Again, and server-unavailable states are the ones the other segments already have.
  2. The song's long-press sheet. For a Navidrome track the YouTube controls (like, dislike, add to playlist, remove from playlist) are hidden, because they would send a YouTube request with a Navidrome id. A single "Add to favorites" / "Remove from favorites" row takes their place. Other tracks are unchanged.
- **Other heart surfaces.** The player's heart and the notification's favorite button are YouTube controls. For a Navidrome track `setLike` routes to the server favorite instead of YouTube, and the notification button and widget favorite are hidden or ignored for Navidrome tracks. The player's heart is still shown only while YouTube is signed in, which is unchanged; see the gaps below.
- **Error states.** Mapped through the existing `navidromeErrorText` path: `NotAllowed` has its own string; a network or server failure uses the existing network and server strings. Failures on toggle are shown as a short toast; the list shows the same error states as the other segments. No error text contains a URL, token or salt.
- **Per-server isolation.** A favorite is written only to the server the track came from. The request uses the server's own song id, taken from the track key after its config id has been checked, so the same song id on two servers is two independent favorites. The favorites list for one server never contains another server's tracks. A favorite on a server that is disabled or removed is not reachable from the sheet, because the source is not browsable.
- **Ratings (deferred).** Subsonic `setRating` (0 to 5) and the `userRating` field on songs would need a star-style rating control, a different sheet row and a second write path. Not started, so as to keep this change to one reviewable concept. It gets its own short design and PR, as the other Phase 4 items do.
- **Strings.** Five new keys (`navidrome_favorites`, `navidrome_favorite`, `navidrome_unfavorite`, `navidrome_favorite_failed`, `navidrome_err_not_allowed`), in `values/strings.xml` and the 18 locales. The locale entries are English placeholders until translators replace them, as with the error text in 11.3.

**Known gaps (not fixed in this change)**

- The player's heart is hidden for Navidrome-only users (it needs a YouTube sign-in), so those users favorite from the sheet only.
- A Navidrome track's initial heart state comes from the Favorites segment, not from each track. A track starred on the server but never shown in Favorites opens as not favorite, and tapping "Add to favorites" sends the same star again; the result is correct, but the first tap looks like a no-op.
- The Favorites list does not drop a track that was removed from it until the segment is reopened.
- Four segments in one control may be cramped on a narrow phone; not checked on a device.
- Not verified against a live server or on a device (see 11.7).

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

Ticked only where there is evidence; the notes say what is missing.

- [x] User can configure and validate a Navidrome server. *(Client and source verified live; the editor exercised on an emulator, including wrong-password, wrong-host and https-to-http errors. Not run on a physical device.)*
- [x] Credentials are stored securely and excluded from logs, exports, and persistent track identities. *(Separate encrypted store, excluded from backups, never written to the plain fallback; no logging of resolved URLs found. On an emulator the password was in the encrypted store only, and absent from app storage and from the full session log. Keystore behaviour on a physical device not checked.)*
- [x] Search results map correctly into existing BitChord song models. *(Also verified live.)*
- [x] A selected result plays through the existing playback engine. *(On an emulator: from an album and from a search result, with Next, Previous and Pause, and resumed after the app was killed. Below Lossless this only works with the quality-ceiling fix in the same PR as the emulator results. Seeking and the lock screen were not checked.)*
- [x] Multiple servers remain isolated when track IDs overlap. *(Tested through the resolver and live.)*
- [x] Errors are useful, safe, and recoverable.
- [x] Automated tests cover authentication, parsing, mapping, source identity, and stream resolution.
- [x] Existing sources and playback behavior pass regression tests. *(1,220 tests, 0 failures; after the quality-ceiling fix the `app` module runs 1,209 with 16 skipped live tests and 0 failures.)*
- Waived: other modules still compile after shared changes. *(Only the phone app (`app`) is in scope, so `tv`, `wearos` and `web` are not built or tested for this work. For reference, no code outside `app` references `SourceKind`, and `tv` happened to build with the test run.)*
- [ ] Required CI/build checks pass on the exact PR head SHA. *(The repository reports no CI checks on these branches; tests were run locally.)*
- [x] Any waived or unperformed manual testing is reported honestly.
- [x] PR description records scope, tests, limitations, and follow-up work.

**First milestone:** Phases 0 and 1 (authenticated search and playback). Complete; see the phase outcomes above.

## 11. Remaining work

Everything through Phase 3 is implemented and merged, with 1,220 automated tests passing and the client and source verified against a real server (see "Live verification"). **The app has been run on an emulator (11.1, results under "Live verification") but not on a physical device.** Do the tasks below in order; each says how to know it is done.

### 11.1 Verify on an Android emulator (done 2026-10-28)

Run once; the results, one bug found and fixed, and what is still unverified are under "Live verification". The runbook is kept so it can be repeated after changes. It matters because the unit tests cannot cover the UI, the Keystore, the player or Android's network policy: the bug it found (below Lossless, no Navidrome track played) was invisible to them.

**What this verifies that tests cannot:** that `http://` to a LAN server connects on Android (the manifest sets `usesCleartextTraffic="true"`; JVM tests do not enforce that policy), that the password is really stored encrypted, that the screens work, and that the audio actually plays.

#### Setup

These commands were run on the development Mac (Apple Silicon, `arm64`, JDK 17 and the SDK installed under `/opt/homebrew`) and worked, with one addition: step 2b below. The system image alone is about 1.9 GB, so allow a few GB of disk and a good connection; on a slow one the download stalled and had to be restarted.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 1. Emulator and an ARM64 system image (API 36 matches targetSdk = 36)
sdkmanager "emulator" "system-images;android-36-ext19;google_apis;arm64-v8a"

# 2. A virtual phone
avdmanager create avd -n bitchord-36 -d pixel_7 \
  -k "system-images;android-36-ext19;google_apis;arm64-v8a"

# 2b. avdmanager may fail to read the device definitions ("Could not load devices ... devices.xml") and
#     fall back to weak defaults: GPU off and 2 GB of RAM. That leaves the whole UI rendering on the CPU,
#     and Android shows "isn't responding" dialogs and drops typed characters. Fix it before the first boot.
sed -i '' -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' -e 's/^hw.gpu.mode=.*/hw.gpu.mode=host/' \
  -e 's/^hw.ramSize=.*/hw.ramSize=4096/' ~/.android/avd/bitchord-36.avd/config.ini

# 3. Start it (leave this running; a second terminal for the rest)
emulator -avd bitchord-36 -no-snapshot -gpu host -netdelay none -netspeed full
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

# 4. Build and install the dev flavour (package com.dev.bitchord, so it will not touch a real BitChord install)
./gradlew assembleDevDebug
adb install -r app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk
adb shell monkey -p com.dev.bitchord -c android.intent.category.LAUNCHER 1
```

If no `latest` directory exists under `cmdline-tools`, put the `cmdline-tools/bin` directory on `PATH` instead.

**Reaching the server.** The emulator reaches the host's LAN through NAT, so the Navidrome server's normal LAN address works. If the server runs on the Mac itself, use `10.0.2.2` instead of `localhost`. ICMP ping does not work from the emulator; use the app's Test button to check reachability.

**Credentials.** Take them from the local env file (never commit it, and never paste them into an issue, a PR or this document). Type them into the emulator's keyboard, or use `adb shell input text '<value>'` (it mishandles some special characters). Read the values in a small script and quote them for the device shell, so they are never printed; the editor's fields can be moved between with `adb shell input keyevent 61` (Tab), and a field cleared with `input keycombination 113 29` then `input keyevent 67`. Prefer a read-only Navidrome account.

Keep a log running in a second terminal while testing:

```bash
adb logcat -c && adb logcat | tee /tmp/bitchord-emulator.log
```

#### Checklist

Mark each as pass or fail and note what you saw. A failure here is a bug to fix before the work is called done.

**A. Cleartext (the main unknown)**
1. Add a server with an `http://` LAN address and press Test. Expect "Connected · Navidrome v… API 1.16.1".
2. While the address starts with `http://`, the card shows the "not encrypted" warning. Expect it to disappear for `https://`.
3. If Test fails, search the log for `cleartext`. "Cleartext HTTP traffic … not permitted" means the manifest setting is not taking effect, which would break LAN support.

**B. Credentials**
4. Save the server. Then:
   ```bash
   adb shell run-as com.dev.bitchord ls shared_prefs
   ```
   Expect `bitchord_navidrome.xml` and **no** `bitchord_navidrome_plain.xml` (the plain file means the Keystore fallback was used, which should not happen on an emulator).
5. Confirm the password is not readable in storage:
   ```bash
   adb shell run-as com.dev.bitchord grep -rl "<the password>" shared_prefs
   ```
   Expect no output.
6. Remove the server and repeat step 4. Expect the entry for it to be gone.

**C. Editor and errors**
7. Wrong password: red "Wrong username or password". Wrong host: a network message that does not include the password. `https://` to an `http` server: a connection or certificate message.
8. Edit only the label: same server, no re-keying. Edit only the password: the Sources row re-checks the server by itself (watch the health line).
9. Edit the address: the server keeps its place in the list and its on/off state. Anything queued from the old address is skipped or re-matched, never played from the new server by id.

**D. Sources screen**
10. The health line shows the server version when reachable, and a clear Rejected or Unreachable message otherwise.
11. A "Server libraries" group lists the server only while it is enabled and complete. Disable it: the row disappears and the log shows no further requests to it.

**E. Browse**
12. Open the library: Artists and Albums tabs both load. Scroll a long list and confirm it loads in batches (50 at a time) with no freeze, no duplicates and no gaps.
13. Open an artist, then an album: the cover, title, Play, Shuffle and the track list in order. Back returns one level at a time.
14. Stress it: rotate the screen, set a large font (`adb shell settings put system font_scale 1.5`), switch dark mode, and use a small-screen device definition. Nothing should clip, overlap or lose its state unexpectedly.
15. Turn the server off (or break the address) mid-browse: an error with Try Again appears, rows already loaded stay, and retrying works once the server is back.

**F. Playback**
16. Play from an album: audio starts, the notification and lock-screen controls show the title and artist, seeking works, Next/Previous work, and Shuffle works.
17. Play a track from the Search tab (Addons mode): results include the Navidrome server and tapping one plays it.
18. Kill the app, reopen it, and resume the queue: the tracks still play (this exercises the pre-playback check).
19. Take the server away while a Navidrome track is next in the queue: it is skipped with an error, and a YouTube copy is **not** substituted.
20. Check the quality settings: with the connection set to a lower quality, the stream is the transcoded one and still plays. Try Low, Medium and High, each on a track not played before (a cached track plays without being resolved, which hides the fault). This is the step that found the bug described under "Live verification".

**G. Covers**
21. Covers appear in the browse lists and the album header, and a missing cover shows the placeholder. They are cached only in memory, so expect them to reload after a restart (a known limitation).

**H. Secrets in logs**
22. After a full session, search the captured log for anything that carries the login. Each of these must return nothing:
   ```bash
   grep -iE "getCoverArt|/rest/stream|[?&]t=[0-9a-f]{32}|[?&]s=[0-9a-f]{16,}|password" /tmp/bitchord-emulator.log
   ```
   If anything matches, find where the URL is being logged and remove it.

**Finish**
```bash
adb emu kill                          # stop the emulator
avdmanager delete avd -n bitchord-36  # optional: remove the virtual phone
```

**Record the outcome** in the "Live verification" section: what passed, what failed and what was fixed. Fix failures on a branch and open a PR as before. Tick the "A selected result plays through the existing playback engine" box in section 10 only when steps 16 to 18 pass. *(Done for the 2026-10-28 run.)*

### 11.2 Quality gates not yet run

- **Lint and static analysis:** done. `./gradlew :app:lintDevDebug` (the root task aborts on the out-of-scope `wearos` module) now reports no findings in Navidrome files; the remaining `app` findings are outside Navidrome and were left alone.
- **Other modules:** not needed. Only the phone app (`app`) is built and tested for this work; `tv`, `wearos` and `web` are out of scope.
- **CI:** the repository reports no CI checks on these branches, so "required CI passes" in section 10 cannot be ticked. Decide whether to add a workflow that runs `./gradlew testDevDebugUnitTest` and `assembleDevDebug`; the live test skips itself without the `NAVIDROME_*` variables, so it is safe there.
- **Translations:** 13 strings, all named `navidrome_*` or `add_navidrome*`, are machine-written in 18 locale files under `app/src/main/res/values-*`. Have a speaker of each language check them, or at least the ones for the languages that matter most.

### 11.3 Known gaps worth closing

Status after the gap-closing pass (branch `navidrome-gaps`, one commit per gap). "Verified" means unit-tested; the live and device checks are listed under 11.7.

| Gap | Where | Status |
|---|---|---|
| Covers are cached in memory only | `NavidromeArtFetcher` | Done in code: cover references use the `navidrome:<configId>:<ref>:<size>` key, never a URL. Coil's existing 100 MB disk cache holds them. Persistence across restarts needs a device check (11.7). |
| Search result rows have no artwork | `NavidromeSource.toSong` | Done: a row stores `navidrome-art:<configId>/<ref>/<size>` (no login) and a Coil fetcher loads it. Needs a live check (11.7). |
| Capped (transcoded) streams show an unknown codec | `NavidromeSource.stream` | Done: codec read from the stream's Content-Type with a one-byte request. Needs a live check (11.7). |
| Single-character searches returned nothing on the live server | `search3` use | Won't fix (decision 2026-10-28). Not needed; no UI text added. |
| Two servers with the same explicit label are not flagged | `NavidromeEditorAlert` | Done: the editor warns (does not block). |
| A deleted or re-pointed server's queued tracks were re-matched across all sources | `SourceResolver.resolve`, `SourceRegistry` | Done: retired ids are persisted with their successor. A re-pointed id may be answered only by its replacement; a removed id is answered by nothing. Covered by unit tests. |
| Removing a server has no confirmation | `NavidromeEditorAlert` | Done. |
| The Albums list returns to the top after a font-scale change | browse screens | Done in code: the first 200 loaded albums are kept in saved state. Needs a device check (11.7). |
| In landscape the floating bars cover list rows | browse screens | Done in code: the lists end in a spacer as tall as the measured floating bars. Needs a device check (11.7). |
| Client error text is English only | `NavidromeException` | Done: each category maps to a string resource. English text is in `values`; the 18 locale files carry English placeholders for translators to replace. |
| No TalkBack audit | browse screens, editor | Partly done: the album cover is labelled and the server editor fields take their name from the placeholder. The full walkthrough needs a device (11.7). |

Adding strings: every new key is in `values/strings.xml` and all 18 locales (`LocaleStringsTest` requires it). The locale entries are English placeholders until translators replace them.

### 11.4 Upstream

All of this work is on the fork (`abucalip/BitChord`, PRs #1 to #8). **Nothing has been proposed to the upstream project** (`kushagrasinghx/BitChord`), and the decision of 2026-10-28 is to ignore upstream work for now. If that changes, before opening anything there:

1. `CONTRIBUTING.md` says substantial features should be discussed in an issue first; open one that links this document.
2. It also says new work should branch from the current active version branch rather than `main`. Only `main` (latest tag `v1.8.1`) exists on the remotes, so ask the maintainers which branch to target.
3. Split the work into the reviewable PRs listed in section 7, rebased on that branch, and attach the emulator results from 11.1.
4. Two changes affect the whole app, not just Navidrome, and should be called out for review: `usesCleartextTraffic="true"` in the manifest, and the new rule in `SourceResolver.resolve` that a track from a Navidrome server is never answered by another source.

### 11.5 Phase 4 (optional, only after the above)

Each item needs its own short design and tests first, and its own PR: playlists (list and play first), favorites and ratings, scrobbling (avoid duplicates with any existing scrobbler), transcoding controls, offline playback, advanced search, and server capability detection. See Phase 4 above.

### 11.6 Credentials hygiene

The live test and the emulator checks use a real account. Keep credentials in a local, untracked env file (mode 600), use a read-only Navidrome account where possible, and never put credentials, tokens or the server address in a commit, PR, issue or this document.

### 11.7 Checks that still need a live server or a device

Not run in this pass; each needs the `NAVIDROME_*` variables (live test) or an emulator/device:

- `NavidromeLiveTest` (skips itself without the variables). Not run: no local env file was available to the agent. Run it with the credentials supplied at run time, never written into the repo.
- Search-row artwork and the transcoded codec against a live server.
- The album list kept across a font-scale change, and the landscape clearance above the floating bars.
- Covers still present after an app restart (disk cache).
- TalkBack walkthrough of the browse screens, with focus order.
