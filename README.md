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
- **Auth**: Google sign-in, implemented — identity only (gated to an
  allowlist of two accounts). Gmail access is separate: an "app password"
  entered once per account, not part of the Google login itself (see
  Stack below for why).

## Stack (decided)

- **Backend**: Kotlin + Ktor.
- **Storage**: Firestore.
- **No framework, backend or frontend** — plain Ktor (no Spring/Micronaut),
  server-rendered HTML via template files (no React/Vue/etc.), plain JS/CSS.
- **Email access: IMAP + Gmail app passwords, not the Gmail API/OAuth.**
  Originally built the other way (Gmail REST API with an OAuth
  `gmail.readonly` scope) and got it fully working — but that scope is
  Google-"restricted," meaning a real, recurring cost (~$500–$1,000/year
  for a required security assessment) to leave OAuth's "Testing" mode, and
  even while staying in Testing, Gmail refresh tokens reportedly expire
  every 7 days. Not worth either for two people. IMAP with a per-account
  app password (`myaccount.google.com/apppasswords`) sidesteps all of
  that entirely — no OAuth scope, no expiry.

See `.claude/context.md` for the reasoning and for architecture details as
they firm up.

## Open questions / not yet decided

- Whether calendar integration targets Google Calendar directly or an
  in-app calendar with optional export/sync.
- How much human review happens between AI extraction and calendar
  creation (auto-create vs. confirm-first).

## Status

The main flow works end to end: Google sign-in (gated to an allowlist of
two accounts, identity only), a separate "connect Gmail" step (app
password, entered once per account on `/inbox`), then `/inbox` scans the
last N weeks of email from a configured sender list only (never the whole
inbox — both editable in a form right on the page) via IMAP, and runs each
match through Gemini to show a summary and action items — with dates and
times when the email states them. Nothing about the scan is persisted
beyond the sender list/lookback setting and each account's app password:
every visit to `/inbox` re-pulls from Gmail and re-runs Gemini fresh, with
no caching or dedup of already-seen messages yet. No calendar view/export
yet, and no human-review step before extraction is shown (there's nothing
to auto-create yet, so nothing to review).
