# An abandoned dialog drag leaves a glass pane that kills the whole UI

**Component:** `stroom-core-client-widget` — `stroom.widget.popup.client.view`
**Severity:** High. Every modal dialog in Stroom is affected, the entire UI becomes
unresponsive, and nothing in the normal diagnostic toolkit points at the cause.
**Found:** 2026-09-04, while setting up floor map test data. Not floor-map specific.
**Status:** fixed locally on `enterprise-floormapping-code-review-b`; raise upstream.

---

## Symptoms

The whole Stroom UI stops responding — explorer tree, tabs, toolbar, everything. Other browser
tabs are unaffected.

What makes this expensive to diagnose is that **every instrument reads normal**:

| Check | Result | What it wrongly suggests |
|---|---|---|
| Browser console | **nothing at all** | no error, no runaway logging |
| DevTools → Performance | **no activity** | nothing is executing |
| Server | responds instantly to queries | not a backend stall |
| Network | nothing pending | nothing is being waited on |
| Other tabs | fine | not the browser or the machine |

An idle CPU with a live page and no pending requests is the signature, and it is easy to read as
"the app has died". It has not. It is running perfectly and **cannot receive input**, because a
transparent element covers the viewport.

## Cause

`AbstractPopupPanel` owns a drag glass — a full-viewport div used during a drag to capture the
mouse over iframes:

```java
private final Glass dragGlass = new Glass("popupPanel-dragGlass", "popupPanel-dragGlassVisible");
```

`Dialog` and `ResizableDialog` each drive it from exactly two places:

```java
beginDragging(MouseDownEvent)   ->  getDragGlass().show()    // caption or resize handle
endDragging(MouseUpEvent)       ->  getDragGlass().hide()    // the ONLY hide
```

So the glass comes off **only on a mouse-up the popup actually receives**. Three routes avoid that:

1. **The button is released outside the browser window.** The browser delivers no event at all, so
   no listener can fire. This is the easiest to hit — drag a dialog by its title bar toward the
   edge of the screen and let go.
2. **Focus is lost mid-drag** (alt-tab, a system dialog), so the release goes elsewhere.
3. **The popup is closed while the button is still down**, so it is unloaded before any mouse-up.

The orphan then sits on the body for the life of the page:

```html
<div class="popupPanel-dragGlass popupPanel-dragGlassVisible"
     style="left: 0px; top: 0px; width: 1527px; height: 1033px;"><div class="marker"></div></div>
```

`dragging` is also left `true`, which is its own problem: `ResizableDialog.onBrowserEvent` forwards
*every* mouse event in the document to the dialog while that flag is set.

## Reproduction

1. Open any modal dialog — a document's Settings, or "Initialise New Floor Map".
2. Press and hold on its title bar and drag toward the edge of the screen.
3. Release the button **outside the browser window**.
4. Move the mouse back over the page and click anything.

The UI is dead. Console and profiler are both silent.

## Recovery without a fix

From the DevTools console — it still works, because the page is idle rather than blocked:

```js
document.querySelectorAll('.popupPanel-dragGlassVisible')
        .forEach(e => e.classList.remove('popupPanel-dragGlassVisible'));
```

Or find the element at the end of `<body>` in the Elements panel and delete the node.

## Fix as applied

Three hunks, all marked `STROOMWORKS-LOCAL`.

**`AbstractPopupPanel`**

- `abandonDrag()` — hides the glass; idempotent, because it is reachable from several directions.
- `onUnload()` — calls it. Covers route 3 deterministically: a popup torn down mid-drag cannot
  leave the glass behind.
- `isButtonHeld(NativeEvent)` — a JSNI read of `buttons`, the held-button bitmask. Note this is
  **not** `button`, which identifies the button of a press or release and means nothing on a move.
  Defaults to `true` where `buttons` is undefined, so an old browser keeps today's behaviour rather
  than gaining a new failure.

**`Dialog` and `ResizableDialog`** (identical drag code, so both need it)

- Override `abandonDrag()` to clear `dragging` and release the mouse capture before delegating, so
  an abandoned drag cannot leave the dialog thinking it is still being dragged.
- At the top of `continueDragging`, if a drag is active and no button is held, abandon it. This is
  what covers routes 1 and 2: a move with nothing held is the first evidence the page ever gets
  that the release happened somewhere it could not see.

## Why the fix is shaped that way

The instinct is to hide the glass on a document-level mouse-up. That does not work for the common
case: for a release outside the browser window **there is no event to listen for**. Hence the
mousemove check, which is the earliest observable moment.

`onUnload` alone would not have helped here either — the dialog in the observed incident closed
cleanly on OK, minutes after the drag was abandoned, and by then the glass had been up the whole
time. Both halves are needed: one for the deterministic case, one for the undetectable one.

## Residual case

If the release happens outside the window and the pointer **never returns to the page**, nothing
fires and the glass stays. Harmless in practice — the user cannot be blocked by a glass they are
not trying to click through, and the first move back over the page clears it. Closing the dialog
also clears it via `onUnload`.

## Suggested upstream shape

The same, or `Glass` could refuse to outlive its owner. A cheaper variant worth considering: give
`Glass.show()` a watchdog that hides it if no corresponding `hide()` arrives within a few seconds
of the last mouse activity. That fixes every route at once without needing button-state reads, at
the cost of a timer.
