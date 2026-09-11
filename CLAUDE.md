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

## Deploy pipeline, Firestore config, Gemini integration gotchas

Not deployed yet, but this section already has a few real ones. Check
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
- **`gmail.readonly` is a Google "restricted" OAuth scope.** With the
  consent screen left in "Testing" publish status (fine indefinitely for
  a fixed 2-user app — no CASA security assessment/formal verification
  needed), sign-in shows a "Google hasn't verified this app" warning that
  has to be clicked through (Advanced → Go to Schoolio), and only accounts
  added as test users on that consent screen can sign in at all — separate
  from, and in addition to, this app's own `ALLOWED_EMAILS` gate.
- **`extraAuthParameters = access_type=offline, prompt=consent`** on the
  OAuth provider (`GoogleAuthFlow.kt`) is what makes Google actually return
  a `refresh_token` on every sign-in, not just the first — without
  `access_type=offline` no refresh token comes back at all; without
  `prompt=consent` one only comes back the very first time an account
  consents. Forces the consent screen every sign-in as a side effect,
  which is an acceptable trade for two accounts that sign in rarely.
- **schoolio needs its own OAuth 2.0 Client ID**, distinct from `foodie`'s,
  even though both share the `foodie-503510` GCP project/consent screen —
  a Client ID's redirect URIs are specific to one app.
