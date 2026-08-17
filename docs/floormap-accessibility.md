# Floor Map — accessibility status

**Scope:** the Floor Map feature on branch `enterprise-floor-mapping-accessibility` —
the Map tab (SVG canvas, timeline, Tracking / Groups / Layers dock panels), the Editor
tab (canvas, Fact List, Time List, Layers, dialogs), and the shared
`HistogramWidget` the timeline uses.

**Target:** WCAG 2.2 Level AA.

**Assessed by:** code review of the branch diff against `master`, plus a build-level
verification pass. **A browser pass was added on 2026-08-14** — method and its limits in
§8, findings in §9, the five previously unassessed tabs in §10, revised priorities in §11.
Screen-reader behaviour is still unverified. §2 and §3 are the original code-review
conclusions, left as written; where the browser contradicted them §9 says so, and §1 has
been corrected.

**Contrast fixes from that pass have been applied** — see §12. The ratios in §9.7 are the
before-state; §12 records what changed and what it measures now.

---

## 1. Summary

| Area | Status |
|---|---|
| Timeline transport controls (play, step, settings) | **Good** — confirmed in browser |
| Timeline scrubber (keyboard + ARIA slider) | **Good** — confirmed in browser |
| Speed badge | **Was Fail** (3.93:1 dark, 2.62:1 light) — **fixed and verified in browser**: 6.93/5.75 resting, 5.13 hover (§12.2) |
| Histogram | **Unverified** — no event data on the test document (§8.2) |
| Tabular panels (Tracking, Fact List, Time List, Groups) | **Adequate** — headers split from cells, no roles (§9.9) |
| Layers panel | **Mixed** — labels good, keyboard reorder drops focus (§9.1) |
| Dialogs (Object Edit, Settings, Init, Set Scale, Group Edit, Layer Style) | **Fail** — no dialog role, focus not moved in, labels on wrappers (§9.3, §9.4) |
| Map canvas — text alternative | **Fixed, pending browser check** — summary now also on the focus target (§14.1). Covering only tracked persons is **by design** (§9.8) |
| Map canvas — keyboard operation | **Adequate** — pan/zoom/menu confirmed working; per-entity still no |
| Cluster membership detail | **Gap** — pointer-only |
| Reduced motion | **Good** — implemented and reasoned; not triggerable in test env (§8.1) |
| Colour contrast — text | **Good** — verified in browser; badge and all five tooltip/readout rules now ≥5.75:1 (§12.2, §12.3) |
| Colour contrast — non-text (icons, 1.4.11) | **Was Fail** in light theme (2.34:1, 2.19:1) — **fixed and verified**: 3.50:1, 3.20:1 (§12.1) |
| Colour contrast over user imagery | **Unmanaged** — unchanged |
| Document tab bars | **Fail** — keyboard works, but no tab/tablist roles (§10.4) |
| Events Query / Settings tabs | **Fixed** — Value Schema keyboard-operable and named (§13); Events Query column boxes named, pending browser check (§14.4) |
| Dialogs — Loop Playback checkbox | **Fixed, pending browser check** — name was landing on a wrapper `div` (§14.2) |
| Layers panel — reorder announcement | **Fixed, pending browser check** — panel had no live region at all (§14.3) |
| Assets / Documentation / Permissions tabs | **Fail** — shared-component issues (§10.3) |

The single largest change is that the map is no longer opaque. It was previously an
unlabelled SVG that a screen reader walked as a stream of disconnected captions, and
that a keyboard user could focus but not move. It is now a named image with a
generated summary, a live region that narrates what changes, and keyboard pan/zoom.

The most useful thing already present before this work — and now formally wired in as
the map's text alternative — is the **Tracking panel**: a keyboard-navigable grid with
one row per entity giving Name, Type, containing Area and Id, updating with the
timeline, where selecting a row tracks that entity on the map.

---

## 2. What is accessible

### 2.1 The map canvas

The canvas is exposed as a single image rather than a shape tree. `role="img"` and a
generated `aria-label` sit on the stable container element; the regenerated `<svg>`
inside is `aria-hidden="true"` and `focusable="false"`.

The summary states time, population by type, area count, what is being followed or
selected, and zoom — for example:

> Floor map at 2026-08-12 14:03. 14 persons, 2 vehicles. 3 areas. Following Alice, in
> Meeting Room A. Zoom 150%.

It is rebuilt when the map's *content* changes (events, area membership, selection,
tracking, time, zoom) and deliberately **not** from `redraw()`, which runs once per
animation frame.

`aria-describedby` points at the Tracking grid (Map tab) or the Fact List grid (Editor
tab), so the row-by-row detail behind the summary is discoverable.

A visually-hidden `role="status"` live region announces:

| Event | Announcement |
|---|---|
| Object selected | `«name», in «area» selected` |
| Multiple selected | `«n» objects selected` |
| Selection cleared | `Selection cleared` |
| Tracking started | `Following «name», in «area»` |
| Tracking stopped | `Stopped following` |
| Play / pause | `Playing` / `Paused` |
| Deliberate seek | `Showing «time»` |
| Zoom, view reset | `Zoom «n»%` / `View reset. Zoom «n»%` |

Time is **not** announced during playback. That is deliberate: at several ticks a
second it would drown out everything else in the region. The summary still carries the
current time, so "where am I now?" stays answerable on demand.

Keyboard operation of the view:

| Key | Action |
|---|---|
| Arrows | Pan (Shift = 5×) |
| `+` / `-` (main row or keypad) | Zoom about the viewport centre |
| `0` | Reset to the opening view |
| `Enter` / `Space` | Context menu for the selection (Editor tab, no gesture in progress) |
| `Escape` | Cancel gesture, else clear selection (Editor tab) |

Tab is untouched and always leaves the map in one press — there is no keyboard trap.
Zoom is centred on the viewport rather than the cursor, because a keyboard user has no
cursor. A keyboard pan pauses camera-follow, exactly as a mouse drag does.

The `Enter` context menu is the fix for a real defect: a keyboard `contextmenu`
(Shift+F10) hit-tested the focus panel, found no object id, and could therefore only
ever open the canvas menu. Every per-object action was unreachable without a mouse.

### 2.2 The timeline

The scrubber is a `role="slider"` with `aria-valuemin/max/now/valuetext/label`, and now
has the key bindings the ARIA slider pattern specifies:

| Key | Action |
|---|---|
| Right / Up | Forward one histogram bin |
| Left / Down | Back one histogram bin |
| Page Up / Page Down | ±10 bins |
| Home / End | Range start / end |
| Escape | Dismiss the datetime pill |

Arrow steps route through the same bin-stepping code as the step buttons, so the two
cannot disagree about where "one step" lands. `aria-valuetext` is refreshed on every
path that moves the time — drag, keyboard, playback, external seek — not only on drag.

The datetime pill appears while the bar holds focus, so a sighted keyboard user gets
the readout a dragger gets, and Escape dismisses it without moving focus.

Transport controls (play/pause, step, settings) are real `<button>` elements, named
from `title`, with the app's `.icon-button:focus-visible` ring. The speed badge is a
`role="button"` with `tabindex`, Enter/Space activation, a focus style, and an explicit
`aria-label` carrying purpose *and* value — its visible `×1` would otherwise be the
whole accessible name.

Out-of-range state is announced in words through the timeline's own live region; the
`«` / `»` chevrons are `aria-hidden` because guillemets read as punctuation or not at
all.

Playback never autostarts and pause is always available (WCAG 2.2.2).

### 2.3 Tabular panels

Tracking, Fact List, Time List and Groups all use `MyDataGrid` with
`KeyboardSelectionPolicy.ENABLED` — real table semantics, real column headers,
arrow-key row navigation. These are the feature's strongest accessibility asset and
needed no change.

### 2.4 Layers panel

- Reorder is no longer drag-only. Each row's grip is a real `<button>`; Up/Down arrows
  on a focused grip move the layer, and focus is deliberately handed back to the moved
  row's grip after the list rebuilds, so a second press works without re-tabbing.
- The appearance swatch is a real `<button>` (was a `div` with a click handler).
- Eye, lock, add and swatch buttons all name **which layer** they act on — a panel of
  identically-titled "Edit appearance" buttons tells a screen-reader user nothing.
- Visibility state is carried by icon *and* title text, not opacity alone.
- Enabled-icon opacity floors raised so icons clear 3:1 (0.45 composited to ≈2.96:1).
- Targets raised to 24×24 (grip, swatch, eye, lock, add, Discover).

### 2.5 Dialogs and forms

Every control the feature adds now has an accessible name. Three mechanisms, chosen by
what the row actually contains:

- **`identity`** on the `FormGroup` where the child is a labelable element — produces a
  real `<label for>`, which also makes the visible label click-to-focus. Used for Name,
  Type, Area Fill Opacity.
- **`aria-labelledby` at the label cell** for the `Grid`-based dialogs (Set Scale, Group
  Edit, Layer Style), where the label text lives in a `<td>` that names nothing. Chosen
  over swapping in a real `<label>` so the existing markup and `td:first-child` CSS are
  untouched.
- **`role="group"` + `aria-label`** where the row holds a composite widget
  (`DateTimeBox`, `SelectionBox`), an injected picker view, or two inputs under one
  label. Paired inputs additionally get their own names, so X and Y are
  distinguishable; the redundant "m" suffixes are `aria-hidden`.

The helper carrying this is [`FloorMapAria`](../stroom-core-client/src/main/java/stroom/floormap/client/FloorMapAria.java),
which documents why each case needs what it needs.

The Init dialog's `focus()` also worked only by accident before — it called `focus()` on
a plain `SimplePanel` div, which is a silent no-op, so the dialog opened with focus
still outside it. It now focuses the first real control inside.

### 2.6 Motion and colour

- `@media (prefers-reduced-motion: reduce)` disables the out-of-range chevron's
  indefinite pulse and the decorative transitions. Two CSS comments already *claimed*
  this was handled; no such block existed, so the chevron animated with
  `infinite` regardless — moving content the user could not stop.
- Entity movement and timeline playback are deliberately **not** reduced-motion
  disabled. They are the information the map exists to convey, and both are already
  under user control.
- `--text-color--low-emphasis` was used 14 times and **defined nowhere**, so every
  "low emphasis" label silently fell back to the inherited colour. It now has per-theme
  values (light `#5f6368`, 6.0:1; dark `#9aa0a6`, 6.5:1).
- The out-of-range chevron moved from `#f57c00` (2.7:1 on white — a meaningful non-text
  indicator below the 3:1 floor) to a per-theme variable: `#e65100` light (3.8:1),
  `#ffa726` dark (8.4:1). Its glow uses `currentColor` so it tracks the theme.
- The 7px scrubber track gained a transparent 24px hit area via a pseudo-element, which
  is not its own event target and so leaves the drag maths untouched.
- The histogram's bars now follow the theme. They were painted from two hard-coded
  `rgba()` literals, making them the one part of the timeline that ignored light/dark.
  Because a canvas `fillStyle` takes a colour value — `var()` and `currentColor` mean
  nothing to it — the `--histogram-bar__color` / `--histogram-peak__color` custom
  properties are resolved off the canvas's computed style at draw time, and the
  timeline repaints on `ChangeCurrentPreferencesEvent` (the same pattern the dashboard
  visualisations use) because canvas pixels do not re-style themselves.

---

## 3. Known gaps

Ordered by how much they matter.

### 3.1 Cluster membership is pointer-only

When nearby entities merge into a cluster glyph, the names of its members are shown in
a tooltip driven purely from `mousemove`. There is no focus or keyboard path to it, and
it is not Escape-dismissible (WCAG 1.4.13, 2.1.1).

*Mitigation:* clustering is an optional toggle, and the Tracking grid always lists every
entity individually with its containing area, so the information is available by another
route. *Fix:* give clusters a keyboard traversal, or announce membership on cluster
selection — neither helps until clusters can be reached without a mouse.

### 3.2 No per-entity keyboard traversal on the canvas

Arrow keys pan the view; there is no way to walk from entity to entity on the map
itself. This was a deliberate trade: arrow keys are more valuable for panning, and the
Tracking grid is already a better entity list than anything bolted onto the canvas —
it is navigable, it filters, and selecting a row centres and follows on the map.

*Consequence:* selecting a specific entity requires going via the grid. Acceptable, but
it means the canvas alone is not sufficient for the task.

> **Confirmed 2026-08-17: the Tracking grid is the sanctioned route, for now.** The
> trade-off above is accepted rather than merely observed, so this is a documented design
> decision and not an open gap. Revisit only if the grid stops being a sufficient
> substitute. Note this makes §9.2 — naming the canvas so a keyboard user is told what they
> have landed on — more important rather than less, since the canvas is where they arrive.

### 3.3 Composite widgets cannot take a real `<label for>`

`DateTimeBox` and `SelectionBox` (and `BaseSelectionBox` generally) are composites whose
root is a wrapper `div` with the real `<input>` nested inside, so `FormGroup.setIdentity()`
lands the id where `for` cannot follow. `role="group"` + `aria-label` is a workable
mitigation — the group is announced on entry, then the inner field — but it is not as
good as a real label, which would also make the visible text click-to-focus.

*Proper fix is upstream:* give those widgets a way to forward a label or id to their
inner input. That is an app-wide change, not a Floor Map one, and was left out of scope
here rather than changed on a feature branch.

### 3.4 Icon buttons are 22×22 app-wide

`.icon-button` in `stroom-button.css` sizes every icon button in Stroom at 22×22, under
the WCAG 2.5.8 minimum of 24. Raised locally for the Floor Map's own buttons; the
shared `ButtonPanel` toolbars (Tracking, Fact List, Time List, Groups) still inherit
22×22.

*Proper fix is one central change* to `.icon-button`, which would move every toolbar in
the application and needs a deliberate decision.

### 3.5 App-wide `outline: none`

`stroom.css:48` removes the focus outline from `div`, `span`, `input`, `button` and more.
Every focusable thing therefore needs its focus indicator drawn explicitly. The Floor
Map now does this for the slider, the canvas, the grip and the swatch, and inherits
`.icon-button:focus-visible` for icon buttons — but the underlying rule remains a trap
for anything added later (WCAG 2.4.7).

### 3.6 Colour-only meaning on the canvas

Selection is an orange stroke, containment highlight is green, group highlight is the
group's colour. Selection is now also announced, so it is not *only* visual — but for a
sighted user with a colour vision deficiency these remain hue-only distinctions
(WCAG 1.4.1).

### 3.7 Contrast over user-supplied imagery

Entity glyphs are drawn over an arbitrary uploaded floor plan. Default type colours
(`#607d8b`, and `#1f77b4` for `person`) cannot be guaranteed to clear 3:1 against
whatever is underneath (WCAG 1.4.11), and by construction no static choice can be. A
halo or outline behind glyphs would bound the problem; the caption text already has
one.

### 3.8 Unconfigured types share a default appearance

A type with no configured style gets the same fallback shape and the same default
colour as every other unconfigured type, so two such layers are visually
indistinguishable. In practice imageless objects carry an on-map text label, and the
Layers panel names each type, so this is a discoverability wrinkle rather than a
barrier.

### 3.9 Decorative SVG icons are not hidden

`SvgImageUtil` does not mark generated icon SVGs `aria-hidden`/`focusable="false"`.
Icon buttons get their name from `title`, so this is not currently harmful, but it is a
latent app-wide issue.

---

## 4. Verification performed

| Check | Result |
|---|---|
| `:stroom-core-client:compileJava` | Pass |
| `:stroom-core-client:checkstyleMain` | Pass (see note) |
| `:stroom-core-shared:test --tests "stroom.floormap.shared.*"` | Pass — 569 tests |
| `:stroom-app-gwt:gwtDraftCompile` | Pass |
| `./gradlew check` | 6 failures, none in scope — see below |

`gwtDraftCompile` is not optional here: it is the only step that validates the
UiBinder templates and the JSNI method in `HistogramWidget`, neither of which plain
`javac` reads.

**The `check` failures are unrelated.** Five are `stroom-proxy-app` end-to-end tests
failing on `Failed to bind to /0.0.0.0:8080` (environmental). One is
`TestMergeProcessor.interruptedMergeKeepsDataAndStopsConsumer`, which was confirmed to
pass in isolation on a clean stash of this branch — it is timing-flaky under parallel
load, from the `#5696` work-queue interruption work. Neither module depends on
`stroom-core-client`.

**Checkstyle note:** `FloorMapEditorPresenter.java` is 2,006 lines against a 2,000-line
`FileLength` limit. This is warning severity and does not fail the build, but the file
was under the limit before this work added 12 lines to it. Two sibling files in the same
package already exceed it substantially.

---

## 5. Automated coverage

There is none, and that is worth stating plainly. Everything in §2 lives in the GWT
presenter/view layer, which the 569 floormap unit tests do not reach — they cover the
shared geometry, clustering, parsing and model code in `stroom-core-shared`.

The compile-level checks above prove the code builds and the templates are valid. They
prove nothing about whether a screen reader says the right thing.

---

## 6. What remains unverified

The browser pass has now been run (§8). These seven were the open questions; this is how
each resolved.

| # | Question | Outcome |
|---|---|---|
| 1 | Live region announces at the right moments, not chatty | **Pass** |
| 2 | `role="img"` + `aria-hidden` stops NVDA/JAWS/VoiceOver walking captions | **Still unverified** — needs a human |
| 3 | Focus visible in both themes on slider, canvas, grip, swatch | **Pass** (canvas + slider confirmed; grip/swatch by computed style only) |
| 4 | Layers keyboard reorder keeps focus; arrows don't scroll the panel | **Fail** — §9.1 |
| 5 | `Enter` opens the context menu on the Editor tab | **Pass** functionally — but no ARIA roles on the menu (§9.6) |
| 6 | Enlarged scrubber hit area hasn't broken histogram click-to-seek | **Untestable** — no event data (§8.2) |
| 7 | Histogram bars repaint on theme switch without reload | **Untestable** — no event data (§8.2) |

**Item 1 — passed, measurably.** A `MutationObserver` on both floormap live regions
recorded exactly **one** announcement across six seconds of playback (`Playing`). Time is
not announced while playing, as designed. Discrete actions announce once each: `Zoom 73%`
on `=`, `Following guest.user.742` on selecting an entity, `Stopped following` on clearing
it. Keyboard panning announces nothing — defensible, since 40px per arrow press would
flood the region, but it does mean a non-sighted user gets no confirmation of a pan.

**Item 2 — cannot be closed here.** Chrome's tree confirms the `<svg>` carries
`aria-hidden="true"` and `focusable="false"`, and the captions do not surface as separate
nodes. Whether all three screen readers honour that still needs a real one. Note §9.2,
which makes this moot on the Map tab for a different reason.

**Item 3 — passed.** The canvas draws a clear blue focus border in dark theme (confirmed
by screenshot). The scrubber uses a blue `box-shadow` ring rather than an `outline`,
present in both themes by computed style. Not every control was visually confirmed in
light theme.

**Item 4 — failed.** See §9.1. Arrow keys do **not** scroll the panel (`scrollTop` stayed
0), so that half of the question is fine.

**Items 6 and 7 — blocked on data, not on code.** See §8.2.

[§9 of the manual test plan](floormap-test-plan.md) remains the reference for the
screen-reader pass, which is the part still outstanding.

---

## 7. Recommended next steps

1. Run [§9 of the test plan](floormap-test-plan.md). Nothing else on this list is worth
   doing first.
2. Decide on the two app-wide items — `.icon-button` sizing (§3.4) and `outline: none`
   (§3.5). Both are one central change each and both affect far more than Floor Map.
3. Add label forwarding to `DateTimeBox` / `BaseSelectionBox` (§3.3), which would let
   the `role="group"` mitigations be replaced with real labels here and everywhere else.
4. Give the cluster tooltip a keyboard path (§3.1), or accept the Tracking grid as the
   route to that information and document it in the UI help.
5. Consider a glyph halo for contrast over arbitrary backgrounds (§3.7).

---

## 8. Browser pass — method and limits

Run 2026-08-14 against a local instance (`https://localhost`, GWT super dev mode) on
branch `enterprise-floor-mapping-domain-types`, which carries this accessibility work,
using the `System/Enterprise Floor Mapping/FLOOR_MAP` document.

**Method.** axe-core 4.12.1 injected into the live page and run per tab, per dock panel
and per dialog, against tags `wcag2a/aa`, `wcag21a/aa`, `wcag22a/aa`. Every axe run was
**scoped to `.stroom-main-content-container`** so app chrome and the explorer tree did not
contaminate floormap results; the unscoped numbers are in §10.4. Alongside that: Chrome's
own accessibility tree, keyboard-only passes recorded with a `focusin` logger, live-region
announcements recorded with a `MutationObserver`, and computed-style contrast, with ratios
recomputed independently from the sRGB relative-luminance formula.

**What this covers that code review could not:** actual accessible names as Chrome
computes them, actual tab order, actual announcement counts, actual contrast ratios.

### 8.1 What was not covered

* **Screen readers.** No NVDA, JAWS or VoiceOver. Everything below is the accessibility
  tree and the DOM, which is where a screen reader reads from, but is not the same as
  hearing it.
* **Light-theme visual confirmation.** Light theme was reached by swapping the
  `stroom-theme-dark` class on `<html>`, which exercises the CSS but not the app's
  preference-change event path. No user preference was altered.
* **Reduced motion.** The OS running the browser does not set
  `prefers-reduced-motion: reduce`, so the rule block could not be triggered live. It is
  present and deliberate in `stroom-floormap.css:440`, with a documented rationale for what
  it does *not* disable (entity movement and playback, as information rather than
  decoration, with playback never autostarting). That reasoning holds against 2.2.2/2.3.3.
* **Non-text contrast, by axe.** This one is a trap worth stating plainly: axe's
  `color-contrast` rule evaluates **text only**. It does not check icons, borders or
  component states, so it is silent on WCAG 1.4.11 — and it also skips **hidden**
  elements, so tooltips and readouts that were not on screen during the run were never
  examined. Both gaps hid real failures; see §9.7.1 and §9.7.2. An axe run reporting one
  violation on a tab does not mean one problem on that tab.

### 8.2 Why items 6 and 7 could not be tested

This document has **`Events Store: None`** (Settings tab). The timeline reports "No events
in this time range" and the histogram draws no bars. Histogram click-to-seek and the
theme-switch bar repaint therefore have nothing to act on.

To close those two, re-run against a Floor Map document whose Events Store points at a
store with data inside the timeline's range. Everything else here was exercisable with
facts alone.

---

## 9. Browser pass — findings

Ownership matters for triage: **[FM]** is floormap code, **[shared]** is a Stroom widget
used by every document type, where a fix is an upstream change with much wider blast
radius. §10.4 covers shared issues found outside floormap.

### 9.1 Layers keyboard reorder drops focus to `<body>` — [FM]

*Serious. WCAG 2.4.3, and 4.1.3 for the missing announcement.*

Focus a row's reorder grip and press `Down`. The row moves — and focus lands on `<body>`.
A second `Down` does nothing, because nothing relevant has focus. To make a second move
the user must Tab all the way back into the panel.

Verified with focus asserted on the grip before the keypress (`document.activeElement ===
grip` was true), order captured either side, and `document.activeElement.tagName ===
'BODY'` after. The move is also silent — the panel has no live region, so there is no
confirmation that anything happened, or of the item's new position.

> **Correction, 2026-08-17 — the focus half of this finding is probably wrong.**
> `FloorMapLayersPresenter` already implements the restore, and a trace of the whole path
> found no defect in it:
>
> * the keydown handler sets `focusGripForType`, then `moveBy` → `commitOrder` → `rebuild()`;
> * `buildRow` — confirmed as the real-layer builder, not the provisional one, so the type
>   cannot match the wrong row — matches the type, clears the field, and defers
>   `grip.setFocus(true)` on the freshly built grip, deferred precisely because the widget
>   is not attached yet;
> * the edit handler that fires next, `FloorMapEditorPresenter.onLayerTypeStylesEdited`,
>   stages the styles and touches the canvas and object editor but **never re-enters the
>   Layers panel**, so no second rebuild wipes the pending focus.
>
> All of it present since `2aeb041a47`. The measurement above read `activeElement`
> **immediately** after the keypress, with no wait, so it most likely observed the gap
> before the deferred command ran — and the second arrow press was then sent while focus was
> still on `<body>`, which explains it doing nothing with no defect involved. A browser
> re-test with a wait is the decider; until then nothing has been changed here.
>
> The **announcement** half stands and was independently confirmed: there is no live
> region anywhere in the Layers panel. Fixed — see §14.3.

### 9.2 The map's focus target is an unnamed generic — [FM]

*Serious. WCAG 4.1.2. This undercuts §2.1.*

The generated summary works — the live label reads `Floor map at 2026-08-14 08:11.
8 persons. Following guest.user.742. Zoom 73%` and updates correctly. But it is on the
wrong element.

```
div.stroom-floormap-canvas-focus     ← tabindex="0"  ← this is what focus lands on
  div.gwt-HTML.max                   ← role="img", aria-label="Floor map at…",
                                        aria-describedby="floormap-tracking-grid"
    svg                              ← aria-hidden="true", focusable="false"
```

The focusable ancestor has no role, no `aria-label` and no `aria-labelledby`. Chrome
exposes it as `generic` with **no name**. A roleless container does not take a name from
its descendants, so tabbing to the map announces nothing useful — the summary and the
`aria-describedby` link to the Tracking grid are both one level too deep to be announced
on focus.

Move `role="img"`, `aria-label` and `aria-describedby` onto `.stroom-floormap-canvas-focus`.
(`aria-describedby` does resolve — `#floormap-tracking-grid` exists.)

### 9.3 `aria-label` on wrapper `<div>`s, where ARIA prohibits it — [FM]

*Critical. WCAG 4.1.2.* The same mistake as §9.2 and the more instructive one, because the
labels were written, and are good — they are just ignored.

In the Timeline Settings dialog, axe reports `aria-prohibited-attr` on `div.SimpleTickBox`
and `label` violations on three controls:

| Element | What it has | Why it's unnamed |
|---|---|---|
| Loop Playback checkbox | `aria-label` on the wrapping `div.SimpleTickBox` | `div` computes to `generic`; ARIA prohibits naming it, so the label is dropped |
| Start Date input | `aria-label="Timeline start date and time"` on the wrapper `div` | same — the inner `<input>` has nothing |
| End Date input | `aria-label="Timeline end date and time"` on the wrapper `div` | same |

Move each `aria-label` onto the control. Worth grepping the feature for the pattern —
three in one dialog plus §9.2 suggests habit rather than accident.

### 9.4 Timeline Settings dialog is not a dialog — [FM]/[shared]

*Serious. WCAG 4.1.2, 2.4.3.* The popup (`.simplePopup-popup`) has no `role="dialog"`, no
`aria-modal` and no accessible name, and **focus is not moved into it** when it opens —
`activeElement` stays on the gear button. It holds four focusable controls the user is
never told about.

`Escape` does close it and return focus to the trigger, which is correct and worth
keeping. `simplePopup` is shared, so the role/focus part is likely upstream; the
unlabelled contents (§9.3) are floormap's.

### 9.5 Value Schema editor is unreachable by keyboard — [FM] — **FIXED, see §13**

*Critical. WCAG 2.1.1.* On the Settings tab the six **Role** dropdowns (`TYPE`, `LABEL`,
`POSITION`, `IMAGE`, `WORLD_TO_MAP`, `MAP_TO_SCREEN`) are visible `<select>` elements
carrying `tabindex="-1"`. They are not in the tab order.

The grid has a single container focus stop, and from it neither `Down` nor `Enter` moves
focus inward — after both, `activeElement` is still the container, and only one `<select>`
on the tab is focusable (Value Format). The primary editing controls of the Value Schema
cannot be reached or operated by keyboard at all. The same seven selects also have no
accessible name (`select-name`, critical).

### 9.6 Popups and menus carry no ARIA roles — [FM]/[shared]

*Moderate. WCAG 4.1.2.* `Enter` on the Editor canvas opens the expected menu (`Add Object
Here`, `Draw Area Here`, `Set Scale`), arrow keys move into and through it, and `Escape`
closes it and restores focus to the canvas. Functionally good.

But the popup has no `role="menu"`, items have no `role="menuitem"`, and the canvas has no
`aria-expanded`. To assistive tech, unnamed generic divs appear with no announcement.

### 9.7 Contrast — three failures, only one of which axe could see

*The section heading in the original doc read "Speed badge fails contrast". That was too
narrow: the badge was the only failure axe could detect, not the only failure.*

**9.7.0 — Speed badge, text (WCAG 1.4.3, needs 4.5:1).** Measured, not estimated:

| Theme | Effective foreground | Background | Ratio |
|---|---|---|---|
| Dark | `#2185d5` | `#22252c` | **3.93:1** |
| Light | `#42a6f5` | `#ffffff` | **2.62:1** |

At `font-size: 10px` the large-text allowance cannot apply, so 4.5:1 stands. Neither
foreground appears in the stylesheet: both are `--blue-500` (`#2196f3`) composited by
`opacity: 0.85` over its panel. The declared colour was never the shipped colour — the
fade was, and it removed what headroom the colour had.

#### 9.7.1 Icon buttons fail 1.4.11 in light theme — [shared], invisible to axe

*Serious. WCAG 1.4.11 Non-text Contrast, needs 3:1.* axe never examined these, because
`color-contrast` is a text-only rule. Computed by hand, using the resting opacities in
`stroom-floormap.css`:

| Control | Resting opacity | Dark | Light |
|---|---|---|---|
| Step buttons | `0.75` | 4.55:1 ✓ | **2.34:1 ✗** |
| Settings gear | `0.70` | 4.15:1 ✓ | **2.19:1 ✗** |

Dark theme is comfortable. Light theme fails both, because `--icon-button__color` resolved
to `--blue-500`, which is only 3.12:1 on white *before* any fade — it cleared 3:1 by a
hair, and a 0.7 multiplier put it well under. These icons are the sole identifier of their
controls (no text label), so 1.4.11 applies.

#### 9.7.2 Five more text failures that were hidden during the run — [FM]

axe skips elements that are not rendered. Five floormap rules use `--blue-500` as small
text on `--page__background-color` (`#ffffff` in light theme), and all five are tooltips
or readouts that were off-screen when axe ran:

| `stroom-floormap.css` | Element | Size |
|---|---|---|
| 125 | speed badge (the one axe caught) | 10px |
| 199 | histogram tooltip | 10px |
| 309 | scrub tooltip | 10px |
| 685 | readout | 12px |
| 717 | gesture readout | 11px |
| 771 | hover tooltip caption | — |

All were ~3.12:1 in light theme, failing 4.5:1, and no automated pass would ever have
reported them. **So the real count on the Map and Editor tabs was one visible text
failure, five hidden text failures and two icon failures — not the single violation axe
reported.**

#### 9.7.3 Root cause — a palette tuned to the wrong threshold

Not a missing token. Stroom already had a theme-aware primary-accent family
(`--button-default-primary__color`, `--button-outline-primary__color`,
`--button-icon-primary__color` in `Button_Themes.css`, and `--icon-button__color` in
`theme-root.css`), all resolving to `--blue-500` in light theme and `--blue-300` in dark.

The defect was the *value*: `--blue-500` on white is 3.12:1 — adequate for the 3:1 of
1.4.11, never adequate for the 4.5:1 of 1.4.3. Text usages inherited a colour tuned for
icons. Because 4.5:1 subsumes 3:1, one text-grade accent per theme serves both purposes,
so the fix was to tighten the existing tokens rather than add a parallel family. See §12.

The floormap's own contribution was hardcoding `var(--blue-500)` — a raw palette entry,
identical in both themes — instead of using those theme-aware tokens.

### 9.8 The map's text alternative covers only tracked persons — [FM] — **BY DESIGN**

> **Resolved 2026-08-17: this is correct behaviour, not a defect.** The summary describes
> the tracked population and deliberately does not enumerate the static facts drawn beneath
> it. Recorded here as a decision so the observation below is not re-raised as a finding.
> The observation itself is accurate and worth keeping — it documents what the summary does
> and does not cover, which anyone extending it will want to know.

*Originally logged as: moderate, WCAG 1.1.1.* The summary counts `8 persons`. The map also
draws roughly fifteen
labelled glyphs — `C-office-1`, `P-L1-CAFETERIA`, `P-L2-CORRIDOR`, `P-L3-RM4`, `LOBBY`,
`G-SOUTH-22`, `G-EAST-04` and more, across the `computers` and `gates` layers.

None appear in the summary, and none appear in the Tracking grid that `aria-describedby`
points at — that grid lists only the eight persons. On the Map tab the static facts are
invisible to assistive tech, both in the summary and in its stated text alternative. The
Editor tab's Fact List does list them, so the information exists; it is just not reachable
from the Map tab.

### 9.9 Grids split header from body, with no roles — [FM]/[shared]

*Moderate. WCAG 1.3.1.* This is why axe returns `th-has-data-cells` as *incomplete* on
every tab with a grid. Tracking, Fact List and Time List render as the GWT `DataGrid`
two-table pattern: one `<table>` for the header row (`Name`, `Type`, `Area`, `Id`) and a
**separate** `<table>` for the data rows. Neither has a `role` or an `aria-label`, and rows
are roleless `<div>`s.

Because headers live in a different table element from the cells, a screen reader cannot
associate a cell with its column. That matters more than usual here: §2.1 nominates this
grid as the map's text alternative. The panels' own view code contains no ARIA at all, so
§1's original "Good" was inherited from the shared widget rather than verified.

---

## 10. The other five tabs

§1's scope covered Map and Editor. The document has **seven** tabs, and the other five had
never been assessed. Scoped axe results:

| Tab | Violations | Owner |
|---|---|---|
| Map | `color-contrast` ×1 (§9.7) | FM |
| Editor | `color-contrast` ×1 (§9.7) | FM |
| Events Query | `label` ×3 **critical**, `target-size` ×3 | FM + shared |
| Settings | `select-name` ×7 **critical**, `target-size` ×2 | FM |
| Assets | `aria-required-parent` ×3 **critical**, `image-alt` ×3 **critical** | shared |
| Documentation | `label` ×1 **critical**, `frame-title` ×1 | shared |
| Permissions | `label` ×1 **critical**, `target-size` ×1 | shared |

Read these alongside §8.1 — they are text-only counts of visible elements.

### 10.1 Events Query — [FM]

**Entity ID Column** and **Location ID Column** have no accessible name: no `aria-label`,
no `id`, no `title`, no `<label for>`. The visible text beside them is not associated with
them in any way. The third `label` violation is the Ace editor's hidden `textarea` (shared,
and the same one on the Documentation tab).

### 10.2 Settings — [FM]

`select-name` ×7 covers the Value Format `<select>` (focusable, unnamed) and the six Role
selects from §9.5 (unnamed *and* unreachable).

### 10.3 Assets, Documentation, Permissions — [shared]

Generic Stroom components, so these affect every document type:

* **Assets** — three `<img>` with no `alt`, three elements whose `role` requires an absent
  parent (`aria-required-parent`), plus `aria-required-children` incomplete.
  `DocumentAssetPresenter`.
* **Documentation** — `#markdown-preview-frame` has no `title`; the Ace `textarea` has no
  label. `MarkdownEditPresenter`.
* **Permissions** — one unlabelled control, one undersized target.

### 10.4 Tab bars, and the app chrome — [shared]

Both tab bars — the document's (`Map … Permissions`) and the dock's (`Tracking | Groups |
Layers`) — are built from roleless `<div class="linkTab">`.

The keyboard behaviour is **better than the markup suggests, and was worth checking before
writing it up as broken**: only the selected tab is in the tab order (correct roving
tabindex), arrow keys move along the bar, and `Enter` activates. Switching tabs by keyboard
works.

What is missing is semantics: no `role="tablist"`, no `role="tab"`, no `aria-selected`, and
each tab's text is duplicated in the DOM (`"MapMap"`). Assistive tech is told nothing about
a tab set existing, which tab is current, or that six siblings exist. Adding roles to the
shared `linkTab` widget fixes it everywhere at once.

Unscoped runs also surface app-chrome issues outside floormap: `button-name` ×2 (the Stroom
logo and the menu button, both unnamed), a missing `lang` on `<html>`, and `target-size` on
the explorer-tree icon buttons.

---

## 11. Revised priorities after the browser pass

1. ~~**§9.5** — Value Schema is keyboard-inoperable.~~ **Done — §13.**
2. **§9.3 and §9.2** — the `aria-label`-on-a-wrapper pattern. One cheap fix each, and it is
   what makes the map summary inaudible and three dialog controls unnamed. Grep for others.
   **Now the top item.**
3. **§9.1** — Layers reorder focus loss. Small fix; makes a working feature usable. Note
   §13.4 found the same root cause behind it, so the two are related.
4. ~~**§10.2** — unnamed selects on Settings.~~ **Done — §13**, as a side effect. **§10.1**
   (Events Query selection boxes) still stands.
5. ~~**§9.7** — contrast.~~ **Done — §12.**
6. **§9.4 / §9.6 / §10.4** — dialog and tab-bar roles. Mostly shared-widget work with a
   larger blast radius, so worth doing deliberately rather than quickly.
7. **§9.8** — decide whether the Map tab's text alternative should include static facts. A
   design question, not a bug to fix blind.
8. Re-run against a document with event data to close §6 items 6 and 7, and get a screen
   reader onto §6 item 2.

---

## 12. Contrast fixes applied

Applied 2026-08-14 in response to §9.7, and **verified in a running browser on 2026-08-17**
— every ratio below was re-measured from computed style in the live app, in both themes,
with `:hover` states triggered by a real pointer rather than inferred. Measured values
matched the predicted ones exactly.

Everything in §9.7, §9.7.1 and §9.7.2 is now closed. What is *not* settled is whether the
darker light-theme accent reads well across the whole product: the shared-token half of
this touches every button in Stroom, so it still wants a designer's eye before a PR. That
is a look question, not a compliance one.

### 12.1 Light-theme accent tokens tightened to `--blue-800`

| File | Token | Was | Now |
|---|---|---|---|
| `theme-root.css` | `--icon-button__color` | `--blue-500` | `--blue-800` |
| `Button_Themes.css` | `--button-default-primary__color` | `--blue-500` | `--blue-800` |
| `Button_Themes.css` | `--button-outline-primary__color` | `--blue-500` | `--blue-800` |
| `Button_Themes.css` | `--button-icon-primary__color` | `--blue-500` | `--blue-800` |

`--navigation-icon-button__color` inherits from `--icon-button__color` and follows. Dark
theme (`--blue-300`) is unchanged — it already passed at rest.

**`--blue-700` is not sufficient**, which is the non-obvious part and is recorded in a
comment at each site. These tokens sit on 10%/20% accent tints for selected/hover, and the
candidate must clear 4.5:1 on all three backgrounds:

| Candidate | on white | on 10% tint | on 20% tint |
|---|---|---|---|
| `--blue-500` (was) | 3.12 ✗ | 2.80 ✗ | 2.52 ✗ |
| `--blue-700` | 4.60 ✓ | 4.13 ✗ | 3.72 ✗ |
| `--blue-800` (now) | **5.75 ✓** | **5.15 ✓** | **4.64 ✓** |

This also clears §9.7.1. Browser-measured in light theme, *keeping* the existing opacity
fades: step buttons **3.50:1**, settings gear **3.20:1**, against the 3:1 of 1.4.11 — up
from 2.34:1 and 2.19:1.

**It did not, on its own, clear §9.7.2 — and the reason is worth keeping.** An earlier
revision of this section claimed it did. That was wrong, and the browser caught it. Those
rules hardcode `var(--blue-500)`, the raw palette entry, whereas §12.1 changed the
*semantic* tokens that happened to point at the same value. Tightening
`--icon-button__color` and `--button-*-primary__color` leaves `--blue-500` itself untouched,
so anything referencing the palette directly was unaffected: all five still computed to
`rgb(33, 150, 243)`, 3.12:1 on white. Fixed separately in §12.3.

The general lesson: changing a semantic token fixes only the code that *uses* that token.
Anywhere in the codebase reaching past it to the palette has to be found and changed by
hand.

### 12.2 Speed badge: fade removed, hover made neutral

`stroom-floormap.css`. Four changes: `color` now `var(--button-default-primary__color)`
instead of `var(--blue-500)`; `opacity: 0.85` removed; `opacity: 1` removed from the hover
rule; hover background now `var(--icon-button__background-color--hover)` instead of
`var(--button-default-primary__background-color--hover)`.

The token change alone was **not** enough for the badge — at `opacity: 0.85` even
`--blue-800` gives 4.29:1 resting and 3.60:1 on hover. The fade had to go.

The hover background had to change too, and the reason generalises. The accent tint is the
*same hue* as the label — 40% `--blue-300` in dark theme — so it collapses the text to
3.03:1, and no choice of blue fixes it, because lightening the text lightens the tint with
it (`--blue-200` reaches only 3.37:1). The neutral wash the sibling step and settings
buttons already use holds 5.13:1 in **both** themes:

Browser-measured, `:hover` triggered by a real pointer and asserted with
`el.matches(':hover')`:

| Badge state | Light | Dark |
|---|---|---|
| Resting | 5.75:1 ✓ (`#1565c0` on `#ffffff`) | 6.93:1 ✓ (`#64b5f6` on `#22252c`) |
| Hover / focus-visible | 5.13:1 ✓ (on `#f2f2f2`) | 5.13:1 ✓ (on `#373a43`) |

Computed `opacity` is `1`, confirming the fade is gone. The badge also now matches its
neighbours visually, and needed no new token.

Incidentally, this run resolved a loose end from §9.7.0: computed `font-weight` is `600`,
as the stylesheet declares. The first pass recorded axe reporting `normal`; the live value
does not agree, and nothing about the requirement changes either way, since 10px is never
"large text".

One accepted trade-off: the neutral wash is subtle as a background in its own right —
1.12:1 against the page in light theme, 1.35:1 in dark — which is presumably why a strong
accent tint was chosen originally. WCAG does not hold transient pointer-hover states to
1.4.11 and the sibling buttons already accept this, so it is compliant; if it reads as too
faint in review, put the emphasis somewhere that is not behind the text (an accent 1px
border is 5.75:1 against the page, satisfying 1.4.11 for state on its own).

### 12.3 Tooltips and readouts moved off the raw palette

`stroom-floormap.css` now contains **zero** references to `var(--blue-500)`. All eleven
moved to `var(--button-default-primary__color)`:

* **Five text `color:` declarations** — the histogram tooltip, scrub tooltip, area-draw
  hint, gesture readout and hover-tooltip caption. These were the §9.7.2 failures.
* **Five tooltip `border: 1px solid` declarations, and the scrub tooltip's
  `::before` caret (`border-top-color`).** Non-text, so these owed only 3:1 and were
  already passing at 3.12:1 — but each tooltip is a single visual unit of accent text,
  matching accent border and accent caret, so changing the text alone would have left them
  two-tone. They now measure 5.75:1 in light theme.

Browser-measured after the change, all five text rules:

| Rule | Size | Dark | Light (was 3.12:1) |
|---|---|---|---|
| `timeline-histogram-tooltip` | 10px | 6.93:1 ✓ | **5.75:1** ✓ |
| `timeline-scrub-tooltip` | 10px | 6.93:1 ✓ | **5.75:1** ✓ |
| `area-draw-hint` | 12px | 6.93:1 ✓ | **5.75:1** ✓ |
| `gesture-readout` | 11px | 6.93:1 ✓ | **5.75:1** ✓ |
| `hover-tooltip__caption` | 14.4px | 6.93:1 ✓ | **5.75:1** ✓ |

Two counting corrections to §9.7.2, which described this set from a `grep` rather than from
the file: it is **five** text declarations, not six, and the sixth accent site is the
caret's `border-top-color`, which is non-text. An earlier revision of §12.3 said "seven
declarations across six rules"; that was also wrong.

### 12.4 Testing note — CSS edits need a hard reload

Worth knowing before re-testing any of this. `app.css` pulls the stylesheets in with plain
`@import url("stroom-floormap.css")` — no version query — so browsers hold their cached
copy across an ordinary reload, and **restarting Stroom does not help**. During this run the
server served the updated file correctly while the browser rendered the old one for two
rounds of probing. `Ctrl+Shift+R` clears it.

If you are checking a CSS change, verify both ends: `curl -k https://localhost/ui/css/<file>`
for what the server has, and the browser's own `document.styleSheets` for what it is
actually using.

### 12.5 Not done

* **Dark-theme primary tints.** `--button-*-primary__background-color--hover/--selected` are
  40%/30% `--blue-300`, which puts *text* primary buttons elsewhere in the app at 3.03:1 and
  3.78:1. The badge escapes this now (§12.2), but the tokens are unchanged. Fixing it means
  reducing those opacities toward light theme's 10%/20% — a visual change beyond the tokens
  named for this work.
* **Secondary and green accents.** `--button-*-secondary__color` (`--pink-500`) and
  `--button-icon-green__color` (`--green-500`) on white are unmeasured and likely carry the
  same defect as `--blue-500` did.
* **Contrast over user imagery.** Unchanged, and still the open design question from §3.7.

---

## 13. Value Schema keyboard access — fixed

Applied and **verified in a running browser on 2026-08-17**, closing §9.5 (WCAG 2.1.1) and
the Settings half of §10.2 (WCAG 4.1.2). All four columns of the Value Schema grid are now
keyboard-operable and individually named.

### 13.1 Why the controls were unreachable

Not an oversight in floormap. GWT's `SelectionCell` and `TextInputCell` both hardcode
`tabindex="-1"` on the control they render, deliberately: a cell is not meant to be its own
tab stop, because `AbstractCellTable` is expected to move between cells with the arrow keys.

That expectation does not hold in Stroom.
[`MyDataGrid.setSelectionModel`](../stroom-core-client-widget/src/main/java/stroom/data/grid/client/MyDataGrid.java)
enables `KeyboardSelectionPolicy.ENABLED` and then immediately replaces the handler with an
empty lambda — commented "We need to set this to prevent default keyboard behaviour". So the
arrow keys never arrive. `DataGridSelectionEventManager` handles only pointer events, so
there is no substitute path. The controls end up unreachable by any keyboard route at all.

### 13.2 What changed

Two new cells, in `stroom.floormap.client.cell`, each a subclass that overrides only
`render` — faithfully mirroring the superclass's view-data handling so an in-flight edit
still survives a redraw:

* **`AccessibleSelectionCell`** — replaces `SelectionCell` for the Role column.
* **`AccessibleTextInputCell`** — replaces `EditTextCell` for Path, Display Name and Default.

Both drop the `tabindex="-1"`, add an `aria-label`, and render `disabled` when the document
is read-only, so a control stops presenting itself as editable in a state where the field
updater discards the edit.

Note the second is a change in *kind*: `EditTextCell` renders static text and swaps to an
input **on click**, which is a pointer-only affordance — there was nothing for a keyboard
user to focus in the first place. These cells are always inputs. **The visible consequence
is that the three text columns now look like the input fields they are.** That is a UI change
to the Settings tab and deserves a designer's glance, though for a settings grid it is
arguably clearer than click-to-reveal.

Labels identify the row rather than just the column, since six controls all called "Role"
are no better than none. `schemaCellLabel` builds them from the row's JSON path, falling
back to position for a freshly added row with no path yet: `Role for .type`,
`Path for .tm-world-to-map`, `Default for .coords`.

### 13.3 Two defects the browser found that code review would not have

**A stray tab stop that sent focus backwards.** With the controls tabbable, the tab order
still jumped back on itself. The cause was GWT marking the keyboard-selected cell's wrapper
`div` as `tabindex="0"` to give the table a tab stop for the very arrow-key navigation
`MyDataGrid` disables. The stop led nowhere, and as the selected cell moved it landed
*before* the control being tabbed away from. Fixed by setting
`KeyboardSelectionPolicy.DISABLED` on this grid, after `setSelectionModel` turns it on.

**Focus lost to `<body>` after every edit.** A single edit ejected the user from the grid,
who then had to tab all the way back — up to 24 stops. The first attempt at a fix, removing
`refreshGrid()` from `replaceMapping`, changed nothing, because the redraw does not come
from there: `ListDataProvider.getList()` returns a wrapper that flags itself modified and
flushes on mutation, so `list.set(...)` redraws the row by itself. There is no unnotified
path, and keeping a shadow model to dodge it would put two sources of truth behind a
data-bearing grid — not worth it here. Instead `restoreSchemaFocusAfterRedraw` re-focuses
the cell in a deferred command, and **only if focus was actually lost**: when the user
commits by tabbing to another row, that control survives and keeps focus, and stealing it
back would be worse than the problem.

### 13.4 Verified

| Check | Result |
|---|---|
| Controls reachable | 24 (6 rows × 4 columns), all in the tab order |
| Tab order | Strictly row-major, no repeats, no backwards jumps, exits the grid cleanly |
| Accessible names | 24 unique, row-specific; no duplicates |
| Keyboard trap (2.1.2) | None — row 6 reachable, focus exits to the next control |
| Select edit reaches the model | `TYPE` → `LABEL` by ArrowDown; document went dirty |
| Text edit reaches the model | Typed value committed on Tab |
| Focus after select edit | Kept on the select |
| Tab after an edit | Advances normally to the next control |

### 13.5 Known limitations

* **A keystroke can be dropped immediately after an edit.** Focus is restored in a deferred
  command, so a key pressed in that window lands on `<body>` and is lost. Observed while
  testing: an ArrowUp sent straight after an ArrowDown did not register. Harmless at human
  typing speed, but it is a real edge.
* **The root cause is still there.** The empty keyboard handler in `MyDataGrid` affects
  *every* grid in Stroom, and §9.1's Layers focus loss is the same family of bug. Restoring
  proper arrow-key navigation there would fix this class of problem app-wide, but it is a
  shared-widget change, the empty handler looks deliberate, and it needs its owner. §13 is
  the contained alternative, not the real fix.
* **`./gradlew check` is green.** A run with Stroom **stopped** gave `BUILD SUCCESSFUL` in
  9m 4s: zero failing tasks, zero test failures, and no checkstyle warnings against any file
  changed by this work. `:stroom-app-gwt:gwtDraftCompile` also succeeds — the only step that
  validates the UiBinder templates.

  Two earlier runs failed, and that green run settles both as environmental rather than
  regressions. Neither module has a dependency path to `stroom-core-client`:
    * `:stroom-proxy:stroom-proxy-app:test` — 13 × WireMock `Failed to bind to /0.0.0.0:8080`
      (`Address already in use`). A running Stroom holds that port; the tests need it free,
      and **restarting Stroom does not help — it must be stopped.**
    * `TestSimpleGuard.testDestroyDuringActiveAcquisitionRace` — flaky, from unrelated
      `#5364` work. It asserts that some of 100 racing threads acquire before one of them
      destroys the guard, which nothing orders; it passed 3/3 in isolation, passed in an
      earlier full run on identical code, and passed again in the green run.

  **Worth remembering for the next full check:** a running Stroom holds `:8080`, and
  restarting it does not release the port — it has to be stopped, or those 13 proxy tests
  fail every time and look like a regression.

---

## 14. Naming and announcement fixes applied

Applied 2026-08-17. **Compiled and checkstyle-clean, but not yet verified in a browser** —
the instance was stopped for a test run. Each item below states what to check.

All four are floormap-owned. Shared-widget findings (§9.4, §9.6, §9.9, §10.3, §10.4, §12.5,
and the `MyDataGrid` keyboard handler) are deliberately out of scope for this pass.

### 14.1 §9.2 — the map's summary is now announced on focus

The generated summary was correct all along; it was attached to the wrong element.
`role="img"` and the `aria-label` sat on `svgContainer`, a **child** of the focusable
`FocusPanel`, and a roleless `div[tabindex="0"]` computes to `generic` — which ARIA forbids
naming, so tabbing to the map announced nothing.

**The role could not simply be moved up.** `role="img"` makes its whole subtree
presentational, and `statusRegion` — the live region carrying every announcement the map
makes — is a descendant of the focus panel, as are the hover tooltip and scale bar. Moving
it would have silenced the lot. So:

* `role="img"` stays on `svgContainer`, scoped to the SVG.
* `focusPanel` gets `role="group"`, which takes a name without making descendants
  presentational.
* `setMapSummary` and `setMapDescribedBy` now write to both elements — the container so the
  image is named when browsing, the focus panel so the summary and the pointer to the
  Tracking grid are spoken on focus.

*Check:* tab to the map; Chrome's tree should show a **named group**, not an unnamed generic.
Then confirm announcements still fire — select an entity and watch the live region. That
regression is the one this fix was shaped around.

### 14.2 §9.3 — Loop Playback checkbox was named on its wrapper

`FloorMapAria.label(loopCheckBox, …)` put the name on `CustomCheckBox`'s root, which is a
`div.SimpleTickBox` wrapping the real `<input>` — exactly what `label()`'s own javadoc warns
against. Now uses a new `FloorMapAria.labelInnerControl`, which names the first focusable
descendant and returns whether it found one.

`group()` was the alternative but describes a single checkbox as a group of controls, which
reads worse than naming the checkbox.

**The two `DateTimeBox`es beside it were already correct** — `group()` there is the
documented mitigation for a composite whose inner input a `<label for>` cannot reach (§3.3).
axe still reports their inner inputs as unlabelled; that is the acknowledged trade-off of the
group pattern, not a new defect.

**The grep for other instances came back almost empty**, which was the useful result: every
other `FloorMapAria.label()` call in the feature targets a genuine control — `TextBox`,
`ListBox`, and `ColourBox`, whose root *is* its input. The wrapper mistake was one site, not
a habit.

*Check:* axe on the Timeline Settings dialog should lose `aria-prohibited-attr` and one of
its three `label` violations. The two date-box violations are expected to remain.

### 14.3 §9.1 — the Layers reorder is no longer silent

The panel had no live region anywhere, so a keyboard reorder produced nothing a screen
reader could report. Added one to `FloorMapLayersViewImpl` with an `announce()` on the view
interface, mirroring the canvas's region.

It is placed **beside** the scroll panel, not inside the list, because `rebuild()` clears the
list on every reorder and a live region that is destroyed and re-created never announces —
assistive technology only reports changes to a region it was already watching.

`moveBy` now announces e.g. `computers layer moved to position 2 of 4`, after the commit so
the position quoted is the one that stuck, and 1-based to match what the user sees.

*Check:* a `MutationObserver` on the region should record exactly one announcement per move,
and none for a move that is refused at either end of the list.

**Not changed: the focus-restore half.** See the correction in §9.1 — that mechanism already
exists and the evidence against it was raced. It needs re-testing with a wait before anyone
touches it.

### 14.4 §10.1 — Events Query column boxes were unnamed

Both `FormGroup`s carry `identity` and a visible label, but `SelectionBox` is a composite
whose root is a wrapper `div`, so `setIdentity()` lands the id there and the `<label for>`
resolves to a non-labelable element — naming nothing. This is the exact case `FloorMapAria`'s
class javadoc describes. Both now name their inner input via `labelInnerControl`.

The label text is duplicated in Java rather than wired to the visible label, because the
`FormGroup`'s copy cannot reach the inner input. A real fix is label forwarding in
`BaseSelectionBox` (§3.3) — shared, and out of scope here.

*Check:* axe on the Events Query tab should drop from three `label` violations to one. The
survivor is the Ace editor's hidden textarea, which is shared.
