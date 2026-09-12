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
- **A stray `?authError=1` can land in the URL even after a real,
  successful sign-in** - hit in production, not just theoretical. A
  duplicate `/auth/google/callback` request (Google's own internal
  redirect chain when the browser already has an active Google session
  can fire the callback more than once) tries to reuse an
  already-consumed authorization code on the second hit; that one fails
  and redirects to `/?authError=1`, even though the *first* hit already
  completed sign-in successfully. `splash.ftl` used to show the error
  banner purely off `authError??`, independent of whether `currentUser`
  was set - fixed by gating the banner on `authError?? && !(currentUser??)`
  (parenthesized deliberately - FreeMarker's `!`/`??` precedence when
  mixed is easy to get wrong). See `testSuccessfulSignInHidesErrorBanner...`
  in `AuthTest.kt` for the regression test.
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
