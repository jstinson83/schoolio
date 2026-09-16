# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Active task

Periodic sync + push notifications, so the inbox stays fresh without an
explicit pull and both household members get notified of new items:

- [ ] Set up a Cloud Scheduler job hitting `POST /internal/sync` on a cron
      interval, with a header carrying `INTERNAL_SYNC_SECRET` (env var, no
      dev fallback - unset means the route always 401s).
      No separate Cloud Run Job resource — reuse the running service.
- [ ] Add Web Push support: VAPID keypair, `sw.js` `push`/`notificationclick`
      handlers, client-side `PushManager.subscribe()` wired into `app.js`,
      subscription stored per-user in Firestore (same shape as
      `gmailAppPassword`), and a small hand-rolled Web Push sender on the
      backend (no SDK, matching the Gmail/Gemini/Calendar REST convention).
- [ ] Wire the sweep to push a notification when new action items land,
      instead of/alongside just updating Firestore.
- [ ] Note: iOS Safari only receives push if the PWA is actually added to
      the home screen, not just opened in a tab.
