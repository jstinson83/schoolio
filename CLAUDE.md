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
- Feature branches get reused across tasks. If the branch's previous PR has
  already merged, rebuild it from `origin/main` before adding new commits
  (`git checkout -B <branch> origin/main`), rather than stacking on
  stale/merged history.
- Commit authorship/attribution follows whatever this Claude Code session's
  own instructions specify (it can vary by environment) — don't hardcode a
  specific author line here.

## Deploy pipeline, Firestore config, Gemini integration gotchas

Nothing deployed yet — this section fills in the same way `foodie`'s did,
as real infrastructure and integration bugs show up. Check `foodie`'s
`CLAUDE.md` for the shape these sections tend to take (Cloud Run + Cloud
Build specifics, Firestore composite-index gotchas, Gemini prompt/response
quirks) before re-deriving something Schoolio is likely to hit the same way.
