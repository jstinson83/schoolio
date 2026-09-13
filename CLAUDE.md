# Schoolio

An app that turns school-related email into calendar-ready items, shared
between the maintainer and their spouse. See `README.md` for the full scope
and `.claude/context.md` for architecture decisions made so far.

## Session continuity (`.claude/context.md` and `.claude/current.md`)

- `.claude/context.md` is the stable project overview (architecture, major
  features, decisions already made).
- `.claude/current.md` holds the maintainer's active **sprint plan**: a
  checklist of tasks they've laid out in conversation, not a "last thing
  done" log. It's maintainer-authored — when they describe a plan, write it
  down as a checklist; don't add tasks to it on your own initiative.
- Don't rewrite `current.md` at the start of every task — it persists
  across tasks/sessions untouched by default. It only changes when:
  - The maintainer communicates a new or updated plan — write it down
    (replacing what's there).
  - A task gets finished — check the plan for a matching item and remove
    it if present. If the finished task isn't on the plan, leave the file
    alone; not everything has to be planned.
- If the maintainer asks you to "consult the plan," read `current.md` to
  see what's left and use it to decide what's next.
- Keep entries as a short checklist (one line per task), not a narrative
  status writeup — commit history and PR descriptions already capture the
  "what happened"; this file is just "what's still open."
- Update `context.md` (separately from the sprint plan) when a task changed
  architecture, added a major feature, or made a decision worth not
  relitigating later.
- Keep `context.md` high-level: architecture, decisions, and concrete
  config facts (IDs, regions, key/secret locations) other tasks need
  without re-deriving them. Operational gotchas — what breaks, how it bit
  us before, how to debug it — belong in this file (`CLAUDE.md`) instead,
  as they accumulate. Don't restate `context.md`'s facts here; reference
  them.

## Workflow conventions

- After pushing commits to a feature branch, open a PR against `main` if
  the maintainer hasn't already asked for one to exist — same convention as
  `foodie`. The maintainer merges from the PR link.
- Feature branches get reused across tasks. Before adding new commits after
  maintainer feedback, actually check (don't assume) whether the branch's
  previous PR has merged — expect it usually has. If so, rebuild the branch
  from `origin/main` first (`git checkout -B <branch> origin/main`) rather
  than stacking on stale/merged history, then open a new PR after pushing.
- Commit authorship/attribution follows whatever this Claude Code session's
  own instructions specify (it can vary by environment) — don't hardcode a
  specific author line here.

## Deploy pipeline, Firestore config, Gmail/IMAP, Gemini integration gotchas

This section already has a few real ones. Check
`foodie`'s `CLAUDE.md` for the shape more of these tend to take (Cloud Run +
Cloud Build specifics, Firestore composite-index gotchas, Gemini
prompt/response quirks) before re-deriving something Schoolio is likely to
hit the same way.

- **A Firestore date field read back through a raw `doc.get(field)`/
  `doc.data` map comes back as `com.google.cloud.Timestamp`, not
  `java.util.Date`** — only the typed `DocumentSnapshot.getDate(fieldName)`
  accessor (`MessageStore.kt`'s `receivedAt`) does that conversion for you.
  `ScanStateStore.kt`'s `bySender` array-of-maps used to hard-cast each
  entry's `seenAt` to `Date` after reading the whole array via the raw
  `List<Map<String, Any?>>` cast - worked in every test (`FakeScanStateRepository`
  is a plain in-memory map, so it never touches a real Firestore response
  shape) but threw a `ClassCastException` in production on every watermark
  read once a sender had ever been scanned. `UserStore.kt`'s `createdAt`
  already got this right (cast to `Timestamp`, then
  `Instant.ofEpochSecond(it.seconds, it.nanos.toLong())`) - `ScanStateStore.kt`
  now does the same (`toFirestoreInstant()`). If a future field is read via a
  raw map/array cast instead of a typed accessor, assume it needs the same
  treatment rather than `as Date`.
- **Local `./gradlew run` crashes at startup without Google Cloud
  credentials.** `FirestoreUserStore`'s Firestore client is a default
  constructor argument (`module()`'s `userStore` param), so it's built
  eagerly the moment `main()`/`module()` runs with no override — and
  without `gcloud auth application-default login` (or
  `GOOGLE_APPLICATION_CREDENTIALS` pointing at a service account key) it
  fails with an NPE deep inside `google-cloud-firestore`
  (`DatabaseRootName` hitting a null project id), not a clear "no
  credentials" error. Automated tests never hit this — `testModule()`
  always passes `FakeUserRepository`, so the real default is never
  evaluated.
- **SUPERSEDED, kept for the reasoning: `gmail.readonly` (the original
  Gmail-access approach) is a Google "restricted" OAuth scope, and that's
  why Gmail access moved to IMAP + app passwords instead (see
  `context.md`'s Gmail integration section for the full story).** This app
  no longer requests `gmail.readonly` or any Gmail OAuth scope at all — if
  you're debugging an "unverified app" warning or a test-user-list issue on
  sign-in today, something has regressed, because plain identity scopes
  (`openid`/`email`/`profile`) shouldn't trigger either. The real numbers
  that drove the switch: restricted scopes need an annual CASA Tier 2
  security assessment (~$500–$1,000/year, recurring) to leave "Testing"
  status, and refresh tokens for a Testing-status app reportedly expire
  after 7 days regardless — both real costs for a two-person app that
  IMAP + app passwords avoid entirely (app passwords don't expire on a
  timer, and aren't OAuth at all).
- **Jakarta Mail's `Store`/`Folder`/`Message` API (`ImapGmailClient`,
  `GmailClient.kt`) is blocking I/O, not coroutine-friendly** — wrapped in
  `withContext(Dispatchers.IO)`. Don't call it directly from a
  non-IO-dispatched coroutine context without that wrapper, or it'll block
  whatever thread pool is running the request.
- **`ImapGmailClientTest` uses GreenMail over plain `imap`, not `imaps`,
  against `ImapGmailClient`'s real code** (not a hand-rolled fake) —
  `host`/`port`/`protocol` are constructor params specifically so tests can
  point at GreenMail's in-process fake server. Plain `imap` (not `imaps`)
  is deliberate: GreenMail's IMAPS uses a self-signed cert Jakarta Mail
  won't trust by default, and that's not what these tests are verifying.
  The real `ImapGmailClient()` default (`imap.gmail.com:993`, `imaps`) is
  unaffected — only test construction passes different values.
- **Changing `GMAIL_APP_PASSWORD_KEY` makes every already-stored app
  password undecryptable.** `AppPasswordCipher` derives the AES key from
  this one env var - rotate it and every existing encrypted
  `gmailAppPassword` in Firestore silently stops decrypting (falls back to
  `null` via `UserStore.kt`'s `runCatching`, not a crash - see that gotcha
  below). Not a migration path, just "both accounts have to re-enter their
  app password via `/inbox/connect-gmail` after a key rotation" - fine for
  two people, worth knowing before treating this env var as freely
  changeable the way `SESSION_SECRET` effectively is (rotating that only
  invalidates sessions, which just means signing in again - cheaper than
  re-generating a Google app password).
- **schoolio needs its own OAuth 2.0 Client ID**, distinct from `foodie`'s,
  even though both share the `foodie-503510` GCP project/consent screen —
  a Client ID's redirect URIs are specific to one app.
- **FreeMarker's `??` (existence) built-in tests whether a variable is
  *defined*, not whether it's *true* - checking a real `Boolean` model
  value with `??` is very likely a bug, not just a style choice.**
  `Application.kt`'s `GET /` handler always puts `"authError"` in
  `splash.ftl`'s model as an actual `Boolean` (`queryParameters["authError"]
  != null`) - present whether the query param was there or not. `splash.ftl`
  used to gate its "Couldn't sign you in" banner on `authError??`, which is
  always true once a variable is merely *present* - so the banner rendered
  on every plain visit to `/`, signed out, whether or not anyone had ever
  attempted (or failed) a sign-in. Hit for real, not just theoretical - not
  caught by `testSplashShowsSignInWhenSignedOut` because that test never
  asserted the banner's *absence*. Fixed by reading the actual value with
  the default-value operator instead (`authError!false`) -
  `testSplashHidesErrorBannerWithNoAuthErrorParam` in `AuthTest.kt` is the
  regression test. If a future template checks a Boolean/String model value
  with `??`, assume it has the same bug rather than trusting it means what
  it looks like it means.
  - Separately: a stray `?authError=1` can land in the URL even after a
    real, successful sign-in - a duplicate `/auth/google/callback` request
    (Google's own internal redirect chain when the browser already has an
    active Google session can fire the callback more than once) tries to
    reuse an already-consumed authorization code on the second hit; that
    one fails and redirects to `/?authError=1`, even though the *first* hit
    already completed sign-in successfully. This one *is* handled, but not
    by anything in `splash.ftl` - `currentUser` is never even in this
    template's model (see its own comment) because `Application.kt`'s
    `GET /` handler redirects a signed-in visitor straight to `/inbox`
    *before* ever rendering `splash.ftl`, so a stale `authError=1` on an
    already-signed-in request never reaches the template at all. An earlier
    version of this gotcha entry credited a `currentUser??` check inside
    `splash.ftl` for this instead - that check was always a no-op (same
    `??`-on-a-sometimes-false-value mistake as above, compounded by
    `currentUser` never being defined there in the first place) and has
    since been removed. See
    `testSuccessfulSignInHidesErrorBannerEvenWithStaleAuthErrorParam` in
    `AuthTest.kt` for that regression test.
- **Gemini model names churn on Google's release schedule, same as
  `foodie`** — 1.5 and 2.0 Flash are both already retired as of mid-2026,
  and this project's own first pass at `GeminiClient.kt` shipped with
  `gemini-2.5-flash` (copied from general knowledge, not checked against
  `foodie`) before being corrected to match `foodie`'s actual
  `gemini-3.6-flash`. If `/inbox`'s Gemini call starts 404ing, check
  `foodie`'s `RecipeParser.kt`/`GroceryItemParser.kt`/etc. for whatever
  model they've since moved to (or
  https://ai.google.dev/gemini-api/docs/models directly) rather than
  guessing — `foodie` hits this churn more often (four call sites) and its
  `.claude/context.md`/`CLAUDE.md` are kept current with the actual model
  in use.
- **Gemini sometimes wraps its JSON response in a ` ```json ... ``` `
  code fence even with `responseMimeType: application/json` set** — same
  quirk `foodie`'s `RecipeParser.kt` works around. `RestGeminiClient.extract`
  strips it (`stripJsonFence`) before decoding; don't remove that without
  re-verifying against a live response first.
- **Google's CalDAV endpoint (`apidata.googleusercontent.com/caldav/v2`)
  rejects Basic Auth/app passwords outright (401), unlike IMAP.** The first
  attempt at Calendar access assumed the same app-password mechanism that
  works for Gmail IMAP would also work for CalDAV (they're both "legacy
  protocol access" per Google's own framing) - that assumption was wrong,
  verified against a live account with a flat, content-free `401
  Unauthorized`/`<D:error/>` response, not a bug in the request-building.
  Confirmed real only because `CalDavCalendarClient.fetchEvents` originally
  didn't check the response status at all - a non-2xx response still has a
  body, and the VEVENT parser just found no `<calendar-data>` in it and
  silently returned an empty list, indistinguishable from "no upcoming
  events." **If a future third-party integration silently returns nothing
  useful, check the response status before assuming the data itself is
  just empty** - this bit calendar hard enough that it's worth checking
  early rather than last. Calendar access now goes through a shared Google
  Cloud service account instead (`GoogleCalendarApiClient`, real Calendar
  API v3 - see `context.md`'s Calendar pull section) - service-account
  credentials aren't subject to the same Basic-Auth restriction, and also
  sidestep the OAuth-consent-screen "sensitive scope" verification
  questions entirely, since that machinery is about consumer "Sign in with
  Google" flows, not machine identities.
- **A calendar event's `description` can carry an entire email
  confidentiality disclaimer, not just the actual event content.** Hit for
  real on a live account the day Calendar pull went live: a school district
  creates its calendar invites by forwarding/pasting an email, and that
  email's org-wide bilingual (English/French) legal footer came along with
  it into the Calendar API's `description` field - genuinely ugly rendered
  straight into `/inbox`'s action-item list. `CalendarEvent.description` is
  cleaned at the source (`GoogleCalendarApiClient`'s
  `stripDisclaimerFooter`, matched against a couple of known marker
  phrases, e.g. "this e-mail message" / "le présent message électronique")
  before it ever reaches `ActionItem` - if a different district's
  disclaimer wording shows up ugly again, add its marker phrase there
  rather than solving this generically (there's no HTML/boilerplate parser
  here, same "hand-rolled just far enough" call as GmailClient's own crude
  HTML-tag-strip fallback).
- **`sw.js`'s `CACHE_NAME` must be bumped whenever any file in its
  `STATIC_ASSETS` list changes** (`css/base.css`, `app.js`, `manifest.json`,
  `logo.svg`) — same requirement as `foodie`'s service worker, for the same
  reason: bumping the version string is what changes `sw.js`'s own bytes,
  which is what makes browsers notice the update, install a new worker, and
  repopulate the cache. Editing a cached file's contents without bumping it
  leaves every browser that already installed the service worker serving
  the stale cached version indefinitely.
