# ADR-002: `CloudApi` interface seam for `SemperApi`

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

`SemperApi` (`app/.../data/net/SemperApi.kt:44`) is `class SemperApi private
constructor(context)` with a singleton `get(context)`. It is reached from 21
call sites in 10 files. Its OkHttp clients are private companion objects and
its base URL is `BuildConfig.SEMPER_API_BASE_URL`, with an `https` `require`.

So nothing that talks to the backend can be unit-tested with a fake:
`AuthRepository` (sign-in, terms, config, device registration), `SeatLease`
(floating-seat checkout/renew/release), `CloudSync`, and the Settings cloud
export all go straight to the real client. TD-25 recorded this, and the cloud
export's cancel bug (TD-41) went unnoticed partly because of it. The user has
decided cloud flows are verified by JVM fakes rather than on a device, which
makes this seam a prerequisite.

The test stack has no mocking library — tests use `MockWebServer` for
URL-parameterised calls and hand-written lambda seams (`SeatLease.release` /
`heartbeat` already take lambdas). CONTEXT rules out a DI framework (Hilt was
removed).

## Decision

Extract an interface `CloudApi` in `data/net/` with the 23 public members of
`SemperApi` (`enabled`, `me`, `getConfig`, `exportAccount`, `registerDevice`,
`activateLicense`, `checkoutLease`, `releaseLease`, `acceptTerms`,
`setImprovementConsent`, `listSessions`, `createSession`, `sessionUploads`,
`completeFile`, `listSessionFiles`, `downloadRange`, `downloadFile`,
`listUsers`, `setUserStatus`, `deleteAccount`, `deleteSession`,
`uploadResumable`). `SemperApi` implements it; its nested exception types stay
where they are, so no `catch` site changes.

Inject it through **defaulted constructor or function parameters** only where
there is logic worth testing:

```kotlin
class AuthRepository(
    context: Context,
    private val api: CloudApi = SemperApi.get(context),
    private val auth: AuthBackend = FirebaseAuthBackend,
    private val tokens: TokenSource = TokenProvider,
)
```

plus `SeatLease`, `CloudSync`, and the export function extracted from
`SettingsYourDataSection`. The other call sites keep `SemperApi.get(context)`,
which now simply returns a `CloudApi`-compatible object.

Tests use a hand-written `FakeCloudApi` (records calls, scripted answers or
exceptions, including `CancellationException`).

## Options considered

### A: Interface + defaulted parameters at the classes under test (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | One interface, ~5 constructors |
| Scalability | Extends class by class |
| Team familiarity | Same style as the existing lambda seams |

**Pros:** no call-site churn outside the classes under test; release code path
unchanged. **Cons:** two ways to reach the client coexist.

### B: Constructor injection everywhere through a small `AppGraph`

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | 21 call sites, Activities and Workers |
| Scalability | Good |
| Team familiarity | New pattern |

**Cons:** Workers and Activities need a factory story; large diff for no extra
test value today.

### C: Hilt / Dagger

Rejected: removed once already; CONTEXT says no DI framework.

### D: Base-URL override pointed at MockWebServer

**Cons:** the client `require`s `https`; testing over http needs either a
weakened check or okhttp-tls certificates, and still exercises the network
layer rather than the repository logic.

## Trade-off analysis

A gives the testability that matters (the repositories' decisions) with the
smallest diff and no change to release behaviour. B is the natural next step if
more classes need fakes; nothing in A blocks it.

## Consequences

- Easier: `AuthRepositoryTest`, `SeatLeaseTest`, cloud export cancel test.
- Harder: an `SemperApi` member added later must be added to the interface too
  (compile error if a fake does not implement it, so it cannot drift).
- Revisit: when a third class needs `auth` or `tokens` fakes, consider B.

## Action items

1. [x] `CloudApi` and `TokenSource` (`data/net/CloudApi.kt`); `SemperApi` and
       `TokenProvider` implement them. ~~`AuthBackend` / `FirebaseAuthBackend`~~:
       replaced by a `signedIn` lambda (As built).
2. [x] Defaulted parameters on `AuthRepository`, `SeatLease`, `CloudSync`,
       and the extracted `data/CloudAccountExport.download`.
3. [x] `FakeCloudApi` + `FakeTokens` in `app/src/test/…/cloud/`;
       `AuthRepositoryTest` (14), `CloudSeamTest` (13: seat refresh, reconcile
       verdicts, erase order, export success / failure / cancel).
4. [x] Close TD-25.

## As built (2026-09-23)

- **No `AuthBackend`.** Wrapping `FirebaseAuth` would have meant an interface
  over some 20 sign-in, MFA, reset and link calls, none of which the tests
  drive. The decisions worth testing (status, terms, consent) need only "is
  anyone signed in", so `AuthRepository` takes
  `signedIn: () -> Boolean = { FirebaseAuth.getInstance().currentUser != null }`
  and looks `FirebaseAuth` up lazily. A test that never signs in never
  initialises Firebase. Revisit if the sign-in flows themselves need JVM tests.
- **Defaults live on the interface.** Kotlin forbids an override from
  restating a default, so `listSessions(verify)`, `downloadFile(expectedBytes,
  onBytes)`, `listUsers(status)` and `uploadResumable(onBytes)` declare theirs
  on `CloudApi`; calls through `SemperApi` inherit them unchanged.
- **The fake fails loudly.** Every `FakeCloudApi` call a test did not script
  throws `AssertionError`, so a decision that reaches the backend when it
  should not fails the test instead of getting an answer.
- **Other call sites unchanged.** `CloudRestore`, `DicUploadWorker` and the
  workers still take `SemperApi` in their private helpers; widen them to
  `CloudApi` when a test needs them.
