# Schoolio — Project Context

Stable overview of the project. Update this when architecture, infra, or
major conventions change — not for day-to-day task status (see `current.md`
for that).

## What this is

An app that turns school-related email (permission slips, field trips,
schedule changes, deadlines) into calendar-ready items, so nothing important
gets missed in the noise of a normal inbox. Shared between the maintainer
and their spouse — not a single-user tool.

Solo/household project — see `CLAUDE.md` at the repo root for operational
gotchas as they accumulate (deploy pipeline, Firestore config, Gemini
integration quirks, git/PR workflow conventions). This file is the
higher-level "what and why"; `CLAUDE.md` is the "how, precisely, and what
bit us before."

Sibling project: `foodie` (same maintainer) uses the same backend/DB stack
and a number of the patterns referenced below (Google sign-in via Ktor's
OAuth2 provider, household sharing, Firestore repository-interface style).
Worth checking there for precedent before inventing a new pattern here.

## Architecture at a glance (decided)

- **Backend**: Kotlin + Ktor, same choice as `foodie`.
- **Storage**: Firestore, same choice as `foodie`. Concrete database
  id/region TBD — record here once created (see `foodie`'s Configuration
  reference for the shape of what to capture: database id, region,
  migration history if any).
- **No framework, backend or frontend** — same call as `foodie` (no
  Spring/Micronaut on the backend; no React/Vue/etc. on the frontend).
  Server-rendered HTML via templates (FreeMarker, matching `foodie`'s
  `backend/src/main/resources/templates/*.ftl` setup) plus plain JS/CSS —
  not a "no UI, API-only" project. Template files, static CSS, and a
  shared plain-JS file (`foodie`'s `app.js` pattern: one file, per-page
  blocks gated on a DOM element that only exists on that page) are the
  plan, same shape as `foodie`, just without a JS framework on top.
- **AI**: Gemini API, for extracting structured data (dates, deadlines,
  action items, event details) from filtered school emails — same role
  Gemini plays in `foodie` for recipe/grocery-item parsing. Implemented as
  `RestGeminiClient` (`GeminiClient.kt`) — plain REST calls against
  `generativelanguage.googleapis.com`'s `generateContent` endpoint (same
  no-SDK convention as `GmailClient`/`GoogleAuthFlow`), using
  `generationConfig.responseSchema` to force a JSON response shaped like
  `{summary, actionItems: [{description, dueDate?, dueTime?}]}` rather than
  parsing free-form prose. `dueDate`/`dueTime` are separate optional
  ISO-8601 fields (not one combined timestamp) since emails often state
  only a date. Runs on its own `geminiHttpClient` (longer request timeout
  than the shared OAuth/Gmail client) — same split as `foodie`'s own
  Gemini client. Model is configurable (`GEMINI_MODEL`, defaults to
  `gemini-2.5-flash`); no response caching, same "low volume, not worth it
  yet" reasoning as the Gmail token refresh.
- **Auth**: Google sign-in, implemented. Same pattern as `foodie`
  (Ktor's built-in OAuth2 provider, signed session cookie via
  `SessionTransportTransformerMessageAuthentication`) rather than
  client-side Google Identity Services — simpler for a server-rendered app.
  Scope requested is `openid email profile
  https://www.googleapis.com/auth/gmail.readonly` in one shot at login,
  not a separate "connect Gmail" step later, since Gmail access is the
  whole point of this app. `extraAuthParameters = access_type=offline,
  prompt=consent` on every sign-in (not just the first) so Google reliably
  returns a `refresh_token` to persist — see Gmail integration below.
- **Shared state / sign-in gating**: **decided — fixed two-person
  allowlist**, not a `foodie`-style household/invite-code model. Only two
  Google accounts will ever use this app (maintainer + spouse), so an
  open-ended join/invite system would be solving a problem that doesn't
  exist here. `ALLOWED_EMAILS` (comma-separated, case-insensitive env var,
  same shape as `foodie`'s `ADMIN_EMAILS`) is checked in the
  `/auth/google/callback` handler *before* creating a user record or
  session — an unlisted Google account gets redirected back with no
  session and no Firestore write, not just a gated page. No hardcoded
  fallback: unset/empty means nobody can sign in.
  - **Implication for `User`**: no opaque UUID id decoupled from the
    provider the way `foodie`'s `User.id` is (that existed there to let a
    second sign-in method resolve to the same account). Schoolio has
    exactly one sign-in method, so `User.id == googleSub` directly — see
    `UserStore.kt`.
- **Gmail integration**: plain REST calls against `gmail.googleapis.com`
  (`GmailClient`/`RestGmailClient` in `GmailClient.kt`), not the
  `google-api-services-gmail` client library — consistent with the
  no-framework call above and with how `foodie`'s own Google/Firebase
  calls (`GoogleAuthFlow.kt`, `EmailAuthFlow.kt`) go through a plain
  injected `HttpClient` rather than a provider SDK, which is also what
  makes it fake-able with `MockEngine` in tests. Each user's Google
  `refresh_token` is stored on their Firestore `User` doc
  (`googleRefreshToken`) and exchanged for a short-lived access token on
  every Gmail call (`RestGmailClient.refreshAccessToken`) rather than
  cached — pulls are infrequent (on app open, or periodically — still an
  open question below), so there's no hot path worth caching a ~1-hour
  token for yet.
  - `GET /inbox` (`InboxRoutes.kt`) is now the app's main flow, not just a
    proof-of-pull: `RestGmailClient.searchMessages` scopes the Gmail
    `messages.list` call server-side with a `q=after:<epoch> (from:a OR
    from:b OR ...)` query — never an unfiltered inbox pull, see README's "I
    don't want to pull all my email" framing — then fetches each match with
    `format=full` (not `metadata`) since Gemini needs body text, not just
    headers. Body extraction walks the MIME `parts` tree for a `text/plain`
    part (base64url-decoded), falling back to Gmail's own `snippet` field
    for the rare message with none. Each result is run through
    `GeminiClient.extract` and rendered with its summary + action items.
    Still prompts to "sign in again" if no refresh token is stored yet
    (shouldn't normally happen given `prompt=consent` above, but the route
    handles it rather than crashing) — and now also shows a distinct
    "configure `SCHOOL_SENDERS`" state when no senders are configured at
    all, checked *before* ever calling Gmail.
  - Not paginated — a single `messages.list` call (Gmail's default page
    size) is assumed to cover a household's few-weeks/few-senders volume;
    worth revisiting if that assumption ever breaks.

## Not yet decided / open questions

- How periodic email pulling runs (background job vs. purely on-open) —
  `GET /inbox` currently only pulls on-demand, when the page is loaded.
- Calendar target: push to Google Calendar directly, or maintain an
  in-app calendar with optional export/sync.
- How much human review sits between AI extraction and calendar creation
  (auto-create vs. confirm-first).

## Configuration reference

- **Backend**: `backend/` — Kotlin/Ktor, FreeMarker templates
  (`backend/src/main/resources/templates/*.ftl`), static assets under
  `backend/src/main/resources/static/`, same layout as `foodie`. Routes so
  far: `GET /` (`splash.ftl` — deploy-confirmation revision display, plus
  sign-in/sign-out), `GET /auth/google` + `GET /auth/google/callback` +
  `POST /logout` (`Auth.kt`), `GET /inbox` (`InboxRoutes.kt`, gated behind
  `authenticate(USER_SESSION_PROVIDER_NAME)` — the main scan-and-extract
  flow, see above).
- **Env vars** (Cloud Run + local `.env`/shell, not committed): 
  `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` (a *new* OAuth 2.0 Client ID
  under the shared `foodie-503510` project — not foodie's own client, since
  each app needs its own redirect URIs), `SESSION_SECRET` (HMAC key for
  cookie signing — falls back to a hardcoded insecure dev value if unset,
  fine locally but must be set on Cloud Run), `OAUTH_REDIRECT_BASE_URL`
  (externally-visible base URL for the OAuth callback — defaults to
  `http://localhost:8080` locally), `ALLOWED_EMAILS` (comma-separated,
  gates sign-in itself — see the Shared state/sign-in gating decision
  above), `FIRESTORE_DATABASE_ID` (defaults to `"schoolio"` if unset — see
  below), `SCHOOL_SENDERS` (comma-separated sender addresses/domains that
  `/inbox` scans — no fallback, unset/empty means "not configured yet", a
  state `/inbox` shows distinctly rather than treating as zero senders
  found), `LOOKBACK_WEEKS` (how far back `/inbox` searches; defaults to
  `4`), `GEMINI_API_KEY` (required for `RestGeminiClient` to authenticate
  against `generativelanguage.googleapis.com`), `GEMINI_MODEL` (defaults to
  `gemini-2.5-flash`).
- **Firestore database**: not created yet. Must be a *new* database under
  the shared `foodie-503510` project, distinct from `foodie`'s
  `foodie-nne1` — planned id `schoolio` (or `schoolio-nne1` to mirror
  `foodie`'s region-suffixed naming, not decided), region
  `northamerica-northeast1` to match Cloud Run/co-locate with `foodie`'s
  database. Firestore databases aren't created implicitly by the app the
  way a Firestore *collection* is — this needs a one-time manual step
  (console or `gcloud firestore databases create`) before `FirestoreUserStore`
  will actually work.
- **Local dev needs Google Cloud Application Default Credentials** for the
  real `FirestoreUserStore` — running the app locally (`./gradlew run`)
  without `gcloud auth application-default login` (or a service account
  key via `GOOGLE_APPLICATION_CREDENTIALS`) crashes at startup with an NPE
  deep inside `google-cloud-firestore` (`DatabaseRootName` builder hitting
  a null project id) rather than a clear error - see CLAUDE.md's gotchas
  section. Automated tests never hit this: `testModule()` always injects
  `FakeUserRepository`/`FakeGmailClient`, so the real Firestore/Google
  clients are never constructed in CI.
- **Deploy**: `cloudbuild.yaml` at repo root + `backend/Dockerfile`
  (multi-stage: `eclipse-temurin:21-jdk-jammy` builds the fat jar via
  `./gradlew buildFatJar`, `eclipse-temurin:21-jre-jammy` runs it), same
  shape as `foodie`. Cloud Run service name `schoolio`, region
  `northamerica-northeast1` (matches `foodie`'s region).
- **GCP project: shared with `foodie`** — `foodie-503510`, hardcoded into
  `cloudbuild.yaml`'s `_IMAGE` substitution
  (`northamerica-northeast1-docker.pkg.dev/foodie-503510/cloud-run-source-deploy/schoolio:${SHORT_SHA}`),
  same as `foodie`'s own `cloudbuild.yaml`. Deliberate choice — one GCP
  project/billing account for both small apps rather than standing up a
  second one. Cloud Build's built-in `$PROJECT_ID` substitution was tried
  first (so the file wouldn't need a real value baked in) but the build
  failed without a hardcoded project id, so this follows `foodie`'s
  pattern instead — worth investigating properly if it comes up again, but
  not blocking right now.
  - **Implication**: schoolio's image lands in the *same* Artifact
    Registry repo `foodie` already uses (`cloud-run-source-deploy` in
    `northamerica-northeast1`), just under a different image name
    (`schoolio` vs. `foodie-backend`) — no new AR repo needs creating.
  - **Implication**: once Firestore is set up, schoolio's database id must
    be distinct from `foodie`'s (`foodie-nne1`) — same project means same
    Firestore instance's list of databases, so a name collision is a real
    risk, not just a style concern.
  - **Implication**: IAM roles granted to the Cloud Build trigger's
    service account for `foodie` (`artifactregistry.writer`,
    `run.developer`, `iam.serviceAccountUser`) may already cover schoolio
    too, if it's the same service account — worth checking before
    re-granting anything.

## Decisions log

- Backend: Ktor. Chosen up front, matching `foodie` — no new backend
  framework to learn/maintain for a second small project.
- DB: Firestore. Same reasoning — one DB technology across both projects.
- No framework layered on Ktor, and no JS framework on the frontend: same
  reasoning as `foodie`'s original choice — the app is small enough not to
  need one. Still server-rendered with template files (FreeMarker) plus
  plain JS/CSS, same as `foodie` — "no framework" isn't "no UI."
