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
  (teacher, school office, PTA, district newsletter, etc.).
- **AI extraction**: send filtered emails to Gemini to pull out structured
  data — dates, deadlines, action items, event details.
- **Calendar organization**: turn extracted items into a calendar view.
- **Calendar invites**: possibly auto-generate calendar invites/events from
  the extracted items.
- **Shared state**: my wife and I both see the same data (shared household,
  not per-user silos).
- **Auth**: Google sign-in is sufficient — no need for a separate account
  system.

## Open questions / not yet decided

- Email source: Gmail API vs. IMAP.
- How "periodic" pulling would run (background job vs. purely on-open).
- How senders are configured (manual allowlist vs. some learned/suggested
  list).
- Data store for extracted items and shared household state.
- Whether calendar integration targets Google Calendar directly or an
  in-app calendar with optional export/sync.
- How much human review happens between AI extraction and calendar
  creation (auto-create vs. confirm-first).

## Status

Early planning. No implementation yet.
