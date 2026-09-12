# Schoolio

A small app to keep on top of school-related email for my kids — pull the
signal out of the inbox noise and put it somewhere useful.

## Problem

School communication (permission slips, field trips, schedule changes,
fundraisers, deadlines) mostly arrives as email. It's easy to miss or forget
things buried in a newsletter-style message. This app is meant to watch for
that mail, extract what actually matters, and surface it as calendar-ready
items — shared with my wife, not just me.

## Scope (v1 ideas)

- **Email ingestion**: pull mail from my inbox, either periodically (polling)
  or on-demand when the app is opened.
- **Sender filtering**: only process email from known school senders
  (teacher, school office, PTA, district newsletter, etc.), scanning only
  the last N weeks of mail rather than the whole inbox — both configurable
  right on the `/inbox` page, shared between both accounts (stored in
  Firestore, not per-user).
- **AI extraction**: send filtered emails to Gemini to pull out a summary
  and action items — including dates and times when the email states one.
- **Calendar organization**: turn extracted items into a calendar view.
- **Calendar invites**: possibly auto-generate calendar invites/events from
  the extracted items.
- **Shared state**: my wife and I both see the same data — a fixed
  two-person allowlist, not an open sign-up system.
- **Auth**: Google sign-in, implemented — requests Gmail read access up
  front at login, gated to an allowlist of two accounts.

## Stack (decided)

- **Backend**: Kotlin + Ktor.
- **Storage**: Firestore.
- **No framework, backend or frontend** — plain Ktor (no Spring/Micronaut),
  server-rendered HTML via template files (no React/Vue/etc.), plain JS/CSS.

See `.claude/context.md` for the reasoning and for architecture details as
they firm up.

## Open questions / not yet decided

- How "periodic" pulling would run (background job vs. purely on-open) —
  the inbox view currently only pulls on-demand, when the page loads.
- Whether calendar integration targets Google Calendar directly or an
  in-app calendar with optional export/sync.
- How much human review happens between AI extraction and calendar
  creation (auto-create vs. confirm-first).

## Status

The main flow works end to end: Google sign-in (gated to an allowlist of
two accounts), then `/inbox` scans the last N weeks of email from a
configured sender list only (never the whole inbox — both editable in a
form right on the page) and runs each match through Gemini to show a
summary and action items — with dates and times when the email states
them. Nothing about the scan is persisted beyond that sender list/lookback
setting itself: every visit to `/inbox` re-pulls from Gmail and re-runs
Gemini fresh, with no caching or dedup of already-seen messages yet. No
calendar view/export yet, and no human-review step before extraction is
shown (there's nothing to auto-create yet, so nothing to review).
