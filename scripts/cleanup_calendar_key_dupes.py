#!/usr/bin/env python3
"""Clean up calendar ActionItem duplicates left behind by the iCalUID key
switch (see .claude/context.md's "Dedupe calendar events invited between
household members" entry).

Background: CalendarEvent.uid used to be Google Calendar API's per-calendar
`id`, so an event invited between both household members' calendars got a
separate ActionItem per calendar (each keyed "calendar-<id>"). The fix
switched to `iCalUID` (identical across every attendee's copy), keyed
"calendar-<iCalUID>". That's a *different* document id than before, so it
creates a new doc instead of updating the old one - the old "calendar-<id>"
doc is now an orphan sitting next to the new, correct one.

This script finds those orphans and deletes them. It tells old-keyed docs
apart from new-keyed ones by shape, not by tracking actual migrations:
Google's `iCalUID` always contains "@" (e.g. "<token>@google.com"); the old
per-calendar `id` never does. It only ever considers documents in the
`actionItems` collection that have a `sourceCalendarEventId` set (i.e.
calendar-derived items) - email- and photo-import-derived items are left
untouched.

Matching an old doc to its replacement is done on (title, date) - exact
string equality, same fields ActionItemStore already stores. A match found
for exactly one new-keyed doc is deleted; zero or multiple matches are left
alone and reported, since guessing wrong here is a real data loss, not just
a cosmetic dupe. If the old doc was dismissed and its replacement isn't,
the replacement is marked dismissed too, carrying the household's decision
forward - the same thing a normal pull would have done had the id not
changed out from under it.

Usage (from Cloud Shell):
    gcloud config set project foodie-503510        # if not already
    gcloud auth application-default login           # one-time, for ADC
    pip install --user google-cloud-firestore
    python3 scripts/cleanup_calendar_key_dupes.py                # dry run
    python3 scripts/cleanup_calendar_key_dupes.py --apply         # deletes
    python3 scripts/cleanup_calendar_key_dupes.py --apply --yes   # no prompt

Flags:
    --project   GCP project id (default: whatever ADC/gcloud resolves)
    --database  Firestore database id (default: schoolio, matching
                Application.kt's FIRESTORE_DATABASE_ID default)
    --apply     Actually delete - omitted means dry run (report only)
    --yes       Skip the confirmation prompt before deleting
"""

import argparse
import sys

from google.cloud import firestore

COLLECTION = "actionItems"


# DocumentSnapshot.get(field) is not dict.get() - it raises KeyError for a
# field that's genuinely absent from the stored document (e.g. an ActionItem
# doc written before sourceCalendarEventId existed as a field at all, not
# just one stored as null), rather than returning None. Wrapping every doc
# in to_dict() once up front sidesteps that everywhere below, with plain
# dict.get() semantics for every field this script reads.
class Doc:
    __slots__ = ("id", "reference", "data")

    def __init__(self, snapshot):
        self.id = snapshot.id
        self.reference = snapshot.reference
        self.data = snapshot.to_dict() or {}

    def get(self, key):
        return self.data.get(key)


def is_new_key(source_calendar_event_id: str) -> bool:
    return "@" in source_calendar_event_id


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--project", default=None, help="GCP project id (default: ADC default)")
    parser.add_argument("--database", default="schoolio", help="Firestore database id (default: schoolio)")
    parser.add_argument("--apply", action="store_true", help="Actually delete - omit for a dry run")
    parser.add_argument("--yes", action="store_true", help="Skip the confirmation prompt")
    args = parser.parse_args()

    db = firestore.Client(project=args.project, database=args.database)
    docs = [Doc(s) for s in db.collection(COLLECTION).stream()]

    calendar_docs = [d for d in docs if d.get("sourceCalendarEventId")]
    old_docs = [d for d in calendar_docs if not is_new_key(d.get("sourceCalendarEventId"))]
    new_docs = [d for d in calendar_docs if is_new_key(d.get("sourceCalendarEventId"))]

    new_by_key = {}
    for d in new_docs:
        key = (d.get("title"), d.get("date"))
        new_by_key.setdefault(key, []).append(d)

    to_delete = []  # (old_doc, new_doc)
    skipped_no_match = []
    skipped_ambiguous = []

    for old in old_docs:
        key = (old.get("title"), old.get("date"))
        matches = new_by_key.get(key, [])
        if len(matches) == 1:
            to_delete.append((old, matches[0]))
        elif len(matches) == 0:
            skipped_no_match.append(old)
        else:
            skipped_ambiguous.append((old, matches))

    print(f"Found {len(calendar_docs)} calendar-derived action item(s): "
          f"{len(old_docs)} under the old key, {len(new_docs)} under the new key.\n")

    if to_delete:
        print(f"Will delete {len(to_delete)} stale old-key duplicate(s):")
        for old, new in to_delete:
            carry_dismissed = old.get("dismissed") and not new.get("dismissed")
            note = "  (will also mark the replacement dismissed)" if carry_dismissed else ""
            print(f"  DELETE {old.id!r}  ->  superseded by {new.id!r}"
                  f"  [{old.get('title')!r} / {old.get('date')}]{note}")
    else:
        print("No confidently-matched duplicates found.")

    if skipped_no_match:
        print(f"\n{len(skipped_no_match)} old-key item(s) have no matching new-key replacement yet "
              f"(the fixed pull hasn't picked them up, or the event was cancelled) - left alone:")
        for old in skipped_no_match:
            print(f"  {old.id!r}  [{old.get('title')!r} / {old.get('date')}]")

    if skipped_ambiguous:
        print(f"\n{len(skipped_ambiguous)} old-key item(s) matched more than one new-key doc - "
              f"left alone, resolve manually:")
        for old, matches in skipped_ambiguous:
            match_ids = ", ".join(repr(m.id) for m in matches)
            print(f"  {old.id!r}  [{old.get('title')!r} / {old.get('date')}]  matches: {match_ids}")

    if not to_delete:
        return 0

    if not args.apply:
        print("\nDry run only - nothing was deleted. Re-run with --apply to actually delete these.")
        return 0

    if not args.yes:
        answer = input(f"\nDelete these {len(to_delete)} document(s)? [y/N] ").strip().lower()
        if answer != "y":
            print("Aborted - nothing was deleted.")
            return 1

    batch = db.batch()
    for old, new in to_delete:
        if old.get("dismissed") and not new.get("dismissed"):
            batch.update(new.reference, {"dismissed": True})
        batch.delete(old.reference)
    batch.commit()
    print(f"\nDeleted {len(to_delete)} document(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
