# Floor Map — accessibility status

**Scope:** the Floor Map feature on branch `enterprise-floor-mapping-accessibility` —
the Map tab (SVG canvas, timeline, Tracking / Groups / Layers dock panels), the Editor
tab (canvas, Fact List, Time List, Layers, dialogs), and the shared
`HistogramWidget` the timeline uses.

**Target:** WCAG 2.2 Level AA.

**Assessed by:** code review of the branch diff against `master`, plus a build-level
verification pass. **Not** yet verified in a browser or with a screen reader — §6
records exactly what is still unproven, and that distinction matters when reading
everything below.

---

## 1. Summary

| Area | Status |
|---|---|
| Timeline transport controls (play, step, settings) | **Good** |
| Timeline scrubber (keyboard + ARIA slider) | **Good** — fixed on this branch |
| Speed badge | **Good** |
| Histogram | **Adequate** — summarised, not explorable |
| Tabular panels (Tracking, Fact List, Time List, Groups) | **Good** |
| Layers panel | **Good** — fixed on this branch |
| Dialogs (Object Edit, Settings, Init, Set Scale, Group Edit, Layer Style) | **Good** — fixed on this branch |
| Map canvas — text alternative | **Adequate** — summary + linked grid |
| Map canvas — keyboard operation | **Adequate** — view yes, per-entity no |
| Cluster membership detail | **Gap** — pointer-only |
| Reduced motion | **Good** — fixed on this branch |
| Colour contrast | **Good** for text and indicators; **unmanaged** over user imagery |

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

**No browser or screen-reader testing has been done.** The claims in §2 are derived from
the code. The following in particular need a real check, because they are the ones code
review cannot settle:

1. That the live region announces at the right moments and is not chatty — especially
   that suppressing time during playback is sufficient.
2. That `role="img"` plus `aria-hidden` on the SVG really stops screen readers walking
   the captions, across NVDA, JAWS and VoiceOver.
3. That focus is visible in both themes on the slider, canvas, grip and swatch.
4. That keyboard reordering in the Layers panel keeps focus in practice, and that the
   arrow keys do not also scroll the panel.
5. That `Enter` opens the *object* context menu, beside the object, on the Editor tab —
   and is not swallowed on the Map tab.
6. That the enlarged scrubber hit area has not disturbed histogram click-to-seek.
7. That the histogram bars change colour when the theme is switched **without**
   reloading — the repaint is driven by an event, so this is the one place a missed
   handler would show as bars stuck in the old theme's blue.

[§9 of the manual test plan](floormap-test-plan.md) is written for exactly this: a
keyboard-only pass, a screen-reader pass, and a reduced-motion/contrast pass, with the
likely regressions called out. **The status in this document should be read as
"implemented and building" rather than "verified accessible" until that has been run.**

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
