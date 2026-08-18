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
| Histogram | **Good** — click-to-seek and theme repaint verified with real data; bar contrast fixed (§17) |
| Tabular panels (Tracking, Fact List, Time List, Groups) | **Adequate** — headers split from cells, no roles (§9.9) |
| Layers panel | **Good** — labels good; reorder now announced (§14.3). The reported focus loss is unconfirmed and probably a measurement race (§9.1) |
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
- `--text-color--low-emphasis` was used 15 times and **defined nowhere**, so every
  "low emphasis" label silently fell back to the inherited colour. It now has per-theme
  values (light `#5f6368`, 6.0:1; dark `#9aa0a6`, 5.81:1).
- The out-of-range chevron moved from `#f57c00` (2.7:1 on white — a meaningful non-text
  indicator below the 3:1 floor) to a per-theme variable: `#e65100` light (3.8:1),
  `#ffa726` dark (7.89:1). Its glow uses `currentColor` so it tracks the theme.
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
| 4 | Layers keyboard reorder keeps focus; arrows don't scroll the panel | **Unresolved** — arrows don't scroll ✓; the focus half is probably a measurement race, see the correction in §9.1 |
| 5 | `Enter` opens the context menu on the Editor tab | **Pass** functionally — but no ARIA roles on the menu (§9.6) |
| 6 | Enlarged scrubber hit area hasn't broken histogram click-to-seek | **Pass** — §17.1 |
| 7 | Histogram bars repaint on theme switch without reload | **Pass** — §17.2 |

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
  present and deliberate in `stroom-floormap.css:455`, with a documented rationale for what
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

| Element | What it has | Verdict |
|---|---|---|
| Loop Playback checkbox | `aria-label` on the wrapping `div.SimpleTickBox`, via `FloorMapAria.label()` | **Defect.** A bare `div` computes to `generic`, which ARIA 1.2 prohibits naming, so the name is dropped and the checkbox is anonymous. Fixed in §14.2 |
| Start Date input | `aria-label` on the wrapper `div`, via `FloorMapAria.group()` | **Not a defect.** `group()` also sets `role="group"`, so the wrapper is not generic and the name is exposed. This is the documented §3.3 mitigation |
| End Date input | as above | as above |

> **Correction, 2026-08-17.** The original version of this table gave all three rows the same
> "div computes to generic, so the label is dropped" diagnosis, and prescribed moving every
> `aria-label` onto its control. That was wrong for the two date rows: they go through
> `group()`, which supplies the role that makes the name legal. The `label` violations axe
> reports against their inner `<input>`s are real but expected — they are the acknowledged
> cost of the group pattern, where the row is named as a group of controls rather than each
> control being named. Closing that properly needs label forwarding in `DateTimeBox` (§3.3),
> which is shared and out of scope. Only the checkbox row was a genuine defect.

The grep for other instances of the wrapper mistake found **none**: every other
`FloorMapAria.label()` call in the feature targets a real control (`TextBox`, `ListBox`, and
`ColourBox`, whose root element *is* its input). One site, not a habit.

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
2. ~~**§9.3 and §9.2** — the `aria-label`-on-a-wrapper pattern.~~ **Done — §14.1, §14.2.**
   The grep for other instances found none: every other `FloorMapAria.label()` call targets a
   real control.
3. **§9.1** — Layers reorder focus loss. Small fix; makes a working feature usable. Note
   §13.5 found the same root cause behind it, so the two are related.
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

Incidentally, the live `font-weight` computes to `600`, as the stylesheet declares. An
earlier axe run had reported `normal`; the live value does not agree. Nothing about the
requirement changes either way, since 10px is never "large text".

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
| `hover-tooltip__caption` | 11px (inherited) | 6.93:1 ✓ | **5.75:1** ✓ |

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

So reaching a cell's control means finding the table's own tab stop and then arrowing to the
right row — indirect, and easy to miss.

> **Correction, 2026-08-17 — this section originally overstated the problem.** It claimed the
> controls were "unreachable by any keyboard route at all", on the grounds that
> [`MyDataGrid.setSelectionModel`](../stroom-core-client-widget/src/main/java/stroom/data/grid/client/MyDataGrid.java)
> enables the policy and then replaces the handler with an empty lambda. That code is real,
> and the quoted comment ("We need to set this to prevent default keyboard behaviour") is
> verbatim — but it lives in the **two-arg** override, and this grid calls the **one-arg**
> `setSelectionModel`, which GWT routes straight to `AbstractHasData` without going near it.
> So the Value Schema grid kept GWT's own `ENABLED` default *and* its
> `DefaultKeyboardSelectionHandler`: arrow-key row navigation and space-to-select were live
> here all along. The browser test that concluded otherwise had focused a container div found
> by search, most likely not the table's tab stop.
>
> The fix in §13.2 still stands on its own merit — putting the controls directly in the tab
> order beats requiring a user to discover a table tab stop and arrow to a row — but it is an
> improvement, not the rescue of something unusable. §13.3 records what the mistaken premise
> cost.

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
input on click — or on Enter, but only once the user has reached the cell through the table's
own navigation. Nothing in the tab order points at it directly. These cells are always inputs. **The visible consequence
is that the three text columns now look like the input fields they are.** That is a UI change
to the Settings tab and deserves a designer's glance, though for a settings grid it is
arguably clearer than click-to-reveal.

Labels identify the row rather than just the column, since six controls all called "Role"
are no better than none. `schemaCellLabel` builds them from the row's JSON path: `Role for .type`,
`Path for .tm-world-to-map`, `Default for .coords`. A blank path falls back to the row's
position — though note a *newly added* row does not take that fallback, since `onAddMapping`
seeds the path with the placeholder `"."`, so it announces as "Role for ." until edited.

### 13.3 Two defects the browser found that code review would not have

**A stray tab stop that sent focus backwards.** With the controls tabbable, the tab order
still jumped back on itself: GWT marks the keyboard-selected cell's wrapper `div`
`tabindex="0"` to give the table a tab stop, and as the selected cell moved that stop landed
*before* the control being tabbed away from.

This was "fixed" by setting `KeyboardSelectionPolicy.DISABLED`, and **that fix was wrong and
has been reverted** — see §15.1. Disabling the policy did produce a clean row-major order, but
it also switched off the table's arrow and space handling, and this grid's Remove button is
enabled solely by its selection model. Row selection became mouse-only, so Remove stopped
being operable by keyboard at all: an accessibility change that removed a capability, on the
strength of the §13.1 premise that the capability was already dead. It wasn't.

The stray tab stop is therefore still present, and is now understood as pre-existing
behaviour of every Stroom grid rather than anything this feature introduced.

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
  9m 4s: zero failing tasks and zero test failures. Checkstyle is clean against every file
  changed by this work with one carried-over exception: `FloorMapEditorPresenter.java` trips
  the warning-severity `FileLength` limit at 2,019 lines against 2,000, as §4 already records
  — this work added 12 lines to a file that was already close to it. `:stroom-app-gwt:gwtDraftCompile` also succeeds — the only step that
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

Applied 2026-08-17 and **verified in a running browser on 2026-08-18** — see §16 for the
results. Each item below states what was checked.

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
* `setMapSummary` writes the summary to both elements — the container so the image is named
  when browsing, the focus panel so it is spoken on focus. **But not while the focus panel
  holds focus** (§15.2): renaming the focused element is an announcement, and the summary
  embeds the clock. The panel's name is re-synced on blur.
* `setMapDescribedBy` writes **only** to the container (§15.2). Pointing the focusable element
  at the Tracking grid's id made every tab-in recite the whole grid.

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
existed, and re-testing with a wait has now confirmed it works (§16). The original finding was
a measurement race.

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

---

## 15. Code review of the branch, and what it changed

The whole branch (`dashboard-type..HEAD`, 13 files) was reviewed in four independent passes
on 2026-08-17, covering the new cells, the Settings presenter, the views, and the CSS plus
this document's own accuracy. Everything below was found by that review and has been fixed.

Nothing in the executable code was found wrong by the two reviews that diffed it against the
GWT sources: the overridden `render()` methods mirror the superclass view-data handling
faithfully, the `<select>`-as-first-child assumption that GWT's `onBrowserEvent` depends on
holds in every branch, the SafeHtml attribute escaping is correct — which matters, since the
labels embed a user-entered JSON path — the move arithmetic is right in both directions, the
read-only propagation is sound, and the Layers layout change is safe (the mount chain was
traced: `.max` and the dock's own class compute identically to the previous inline sizing).

### 15.1 The regression: `KeyboardSelectionPolicy.DISABLED` — reverted

The most consequential finding, and the reason to run a review at all. Set to remove the
stray tab stop of §13.3, it also disabled the table's arrow and space handling, and this
grid's Remove button is enabled solely by its selection model — so row selection became
mouse-only and **Remove stopped being operable by keyboard**. The justification rested on the
§13.1 premise that the table's keyboard handling was already dead here, which is false for
this grid (see the correction in §13.1).

Reverted, restoring GWT's default `ENABLED` policy and its keyboard handler. The stray tab
stop returns with it, and is left as pre-existing shared-widget behaviour rather than traded
for a lost capability. The call site now carries a comment saying so, to stop the change being
made again.

### 15.2 Two ARIA regressions in the §14.1 canvas fix

* **`aria-describedby` on the focusable element recited the entire grid.** The id names the
  whole Tracking / Fact List grid, and an accessible description is that element's flattened
  text, re-read after name and role on *every* visit — so tabbing to the map read out one line
  per entity, every time. Now on the container only, where it is reached in browse mode. If
  the route to the grid should be spoken on focus it needs a short fixed sentence of its own.
* **The summary was rewritten onto the focused element about three times a second during
  playback.** Renaming a focused element is an announcement, and the summary embeds the clock,
  so this recreated exactly the timestamp chatter the live region's `playing` guard exists to
  suppress. The focus panel's name is now only written while it does *not* hold focus, and
  re-synced on blur.

### 15.3 The Layers announcement could swallow a legitimate repeat

The dedupe copied from the canvas view suppressed any message identical to the previous one,
and the silent reorder paths (drag, external `setLayers`) never updated it. Move a layer down,
drag it back, move it down again and the correct message is byte-identical to the last —
suppressed, leaving that keystroke unannounced. The guard is gone; the region is cleared first
so a genuine repeat still registers as a change. The canvas keeps its guard, where it
re-announces recomputed state rather than discrete commands.

### 15.4 Comments that would have misled the next reader

Fixed in place. The two worth naming:

* **"calling `refreshGrid()` here as well changes nothing" was false**, and the removal is
  load-bearing. Mutating the provider's list marks one row and replaces just its children;
  `refreshGrid()`'s `setRowData(0, list)` covers the whole range and sends GWT down
  `replaceAllChildren`, destroying every row. The focus guard depends on other rows surviving,
  so acting on that comment would have broken it.
* **The `KeyboardSelectionPolicy` comment was wrong on both its factual claims** and invented
  an ordering requirement that does not exist — see §15.1.

Also corrected: `EditTextCell` described as pointer-only (it also enters edit on Enter, just
not from the tab order); a `schemaCellLabel` fallback that a new row never takes; a
`replaceMapping` javadoc still claiming it refreshes the grid, and missing its new `@param`; a
hollow "keeps the method testable" justification on a `private static native` method; a
"third copy" claim about `AnnotationEditPresenter`, which has a different JSNI method
entirely; and an "aria-label is silently dropped" assertion softened to the spec position it
is, since browsers vary.

### 15.5 Reuse and hardening

* `focusSchemaControl` hand-rolled a tag scan that `FloorMapAria.focusFirstFocusable` already
  did — in the same feature, and it additionally skips `disabled` and `tabindex="-1"`
  candidates and reports whether it found anything. Now delegates, and logs a failure instead
  of returning silently.
* `labelInnerControl` did **not** apply that filter, while its sibling did. Both now share one
  private `firstFocusable`, so they cannot disagree about what counts as the control inside a
  widget.
* The live-region attribute triple was copy-pasted between the canvas and Layers views — the
  drift that invited §15.3. Both now call `FloorMapAria.liveRegion`.
* The `COL_*` positional constants are gone: the column is passed as the `Column` itself and
  its index resolved from the grid when needed, so inserting a column cannot silently send
  focus to the wrong cell.
* A null-returning `RowLabelProvider` would have thrown inside the generated SafeHtml escaper.
  Both cells now coalesce.
* The floormap's small text now uses a local `--floormap-accent__color`, aliased to the button
  token rather than referencing it 12 times directly. Tooltips silently tracking a *button*
  colour was a layering smell, and the file already had this pattern in
  `--floormap-out-of-range__color`.

### 15.6 A better fix that was available and not taken

`CustomCheckBox` has its own labelling mechanism: `setLabel()` plus `setIdentity()` wires its
internal `FormLabel`'s `htmlFor` to the real input, and the ui.xml already passes
`identity="floorMapTimelineLoop"` with `label=""`. Using it would have produced a genuine
`<label for>` — click-to-toggle, no ARIA at all — which is strictly better than the
`aria-label` of §14.2.

Not taken, because it moves the visible label inside the widget and replaces the separate
`Label` in the template, and no browser was available to check the result. Worth doing when
someone can look at it.

### 15.7 Corrections to this document

The review checked the document against the code and recomputed every quoted ratio. Eleven
inaccuracies were found and fixed. Three predate the branch: two wrong contrast figures in
§2.6 (6.5:1 → 5.81, 8.4:1 → 7.89, both also wrong in the CSS comments they mirror, now
corrected there too) and a usage count of 14 that was 15.

The substantive one was **§9.3, which gave the wrong diagnosis for two of its three rows** —
the Start/End Date wrappers carry `role="group"`, so they are not generic and their names are
not dropped. It now says so, and no longer prescribes a fix they do not need.

The rest were stale or self-contradictory: a §1 row and a §6 item still asserting the §9.1
focus finding as fact after it had been corrected; §11 still calling a completed item "the top
item"; §13.5 claiming no checkstyle warnings when §4 records a carried-over `FileLength`
warning; a cross-reference to text that does not exist; a wrong section number; a stale line
reference; and a font size read off the wrong element (14.4px against an inherited 11px).

---

## 16. Browser verification of §14 and §15

Run 2026-08-18 against the restarted instance, same method as §8. Every fix from §14 and §15
was checked. All passed.

### 16.1 The reverted regression (§15.1) — capability restored

The important one. With the policy back at GWT's default:

| Step | Result |
|---|---|
| Grid's own tab stop present | Yes — one `tabindex="0"` inside the grid |
| Remove button before selecting | Disabled |
| `Space` on the grid's tab stop | **Remove becomes enabled**, rows show as selected |
| `Down` afterwards | Keyboard selection moves; Remove stays enabled |

So row selection and therefore Remove are operable by keyboard again. This is the direct
confirmation that §15.1 was a real regression and not a theoretical one.

The Value Schema work is undamaged by the revert: **24 controls, 24 unique names**
(`Role for .type`, `Path for .type`, …), exactly as before.

### 16.2 The canvas (§14.1, §15.2)

| Check | Result |
|---|---|
| Focus panel role / name | `role="group"`, named with the live summary |
| `aria-describedby` on focus panel | **absent** — container only, as intended |
| Live region still announces | **Yes** — `Zoom 90%` on `=`. This was the regression the whole design was shaped around |
| Name churn while focused | **None** — label held at `Zoom 82%` while the map went to 90% |
| Re-sync on blur | **Yes** — label became `Zoom 90%` on tabbing away |

### 16.3 The Layers panel (§14.3) and §9.1

* **Focus survives a reorder.** With a 2-second wait, focus after `Down` was back on the moved
  layer's grip. **§9.1's focus finding is therefore closed as a measurement artefact** — the
  existing mechanism works, and nothing needed changing.
* **Announcements fire once per move, with no suppression.** Four moves produced four
  announcements — `background layer moved to position 2 of 4`, `… position 1 of 4`, then both
  again. Correct name, correct 1-based position, correct total, and repeated text still
  announced.

The one thing not reproduced is the *exact* §15.3 scenario, which needs a silent drag
interleaved between two identical keyboard results and cannot be driven from the keyboard. The
dedupe is gone from the code, so no suppression path remains.

### 16.4 Naming fixes (§14.2, §14.4)

* **Loop Playback checkbox**: the inner `input#floorMapTimelineLoop` now carries
  `aria-label="Loop Playback"`, and the `div.SimpleTickBox` wrapper carries none — the name
  moved off the element that could not hold it.
* **Events Query**: both `SelectionBox` inner inputs named `Entity ID Column` /
  `Location ID Column`.
* **Predicted violation counts confirmed structurally.** The only unnamed controls left in the
  Timeline Settings dialog are the two date-box inner inputs, **both inside a named
  `role="group"`** — the acknowledged §3.3 trade-off. The checkbox is no longer among them.

### 16.5 Still outstanding

* The **stray tab stop returns with the revert**, so the Value Schema tab order again includes
  the table's own stop and can jump backwards. Accepted deliberately: it is pre-existing
  shared-widget behaviour, and the alternative cost a working capability. Fixing it properly
  belongs with the `MyDataGrid` work.
* **Screen readers** (§6 item 2) — still the one item no amount of tree inspection can close.
* **Histogram items** (§6 items 6 and 7) — still need a document whose Events Store has data.
* **§15.6** — the better `CustomCheckBox.setLabel()` route, which needs a designer's eye on the
  moved label.

---

## 17. Histogram: the last two §6 items, and what they were hiding

The test document was given event data on 2026-08-18, which finally made §6 items 6 and 7
testable. Both pass. Measuring the bars for the first time also turned up a real contrast
failure that the absence of data had been concealing — the more valuable outcome of the two.

Only §6 item 2, the screen-reader pass, now remains open.

### 17.1 Item 6 — click-to-seek: pass

Clicking at 75% of the histogram's width moved the scrubber's `aria-valuenow` from 97 to
exactly **75** (`aria-valuetext` `2021-10-10 22:31`) and announced `Showing 2021-10-10 22:31`.
The enlarged scrubber hit area has not disturbed it.

### 17.2 Item 7 — theme repaint: pass

This was called out as the likeliest thing to be broken, because the repaint hangs off a
preference-change event and the bars are *painted into a canvas* — no CSS rule restyles them,
so a missed handler would leave them stuck in the old theme's blue. It cannot be tested by
swapping the theme class; it needs a real preference switch.

Sampling the canvas pixels either side of a genuine switch, with no reload:

| Theme | Painted bar pixel | Token |
|---|---|---|
| Dark (before) | `rgb(100,182,246)` | `--blue-300` |
| Light (after) | `rgb(30,135,229)` | `--blue-600` |

The bars repainted. The handler is wired correctly.

### 17.3 What measuring them exposed: bars failed 1.4.11 in light theme

With bars on screen for the first time, they could be measured. The bars are the chart's
content, so WCAG 1.4.11 asks 3:1 against the panel behind them.

| Theme | Token | Effective | Ratio | |
|---|---|---|---|---|
| Light | `rgba(30,136,229, 0.7)` | `#62aced` | **2.43:1** | ✗ |
| Dark | `rgba(100,181,246, 0.75)` | `#5491c4` | 4.55:1 | ✓ |

Same shape as the speed badge of §9.7: an alpha multiplier eating the headroom of a colour
that was already marginal. Note this had nothing to do with the accent work in §12 — the
histogram reads `--blue-500`/`--blue-600` directly and was never touched by it.

**Fixed** in `stroom-histogram.css`: the light theme's bar becomes `rgba(21,101,192, 0.8)`
(blue-800) and its peak the same hue at full alpha, keeping the "peak is more of the same"
reading the file's own comment asks for. The translucency is retained, deliberately — it is
what lets the scrubber show through. Dark theme is unchanged, having already passed.

Verified live after the change: bars paint `rgba(21,101,192, 0.8)`, effective `#4484cd`,
**3.87:1** on the white panel, with the peak at full alpha as intended.

`--histogram-bar__color` lives in the shared `stroom-histogram.css`, but `HistogramWidget`'s
only consumer is `FloorMapTimelineViewImpl`, so this is floormap's in practice.

### 17.4 The histogram's text alternative is better than §1 credited

§1 rated it "Adequate — summarised, not explorable". With data it reads:

> Event distribution over time: 99 events in 100 intervals, busiest interval has 7, about 6%
> of the way through the range

Count, distribution, peak magnitude *and* peak position — a well-judged summary of a chart
that cannot be walked. The §1 row has been raised accordingly.

### 17.5 Chattiness, re-verified against real data

§6 item 1 originally passed against a document with **no events**, which was weak evidence for
a claim about playback. Repeated with real data: eight seconds of playback, time advancing from
`22:31` to `00:44`, entities moving — and exactly **one** announcement (`Playing`). Time is
genuinely suppressed during playback.
