# FloorMap × Domain Types × Layers — Program Plan

**Status:** Decisions ratified · 2026-07-20 · branch `enterprise-floor-mapping-editor`
**Sits above:** [`floormap-layers-implementation-plan.md`](floormap-layers-implementation-plan.md) · [`floormap-domain-types-implementation-plan.md`](floormap-domain-types-implementation-plan.md)
**Source proposals:** [`floormap-layers-ux.html`](floormap-layers-ux.html) · [`floormap-domain-types.html`](floormap-domain-types.html)

The strawman decisions have been reviewed and ratified. This doc records the settled decisions, the
co-owned components, and the two-track delivery sequence. Per-phase scope, UI outcomes and test scripts
live in the two implementation plans.

## 1. Shape of the program

One program, **two packages on a shared spine**, meeting at **one convergence point**:

- **Package L — Layers:** self-contained editor UX (visibility, lock, opacity, restack, presets).
  Small model change + GWT. Low risk, ships value early.
- **Package D — Domain Types:** semantic linking (jump to/from, style by meaning). Requires
  generalising cross-cutting "Jump to" plumbing that today assumes the destination is a dashboard.
  Higher risk, bigger blast radius.
- **The spine:** the type/domain-type model, the styling source of truth, and one legend/swatch
  surface. ~15% of each package, all in the mid/late phases; the tracks run in **parallel** and
  converge only when style-by-meaning lands.

**The packages are not merged** — merging would hold the independent, high-value early phases hostage to
the hardest cross-cutting work. Instead: the spine decisions are settled once (below), the three shared
components are Layers-owned, and the tracks run in parallel.

## 2. Ratified decisions

### Spine — governs both packages

| # | Decision | Outcome |
|---|----------|---------|
| **1** | Additive or replace? | **Additive.** An object gains a domain type *alongside* the free-text `type`; no migration; every map keeps working. |
| **2** | Where is a thing's "look" defined? | **`TypeStyle` now; move to domain-type-keyed at Theme C**, via one accessor so it's a data-source swap, not a UI rewrite. |
| **3** | Is a layer a type or a richer grouping? | **Ship A (layer = type); design toward C (rule-based). B only if proven.** |

### Package D — Domain Types

| # | Decision | Outcome |
|---|----------|---------|
| **4** | How far to generalise the "Jump to" lookup (WS-G0)? | **Minimal generalisation now** (interface + floor-map store); full registry (G19) later. |
| **5** | What is Domain-Types "v1"? | **Tier 1 = v1; Tier 2 fast-follow.** |

### Package L — Layers

| # | Decision | Outcome |
|---|----------|---------|
| **6** | Where does the Layers panel live — one surface or two? | **Single (shared) component.** A **RHS panel on both the Map and Editor tabs** (viewer mode) and, in **editor mode**, on the Settings tab in place of the retired bespoke Type Styles grid. Map-tab panel is **toggle-only**. *Implementation note (2026-07-20): took the lower-risk path — persisted type editing (Discover/reorder/shape/colour) stays on the **Settings** tab via the shared panel, so the document save path is unchanged; making the Editor's RHS panel an editable surface (single writer on the Editor) is deferred.* |
| **7** | Are events (people) just another layer? | **A layer for v1**; revisit clustering/trails later. |
| **12** | Do live layer controls persist? | **Not to the document, but sticky in the client session.** Visibility, lock, solo and opacity are applied to the canvas and **never written to the document** — but they **persist in client memory across a save→reload** (they live on the editor presenter; the save/reload path re-applies rather than resets them) and clear only when the document is closed and reopened. `TypeStyle` gains **no** `visible`/`locked` fields. The only *document-persisted* layer state stays shape/colour/z-order, plus **presets** — a saved named view is the one way to persist a visibility/opacity snapshot for other users / other sessions (L-Phase 3). *(Ratified 2026-07-20.)* This also makes the Settings-grid retirement a pure UX consolidation (no persistence-conflict driver). |

### Deferrable

| # | Decision | Outcome | Revisit at |
|---|----------|---------|-----------|
| 8 | Reorder UX | Arrows ship; HTML5 drag is deferred polish | L-P1 (arrows) / later (drag) |
| 9 | Smart-layer overlap precedence | Topmost matching layer wins; validate on real data | L-Later |
| 10 | Full action registry (G19) & deps (G20) | Deferred beyond the WS-G0 seed | D-Tier 3 |

### Vocabulary ownership

| # | Decision | Outcome |
|---|----------|---------|
| **11** | Who owns the domain-type vocabulary? | **Owned per Content Pack via its `DomainTypeDoc`** — a pack ships the definition of its own domain types (and, at Theme C, their style). |

### Out of scope (program directive)

- **Consistency of styling across domain types** — out of scope.
- **Consistency between content packs** — a future problem, not addressed now.
- **Replacing / migrating** the free-text fact type — additive only (decision #1).
- **Writing live layer visibility / lock / opacity to the document** — not done; they persist in the client session only (decision #12). Save a view as a preset to share it across users/sessions.

## 3. Co-owned shared components (Layers-owned; Domain Types consumes at M3)

These are the seams — the risk of *not* co-owning them is two incompatible builds. **Layers is the
owner**; Domain Types (Theme C) writes style data and reads the widgets.

1. **The styling accessor** — the one function that resolves "a thing → its icon + colour" (decision
   #2). Source is `TypeStyle` now, `DomainTypeDoc` after Theme C; callers don't change.
2. **The swatch** — one reusable chip (`renderSwatch(shape, colour, size)`) that renders a thing's
   icon-shape + colour, fed by the styling accessor. It shares geometry with the canvas glyph
   (`FloorMapShapes.polygonPoints`) so a legend diamond matches a canvas diamond. Used in:
   - a **Layers panel row** — the colour square left of the name (`▪ Desks (12)`);
   - the **canvas semantic legend** — `◆ Camera  ▲ Gate  ● Live person`, each entry toggling that
     meaning;
   - a **Fact List row / dashboard cell** decorated with the type's icon+colour (C9);
   - an **object tooltip / "Jump to" menu header** so you recognise what you're acting on.
3. **The legend / filter surface** — Layers' smart-layer legend (L-Later) **is** Domain Types' semantic
   legend & filter (C11). One control, built once, in the Layers package.

## 4. Delivery sequence

Two tracks, parallel, converging at **M3**. (Milestones are ordering, not dates.)

| Milestone | Track L — Layers | Track D — Domain Types | Demoable outcome |
|-----------|------------------|------------------------|------------------|
| **M0 · Decision spike** | — | — | Decisions ratified (this doc); styling-accessor shape fixed. No build. |
| **M1 · Independent core** | P1 panel (single surface, both tabs) + visibility + solo; retire Settings grid | WS-G0 (generalise jump plumbing) → B6 fact-list "Jump to" → D12 pickers | A RHS Layers panel hides/solos types on both tabs; domain-typed grid cells "Jump to". |
| **M2 · Round out v1** | P2 lock + opacity | A1·A2 register as destination (+A3 land-at-time); D14 validate | Lock/dim layers; a map is a "Jump to" destination landing at the right object + time. **L and D each reach a shippable v1.** |
| **M3 · Convergence** ★ | swatch/legend read the shared style; P3 presets | Theme C: C8·C9 style-by-meaning, C10 smarter Discover, C11 semantic legend; D13 auto-detect; B4·B5 act-from-object | Same thing looks the same everywhere; act-from-object; one semantic legend/filter. Style lands on `DomainTypeDoc`. |
| **M4 · Ambitious (later)** | Smart / rule-based layers | Tier 3: E15 bind-by-meaning, E16 drill-down, F17/F18, G19/G20 | Rule-based layers; bind-by-meaning; drill-down; where-used. |

```
Track L  P1(panel+vis) ── P2(lock+opacity) ──────┐
                                                  ★ M3 (style by meaning = one swatch/legend) ── smart layers …
Track D  G0─B6─D12 ────── A1/A2─D14 ─────────────┘                                             ── Tier 3 …
         └──────────── run in parallel ───────────┘        └── converge ──┘
```

## 5. What the convergence (M3) actually joins

- Layers stops reading the free-text `TypeStyle` for its swatch and reads the **shared style**
  (decision #2 flips source here) — a data-source change, not a UI rewrite (§3.1).
- Domain Types' style-by-meaning (C8/C9) writes a per-domain-type look onto **`DomainTypeDoc`** (which
  has no style field today) and consumes the Layers-owned swatch/legend.
- The **semantic legend** (C11) and the **smart-layer legend** are the same surface — built once in
  Package L, reused by both.
- Everything before M3 is independent; nothing before M3 needs the other track.

## 6. Risks carried into planning

- **Jump plumbing is dashboard-only** — WS-G0 is the single heaviest item; if under-scoped, A/B fragment
  into copy-pasted variants.
- **Map-tab layout change** — the Map tab is flat `SimplePanel`s today; hosting the RHS panel is the one
  genuinely new layout bit in L-P1.
- **Two type systems** (`TypeStyle.type` vs `DomainType`) bridged at M3 via a style field on
  `DomainTypeDoc`; the styling accessor (§3.1) must exist from M0 or the swatch/legend get rewritten.
- **Matching while animating** — semantic style/binding must be precomputed & cached, not per frame.
- **Transient layer state** — visibility/lock/opacity are session-only (decision #12); the sole persisted
  layer state is shape/colour/order + presets. (This removes the earlier legacy-flag / two-writer risks.)

## 7. Status

All decisions ratified. The two implementation plans carry the workstreams, per-phase UI outcomes and
per-phase test scripts. Next step is to size the M0–M4 milestones and cut each phase into tickets.
