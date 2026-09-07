# Floor Map: the Events Query tab's column dropdowns are unusable until the query is run

**Component:** `stroom-core-client` — `FloorMapQueryPresenter.updateColumnSelections`,
`FloorMapQueryViewImpl.setAvailableColumns`
**Severity:** low. Nothing is lost or broken; the tab is briefly unusable and looks broken, which is
the part that costs time.
**Found:** 2026-09-07, while running test G3 of `docs/floormap-test-protocol.md`. That test could not
be followed as written because of this.
**Status:** diagnosed, not fixed. No data-loss path — see *What this is not*.

---

## Symptom

Open a floor map's **Events Query** tab without running the query. The four column dropdowns —
Entity ID, Location, Location Ref, Type — **display the document's mapping correctly**, but opening
any of them shows an empty list. There is no way to change a mapping.

Pressing **Run** fills all four lists and everything works.

Reported as *"cannot set the dropdowns to any other value — the dropdowns appear to be empty"*,
which is exactly right: the values are there, the choices are not.

## Cause

The lists are populated only from a **query result's** columns.
`FloorMapQueryPresenter.updateColumnSelections` (line 183) reads:

```java
final List<Column> columns = queryEditPresenter.getQueryResultPresenter()
        .getTablePresenter()
        .getCurrentColumns();

if (columns != null && !columns.isEmpty()) {
    ...
    getView().setAvailableColumns(colNames);
```

and every one of its four call sites is downstream of a result arriving — the query edit presenter's
change handler, the result table's update handler, the searching→idle edge, and the tail of `read`,
where `getCurrentColumns()` is still null because nothing has run yet.

So with no result there are no column names, `setAvailableColumns` is never called, and
`populateSelectionBox` never runs. The boxes keep the values `setEventColumns` gave them — see
below — and offer nothing.

## A second, worse defect found from the same test — now fixed

Changing a dropdown **did not mark the document dirty**, so the save icon stayed disabled and the
mapping could not be saved at all.

The tab wires dirty from `FloorMapPresenter`:

```java
registerHandler(eventsQueryPresenter.addChangeHandler(() -> fireDirtyEvent(true)));
```

and `addChangeHandler` delegated entirely to the embedded query editor, which tracks the **query
text**. Nothing was listening to the dropdowns — the view exposed no change signal for them at all.

The consequence was worse than "cannot save": the edit persisted **only** as a passenger on an
unrelated query-text edit, because `write()` reads the dropdowns whenever a save happens for any
reason. So it worked sometimes, which is the hardest version to notice. Otherwise it was silently
lost on the next tab switch, with no dirty marker to warn.

**Pre-existing** — the two string settings this replaced had the same gap, which means those column
settings were never editable on their own either.

**Fixed 2026-09-07:** the view exposes `setColumnChangeHandler`, the presenter fires `ChangeEvent`
from it, and `addChangeHandler` now registers on both sources rather than only the delegate. The
tab's dirty wiring is unchanged — it was already listening for exactly this.

## What this is not

**It is not a data-loss path**, which was the first thing worth ruling out, because `write()` reads
the mapping back off these same dropdowns:

```java
this.currentEventColumns = getView().getEventColumns();
```

If an unpopulated dropdown read back as empty, saving a document from this tab would silently wipe
its mapping and the map would stop drawing entities. It does not:
`BaseSelectionBox.setValue` stores the value in its own field independently of the item model, and
`SelectionBox.clear()` empties only the model's items. `getValue()` returns the stored field. So the
mapping survives a save with the lists empty.

**It is also not new.** The same guard governed the two string settings this replaced; the change
from two dropdowns to four made it more visible, not more likely.

## The fix — and why the obvious two do not work

**Not "seed from the query builder".** That was the first suggestion and it is wrong: the builder
generates the *default* query, so it knows the default aliases only. A hand-edited query's aliases
are not derivable from it.

**Not "seed from `queryTablePreferences.getColumns()`"** either, tempting though it is —
`FloorMapDoc` already stores `eventsQueryTablePreferences`, and `QueryTablePreferences` does hold a
`List<Column>`. But `setPreferredColumns` is called only from `QueryTableColumnsManager`, i.e. when
the *user* shows, hides, moves or renames a results-table column. On a document nobody has fiddled
with, it is empty. Useful as a fallback, not as the fix.

What is left, in increasing order of cost:

1. **Include the mapping's own values in the list.** Whatever the document already names is always
   offered, so the list is never empty and a role can always be re-selected. Does not offer columns
   the mapping does not already mention, so it does not make the tab fully usable — but it removes
   "the list is empty", which is the confusing part. Small.
2. **Say why the list is empty.** An empty-list hint on the control — *"Run the query to list its
   columns"* — in keeping with how the rest of this feature was taught to name the reason rather
   than go quiet. Note `MyDataGrid.setEmptyText(String)` exists for exactly this and
   `SelectionBox` is not a `MyDataGrid`, so it needs its own placeholder; the Layers panel had the
   same problem and the same answer.
3. **Parse the `as "..."` aliases out of the query text.** The only source that is always correct
   without running anything. `BasicTokeniser` is client-visible and already used by
   `FloorMapEventsQueryOrder` to mask quoted strings and comments, so the machinery exists — a scan
   for `as` followed by a quoted string or identifier over the unmasked spans. Real work, and the
   same class of hand-rolled parsing F13 needed, so worth doing deliberately rather than casually.

**Recommend 1 and 2 together.** The list then always contains at least what is mapped, and says why
it contains no more. 3 is the proper fix if someone wants the tab usable before a run.

## Also worth knowing, and not a defect

**A role cannot be pointed at a column the query does not select.** The list only ever offers the
result's own columns plus a blank entry, so a mapping that names a missing column cannot be created
through the UI at all — it can only arise by editing the **query** after the mapping.

That is a good property and worth preserving through any fix: it bounds where that class of fault
comes from. It is also why test G3 had to be rewritten — the reachable fault is *unmapping* a role,
by selecting the blank, not misdirecting it.

## Verification

- Open the Events Query tab on a saved map without running the query: all four dropdowns show the
  document's mapping, and each list contains at least those values.
- Save from that state and reopen: the mapping is unchanged. This is the regression that matters,
  and it passes today — any fix must keep it passing.
- Press Run: the lists gain every column the query selects, plus the blank.
- Select the blank for Entity ID, return to the Map tab: the on-canvas line reports the stage and
  the console names the unset role.
