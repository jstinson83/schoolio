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
  Gemini plays in `foodie` for recipe/grocery-item parsing.
- **Auth**: Google sign-in. Likely the same pattern as `foodie`
  (`installGoogleAuth`/Ktor's built-in OAuth2 provider, signed session
  cookie) rather than client-side Google Identity Services, for the same
  reason — simpler if this ends up server-rendered.
- **Shared state**: the app is used by two people (maintainer + spouse)
  seeing the same data — closer to `foodie`'s household concept than to
  per-user-siloed data. Whether it's modeled as a `foodie`-style household
  (join by invite code, etc.) or just a fixed two-person allowlist isn't
  decided yet.

## Not yet decided / open questions

- Email source: Gmail API vs. IMAP.
- How periodic email pulling runs (background job vs. purely on-open).
- How school senders are configured (manual allowlist vs. suggested list).
- Calendar target: push to Google Calendar directly, or maintain an
  in-app calendar with optional export/sync.
- How much human review sits between AI extraction and calendar creation
  (auto-create vs. confirm-first).
- Household/sharing model: fixed two-person allowlist vs. `foodie`-style
  household with invite codes.

## Configuration reference

Nothing deployed yet. Once infra exists, record concrete IDs/regions/config
here (GCP project id, Firestore database id + region, Cloud Run service
name, env var names for secrets) rather than in `CLAUDE.md` — see
`foodie`'s `context.md` for the reference shape to follow.

## Decisions log

- Backend: Ktor. Chosen up front, matching `foodie` — no new backend
  framework to learn/maintain for a second small project.
- DB: Firestore. Same reasoning — one DB technology across both projects.
- No framework layered on Ktor, and no JS framework on the frontend: same
  reasoning as `foodie`'s original choice — the app is small enough not to
  need one. Still server-rendered with template files (FreeMarker) plus
  plain JS/CSS, same as `foodie` — "no framework" isn't "no UI."
