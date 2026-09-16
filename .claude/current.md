# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Active task

Periodic sync + push notifications, so the inbox stays fresh without an
explicit pull and both household members get notified of new items:

- [ ] Set up a second Cloud Scheduler job hitting `POST
      /internal/notify-daily` once each morning (a fixed time via
      Scheduler's own `--time-zone`, not an interval - see
      `internalNotifyRoutes`' doc comment) - same `INTERNAL_SYNC_SECRET`
      header the sync job already uses. Maintainer wanted 7am
      America/New_York as the default time; adjust if they want different.
- [ ] Set `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT` on Cloud
      Run - keys already generated (see chat), waiting on the maintainer to
      set them via `--update-env-vars` (not `--set-env-vars` - see
      CLAUDE.md's env-var gotcha).
- [ ] Do a real end-to-end smoke test once the above are live: subscribe
      from an actual phone (Settings page's "Enable notifications"), then
      fire the Scheduler job once manually and confirm a real notification
      arrives - nothing here has been checked against a live push service
      yet.
- [ ] Note: iOS Safari only receives push if the PWA is actually added to
      the home screen, not just opened in a tab.
