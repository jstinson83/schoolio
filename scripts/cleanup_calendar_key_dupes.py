#!/usr/bin/env python3
"""Clean up calendar ActionItem duplicates left behind by the iCalUID key
switch (see .claude/context.md's "Dedupe calendar events invited between
household members" entry).

Background: CalendarEvent.uid used to be Google Calendar API's per-calendar
`id`, so an event invited between both household members' calendars got a
separate ActionItem per calendar (each keyed "calendar-<id>"). The fix
switched to `iCalUID` (identical across every attendee's copy), keyed
"calendar-<iCalUID>". That's a *different* document id than before, so it
creates a new doc instead of updating the old one.

IMPORTANT, and the reason the first version of this script only found one
duplicate: pullAndStoreCalendarEvents only ever pulls a forward-looking
window (CALENDAR_LOOKAHEAD_DAYS, currently 7 days), never backward. An
event that's already in the past will *never* get a new iCalUID-keyed doc
to replace its old one - there's nothing to pull it into. So most
duplicates aren't "one old doc, one new doc" pairs at all - they're TWO
OLD-keyed docs for the same event (one per parent's calendar, from before
this fix even existed, back when CalendarEvent.uid was the per-calendar id
and each parent's pull created its own copy). Those two will sit there
forever; nothing about the iCalUID fix ever touches them on its own.

So this script groups ALL calendar-derived ActionItems (old-keyed and
new-keyed alike) by (title, date) and collapses each group with more than
one doc down to a single survivor:
  - If the group contains exactly one new-keyed (iCalUID, contains "@")
    doc, that one survives - it's the one that'll stay in sync on every
    future pull. Every other doc in the group (all old-keyed) is deleted.
  - If the group has no new-keyed doc at all (the common past-event case
    above), one old-keyed doc is kept arbitrarily-but-deterministically
    (lowest document id) and the rest are deleted.
  - If a group contains *more than one* new-keyed doc, it's left alone and
    reported - that means two distinct real events happen to share a title
    and date, and guessing which is "the duplicate" risks deleting a real
    event, not a dupe.
Any doc that was dismissed carries that forward onto the survivor if it
isn't already dismissed there, same as a normal pull would have done had
the id not changed out from under it.

It only ever considers `actionItems` docs with `sourceCalendarEventId` set
(calendar-derived) - email- and photo-import-derived items are untouched.
Matching is exact string equality on (title, date), the same fields
ActionItemStore already stores - if a title was hand-edited or a date
recomputation bug (see CLAUDE.md's Eastern-time gotcha) makes an old doc's
date disagree with its true current value, that pair won't group and won't
be touched; it'll show up in the dry run's "singleton" count for you to
check by hand.

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
    old_count = sum(1 for d in calendar_docs if not is_new_key(d.get("sourceCalendarEventId")))
    new_count = len(calendar_docs) - old_count

    groups = {}
    for d in calendar_docs:
        key = (d.get("title"), d.get("date"))
        groups.setdefault(key, []).append(d)

    singleton_count = sum(1 for members in groups.values() if len(members) == 1)
    to_delete = []  # (deleted_doc, survivor_doc)
    skipped_ambiguous = []  # (key, new_key_members) - more than one new-keyed doc in a group

    for key, members in groups.items():
        if len(members) == 1:
            continue
        new_members = [d for d in members if is_new_key(d.get("sourceCalendarEventId"))]
        if len(new_members) > 1:
            skipped_ambiguous.append((key, new_members))
            continue
        survivor = new_members[0] if new_members else min(members, key=lambda d: d.id)
        for d in members:
            if d.id != survivor.id:
                to_delete.append((d, survivor))

    print(f"Found {len(calendar_docs)} calendar-derived action item(s): "
          f"{old_count} under the old key, {new_count} under the new key.")
    print(f"{singleton_count} title+date group(s) have exactly one doc - nothing to do there.\n")

    if to_delete:
        print(f"Will delete {len(to_delete)} duplicate(s):")
        for dupe, survivor in to_delete:
            carry_dismissed = dupe.get("dismissed") and not survivor.get("dismissed")
            note = "  (will also mark the survivor dismissed)" if carry_dismissed else ""
            print(f"  DELETE {dupe.id!r}  ->  kept {survivor.id!r}"
                  f"  [{dupe.get('title')!r} / {dupe.get('date')}]{note}")
    else:
        print("No confidently-matched duplicates found.")

    if skipped_ambiguous:
        print(f"\n{len(skipped_ambiguous)} title+date group(s) have more than one new-keyed doc - "
              f"left alone entirely, resolve manually (this usually means two distinct real events "
              f"share a title and date, not a duplicate):")
        for (title, date), new_members in skipped_ambiguous:
            match_ids = ", ".join(repr(m.id) for m in new_members)
            print(f"  [{title!r} / {date}]  new-keyed docs: {match_ids}")

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

    # A survivor can have more than one dupe pointing at it (a three-doc
    # group collapsing to one, say) - a WriteBatch rejects a second write
    # queued against the same document reference, so the dismissed-carry
    # update is deduped by survivor id before the batch is built, separate
    # from the one-delete-per-dupe loop below (each dupe is unique to its
    # own group, so those never collide with each other).
    survivors_needing_dismiss = {}
    for dupe, survivor in to_delete:
        if dupe.get("dismissed") and not survivor.get("dismissed"):
            survivors_needing_dismiss[survivor.id] = survivor

    batch = db.batch()
    for survivor in survivors_needing_dismiss.values():
        batch.update(survivor.reference, {"dismissed": True})
    for dupe, _ in to_delete:
        batch.delete(dupe.reference)
    batch.commit()
    print(f"\nDeleted {len(to_delete)} document(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
