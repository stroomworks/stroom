#!/usr/bin/env python3
"""Generate Floor Map test data for docs/floormap-test-plan.md.

Every timestamp is relative to the moment of generation, because two of the fixtures depend on it:
the baseline horizon is six hours back from the *selected* time, and the timeline opens on the
span of the data. So the output goes stale - regenerate before a test session rather than reusing
last week's files.

Writes CSV for the CSV_WITH_HEADER data splitter in this directory. The XSLTs assemble the JSON
value, so no field here contains a double quote and only `location` contains commas (handled by
the splitter's container chars).

Usage:
    ./generate.py                     # all fixtures, into ./out
    ./generate.py --out-dir /tmp/fm   # elsewhere
    ./generate.py --bulk-events 24000 # size the over-budget fixture
"""

import argparse
import json
import os
import random
from datetime import datetime, timedelta, timezone

FACTS_MAP = "floor_map_facts"
EVENTS_MAP = "floor_map_events"
BULK_MAP = "floor_map_events_bulk"

FACTS_HEADER = "map,key,time,type,name,x,y,img,matrix,geometry,fill,opacity"
EVENTS_HEADER = "map,key,time,location,type,status,message"

IDENTITY = "1 0 0 1 0 0"

# Desks the events reference by key, so moving one in the Editor moves its occupants.
DESKS = [
    ("desk-101", "Desk 101", 80, 90),
    ("desk-102", "Desk 102", 200, 90),
    ("desk-103", "Desk 103", 320, 90),
    ("desk-104", "Desk 104", 80, 240),
    ("desk-105", "Desk 105", 200, 240),
    ("desk-106", "Desk 106", 320, 240),
]


def iso(dt):
    """Stroom-parseable instant, milliseconds, explicit Z."""
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") \
        + "%03dZ" % (dt.microsecond // 1000)


def csv_field(value):
    """Quote only what needs it. Nothing here contains a double quote - assert rather than escape."""
    s = "" if value is None else str(value)
    assert '"' not in s, "unexpected double quote in %r; the XSLT escapes JSON, not CSV" % s
    return '"%s"' % s if ("," in s or "\n" in s) else s


def row(*values):
    return ",".join(csv_field(v) for v in values)


def write(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n")
        for r in rows:
            f.write(r + "\n")
    print("  %-28s %6d rows" % (os.path.basename(path), len(rows)))


# ---------------------------------------------------------------------------
# Facts - the floor plan
# ---------------------------------------------------------------------------

def facts(now):
    """The floor plan, backdated well before any event so it is always in scope.

    Includes one desk that MOVES partway through the event window. That is what makes A5/A6
    (scrub backwards/forwards) and D2 meaningful: the correct picture at T depends on T, so a
    stale facts snapshot is visible rather than merely suspected.
    """
    laid_out = now - timedelta(days=2)
    moved_at = now - timedelta(minutes=15)
    rows = []

    # Background. Placed by tm-world-to-map like every other fact.
    rows.append(row(FACTS_MAP, "bg-ground", iso(laid_out), "background", "Ground Floor",
                    "", "", "ground-floor.png", IDENTITY, "", "", ""))

    for key, name, x, y in DESKS:
        rows.append(row(FACTS_MAP, key, iso(laid_out), "desk", name,
                        x, y, "", IDENTITY, "", "", ""))

    # desk-106 moves. Its occupant should move with it, retroactively, because the resolver
    # re-places references against the facts at the selected instant.
    rows.append(row(FACTS_MAP, "desk-106", iso(moved_at), "desk", "Desk 106 (moved)",
                    460, 240, "", IDENTITY, "", "", ""))

    # Two areas, for the "n of m" group counts and containment highlighting.
    rows.append(row(FACTS_MAP, "area-north", iso(laid_out), "area", "North Wing",
                    "", "", "", IDENTITY, "40 50 400 50 400 160 40 160", "#1e88e5", "0.25"))
    rows.append(row(FACTS_MAP, "area-south", iso(laid_out), "area", "South Wing",
                    "", "", "", IDENTITY, "40 200 520 200 520 310 40 310", "#43a047", "0.25"))
    return rows


# ---------------------------------------------------------------------------
# Events - the entities
# ---------------------------------------------------------------------------

def events(now, span_minutes=240, interval_seconds=120, seed=20260904):
    """The five behaviours the Group A tests need, in one stream.

    Each entity exists to make exactly one test decidable:

      alice   - hops desk to desk throughout. The control: something must move.
      bob     - moves, then stops 5 minutes before now. Idle well past the old 20-second window
                but inside the 6-hour horizon, so A1 asserts he STAYS.
      carol   - one event 7 hours ago and nothing since, i.e. outside the horizon, so A3 asserts
                she DROPS. Backdated deliberately: waiting out a six-hour horizon is not a test.
      dave    - stationary, re-emitting an UNCHANGED location every 5s. These are exactly the
                rows condense collapses, so A4 asserts he survives with condense on.
      forklift-7 - the coordinate form of location rather than a fact key, so both paths are
                exercised.
      ghost   - location names a fact key that does not exist. Silently dropped, by design;
                present so the console message can be recognised when it is not by design.
    """
    rnd = random.Random(seed)
    rows = []
    # Four hours by default: comfortably inside the 6-hour horizon, so a baseline sees all of it,
    # and wide enough to be easy to find on a timeline that opens on +/-24 hours. A 30-minute
    # window left almost the whole timeline empty.
    start = now - timedelta(minutes=span_minutes)
    step = timedelta(seconds=interval_seconds)

    def emit(key, when, location, etype, status="ok", message=""):
        rows.append(row(EVENTS_MAP, key, iso(when), location, etype, status, message))

    # alice - a mover, every 10s for the whole window.
    t = start
    while t <= now:
        emit("alice@example.org", t, rnd.choice(DESKS)[0], "person", "ok", "seen")
        t += step

    # bob - moves for 25 minutes, then goes quiet. A1's subject.
    t = start
    bob_last = now - timedelta(minutes=5)
    while t <= bob_last:
        emit("bob@example.org", t, rnd.choice(DESKS)[0], "person", "ok", "seen")
        t += step

    # carol - a single event beyond the horizon. A3's subject.
    emit("carol@example.org", now - timedelta(hours=7), "desk-103", "person", "ok",
         "last seen before the horizon")

    # dave - parked at desk-105, re-emitting the same value. A4's subject.
    t = start
    while t <= now:
        emit("dave@example.org", t, "desk-105", "person", "ok", "stationary")
        t += step

    # forklift-7 - coordinate form, drifting across the floor.
    t = start
    x = 100.0
    while t <= now:
        emit("forklift-7", t, "B-GND, %.1f, %.1f" % (x, 180.0), "vehicle", "ok", "")
        x = 100.0 + ((x - 100.0 + 12.0) % 400.0)
        t += step

    # ghost - references a fact that does not exist. Expected to be dropped.
    emit("ghost@example.org", now - timedelta(minutes=1), "desk-999-does-not-exist",
         "person", "ok", "unplaceable on purpose")

    rows.sort(key=lambda r: r.split(",")[2])
    return rows


def bulk_events(now, total):
    """Over-budget fixture for A11: more rows inside the 6-hour horizon than the 20 000 cap.

    Spread across six hours and many entities so the truncation is a genuine cap rather than one
    entity's history. 20 000 rows over six hours is 0.93 events/second sustained - which a hundred
    entities emitting once a minute already exceeds, so this is the steady state on a busy store,
    not a contrived case.
    """
    rnd = random.Random(7)
    rows = []
    span = timedelta(hours=6)
    entities = ["bulk-%03d" % i for i in range(60)]
    for i in range(total):
        when = now - span + (span * (i / float(total)))
        rows.append(row(BULK_MAP, rnd.choice(entities), iso(when),
                        rnd.choice(DESKS)[0], "person", "ok", ""))
    rows.sort(key=lambda r: r.split(",")[2])
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", default=os.path.join(os.path.dirname(__file__), "out"))
    ap.add_argument("--span-minutes", type=int, default=240,
                    help="how far back the events reach (default 240, i.e. inside the 6h horizon)")
    ap.add_argument("--interval-seconds", type=int, default=120,
                    help="gap between one entity's events (default 120)")
    ap.add_argument("--bulk-events", type=int, default=24000,
                    help="rows in the over-budget fixture (default 24000, cap is 20000)")
    args = ap.parse_args()

    now = datetime.now(timezone.utc).replace(microsecond=0)
    os.makedirs(args.out_dir, exist_ok=True)
    print("Generated at %s (UTC). Regenerate before testing - the horizon is relative." % iso(now))

    write(os.path.join(args.out_dir, "facts.csv"), FACTS_HEADER, facts(now))
    write(os.path.join(args.out_dir, "events.csv"), EVENTS_HEADER,
          events(now, args.span_minutes, args.interval_seconds))
    write(os.path.join(args.out_dir, "events-bulk.csv"), EVENTS_HEADER,
          bulk_events(now, args.bulk_events))

    manifest = {
        "generatedAt": iso(now),
        "factsMap": FACTS_MAP,
        "eventsMap": EVENTS_MAP,
        "bulkEventsMap": BULK_MAP,
        "horizonHours": 6,
        "fixtures": {
            "facts.csv": "F-7 floor plan; desk-106 moves 15 min before generation",
            "events.csv": "F-1/F-2/F-3/F-4; alice moves, bob idles 5 min, "
                          "carol is 7 h old, dave is stationary",
            "events-bulk.csv": "F-6 over-budget store for A11",
        },
    }
    with open(os.path.join(args.out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("  manifest.json")


if __name__ == "__main__":
    main()
