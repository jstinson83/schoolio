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

- **Backend**: `backend/` — Kotlin/Ktor, FreeMarker templates
  (`backend/src/main/resources/templates/*.ftl`), static assets under
  `backend/src/main/resources/static/`, same layout as `foodie`. Currently
  just `GET /` (`splash.ftl`) — confirms the deploy pipeline works and
  shows the Cloud Run revision (`K_REVISION` env var) it's running as.
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
