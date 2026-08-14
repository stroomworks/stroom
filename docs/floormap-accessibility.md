# Floor Map — accessibility status

**Scope:** the Floor Map feature on branch `enterprise-floor-mapping-accessibility` —
the Map tab (SVG canvas, timeline, Tracking / Groups / Layers dock panels), the Editor
tab (canvas, Fact List, Time List, Layers, dialogs), and the shared
`HistogramWidget` the timeline uses.

**Target:** WCAG 2.2 Level AA.

**Assessed by:** code review of the branch diff against `master`, plus a build-level
verification pass. **A browser pass was added on 2026-08-14** — method in §8, findings in
§9, and the five previously unassessed tabs in §10. Screen-reader behaviour is still
unverified. §2 and §3 below are the original code-review conclusions and have been left
as written; where the browser contradicted them, §9 says so and §1 has been corrected.

---

## 1. Summary

| Area | Status |
|---|---|
| Timeline transport controls (play, step, settings) | **Good** — confirmed in browser |
| Timeline scrubber (keyboard + ARIA slider) | **Good** — confirmed in browser |
| Speed badge | **Fail** — contrast 3.93:1 dark, 2.62:1 light (§9.7) |
| Histogram | **Unverified** — no event data on the test document (§8.2) |
| Tabular panels (Tracking, Fact List, Time List, Groups) | **Adequate** — headers split from cells, no roles (§9.9) |
| Layers panel | **Mixed** — labels good, keyboard reorder drops focus (§9.1) |
| Dialogs (Object Edit, Settings, Init, Set Scale, Group Edit, Layer Style) | **Fail** — no dialog role, focus not moved in, labels on wrappers (§9.3, §9.4) |
| Map canvas — text alternative | **Fail in practice** — summary is on a child of the focus target (§9.2); omits static facts (§9.8) |
| Map canvas — keyboard operation | **Adequate** — pan/zoom/menu confirmed working; per-entity still no |
| Cluster membership detail | **Gap** — pointer-only |
| Reduced motion | **Good** — implemented and reasoned; not triggerable in test env (§8.1) |
| Colour contrast | **Good** except the speed badge; **unmanaged** over user imagery |
| Document tab bars | **Fail** — keyboard works, but no tab/tablist roles (§10.4) |
| Events Query / Settings tabs | **Fail** — unnamed controls; Value Schema keyboard-inoperable (§9.5, §10.1) |
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

A browser pass has now been run — see §8 for method and §9 for what it found. The seven
items below were the open questions; this is how each resolved.

| # | Question | Outcome |
|---|---|---|
| 1 | Live region announces at the right moments, not chatty | **Pass** |
| 2 | `role="img"` + `aria-hidden` stops NVDA/JAWS/VoiceOver walking captions | **Still unverified** — needs a human |
| 3 | Focus visible in both themes on slider, canvas, grip, swatch | **Pass** (canvas + slider confirmed; grip/swatch by computed style only) |
| 4 | Layers keyboard reorder keeps focus; arrows don't scroll the panel | **Fail** — see §9.1 |
| 5 | `Enter` opens the context menu on the Editor tab | **Pass** functionally — but the menu has no ARIA roles (§9.6) |
| 6 | Enlarged scrubber hit area hasn't broken histogram click-to-seek | **Untestable** — no event data (§8.2) |
| 7 | Histogram bars repaint on theme switch without reload | **Untestable** — no event data (§8.2) |

**Item 1 — passed, measurably.** A `MutationObserver` on both floormap live regions
recorded exactly **one** announcement across six seconds of playback (`Playing`). Time is
not announced while playing, as designed. Discrete actions do announce, once each:
`Zoom 73%` on `=`, `Following guest.user.742` on selecting an entity, `Stopped following`
on clearing it. Keyboard panning announces nothing, which is a defensible choice — 40px
per arrow press would otherwise flood the region — but it does mean a non-sighted user
gets no confirmation that a pan happened.

**Item 2 — cannot be closed here.** Chrome's tree confirms the `<svg>` carries
`aria-hidden="true"` and `focusable="false"`, and that the captions do not surface as
separate nodes. Whether all three screen readers honour that is a separate question and
still needs a real one. Note §9.2, which makes this moot on the Map tab for a different
reason.

**Item 3 — passed.** The canvas draws a clear blue focus border in dark theme (confirmed
by screenshot). The scrubber uses a blue `box-shadow` ring rather than an `outline`,
present in both themes by computed style. Not every control was visually confirmed in
light theme.

**Item 4 — failed.** See §9.1. Arrow keys do **not** scroll the panel (`scrollTop`
stayed at 0), so that half of the question is fine.

**Items 6 and 7 — blocked on data, not on code.** See §8.2.

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
**scoped to `.stroom-main-content-container`** so that app chrome and the explorer tree
did not contaminate floormap results; the unscoped numbers are in §10.4. Alongside that:
Chrome's own accessibility tree, keyboard-only passes recorded with a `focusin` logger,
live-region announcements recorded with a `MutationObserver`, and computed-style contrast.

**What this pass covers that code review could not:** actual accessible names as Chrome
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
  present and deliberate in `stroom-floormap.css:440`, with a documented rationale for
  what it does *not* disable (entity movement and playback, on the grounds that they are
  information rather than decoration and playback never autostarts). That reasoning holds
  up against 2.2.2 and 2.3.3.

### 8.2 Why items 6 and 7 could not be tested

This document has **`Events Store: None`** (Settings tab). The timeline reports "No events
in this time range" and the histogram draws no bars. Histogram click-to-seek and the
theme-switch bar repaint therefore have nothing to act on.

To close those two, the pass needs re-running against a Floor Map document whose Events
Store points at a store with data inside the timeline's range. Everything else in this
document was exercisable with facts alone.

---

## 9. Browser pass — findings

Ownership matters for triage: **[FM]** is floormap code, **[shared]** is a Stroom widget
used by every document type, where a fix is an upstream change with much wider blast
radius. See §10.4 for the shared ones found outside floormap.

### 9.1 Layers keyboard reorder drops focus to `<body>` — [FM]

*Serious. WCAG 2.4.3, and 4.1.3 for the missing announcement.*

Focus a row's reorder grip and press `Down`. The row moves — and focus lands on
`<body>`. A second `Down` does nothing, because nothing relevant has focus any more. To
make a second move the user must Tab all the way back into the panel.

Verified with focus asserted on the grip before the keypress (`document.activeElement ===
grip` was true), order captured either side, and `document.activeElement.tagName ===
'BODY'` after. The move is also silent — the panel has no live region, so there is no
confirmation that anything happened, or of the item's new position.

The rows are rebuilt on reorder, which destroys the focused element. The fix is to
restore focus to the moved row's grip after the rebuild and announce the new position.

### 9.2 The map's focus target is an unnamed generic — [FM]

*Serious. WCAG 4.1.2. This one undercuts §2.1.*

The generated summary works — the live label reads
`Floor map at 2026-08-14 08:11. 8 persons. Following guest.user.742. Zoom 73%` and
updates correctly. But it is on the wrong element.

```
div.stroom-floormap-canvas-focus     ← tabindex="0"  ← this is what focus lands on
  div.gwt-HTML.max                   ← role="img", aria-label="Floor map at…",
                                        aria-describedby="floormap-tracking-grid"
    svg                              ← aria-hidden="true", focusable="false"
```

The focusable ancestor has no role, no `aria-label` and no `aria-labelledby`. Chrome
exposes it as `generic` with **no name**. A roleless container does not take a name from
its descendants, so tabbing to the map announces nothing useful — the summary, and the
`aria-describedby` link to the Tracking grid, are both attached one level too deep to be
announced on focus.

Moving `role="img"`, `aria-label` and `aria-describedby` onto
`.stroom-floormap-canvas-focus` would fix it. (`aria-describedby` does resolve — the
target `#floormap-tracking-grid` exists.)

### 9.3 `aria-label` on wrapper `<div>`s, where ARIA prohibits it — [FM]

*Critical. WCAG 4.1.2.* The same authoring mistake as §9.2, and the more instructive one,
because the labels were written and are good — they are just ignored.

In the Timeline Settings dialog, axe reports `aria-prohibited-attr` on `div.SimpleTickBox`
and `label` violations on three controls:

| Element | What it has | Why it's unnamed |
|---|---|---|
| Loop Playback checkbox | `aria-label` on the wrapping `div.SimpleTickBox` | `div` computes to `generic`; ARIA prohibits naming it, so the label is dropped |
| Start Date input | `aria-label="Timeline start date and time"` on the wrapper `div` | same — the inner `<input>` has nothing |
| End Date input | `aria-label="Timeline end date and time"` on the wrapper `div` | same |

Move each `aria-label` onto the control itself. Worth grepping the feature for the
pattern — three instances in one dialog plus §9.2 suggests it is habitual rather than
isolated.

### 9.4 Timeline Settings dialog is not a dialog — [FM]/[shared]

*Serious. WCAG 4.1.2, 2.4.3.* The popup (`.simplePopup-popup`) has no `role="dialog"`, no
`aria-modal` and no accessible name, and **focus is not moved into it** when it opens —
`activeElement` stays on the gear button that opened it. It contains four focusable
controls that a screen-reader user has no notification of.

`Escape` does close it and return focus to the trigger, which is correct and worth
keeping. The `simplePopup` widget itself is shared, so the role/focus part is likely an
upstream fix; the unlabelled contents (§9.3) are floormap's.

### 9.5 Value Schema editor is unreachable by keyboard — [FM]

*Critical. WCAG 2.1.1.* On the Settings tab, the six **Role** dropdowns
(`TYPE`, `LABEL`, `POSITION`, `IMAGE`, `WORLD_TO_MAP`, `MAP_TO_SCREEN`) are visible
`<select>` elements carrying `tabindex="-1"`. They are not in the tab order.

The grid has a single container focus stop, and from it neither `Down` nor `Enter` moves
focus inward — after both, `activeElement` is still the container and only one `<select>`
on the whole tab is focusable (the Value Format box). So the primary editing controls of
the Value Schema cannot be reached or operated by keyboard at all. The same seven selects
also have no accessible name (`select-name`, critical).

### 9.6 Popups and menus carry no ARIA roles — [FM]/[shared]

*Moderate. WCAG 4.1.2.* `Enter` on the Editor canvas opens the expected menu
(`Add Object Here`, `Draw Area Here`, `Set Scale`), arrow keys move into and through it,
and `Escape` closes it and restores focus to the canvas. Functionally this is good.

But the popup has no `role="menu"`, the items have no `role="menuitem"`, and the canvas
carries no `aria-expanded`. To assistive tech, a set of unnamed generic divs appears with
no announcement.

### 9.7 Speed badge fails contrast in both themes — [FM]

*Serious. WCAG 1.4.3.* Measured, not estimated:

| Theme | Foreground | Background | Ratio | Required |
|---|---|---|---|---|
| Dark | `#2185d5` | `#22252c` | **3.93:1** | 4.5:1 |
| Light | `#42a6f5` | `#ffffff` | **2.62:1** | 4.5:1 |

At 10px it cannot use the large-text allowance. This is the **only** WCAG AA violation
axe reports inside floormap scope on the Map and Editor tabs — those two tabs are
otherwise clean, which is a genuinely good result. §1 currently rates the badge "Good";
that rating is wrong.

### 9.8 The map's text alternative omits everything that isn't a tracked person — [FM]

*Moderate. WCAG 1.1.1.* The summary counts `8 persons`. The map also draws roughly
fifteen labelled glyphs — `C-office-1`, `P-L1-CAFETERIA`, `P-L2-CORRIDOR`, `P-L3-RM4`,
`LOBBY`, `G-SOUTH-22`, `G-EAST-04` and so on, across the `computers` and `gates` layers.

None of them appear in the summary, and none appear in the Tracking grid that
`aria-describedby` points at — that grid lists only the eight persons. So on the Map tab
the static facts are invisible to assistive tech, both in the summary and in its stated
text alternative. The Editor tab's Fact List does list them, so the information exists;
it just is not reachable from the Map tab.

### 9.9 Grids split header from body, with no roles — [FM]/[shared]

*Moderate. WCAG 1.3.1.* This is why axe returns `th-has-data-cells` as *incomplete* on
every tab with a grid. The Tracking, Fact List and Time List panels render as the GWT
`DataGrid` two-table pattern: one `<table>` holding the header row (`Name`, `Type`,
`Area`, `Id`) and a **separate** `<table>` holding the data rows. Neither has a `role` or
an `aria-label`, and rows are roleless `<div>`s.

Because the headers live in a different table element from the cells, a screen reader
cannot associate a cell with its column. That matters more here than usual: §2.1 nominates
this grid as the map's text alternative. The panels' own view code contains no ARIA at
all, so the "Good" rating in §1 was inherited from the shared widget rather than verified.

---

## 10. The other five tabs

§1's scope covered Map and Editor. The document actually has **seven** tabs
([`FloorMapPresenter.java:52`](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapPresenter.java)),
and the other five had never been assessed. Scoped axe results:

| Tab | Violations | Owner |
|---|---|---|
| Map | `color-contrast` ×1 (§9.7) | FM |
| Editor | `color-contrast` ×1 (§9.7) | FM |
| Events Query | `label` ×3 **critical**, `target-size` ×3 | FM + shared |
| Settings | `select-name` ×7 **critical**, `target-size` ×2 | FM |
| Assets | `aria-required-parent` ×3 **critical**, `image-alt` ×3 **critical** | shared |
| Documentation | `label` ×1 **critical**, `frame-title` ×1 | shared |
| Permissions | `label` ×1 **critical**, `target-size` ×1 | shared |

### 10.1 Events Query — [FM]

The **Entity ID Column** and **Location ID Column** selection boxes have no accessible
name: no `aria-label`, no `id`, no `title`, and no `<label for>` — the visible text beside
them is not associated with them in any way. The third `label` violation is the Ace
editor's hidden `textarea` (shared, and the same one that appears on the Documentation
tab).

### 10.2 Settings — [FM]

`select-name` ×7 covers the Value Format `<select>` (focusable, unnamed) and the six Role
selects from §9.5 (unnamed *and* unreachable).

### 10.3 Assets, Documentation, Permissions — [shared]

These are generic Stroom components, so findings here affect every document type:

* **Assets** — three `<img>` elements with no `alt`, and three elements whose `role`
  requires a parent that is absent (`aria-required-parent`), plus
  `aria-required-children` incomplete. `DocumentAssetPresenter`.
* **Documentation** — `#markdown-preview-frame` has no `title`, and the Ace `textarea` has
  no label. `MarkdownEditPresenter`.
* **Permissions** — one unlabelled control and one undersized target.

### 10.4 Tab bars, and the app chrome — [shared]

Both tab bars — the document's (`Map … Permissions`) and the dock's
(`Tracking | Groups | Layers`) — are built from roleless `<div class="linkTab">`.

The keyboard behaviour is **better than the markup suggests, and was worth checking
before writing it up as broken**: only the selected tab is in the tab order (correct
roving-tabindex), arrow keys move along the bar, and `Enter` activates. Switching tabs by
keyboard works.

What is missing is the semantics: no `role="tablist"`, no `role="tab"`, no
`aria-selected`, and each tab's text is duplicated in the DOM (`"MapMap"`). Assistive tech
is told nothing about there being a tab set, which tab is current, or that six siblings
exist. Adding the roles to the shared `linkTab` widget would fix it everywhere at once.

For completeness, unscoped runs also surface app-chrome issues outside floormap:
`button-name` ×2 (the Stroom logo and the menu button, both unnamed), a missing `lang` on
`<html>`, and `target-size` on the explorer-tree icon buttons.

---

## 11. Revised priorities after the browser pass

1. **§9.5** — Value Schema is keyboard-inoperable. Nothing else here locks a user out of
   a feature outright.
2. **§9.3 and §9.2** — the `aria-label`-on-a-wrapper pattern. One cheap fix each, and it
   is what makes the map summary inaudible and three dialog controls unnamed. Grep for
   others.
3. **§9.1** — Layers reorder focus loss. Small fix, and it makes a working feature usable.
4. **§10.1 / §10.2** — unnamed selection boxes and selects on Events Query and Settings.
5. **§9.7** — speed badge contrast. A colour change, failing in both themes.
6. **§9.4 / §9.6 / §10.4** — dialog and tab-bar roles. Mostly shared-widget work; larger
   blast radius, so worth doing deliberately rather than quickly.
7. **§9.8** — decide whether the Map tab's text alternative should include static facts.
   A design question, not a bug to fix blind.
8. Re-run against a document with event data to close §6 items 6 and 7, and get a screen
   reader onto §6 item 2.
