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
  only a date. Runs on its own `geminiHttpClient` (120s request timeout,
  matching `foodie`'s documented value — Gemini generation routinely
  exceeds CIO's 15s default). Model is configurable (`GEMINI_MODEL`,
  defaults to `gemini-3.6-flash` — **check `foodie`'s current model before
  trusting this default**, see CLAUDE.md's Gemini gotchas: names churn on
  Google's release schedule and `foodie` hits it more often). Strips a
  ` ```json ` code fence before decoding — same quirk `foodie`'s
  `RecipeParser.kt` works around, Gemini sometimes adds one even with
  `responseMimeType: application/json` set. No response caching, same "low
  volume, not worth it yet" reasoning as the Gmail token refresh.
- **Auth**: Google sign-in, implemented. Same pattern as `foodie`
  (Ktor's built-in OAuth2 provider, signed session cookie via
  `SessionTransportTransformerMessageAuthentication`) rather than
  client-side Google Identity Services — simpler for a server-rendered app.
  Scope requested is **identity only** — `openid email profile`, all
  "non-sensitive" — deliberately *not* `gmail.readonly` or any other Gmail
  scope. Google sign-in and Gmail data access are two fully separate
  concerns here (see Gmail integration below for why and how) — signing in
  proves who you are; it grants no mailbox access on its own.
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
- **Gmail integration: plain IMAP (Jakarta Mail), not the Gmail REST
  API/OAuth.** This was a deliberate reversal partway through — the
  original build (see git history) used the Gmail REST API with an OAuth
  `gmail.readonly` scope, exactly the `foodie`-consistent "no provider SDK,
  plain HttpClient" pattern the rest of this app follows. That got as far
  as working end to end (real sign-in, real Gmail pull, both verified live)
  before the cost of `gmail.readonly` became clear: it's a Google
  "restricted" OAuth scope, which requires an annual **CASA Tier 2 paid
  security assessment** (~$500–$1,000/year, recurring) to move the app out
  of "Testing" consent-screen status — and *even while staying in Testing*,
  refresh tokens for a Testing-status app reportedly expire after 7 days,
  which would have broken any periodic/background pulling anyway. Not
  worth either cost for a two-person household app. IMAP + a per-user
  **Gmail "app password"** (`myaccount.google.com/apppasswords`, requires
  2-Step Verification) sidesteps all of it — it isn't OAuth at all, so none
  of the restricted-scope machinery applies, and app passwords don't expire
  on a timer.
  - `ImapGmailClient` (`GmailClient.kt`) connects via Jakarta Mail
    (`com.sun.mail:jakarta.mail`) to `imap.gmail.com:993` over `imaps`,
    authenticating with the user's `email` + `gmailAppPassword` (stored on
    their Firestore `User` doc — see `UserStore.kt`). `host`/`port`/
    `protocol` are constructor params, not hardcoded, purely so
    `ImapGmailClientTest` can point the same code at an in-process fake IMAP
    server (GreenMail) over plain unencrypted `imap` instead of real Gmail —
    GreenMail's IMAPS uses a self-signed cert Jakarta Mail won't trust by
    default, and that's not what those tests are exercising anyway.
  - Sender/date filtering happens **server-side via IMAP SEARCH**
    (`AndTerm(OrTerm(FromStringTerm per sender), ReceivedDateTerm)`) — same
    "never pull the whole inbox to filter locally" principle the old Gmail
    `q=` search query followed, just expressed as IMAP search terms instead
    of a Gmail query string.
  - Body text: walks the MIME tree for a `text/plain` part first; if a
    message has none (some HTML-only newsletters), falls back to a crude
    regex tag-strip of the first `text/html` part rather than pulling in a
    full HTML parser (Jsoup, as `foodie` uses) for just this one fallback
    path.
  - Jakarta Mail's `Store`/`Folder`/`Message` API is **blocking I/O**, not
    coroutine-friendly — `ImapGmailClient.searchMessages` wraps the whole
    thing in `withContext(Dispatchers.IO)`.
  - `GET /inbox` (`InboxRoutes.kt`) is the app's main flow: checks for a
    stored app password first (prompts to connect via a form if missing,
    distinct from the "no senders configured" state, checked before ever
    calling Gmail), then calls `ImapGmailClient.searchMessages` and runs
    each result through `GeminiClient.extract`. **`POST
    /inbox/connect-gmail`** is how a signed-in user sets/rotates their app
    password — always a blank field (never echoes the stored secret back),
    a blank submission is a no-op rather than wiping out an existing
    password (see its route comment). Deliberately not part of the Google
    sign-in flow at all — see the Auth decision above.
- **Scan settings** (which senders, how many weeks back): stored in
  Firestore (`SettingsRepository`/`FirestoreSettingsStore` in
  `SettingsStore.kt`), one shared doc at `settings/scan` — not per-user,
  same "two-person household, one shared view" reasoning as
  `ALLOWED_EMAILS`/`User.id`. Editable via a form directly on `/inbox`
  (`POST /inbox/settings`, redirect-after-post back to `GET /inbox` so the
  redirected reload re-runs the scan with the new values). `SCHOOL_SENDERS`/
  `LOOKBACK_WEEKS` env vars only seed the doc's *first* read, before anyone's
  ever saved a value through the form — once saved, Firestore is the source
  of truth and those env vars stop being read. `lookbackWeeks` is clamped to
  1-52 on save regardless of what the form submits (a stray huge value would
  turn into an equally huge Gmail `after:` window for no benefit). Explicit
  maintainer decision: this data is scan configuration (sender addresses,
  a number), not email content — a different privacy bar than the emails
  themselves, so persisting it in Firestore was fine, unlike (say) caching
  extracted email content, which isn't done anywhere yet.

## Not yet decided / open questions

- How periodic email pulling runs (background job vs. purely on-open) —
  `GET /inbox` currently only pulls on-demand, when the page is loaded (and
  re-scans + re-runs Gemini on every visit — no caching/dedup of
  already-seen messages yet, so revisiting the page re-spends a Gemini call
  per matching message every time).
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
  `POST /logout` (`Auth.kt`), `GET /inbox` + `POST /inbox/connect-gmail` +
  `POST /inbox/settings` (`InboxRoutes.kt`, all three gated behind
  `authenticate(USER_SESSION_PROVIDER_NAME)` — the main scan-and-extract
  flow, its Gmail app-password form, and its sender/lookback settings form,
  see above).
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
  below), `SCHOOL_SENDERS` / `LOOKBACK_WEEKS` (seed the Firestore scan
  settings doc's first read only — see "Scan settings" above; once the
  `/inbox` settings form is submitted once, edit the values there instead,
  not these env vars), `GEMINI_API_KEY` (required for `RestGeminiClient` to
  authenticate against `generativelanguage.googleapis.com`), `GEMINI_MODEL`
  (defaults to `gemini-3.6-flash` — verify against `foodie`'s current model
  first, see CLAUDE.md's Gemini gotchas).
- **Firestore database**: created and confirmed working — real sign-in on
  the deployed Cloud Run service has round-tripped through
  `FirestoreUserStore` successfully. Database id/region weren't
  re-confirmed after the fact here; presumed `schoolio` in
  `northamerica-northeast1` (the code's own defaults) unless the
  maintainer set `FIRESTORE_DATABASE_ID` to something else on Cloud Run.
- **Local dev needs Google Cloud Application Default Credentials** for the
  real `FirestoreUserStore` — running the app locally (`./gradlew run`)
  without `gcloud auth application-default login` (or a service account
  key via `GOOGLE_APPLICATION_CREDENTIALS`) crashes at startup with an NPE
  deep inside `google-cloud-firestore` (`DatabaseRootName` builder hitting
  a null project id) rather than a clear error - see CLAUDE.md's gotchas
  section. Automated tests never hit this: `testModule()` always injects
  `FakeUserRepository`/`FakeGmailClient`, so the real Firestore client is
  never constructed in CI (`ImapGmailClientTest` does exercise a real IMAP
  client, just against GreenMail's in-process fake server, not real Gmail
  or Firestore).
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
