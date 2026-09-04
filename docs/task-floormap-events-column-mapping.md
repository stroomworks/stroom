# Give the events query a column mapping instead of magic names

**Component:** `stroom-core-shared` / `stroom-core-client` — `FloorMapDoc`,
`FloorMapEventsQuery`, `FloorMapQueryPresenter.parseRows`, `FloorMapInitPresenter`
**Origin:** the "entity type from `Event Type`" item in `docs/floormap-remediation-plan.md`. The
immediate defect was fixed 2026-09-04 by renaming the alias; this is the structural half.
**Status:** open, no decision taken.

---

## The gap

A floor map has to connect **result column names** to **meanings**. Facts and events do it
completely differently, and only one of them is sound.

**Facts have a schema.** `FloorMapDoc.valueSchema` holds `FloorMapFieldMapping` triples — an
extraction path, a `Role`, and a column alias. The facts query is *generated* from that schema on
every call, and `FloorMapFactTableParser` matches result columns by each role's alias. Query text
and meaning therefore agree **by construction**; neither can drift from the other.

**Events have three ad-hoc bridges.** The query is free-text and stored on the document, and:

| Meaning | How it is found |
|---|---|
| entity identity | `FloorMapDoc.entityIdColumn`, a string setting, matched exactly |
| location | `FloorMapDoc.locationIdColumn`, a string setting, matched exactly |
| entity kind | **a hardcoded literal** — `col.getName().equalsIgnoreCase("type")` |
| status, message | not read at all |

Three different mechanisms for the same kind of relationship, one of them a magic name, and no
guarantee that any of them agrees with the query.

## What that has already cost

- **Every new floor map was born unable to render events** until `db0cd682ee`: the init dialog
  wrote a query aliasing `Entity ID` and `Location ID` and left both settings `null`, so
  `parseRows` matched nothing and returned no entities while the query returned rows perfectly
  well. Fixed by interpolating shared constants into both.
- **Entity type was never read from the data** until 2026-09-04: the query aliased the column
  `Event Type`, the parser looked for `type`, so every entity fell through to an
  `id.contains("@")` heuristic — email-shaped ids became people, everything else an `object`. A
  vehicle could not be styled as a vehicle. It survived because for people the heuristic and the
  data agree.
- **Documents created before each fix are still wrong** and nothing migrates them. There is no
  signal either: the query looks healthy on the Events Query tab while the Map tab looks as though
  playback is off.
- **The two settings need help text** for the same reason — nothing on screen says a value must
  match a column the query selects, by alias.

Every one of those is the same root cause.

## Options

**1 — a third string setting, `entityTypeColumn`.** Consistent with the two that exist and removes
the magic name.
*Against:* grows an unstructured set to three, and answers nothing for `Status`, `Message` or a
display label. It is option 2 done badly.

**2 — a role→alias mapping for events.** *Recommended.* One structured field on the document
mapping each event role (entity id, location, type, status, message, time) to the alias the query
uses for it. Replaces both existing settings and the hardcoded name, keeps the query free-text, and
extends to the columns that are currently unread.
*For:* one concept to learn, one place to document, one place to validate. The Events Query tab
already knows the result's column names, so a role pointing at a name the query does not select
could be flagged **in place** rather than failing silently on another tab.
*Against:* a document-format change, so it needs a migration story — see below.

**3 — generate the events query from a schema, exactly as facts are.** Strongest guarantee: query
and mapping cannot disagree, because one is derived from the other.
*Against:* the facts query is always generated with **no override**, whereas the events query is
deliberately editable — the `sort`-clause handling in `FloorMapEventsQueryOrder` exists precisely
because users customise it. Agreement-by-construction and free text are mutually exclusive, and
editability is the more valuable of the two.

**4 — leave it.** Document the conventions: aliases must be `Entity ID`, `Location ID` and `Type`,
or the settings must be changed to match.
*For:* the immediate defects are fixed, and the scope of what remains is narrow.
*Against:* preserves three mechanisms for one relationship, and the next person to add a column
repeats the same mistake.

## Migration, whichever is chosen

Existing documents must keep working untouched. The safe shape is a fallback chain rather than a
data migration:

1. the mapping, if set
2. else the existing `entityIdColumn` / `locationIdColumn` settings
3. else a column literally named `type` — today's behaviour for kind
4. else the `id.contains("@")` heuristic

**Keep the `@` heuristic as a last resort.** Data carrying no type at all is a real case, and
dropping it would change behaviour for anyone relying on it.

Populate the new mapping in the **init dialog only**, as `db0cd682ee` did for the two column
settings. Existing documents then behave identically until someone opts in, and nothing silently
re-layers a map that people have styled.

## Also worth folding in

- **Help text** for whatever the user ends up editing — already an open item, and this is where it
  belongs.
- **`Status` and `Message` are selected by the default query and never read.** If a mapping exists,
  they become usable — as tooltip or Tracking-panel content — for free.
- **The Map tab never receives `setSeenTypes`**, so discovered types appear in the Editor's Layers
  panel only. Richer event types are more useful once both panels show them; that item is
  separately listed and is small.

## Acceptance criteria

- A map whose events query uses non-default aliases renders entities, with the mapping set and no
  code change.
- A document created before the change renders exactly as it does today, with nothing set.
- A mapping entry naming a column the query does not select is reported **on the tab where it is
  edited**, not by silence on the Map tab.
- An entity whose data carries `type: vehicle` is drawn with the `vehicle` type style and can be
  hidden by the Layers panel.
