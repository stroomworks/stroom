# Floor Map: the Events Query tab's column dropdowns are unusable until the query is run

**Component:** `stroom-core-client` — `FloorMapQueryPresenter.updateColumnSelections`,
`FloorMapQueryViewImpl.setAvailableColumns`
**Severity:** low. Nothing is lost; the tab is briefly unusable and looks broken, which is the part
that costs time.
**Status:** **open.** A second, worse defect in the same control — a dropdown change did not mark
the document dirty, so the mapping could not be saved on its own — **has been fixed**, and is
described here too because the two present as one symptom: dropdowns you cannot use. Neither is a
data-loss path; see *What this is not*.

> Methods are named rather than given line numbers, which drift. Where a number appears it has been
> checked against the current tree.

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
`FloorMapQueryPresenter.updateColumnSelections` reads:

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

## A second, worse defect in the same control — now fixed

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

**Fixed:** the view exposes `setColumnChangeHandler`, the presenter fires `ChangeEvent` from it, and
`addChangeHandler` now registers on both sources rather than only the delegate. The tab's dirty
wiring is unchanged — it was already listening for exactly this.

## What this is not

**It is not a data-loss path**, and that is worth establishing first, because `write()` reads the
mapping back off these same dropdowns:

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

**Not "seed from the query builder".** The builder generates the *default* query, so it knows the
default aliases only. A hand-edited query's aliases are not derivable from it, and a document whose
query has been edited is exactly the case where the mapping needs changing.

**Not "seed from `queryTablePreferences.getColumns()`"** either, tempting though it is —
`FloorMapDoc` already stores `eventsQueryTablePreferences`, and `QueryTablePreferences` does hold a
`List<Column>`. But that list is only written when something calls `setPreferredColumns`, and on
this path nothing does: eleven of its callers are in `QueryTableColumnsManager`, i.e. the user
showing, hiding, moving or renaming a results-table column; one is the results table's reset button;
and the last is in `AbstractQueryDataPresenter`, which this tab does not use — it reads preferences
through `queryEditPresenter.read(...)` instead. So on a document nobody has manipulated columns on,
the list is empty. Useful as a fallback, not as the fix.

What is left, in increasing order of cost:

1. **Include the mapping's own values in the list.** Whatever the document already names is always
   offered, so the list is never empty and a role can always be re-selected. Does not offer columns
   the mapping does not already mention, so it does not make the tab fully usable — but it removes
   "the list is empty", which is the confusing part. Small.
2. **Say why the list is empty.** An empty-list hint on the control — *"Run the query to list its
   columns"*. Note `MyDataGrid.setEmptyText(String)` exists for exactly this purpose, but
   `SelectionBox` is not a `MyDataGrid`, so it needs its own placeholder rather than that call.
3. **Parse the `as "..."` aliases out of the query text.** The only source that is always correct
   without running anything. `BasicTokeniser` is the one tokeniser available to GWT-compiled code
   and is already used by `FloorMapEventsQueryOrder` to mask quoted strings and comments, so the
   machinery exists — a scan for `as` followed by a quoted string or identifier over the unmasked
   spans. Real work, and hand-rolled parsing of query text has bitten this feature before, so worth
   doing deliberately rather than casually.

**Recommend 1 and 2 together.** The list then always contains at least what is mapped, and says why
it contains no more. 3 is the proper fix if someone wants the tab usable before a run.

## Also worth knowing, and not a defect

**A role cannot be pointed at a column the query does not select.** The list only ever offers the
result's own columns plus a blank entry, so a mapping that names a missing column cannot be created
through the UI at all — it can only arise by editing the **query** after the mapping.

That is a good property and worth preserving through any fix: it bounds where that class of fault
comes from. It also means the only fault reachable through the UI is *unmapping* a role, by
selecting the blank — not misdirecting it.

## Verification

- Open the Events Query tab on a saved map without running the query: all four dropdowns show the
  document's mapping, and each list contains at least those values.
- Save from that state and reopen: the mapping is unchanged. This is the regression that matters,
  and it passes today — any fix must keep it passing.
- Press Run: the lists gain every column the query selects, plus the blank.
- Select the blank for Entity ID, return to the Map tab: the on-canvas line reports the stage and
  the console names the unset role.
