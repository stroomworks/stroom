# Floor Map branch — remediation plan

**Branch:** `enterprise-floormapping-code-review-b`
**Base:** `origin/master` (local `master` is stale and fully contained in the branch; always diff two-dot against `origin/master`, or you pull in ~8,000 files of unrelated upstream churn)
**Source:** code review of 21 Aug 2026 (455 files, +67,758 / −1,529)
**Status:** see *Status at a glance* below. That section is the authoritative record; the per-finding sections in Part B carry their own markers but the summary is the thing to read first.

## Decision log

- **2026-08-21 — D1 answered: there is no production or customer data in any of these systems; it is all
  new.** This is the single most load-bearing answer in the plan. It closes D2 and D4 outright, reduces
  F1 from roughly a week at High risk to roughly a day at Low risk, and changes F1's shape entirely:
  the migration has never been applied to any real database, so it is **corrected in place** rather
  than superseded by a second migration with a name→UUID backfill. F1 moves from "schedule separately"
  to the front of the queue. See F1 for the revised approach.

Every finding below was verified by reading the implementation. Two items that *looked* dangerous were
cleared and are recorded in Appendix A so nobody "fixes" them by mistake.

## How to verify any change in this plan

```
./gradlew check                       # full build: all tests + checkstyle. Never module-scoped.
./gradlew :stroom-app-gwt:gwtDraftCompile   # required for any client-side change
```

Repo-wide guards exist (e.g. `TestJsonSerialisation` fails on an undeclared getter), so module-scoped
test runs give false confidence.

---

# Status at a glance

Last reconciled against `git log origin/master..HEAD` on **2026-09-01** (24 commits).

## Done

| | Finding | Commit(s) |
|---|---|---|
| F1 | Temporal store keyed on document name | `94d101554d` |
| F2 | Config keys renamed with no deprecation shim | `44d344cee2` |
| F3 | Every SAX event logs at INFO | `438106dc1f` |
| F4 | Unbatched insert per reference entry | `a6432bd258` |
| F7 | `hasInverse()` promises a check it does not perform | `c7d9573e6b` |
| F12 | Name-clash guard leaks document existence | `94d101554d` |
| C1–C7 | Javadoc corrections (~65) | `bc9abcedf4` |
| — | Migration never ran at bootstrap (found by running it) | `9c23723c9a` |
| — | Four trail defects (found by running it) | `0cdec0fef9` … `e62fb67de9` |
| — | Events store → Plan B, and its playback fallout | `db0cd682ee`, `8ee09c4952` |
| — | Assets never exported, imported, copied or deleted | `ec9c6298e3` |
| — | Write-batch boundary untested | `44e68f0e56` |

## Partly done

| Finding | Done | Left |
|---|---|---|
| F5 | Store resolved once per pipeline run (`8cc85ee889`) | Value cache — **deferred**, raised as `task-sqlstore-lookup-value-cache.md` |
| F6 | Per-frame rebuild and trail growth (`7ab7b0bdbd` + trail commits) | Architecture tier, deferred by D8; see `task-floormap-incremental-canvas-render.md` |

Both deferred tiers are written up as standalone, self-contained issues in `docs/`; neither is blocked on a decision, only on capacity.
| F8 | Histogram no longer reads `longtext` (`9400f3359c`) | `search`/`fetchAll` still end in `.fetch()`; `fetchLazy` + a histogram-only query |
| F11 | 6 of 11 items (`438106dc1f`, `9400f3359c`, `a6432bd258`) | 4 items — see below |

## Open

**Local fix landed, destination still upstream:**

- **F13 · Plan B has no server-side latest-per-key** — HIGH, and a behavioural regression introduced with the Plan B move: an entity that stops emitting disappears after 20 seconds, where the SQL Temporal Store returned its last known position however old. Exactly **one** Map feature is affected — positions; the histogram works natively on Plan B and everything else is downstream. The 20s window is a workaround for the missing reduction and **nothing consumes it**. Two tracks (**F13**): the destination is a server-side reduction in `TemporalStateDb.search`, mirroring what `UpdatableTemporalStoreDaoImpl.search` already does — but its trigger and date-parser questions are **upstream Plan B policy**, so the local plan of record is client-side carry-forward (Option C). Option B is broken by construction. Reviewed twice; both earlier framings of the fix were wrong and are recorded as such. **Option C is implemented as of 2026-09-03** (`0bab388b8c`) — see *As built*, which records five places the implementation diverged from the plan, including one the plan and four reviews all got wrong (a loop wrap froze the map). **Manual testing effectively complete, 2026-09-04.** Ten of Group A pass against real fixtures in `System / Floor Map Test`: the reference state (= A1), horizon pruning both ways, both scrub directions, stop-at-end, loop-at-speed, hidden-tab, the standstill cadence, the truncating baseline, and no behavioural change on a SQL Temporal Store (run against the pre-existing `Enterprise Floor Mapping Demo / Floor Map`, which is a real SQL-store map rather than a reconstruction). Two turned out not to be tests: the condense case is unrunnable (1-day floor, see *As built*), and the never-written-store case returns a clean empty result so silence is the correct outcome. Two low-value ones remain. Detail and per-test observations in `floormap-test-plan.md`. The condense test is **not runnable** — see F13's *As built* note on the 1-day floor. Full state in `floormap-test-plan.md`. The upstream ask stands: nothing local depends on it any more, but until it lands the horizon and the two `condense`-related instructions in `floormap-planb-events-store.md` stay.

**Blocked on a decision:**

- **D7** — why was `try (this)` replaced? Needs the author. Blocks **F10**.
- **F11 · asset servlet** — uploads served same-origin as `html`/`js`/`svg`, vis iframe has no `sandbox`. Sandboxing may break visualisations needing same-origin access.
- **F11 · playback search churn** — ~6–7 destroy/create/poll cycles per second per Map tab. Needs a correct staleness rule or the map shows stale data. **Not fixed by F13's delta read**, and worth being clear about that: the search *count* is unchanged, because a tick still starts a facts search and an events search. What dropped is the rows each one carries, plus the ticks where the events read is skipped entirely. The churn itself is still there. **F15 removes the facts half of it** — see there.
- **Entity type from `Event Type`** — `parseRows` matches a column named `type` case-insensitively; the default query aliases it `Event Type`, so it never matches and entity type always comes from the `entityId.contains("@")` fallback. Fixing it changes rendering for existing maps.
- **Init dialog hardcodes `ValueFormat.JSON` + `initialValueSchema()`** — a deployment whose facts are XML or use other paths gets a new floor map that cannot parse them. Was wrongly suspected as the cause of the missing-layers symptom (see Appendix F.1); the design gap is real regardless.
- **Map tab's Layers panel never shows discovered types** — it is a separate `FloorMapLayersPresenter` instance from the Editor's and only ever receives `setLayers(document.getTypeStyles())`, never `setSeenTypes`, so provisional types appear in the Editor only. **The decision is whether that is intended**: if yes it needs documenting, if no it needs wiring. Either way an empty panel on the Map tab is currently indistinguishable from missing data.
- **F15 · re-fetch cadence** — 30 s or 60 s, once F15 is built. 30 s recommended; 60 s reuses `FloorMapEventState.BASELINE_INTERVAL_MS`. Only felt by someone watching for their own write to land.
- **The `fetchAll` OpenAPI correction** — the removed `ONE_DAY_MS` claim was in the published API description, so an external consumer may have built against a cap that never existed. Whether and whom to tell. See D6.

**Ready to do, no decision needed:**

- **Entity ID Column and Location ID Column need help text** — requested 2026-09-04. The two settings on the **Events Query tab** are the least self-explanatory controls in the feature and the most damaging to get wrong: each must name a **column the events query actually selects**, and if either does not match, `parseRows` matches nothing, no entity reaches the canvas, and the map looks as though playback is switched off while the query still returns rows. Nothing on screen says any of that. See *Help text* below.
- **F8 remainder** — `fetchLazy()` and a histogram-only query.
- **F11 · no upload size cap** — `MAX_EDITABLE_CONTENT_LENGTH` (512 KiB) gates editing, not upload; nothing bounds what streams into the blob.
- **F11 · `DocumentPluginEventManager` banner** — says opening a document routes through the initialisation handler; the only call site is `fireShowCreateDocumentDialogEvent`, i.e. creation. Verified still wrong. Worth fixing precisely because wiring that call into the open path would make Cancel delete an existing document.
- **Settings-tab state-type check** — the init dialog validates that a Plan B events store is `TEMPORAL_STATE`; the Settings tab does not. Needs an async fetch where `onWrite` is synchronous, so it wants a validation hook rather than an inline check.
- **Events query should carry a raw numeric time** — `latestPerEntity` compares the *rendered* time, so a non-lexicographic date pattern preference picks the wrong row. **Narrowed by F13, not closed:** the default query now reduces by arrival order and never looks at the time column at all, so this is reachable only for a query that sorts or that takes its entity id from somewhere other than the store `Key` — see `FloorMapEventsQueryOrder`. The honest fix is still a query-contract change carrying a raw numeric time. See Appendix F.1.
- **F14 · four silent failure paths in the events pipeline** — of the four stages that can produce nothing, three say nothing when they do, and the two most likely first-run failures are among them. Every diagnostic goes to the browser console, where a non-developer will not see it. Written up as **F14**.
- **F16 · a trail reappears when an entity starts moving again** — UNTRIAGED, reported from manual testing 2026-09-04 and recorded so it is not lost. Cancelling a trail's fade because the entity moved again leaves `entityTrails` intact, so the new movement resumes the old trail at full opacity rather than starting a new one; age trimming runs only when a point is recorded, so stale points survive a stationary period and are dropped on the first frame of the next movement. A second candidate is F13's accepted one-tick flicker, which the animator would record as movement and draw — an assumption worth revisiting, since a trail makes a one-tick flicker linger. Not diagnosed, not reproduced, no decision needed yet. Written up as **F16**.
- **F15 · the facts query polls 3×/second for data that changes weekly** — the poll is not serving playback (the answer is almost always identical); it is serving external write detection, at 300 ms intervals, for facts the user says change about once a week. Fetching full history once with `timeRange = null` and computing the snapshot client-side makes playback, scrub and step zero-query for facts, removes half of F11's result-store churn, and improves accuracy at high playback speed. The obvious forward-window version is broken by the same snapshot-lifting the parity report documents, and is recorded so it is not re-proposed. Only open question is the re-fetch cadence; recommend 30 s. Written up as **F15**.

---

# Part A — Decisions needed before work starts

These cannot be settled from the code. Each blocks or reshapes the fix that follows it.

| # | Decision | Blocks | Status |
|---|----------|--------|--------|
| ~~D1~~ | ~~Is there production/customer data in `updatable_temporal_store` yet?~~ | F1 | **Answered 2026-08-21: no, all new** |
| ~~D2~~ | ~~Full UUID re-key, or block-the-dangerous-operations stopgap?~~ | F1 | **Closed by D1: re-key, no stopgap** |
| ~~D3~~ | ~~Keep global name uniqueness?~~ | F1 | **Settled by F12: guard must go, so F1 is mandatory and lands first** |
| ~~D4~~ | ~~What happens to rows already orphaned by a rename?~~ | F1 | **Moot: no rows exist** |
| ~~D5~~ | ~~Which of three cases is the deployed `config.yml` in?~~ | F2 | **Moot: the alias accepts all three.** No need to check |
| ~~D6~~ | ~~Was the `ONE_DAY_MS` future-margin bound intended behaviour?~~ | F9 | **Answered 2026-09-04: no — docs deleted, F9 done** |
| D7 | Why was `try (this)` replaced? The stated reason is false. | F10 | Open — needs the author |
| ~~D8~~ | ~~Is the canvas render rewrite in scope pre-release?~~ | F6 | **Decided: cheap half now, architecture deferred to its own task** |
| ~~D9~~ | ~~Keep the asset module move, or revert it?~~ | F2 | **Decided 2026-08-26: keep it.** Merge cost accepted; F2 solved by alias + migration |

Eight of the nine are now settled. **One remains:** D7, a question for whoever replaced
`try (this)`. It blocks F10 and nothing else.

### ~~D1 — Is there production data in the temporal store yet?~~ — ANSWERED

**No. None of these systems hold production or customer data; it is all new.**

Consequences, in order of importance:

1. F1 needs **no backfill and no second migration**. The migration has never been applied to a real
   database, so `V07_13_00_001__updatable_temporal_store.sql` is corrected in place.
2. D2 is closed — go straight to the proper UUID re-key; the stopgap was only ever a way to defer a
   risky migration, and there is no longer a risky migration.
3. D4 is moot — no rows exist, so none can be orphaned.
4. F1's risk drops from High to **Low**, and it moves to the front of the sequencing.

**One coordination note, not a risk:** anyone whose dev database has already run the migration will
get a Flyway checksum mismatch when the file changes underneath them. The fix is to drop the table and
its `sqlstore_schema_history` row, or rebuild the dev schema. Worth announcing to the team in the same
message as the merge, because the error message is unhelpful if you are not expecting it.

### ~~D2 — Full UUID re-key, or a stopgap?~~ — CLOSED BY D1

Proper re-key. The stopgap (block renames, reject colliding names) existed to remove the blocking risk
without committing to a migration on live data. With no live data there is nothing to defer, and
shipping a guard that papers over a schema flaw nobody has hit yet would be pure waste.

Two pieces of the stopgap are still worth keeping, but as correctness improvements rather than
mitigations: the **name-uniqueness guard** and routing **copy through `UniqueNameUtil`** (which
`stroom-sqlstore` does not currently use at all, so copying a store produces an exact duplicate name).
Both fold into F1 — see D3.

### D3 — Name uniqueness: keep the constraint, or let F1 remove it? — REFRAMED 2026-08-21

**Correction first: a uniqueness guard already exists, and I was wrong to say it did not.**
`SqlTemporalStoreDocStoreImpl` overrides all three mutating paths and rejects name clashes outright:

```java
public DocRef createDocument(final String name) { checkNameNotInUse(name); ... }
public DocRef copyDocument(...)                 { checkNameNotInUse(name); ... }
public DocRef renameDocument(final DocRef docRef, final String name) {
    checkNameNotInUseByOther(name, docRef.getUuid()); ...
}
```

with an error that explains the reason: *"A SqlTemporalStore with name 'X' already exists. Names must be
unique because they are used as map identifiers."* The author was fully aware of the name-as-key design
and guarded it. This is **stricter** than the `UniqueNameUtil.getCopyName` convention other stores use —
that auto-renames to "X - Copy", this refuses. Refusing is the right call here, because silently
auto-renaming a store would silently re-point its data.

So two of my earlier claims were wrong and are withdrawn: copying does **not** produce a duplicate name,
and the "two stores named `locations` share one dataset" scenario is **not** reachable through create,
copy or rename.

#### What the guard does not cover

1. **Rename is still permitted — the data-loss path stands.** `checkNameNotInUseByOther` only ensures the
   *new* name is free. The rename then succeeds and nothing re-keys the table, so the store's rows are
   orphaned under the old name. This is F1's headline failure and the guard does not touch it.
2. **Case and accent mismatch — a live hole.** The guard compares with `.equals(name)`, but the storage
   column is `utf8mb4_0900_ai_ci` (case- *and* accent-insensitive) and the pipeline lookup gate in
   `ReferenceData` uses `equalsIgnoreCase`. So `Locations` and `locations` are two distinct documents to
   the guard but **the same rows** in the table, and both match the same XSLT lookup. `café` and `cafe`
   likewise. This reinstates the shared-dataset bug through a narrow door. **CONFIRMED.**
3. **Import bypasses the guard.** `importDocument` is not overridden, so an import can land a colliding
   name that create/copy/rename would have refused. **CONFIRMED** that the override is absent; the
   consequence depends on import behaviour, which I have not traced.
4. **`copyDocument` drops the base class's permission check.** `AbstractDocumentStore.copyDocument` calls
   `checkDocumentPermission(docRef, VIEW)` first — its javadoc says copy "is authorised by VIEW on the
   source" — and the override omits it, calling `getStore().copyDocument(...)` directly.
   `ExplorerServiceImpl` does appear to check VIEW on its own path, so this is most likely lost
   defence-in-depth rather than an exploitable hole. **PLAUSIBLE**, worth restoring regardless: the
   override should call `super.copyDocument(...)` after its name check, not bypass it.

#### The decision is now made for us — see F12

The team identified that the guard **leaks the existence of documents the user has no permission to see**:
it lists every store in the system with no permission filtering, then names the clash in the error. That
is an enumeration oracle, recorded as **F12**, and it means the guard cannot stay as written. The fix is to
use the house mechanism — the explorer's permission-filtered, folder-scoped `existingNames` plus
`UniqueNameUtil` auto-rename — which also disposes of gaps 2, 3 and 4 above, since all three live in the
overrides being deleted.

**That settles D3, and the consequence is the important part.** Adopting the standard mechanism means
duplicate names become possible again, exactly as for every other store. Names therefore cannot be relied
on as a unique key — which is precisely what the current storage design assumes.

So the chain is:

1. The guard leaks information, so it must go.
2. Without the guard, duplicate names are possible.
3. The storage key is the name, so duplicates share rows.
4. **Therefore F1 (the UUID re-key) is mandatory, not merely recommended — and it must land first, or in
   the same change as the guard removal.**

Fixing the leak on its own would trade an information disclosure for silent cross-document data loss,
which is a worse position than either problem alone. F12 records this and says explicitly not to do it.

The one thing still genuinely open is small: after F1, name-based *addressing* still needs an
ambiguity rule. When an XSLT `lookup()` or a StroomQL `from "X"` matches two stores named `X`, the choice
is fail loudly or pick one. **Recommend fail loudly** — a resolution-time error naming the ambiguity, on
the specific reference that is ambiguous, rather than today's silent `findFirst()`. Note that
`UpdatableSqlTemporalStore.hasPermission` uses the same unfiltered `list()`, so whatever resolution
mechanism replaces it must be permission-filtered too, or F12 simply reappears at lookup time.

### ~~D4 — What about rows already orphaned by a rename?~~ — MOOT

No data exists, so no rows can have been orphaned. Nothing to report, migrate or delete.

### D5 — Does any deployment set `visualisationAsset` / `visualisationAssetDb`? — NARROWED, and de-fanged

D1 covers Floor Map and the temporal store, but the **visualisation asset system already exists in
`origin/master`** (added in 7.11) and **is already in use with real data**, so it is not covered by
"this is all new". Two things I established from the code, which change this item substantially.

**1. The dangerous failure mode does not exist.** `stroom-app/src/main/java/stroom/app/App.java:155–158`
explicitly re-enables strict parsing, with a comment saying so:

```java
// Dropwizard 2.x no longer fails on unknown properties by default but we want it to.
bootstrap.getObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

So a leftover `visualisationAssetDb` key causes a **hard startup failure** naming the unrecognised
field. It cannot be silently ignored, which means the "falls back to the default datasource and the
assets look deleted" scenario I originally flagged **cannot happen**. That was the only genuinely
nasty outcome here. What is left is a loud, self-diagnosing startup failure fixed by editing one key.

**2. Existing asset data is safe regardless of which database it lives in.** Verified:

- the new DAO reads the **same tables** — `VISUALISATION_ASSETS`, `VISUALISATION_ASSETS_DRAFT`,
  `VISUALISATION_ASSETS_UPDATE_DELETE`;
- the servlet serves the **same path**, `PATH_PART = "/assets/*"` in both old and new, so existing
  asset URLs in published visualisations keep resolving;
- the Flyway history table name and migration file are byte-identical (Appendix A.1), so whichever
  database holds the tables, the version reads as already applied and the tables are adopted;
- `DocumentAssetConfig` has the **same fields** as `VisualisationAssetConfig` (`mimetypes`, `default`,
  `assetCacheDir`, `clearAssetCacheOnStartup`, `aceEditorModes`, `defaultAceEditorMode`) — only the
  enclosing key changed.

**So the open question is now only "will an upgrade fail to start", not "will we lose assets".**

**How to find out which case you are in.** Grep the deployed `config.yml`:

```
grep -n "visualisationAsset" /path/to/config.yml
```

- **Case A — absent.** Assets live in the common/default database alongside everything else (module
  config all-null → `mergeConfig` yields the common config). The rename is a complete no-op and the
  upgrade is clean. Nothing to do but a change-log line.
- **Case B — present, but only `connectionPool` tuning or the non-DB `visualisationAsset` block.** Same
  database. Startup fails until the key is renamed. Rename it and everything carries over; *delete* it
  instead and you silently lose the tuning (not data).
- **Case C — present with `connection.url` pointing elsewhere.** A separate database. Startup fails
  until renamed. Rename it and the data is intact in that database, adopted exactly as in Appendix A.1.

If you would rather check empirically than read the YAML, ask the database instead — run
`SHOW TABLES LIKE 'visualisation_assets'` against the main stroom schema. Present means Case A or B;
absent means Case C and the tables are in whatever `visualisationAssetDb.connection.url` names.

**Recommendation.** Grep the config; you will almost certainly be in Case A or B. Either way the fix is
the same and small (see F2), so this no longer blocks anything — it only decides whether you also need
a release note telling operators to rename a key before upgrading. Note that Stroom appears to have
**no existing deprecated-property mechanism** in `ConfigMapper` — I looked and found none — so a true
backwards-compatible alias would mean building one, which is unlikely to be worth it for a key that
strict parsing already reports precisely.

### D9 — Was the document-asset module extraction needed at all? — OPENED 2026-08-21

Raised by the answer that **Floor Map depending on `stroom-dashboard` is acceptable, and Floor Map may
move closer to dashboards in future.** That removes the justification the extraction rested on, so it is
worth separating what the change *had* to do from what it chose to do.

**What was functionally necessary is very small.** Master's asset storage was already type-agnostic:

- `VisualisationAssetDaoImpl` contains **zero** references to `VisualisationDoc` — it is keyed purely on
  `owner_doc_uuid`, so the table and all the draft/live/publish machinery already worked for any document
  type.
- The only coupling was ~5 hard-coded `new DocRef(VisualisationDoc.TYPE, uuid)` calls —
  `VisualisationAssetService` lines 79, 99, 124, 151 and `VisualisationAssetServlet` line 312.

The branch replaces those with a UUID to `DocRef` lookup:

```java
// master, VisualisationAssetServlet:312
final DocRef docRef = new DocRef(VisualisationDoc.TYPE, docId);
// branch, DocumentAssetServlet:321
final DocRef docRef = explorerNodeService.getNodeByUuid(docId) ...
```

That is the whole capability change, and it is why `ExplorerNodeService.getNodeByUuid` was added. It is
needed under **every** option below.

**What was organisational.** Roughly **75 of the 455 changed files**: 64 files of new
`stroom-document-asset` module, the deleted `stroom-dashboard-impl-db` module, plus `settings.gradle`,
`AppConfig`, `CoreModule`, `DbConnectionsModule`, `ConfigProvidersModule`,
`GenerateConfigProvidersModule`, `expected.yaml`, both `VisualisationModule`s and
`VisualisationStoreImpl`. Every item in that second list is shared, upstream-owned code carrying a
STROOMWORKS-LOCAL merge marker — i.e. files the team has already identified as future merge conflicts.

#### Options

**Option R — revert the extraction.** Keep the asset code in `stroom-dashboard-impl`, make the ~5
type-resolution changes in place, let Floor Map depend on `stroom-dashboard-impl`.

- Removes ~75 files from the diff.
- **Deletes F2 outright** — no config rename, so no upgrade note, no `config`-table path migration, no
  startup failure, and no silently-dropped property overrides.
- **Makes Appendix A.1 moot** — nothing moves, so there is no migration-adoption property to preserve.
- Biggest prize: `AppConfig`, `CoreModule`, `DbConnectionsModule`, `ConfigProvidersModule`,
  `VisualisationStoreImpl` and `settings.gradle` all revert to upstream, permanently removing that merge
  surface. A recurring saving, not a one-off.
- Cost: the code keeps `Visualisation*` names while serving Floor Map assets, and `stroom-core-client`
  gains a dashboard dependency. If a third document type ever wants assets, extract then — with the
  advantage of knowing what it actually needs.

**Option H — keep the module, revert only the config key strings.** As F2 Option 2. Kills the whole
user-visible upgrade cost (F2) while leaving working, tested code alone. `AppConfig` still differs from
upstream (different imports and class names), so some merge surface remains, but much less.

**Option K — keep as-is.** Better naming, and it pays off if a third document type wants assets. Costs
the F2 upgrade work and the full merge surface.

#### Two facts that change the cost estimate (checked 2026-08-21)

**1. The generification is real, bidirectional, and genuinely needed.**
`DocumentAssetPresenter<D extends AbstractDoc>` is a properly generic client presenter, and **both** doc
types now use it:

```java
// FloorMapPresenter:59
private final DocumentAssetPresenter<FloorMapDoc> documentAssetPresenter;
// VisualisationPresenter:51
private final DocumentAssetPresenter<VisualisationDoc> documentAssetPresenter;
```

Floor Map has a live Assets tab wired into its save chain (`flushEditorThenSaveAssets`), and the
visualisation side was migrated onto the same generic presenter. So this is not server-side packaging with
a speculative client story — it is a working two-consumer abstraction. Option R does **not** avoid this
work: it would mean generifying in place inside the visualisation package, leaving `FloorMapPresenter`
importing from `stroom.visualisation.client.presenter`. That is worse naming than the status quo for no
functional gain.

**2. A revert would not be mechanical.** Six commits touch `stroom-document-asset/`, spread across two
merges from master:

```
eb09a50c09  Make the Visualisation Asset system generic...   <- the extraction
968f391824  Fix bugs in visualisation asset manager
e30ba82120  Merge branch 'master' into dashboard-type
1d574195b5  Fixed ExplorerNodeService issue
8f350a5a8f  Allow user to choose an image for the fact icon  <- a feature built ON the asset system
d0c24f807d  Merge origin/master into dashboard-type
```

That last one matters: the fact-icon image chooser (`TypeStyle.graphic`) is a Floor Map feature that
*depends* on the asset system. Reverting means unpicking a feature built on top, across merge boundaries.
This is manual conflict resolution, not `git revert`.

#### Recommendation — Option H

**Keep the module and the generification; revert only the two config key strings.** This has changed from
my earlier lean toward Option R, and the reason is the two facts above: I had costed R as subtracting
packaging, but it is actually unpicking a generification that two document types now depend on, plus a
feature built on top, across merges. That is real work with real regression risk, and the end state has
worse naming than what exists today.

Option H removes the entire user-visible cost — F2 disappears, with no upgrade note, no `config`-table
migration, no startup failure, no silently-dropped overrides — for a change to **two string constants**,
with zero code churn.

**Be honest about what H does not fix.** The upstream merge surface only half goes away. `AppConfig` still
differs (imports, class names, two getters), and `DbConnectionsModule`, `CoreModule`, `settings.gradle` and
`VisualisationStoreImpl` still differ simply because the module exists. H removes the config-key half of
the conflict, not the structural half. If merge pain is genuinely the dominant cost, R is still the only
option that eliminates it — accept that it costs a manual unpick.

**The future-move angle argues for H too.** If Floor Map does move closer to dashboards later, that
refactor is the natural moment to fold the asset module back in — done once, with the move, rather than as
a separate exercise now. Keeping the module today does not block that.

**Still not recommended: Option K** (keep as-is), which pays the full upgrade cost for the sake of two
string constants.

### ~~D6 — Was the `ONE_DAY_MS` bound intended?~~ — ANSWERED 2026-09-04

**No. The docs go.** Decided by the user after the evidence below; F9 is done.

Four files documented a `currentTimeMillis() + ONE_DAY_MS` upper bound on `fetchAll`. No such bound
exists — `fetchAll` is a `MAX(effective_time)`-per-key subquery with a single `doc_uuid` predicate and
no time predicate at all. (Careful with the grep: `ONE_DAY_MS` *does* exist, in
`FloorMapEditorPresenter` and `FloorMapMapPresenter`, where it pads the timeline's **visible range** —
±24 h around now, and widening a degenerate `min == max` range. Unrelated to querying, and the likely
source of the generalisation.)

**What settled it without needing the author** — `fetchAll`'s own javadoc said, ten lines after
claiming the bound:

> Use this endpoint to back the "Show all" toggle in the Fact List rather than calling `fetchAtTime`
> with an inflated `timeTo`.

`fetchAll` exists *because* inflating `timeTo` was the workaround. An endpoint added to replace the
margin trick cannot also be applying it internally. So the margin was a client-side convention for
"right now" queries against `fetchAtTime`; when `fetchAll` replaced that convention, the sentence
describing it was carried over instead of deleted. Two confirmations: the same block contradicted
itself within ten lines ("with no upper-time constraint" / "applies an internal `timeTo`"), and the
sole caller of `fetchAtTime` passes the slider position exactly — so nothing anywhere used the margin.

**Why implementing it would have been wrong**, not merely expensive: a key whose latest entry is dated
beyond +24 h would vanish from "Show all", which is the opposite of what that toggle means, and the
margin would hide a bad timestamp rather than surface it.

**Still worth knowing:** the text reached the published OpenAPI description, so an external consumer
may have built against a cap that was never there. That is a "who do we tell", not a "what do we do".

### D7 — Why was `try (this)` replaced with `try/finally`?

The merge marker on `ByteBufferPoolImpl7` says the only divergence is a `value`→`val` rename and that
neutralising it leaves an empty diff. **Neither is true** — there is no such rename in that file, and
the actual change is `try (this)` → `try { … } finally { close(); }` at three sites, mirrored across
three sibling buffer classes. The stated justification ("compatibility with recent JDKs") does not
hold up: `try (this)` is valid and idiomatic on every JDK the project targets.

So the real reason is unknown, and it matters, because the change is not behaviour-neutral (F10).
This needs whoever made it. If there is no real constraint, revert to `try (this)`.

### D8 — Is the canvas render rewrite in scope?

F6 (full SVG rebuild per frame, plus 5000-point trails) sets the ceiling on how many entities the map
can animate smoothly. Fixing it properly is a render-architecture change to the most intricate code on
the branch. The alternatives are to do it now, defer it and cap the supported entity count, or do only
the cheap half (cache the per-frame invariants, shrink the trail cap) and leave the DOM strategy alone.

**Recommendation.** Cheap half now, architecture later, and agree an explicit supported entity count
so there is a number to test against. Also needed: what *is* the realistic maximum — 50 entities or
5,000? The current client caps results at 1,000 facts + 1,000 events, which may already be the answer.

**DECIDED 2026-08-21: cheap half now, architecture deferred.** The deferred half is written up as a
standalone, raiseable task in `docs/task-floormap-incremental-canvas-render.md`, with line references
anchored to commit `7ad6b4d9e4` and the cheap-tier items listed as out-of-scope so they are not redone.
Its one blocking input is the entity-count target — if the real working set turns out to be small, the
cheap tier may suffice and that task can be closed unstarted.

---

# Part B — Fixes

Severity = how bad the bug is. **Risk** = how dangerous the *fix* is. They are not the same, and on
this branch the two most severe bugs have wildly different fix risks.

## F1 — Temporal store keys all data by document name — CRITICAL — **DONE 2026-08-21**

**Files**
- `stroom-sqlstore/stroom-sqlstore-impl-db/src/main/resources/stroom/sqlstore/impl/db/migration/V07_13_00_001__updatable_temporal_store.sql`
- `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/UpdatableSqlTemporalStore.java` (~171–190, ~392)
- `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/UpdatableTemporalStoreProvider.java` (39–51)

### What is wrong

The table carries no document identifier:

```sql
PRIMARY KEY (map_name, key_, effective_time)   -- no doc_uuid
```

and every operation resolves its store from the document *name*:

```java
final String mapName = docRef.getName();
dao.clear(mapName);          // DELETE ... WHERE map_name = ?
```

Stroom document identity is the UUID. Names are free-form, mutable, and not unique — and
`stroom-sqlstore` does not use `UniqueNameUtil` anywhere, so even *copying* a store document produces
an exact duplicate name. The storage column collation is `utf8mb4_0900_ai_ci` and the pipeline lookup
gate compares with `equalsIgnoreCase`, so names differing only in case collide as well.

### Why it matters

Three distinct failures, in descending likelihood:

1. **Rename is silent, total data loss.** Nothing re-keys the table. Every row is orphaned under the
   old name and the document reads as empty. No collision is needed and no error is raised — the store
   simply appears empty. There is no recovery through the UI. Floor Map keeps its facts *and* events in
   these stores, so this is the feature's entire dataset.
2. **Copy or duplicate-name creates a shared dataset.** Two documents named `locations` address the
   same rows. Each owner can `clear()` the other's data.
3. **Authorization resolves to the wrong document.** `hasPermission(String mapName, …)` does
   `.filter(dr -> dr.getName().equals(mapName)).findFirst()` and checks the permission on whichever
   same-named document is returned first, which need not be the caller's. Depending on ordering this is
   either a bypass or a lockout from your own store.

Note what is *not* wrong: the DAO's own predicates are correctly scoped to map, key and effective
time. Nothing is missing. The flaw is that the scoping key is the wrong identifier. Blast radius stays
inside `updatable_temporal_store`.

### The fix

**A hard constraint to design around:** name-based *addressing* cannot be removed. Three user-facing
surfaces resolve stores by name, and all three are contracts users have written against:

- XSLT `lookup('MapName', …)` — `ReferenceData` matches `mapName.equalsIgnoreCase(pipeline.getName())`
- StroomQL `from "StoreName"`
- Floor Map's `param('FactStore')` / `param('EventStore')` substitution

`SqlStoreLookupImpl` even builds `new DocRef(TYPE, "", mapName)` — an empty UUID — which shows how
thoroughly the UUID is absent from that path.

So the fix is not "replace name with UUID everywhere". It is **resolve name→UUID once at the API
boundary, and key storage on the UUID**.

Per D1 there is no data anywhere, which simplifies this considerably — there is no backfill, no second
migration, and no ambiguity to resolve in flight:

1. **Correct `V07_13_00_001__updatable_temporal_store.sql` in place.** Add
   `doc_uuid varchar(255) NOT NULL` and make the primary key `(doc_uuid, key_, effective_time)`. Keep
   `map_name` as a denormalised display/debug column or drop it entirely — it must simply stop being a
   key. Editing a shipped migration would normally be forbidden; this one has never been applied to a
   real database, so it is the correct move rather than a shortcut. Regenerate the jOOQ classes.
2. **Resolve name→`DocRef` at every entry point** and pass the `DocRef` inward. Nothing below the
   boundary should see a bare name.
3. **Delete the `hasPermission(String, …)` overload** outright, so the name-based permission path
   cannot be called at all. This is what currently authorises against `findFirst()` on same-named
   documents; removing the method is better than fixing it, because the compiler then finds every
   caller for you.
4. **Add the name-uniqueness guard and route copy through `UniqueNameUtil`** (from D2), and fail loudly
   on ambiguous resolution (per D3, once decided).

### Risk of making this change — **LOW** (was High before D1)

What made this dangerous was the migration on live data and the undecidable backfill. Both are gone.
What remains:

- **The pipeline lookup path is shared with ordinary reference data.** This is now the main risk. A
  regression in `SqlStoreLookupImpl` or the `ReferenceData` gate affects ingest for everyone, not just
  Floor Map. `TestSqlStoreLookup` and `TestReferenceData` are the guard rails.
- **Dev database churn.** Editing the migration breaks Flyway checksums on any dev database that
  already ran it (see D1's coordination note). Annoying, not risky.
- **The `DocRef` threading touches a lot of call sites.** Mechanical, and deleting the name-based
  overload turns it into a compile-error-driven exercise rather than a hunt.
- Doing this *now* rather than later is itself a risk reduction: every week this ships unfixed is a week
  someone can create data that a later migration would have to rescue.

### Verification

- New DAO test: two documents with the same name write to and clear only their own rows.
- New test: renaming a document leaves its data reachable — the headline bug, and the regression test
  that matters most.
- New test: ambiguous name resolution fails in whatever way D3 decides.
- Existing `TestUpdatableTemporalStoreDaoImplDB`, `TestSqlStoreLookup` and `TestReferenceData` must stay
  green — they cover the shared lookup path this change touches.
- No migration-against-populated-database test needed; there is no population to preserve.

---

## F2 — Config keys renamed with no deprecation shim — MEDIUM — **DONE 2026-08-26**

> **Decide D9 first.** F2 exists only because the asset code moved modules. If D9 goes to Option R
> (revert the extraction), this entire finding disappears — no rename, no upgrade note, no config-table
> migration, no startup failure — and D5 becomes moot with it. Everything below assumes the module move
> stays.

**Files:** `stroom-config/stroom-config-app/src/main/java/stroom/config/app/AppConfig.java`

### What is wrong

Generalising the visualisation-asset subsystem into `stroom.document.asset` renamed
`visualisationAsset` → `documentAsset` and `visualisationAssetDb` → `documentAssetDb`. There is no
deprecated-key mapping, and the only change note on the branch reads "Entity clustering".

### Why it matters

**Downgraded from HIGH to MEDIUM** after establishing two things in D5, and the reasoning is worth
keeping because my original assessment was wrong in the direction that mattered.

I originally flagged the bad outcome as a *silent* fallback to the default datasource — pointing at a
database where `visualisation_assets` does not exist, making every asset appear deleted, and letting a
user re-upload into a divergent second copy. **That cannot happen.** `App.java:155–158` explicitly
enables `FAIL_ON_UNKNOWN_PROPERTIES`, so a leftover `visualisationAssetDb` key is a hard startup
failure naming the unrecognised field, not a silent misroute.

What actually remains: **any `config.yml` setting the old keys fails to start until the key is
renamed.** Disruptive on upgrade, but loud, self-diagnosing, and a one-line fix for the operator. No
data is at risk — the new code reads the same tables, serves the same `/assets/*` path, and adopts the
same Flyway history (see D5 for the verification).

**The storage itself does not change, and did not need to.** Worth stating plainly, because the module
move makes it look otherwise:

- jOOQ codegen still declares `<includes>visualisation_assets.*</includes>` — it generates classes for
  the *same tables* into a new package.
- The migration is byte-identical and the Flyway history table name is unchanged.
- The servlet path (`/assets/*`) and the config field set are unchanged.

What moved is **module ownership**, and that part was necessary: the asset DAO needs jOOQ classes, which
are generated per Gradle module, and the service has to be reachable from both the visualisation code and
Floor Map. Leaving it in `stroom-dashboard-impl` would force Floor Map to depend on the dashboard module.

**The config key rename, by contrast, was a naming choice and not a technical requirement.** The YAML key
is just a string constant (`PROP_NAME_DOCUMENT_ASSET_DB = "documentAssetDb"`), independent of both the
class name and the module name. The old key strings could be kept while everything else is renamed. See
"The fix" for the three options this opens up.

### A silent failure path that does exist — DB-stored property overrides

My "it always fails loudly" conclusion holds **only for the datasource**, and only because of how those
properties are annotated. `AbstractDbConfig` is `@BootStrapConfig` `@NotInjectableConfig` and every
`ConnectionConfig` field is `@ReadOnly @RequiresRestart(SYSTEM)` — so datasource settings are YAML-only
by design and cannot be set from the database or the UI. That case fails loudly, as described.

`DocumentAssetConfig` is **not** `@BootStrapConfig`. It is an ordinary UI-editable, DB-stored config
(`mimetypes`, `default`, `assetCacheDir`, `clearAssetCacheOnStartup`, `aceEditorModes`,
`defaultAceEditorMode`). Overrides set through the Properties screen are keyed by property *path* in the
`config` table, and at boot `GlobalConfigBootstrapService.getValidProperties` does this:

```java
if (dbConfigProp.getName() == null || !configMapper.validatePropertyPath(dbConfigProp.getName())) {
    LOGGER.debug("Property {} is in the database but not in the appConfig model", dbConfigProp.getName());
    if (deleteUnknownProps) { deleteFromDb(dbConfigProp.getName()); }
}
```

`updateConfigFromDb(false)` is what runs at boot, so an override under
`appConfig.visualisationAsset.*` is **silently dropped at DEBUG level** and the row is left in the table
doing nothing. No error, no warning.

Consequence: not data loss, but silently lost customisation — a custom mimetype mapping stops applying
and the asset reverts to `application/octet-stream`, a custom `assetCacheDir` reverts to the default.
Recoverable by re-setting each property under the new path, but only once someone notices.

Two things that remain true and matter more now that assets are known to be **in use with real data**:

- Appendix A.1's byte-identical-migration property is **load-bearing, not incidental**. It is what makes
  existing assets survive the module move. Do not tidy the migration or the history table name.
- The rename itself is internally consistent — no dangling references, `expected.yaml` updated. The gap
  is only the upgrade path.

### The fix

Because the storage does not change, the module move and the key rename are **separable decisions**.
Three coherent options:

**Option 1 — keep the rename, cover both surfaces.** Recommended.
1. **A release/upgrade note** stating that `visualisationAsset`/`visualisationAssetDb` are now
   `documentAsset`/`documentAssetDb`, the blocks' contents are unchanged, and the key must be
   **renamed, not deleted**, to preserve tuning or a non-default database.
2. **A one-line SQL migration for the DB-stored overrides**, which is the only silent surface. Property
   paths are rooted at `appConfig` (`AppConfig.ROOT_PROPERTY_NAME`) and live in the `config` table's
   `name` column, so:

   ```sql
   UPDATE config
   SET name = REPLACE(name, 'appConfig.visualisationAsset', 'appConfig.documentAsset')
   WHERE name LIKE 'appConfig.visualisationAsset%';
   ```

   Belongs in `stroom-config-global-impl-db`, which owns that table. **Caveat:** `config` has
   `UNIQUE KEY name (name)`, so if a row already exists under the new path — possible if someone re-set
   the property after upgrading and before the migration runs — the `UPDATE` fails on the constraint.
   Either delete colliding new-path rows first, or use `INSERT … ON DUPLICATE KEY UPDATE` semantics and
   decide which value wins. This closes the silent-drop path properly rather than relying on someone
   noticing a lost mimetype mapping, and it is the piece most likely to be forgotten.
3. **Optionally, a friendlier startup error.** Jackson's "Unrecognized field visualisationAssetDb" is
   accurate but says nothing about what to do; a check that spots the old key and names its replacement
   turns a five-minute puzzle into a ten-second fix.

**Option 2 — keep the old YAML key strings.** Revert only `PROP_NAME_DOCUMENT_ASSET` and
`PROP_NAME_DOCUMENT_ASSET_DB` to `"visualisationAsset"`/`"visualisationAssetDb"`, keeping the new module,
class and package names. Zero upgrade impact on either surface — no release note, no config migration,
no startup failure. The cost is an incongruous key name: a block called `visualisationAsset` configuring
a generic subsystem that Floor Map also uses. Choose this if upgrade friction matters more than naming
consistency, or if there are deployments whose configs you cannot edit.

**Option 3 — do not build a deprecated-property alias.** Stroom has no such mechanism to hook into, so
this means building one for a single rename. Option 1 step 2 achieves the same outcome for one line of
SQL, and Option 2 achieves it for none.

### Risk of making this change — **LOW**, and near **NONE** for the recommended option

A release note carries no code risk. If you add the optional startup check, keep it a *check* — do not
let it quietly map the old key onto the new config. A half-done alias that accepts the old key and wires
it to nothing would manufacture exactly the silent-wrong-datasource failure that strict parsing
currently prevents, which would be strictly worse than doing nothing.

If an alias is built anyway, test that setting *only* the old key resolves to the same datasource as
setting *only* the new one.

### Verification

`TestConfigMapper`; confirm `expected.yaml` regeneration is unaffected. If a startup check is added, a
test that a config containing the old key fails with a message naming the new one. If an alias is built,
a test asserting old-key and new-key configs resolve to identical datasources.

---

## F3 — Every SAX event on the ingest path logs at INFO — CRITICAL — **DONE 2026-08-25** (`438106dc1f`)

**File:** `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/pipeline/SqlStoreFilter.java` — 13 sites, incl. 131, 186, 234

```java
LOGGER.info("SqlStoreFilter.startElement({}, {}, {}, {})", uri, localName, qName, atts);
LOGGER.info("SqlStoreFilter.characters()");
LOGGER.info("SqlStoreFilter.endElement({}, {}, {})", uri, localName, qName);
```

`startElement`, `endElement`, `characters` and the prefix-mapping hooks all log at `INFO`, which is
enabled by default for the `stroom` package. A million-entry reference stream produces tens of
millions of log lines, including an `Attributes.toString()` per element. This will dominate ingest CPU
and disk before anything else on the branch does.

### The fix

Change all 13 to `LOGGER.trace(...)`, or delete the ones that carry no diagnostic value. The
`error(...)` sites should be `LOGGER.error`, not `info`.

### Risk of making this change — **TRIVIAL**

Mechanical, no behaviour change, entirely reversible. Highest payoff per character changed anywhere in
the diff. This should be its own one-file commit and should land immediately regardless of what else
is decided.

### Verification

`./gradlew check`; ingest a reference stream and confirm the log is quiet at INFO.

---

## F4 — Document-store scan and unbatched insert per reference entry — HIGH — **DONE 2026-08-28** (`a6432bd258`)

**Files:** `SqlStoreFilter.java:321,334`; `UpdatableTemporalStoreProvider.java:39–46`

```java
public UpdatableTemporalStore get(final String mapName) {
    final boolean exists = sqlStoreDocStore.list().stream()
            .anyMatch(docRef -> docRef.getName().equals(mapName));
```

`addReference()` runs once per entry. Each call lists and linearly scans every temporal-store
document, then issues a single-row upsert on its own connection. A 100k-entry stream costs 100k
document-store listings and 100k autocommit inserts.

### The fix

`SqlStoreFilter` is `@PipelineScoped`, so memoise the existence check per filter instance — one check
in `startProcessing`, or a small map keyed by map name. Buffer entries and flush in batches (e.g.
1,000) via a multi-row insert, with a final flush in `endProcessing`.

### Risk of making this change — **MEDIUM**

Batching changes failure granularity: a mid-stream failure currently leaves entries 1..n−1 committed,
whereas a batch leaves the last partial batch uncommitted. Confirm that is acceptable for reference
data ingest (it usually is — the stream is reprocessed) and that `endProcessing` flushes on *both* the
success and error paths, or a batch is silently dropped. Coordinate with F1: if F1 lands first, the
memoised key becomes the UUID.

### Verification

`TestSqlStoreFilter`; a test that ingests more entries than the batch size and asserts every one is
persisted; a test asserting a mid-stream error does not silently drop a buffered batch.

---

## F5 — XSLT lookups against a temporal store are entirely uncached — HIGH — **PARTLY DONE** (`8cc85ee889`); value cache **DEFERRED 2026-09-01** to `task-sqlstore-lookup-value-cache.md`

**File:** `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/pipeline/SqlStoreLookupImpl.java:46–77`, reached from `ReferenceData.getValue`

This sits on the same hot path as LMDB-backed reference lookups but amortises nothing — no per-stream
cache, no per-key cache. A translation doing one `lookup()` per event over a million-event stream
issues a million document-store scans (F4's `get()`) plus a million SQL queries, each running the
MAX-subquery and self-join. The classic path absorbs this through the off-heap store and effective
stream cache. `SqlStoreValueProxy.resolveValue` also builds a fresh `SAXParser` per XML value rather
than reusing one.

### The fix — split, and the second half deferred

**Taken (`8cc85ee889`).** Memoise the store resolution per pipeline instance and pool the XML
reader. A million-event stream went from a million document-store listings to one. Two invariants
were established there: the memoised map caches *identity, not authorisation* — `find(DocRef, …)`
still permission-checks every lookup — and nothing time-dependent was cached, because it needs a
temporal cache key to be correct.

**Deferred.** The per-lookup SQL query. Raised as a standalone issue in
`docs/task-sqlstore-lookup-value-cache.md`.

**A correction to this plan's original proposal.** It said to add a
`(map, key, time-bucket)` → value cache, and that bucket is what stalled the work: the granularity
has to match the data's real granularity, guessing coarse is silently wrong, and no one can commit
to a number on behalf of every deployment. Bucketing is the worst of the available options, not the
obvious one.

Caching the **validity interval** instead — `(docUuid, key) → {value, validFrom, validTo}`, a hit
when `validFrom <= T < validTo` — is exact, needs no granularity agreed, and gives one query per
*change* rather than per *event*. It needs the next entry's effective time alongside the current
one, which `TIME_FIELD` already supports through the expression mapper. So the decision this plan
recorded as blocking was an artefact of the proposal, not of the problem; the deferral is about
capacity, not about an unanswered question.

### Risk of making this change — **MEDIUM**

Caching temporal data is subtly wrong if the key is careless: the value depends on the *effective
time*, so the cache key must include it (or a bucket), and a bucket coarser than the data's real
granularity will return stale values. Cache invalidation on store writes needs thought — a pipeline
that both writes and reads the same store within a run could read its own stale entry. Prefer a
per-pipeline-instance cache with a short lifetime over a long-lived shared one.

### Verification

`TestSqlStoreLookup`; a test asserting two lookups at different effective times return different
values (i.e. the cache key includes time); a test asserting a write followed by a read in the same
pipeline run sees the write.

---

## F6 — Canvas rebuilds the whole SVG per frame; trails grow to 5000 points — HIGH — **PARTLY DONE** (`7ab7b0bdbd` + trail commits; architecture tier deferred by D8)

**Files:** `FloorMapCanvasViewImpl.java:595–789`; `FloorMapEntityAnimator.java:47,343–376`;
`FloorMapCanvasPresenter.java` (redraw ~1624, animation loop ~2469)

Every frame — and every pan mousemove — serialises facts, events, clusters, badges and captions into
one HTML string and replaces the entire SVG subtree, including the floor-plan image. At the client's
own cap of 1,000 facts + 1,000 events that is thousands of elements re-parsed and re-laid-out 60×/s
because one entity moved. Per-frame invariants are recomputed too: `imageFactsByKey` is rebuilt over
all facts and all facts are re-sorted by z-order.

Trails compound it. `TRAIL_MAX_PTS = 5000`, one point appended per animating entity per frame, and at
the cap `trail.remove(0)` is an O(5000) array shift per entity per frame. `attachTrail` allocates a
fresh `double[3]` per point per frame to recompute alpha, and the view rebuilds the full path string.
Fifty moving entities at the cap is roughly 250k allocations and several MB of string per frame,
inside a 16 ms budget, in compiled JS.

### The fix

Per D8, in two tiers.

**Cheap tier (recommended now).** Cache `imageFactsByKey` and the z-sorted facts list, invalidating on
`setFacts`/`setTypeStyles`. Replace `ArrayList.remove(0)` with a ring buffer. Derive trail alpha from
index instead of allocating. Cap the *rendered* trail near 200–500 points — a 5000-point 6px path is
visually indistinguishable from a decimated one. Cache the path string and append only the new
segment.

**Architecture tier (defer).** Split static and dynamic SVG layers so per-frame work is limited to
`transform` attribute updates on moved entities, and the floor-plan image is never re-created.

### Risk of making this change — **LOW** (cheap tier) / **HIGH** (architecture tier)

The cheap tier is local and each item is independently testable; the shared geometry and animator
classes already have JVM unit tests to extend. The architecture tier is the highest-risk change on
this list: it rewrites the most intricate code on the branch, the prior review already found and fixed
a cluster of animation bugs there (ghost entities, leaked previews, duplicate loops), and its failure
mode is visual and hard to unit-test. Do not attempt it without a runtime sanity pass in super dev
mode covering pan, zoom-toward-cursor, drag, fit, follow and playback.

### Verification

`TestFloorMapEntityAnimator` extended for the ring buffer and trail cap; `TestFloorMapScreenGeometry`
for cached geometry; `gwtDraftCompile`; runtime pass in super dev mode.

---

## F7 — `hasInverse()` promises a finiteness check it does not perform — HIGH — **DONE 2026-08-24** (`c7d9573e6b`)

**File:** `stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapTransformationMatrix.java:285–313`

This is a latent correctness bug, not a documentation defect. The doc states a matrix is
non-invertible if "any of its components is not finite" and that `true` means `inverse()` will
succeed. Only `a, b, c, d` reach the determinant and magnitude tests. The translation components `e`
and `f` are never examined, so a matrix with `e = NaN` passes, and `inverse()` then computes
`invE = (c*f - d*e) * invDet` and returns a non-finite matrix.

That is exactly the failure the `inverse()` javadoc argues at length against: it documents that an
earlier version returned the identity for singular input and that this was "actively harmful" because
a plausible-looking answer let every subsequent coordinate conversion silently misbehave — and in the
vertex editor those coordinates were written back to the document. A non-finite matrix is the same
class of harm.

### The fix

Check all six components in `hasInverse()`. The method exists to be called on data of uncertain
provenance, so tighten the code, not the wording.

### Risk of making this change — **LOW**

Three lines, strictly more conservative. The only hazard is that some caller currently relies on a
translation-only non-finite matrix passing the check; nothing in the branch appears to, and if
anything did it would be a bug. `inverse()` throws on failure, so a newly-rejected matrix surfaces
loudly rather than silently — which is the intent.

### Verification

Extend `TestFloorMapTransformationMatrix` with `e = NaN` / `f = Infinity` cases asserting
`hasInverse()` is false and `inverse()` throws.

---

## F8 — Unbounded result sets materialised in memory — MEDIUM — **PARTLY DONE** (`9400f3359c` drops the longtext read; `.fetch()` remains)

**File:** `UpdatableTemporalStoreDaoImpl.java:451–482` (`search`), `262–275` (`fetchAll`)

Both end in `.fetch()` with no `LIMIT`, so the streaming `Consumer` signature is an illusion — the
whole result is materialised before the consumer sees a row. The timeline histogram deliberately
passes a null time range to bypass deduplication, so every version of every key, `longtext` values
included, loads into heap on document open and on every visible-range change — in order to extract
timestamps. `find()` paginates correctly; these two do not.

### The fix

Use `fetchLazy()`/`fetchStream()` with a fetch size so rows stream. For the histogram specifically,
add a purpose-built query that selects only `effective_time` (or aggregates to buckets in SQL) instead
of shipping full rows.

### Risk of making this change — **LOW–MEDIUM**

Lazy fetching holds a cursor and therefore a connection for the duration of consumption; if the
consumer is slow or can block, that is a connection-pool hazard where the eager fetch was merely a
memory hazard. Ensure the cursor is closed on the exception path. The histogram-specific query is
lower risk and higher value — consider doing only that.

### Verification

`TestUpdatableTemporalStoreDaoImplDB` with a row count above any fetch size; assert the consumer sees
every row and the connection is released.

---

## F9 — `ONE_DAY_MS` documented in four places, implemented nowhere — MEDIUM — **DONE 2026-09-04**

**Files:** `UpdatableTemporalStore`, `UpdatableSqlTemporalStore`, `FetchAtTimeRequest`,
`SqlTemporalStoreResource`

Four files stated the server applies `currentTimeMillis() + ONE_DAY_MS` as an internal upper bound.
No such bound exists in the sqlstore module and `fetchAll` applies no time predicate at all — a
far-future entry is always returned. (`ONE_DAY_MS` itself does exist, but only in two client
presenters as timeline **display** padding; see D6.) `FetchAtTimeRequest` went further and told
callers the constant was "defined on this class for convenience", which would not compile. One file
contradicted itself within a few lines, and the text reached the published OpenAPI description.

### The fix — done

D6 answered: the docs went. All four blocks are corrected, including the `@Operation` description
string, and each now states the real contract — no upper time bound, the latest version of every key,
including future effective times. `FetchAtTimeRequest` now points callers at `fetchAll` instead of
telling them to inflate `timeTo` with a constant that does not exist.

### Risk of the change made — **TRIVIAL**

Documentation only; no behaviour changed. The alternative, *implementing* the bound, would have
silently hidden future-dated entries that callers currently see — see D6 for why that would have been
wrong rather than merely risky.

---

## F10 — False merge marker hiding a real behaviour change — MEDIUM — **BLOCKED on D7**

**Files:** `ByteBufferPoolImpl7.java:38` and three sibling buffer classes

The banner claims the only divergence is a `value`→`val` rename and that neutralising it leaves an
empty diff. There is no such rename in that file. The real change is `try (this)` →
`try { … } finally { close(); }` at three sites, mirrored in `PooledByteBufferImpl` (both copies) and
`NonPooledByteBuffer`.

That change is not neutral. Under try-with-resources an exception from `close()` is *suppressed* and
attached to the primary exception. Under `try/finally` it **replaces** it, and the original is lost.
These classes sit on the LMDB reference-data path, so a genuine refdata failure can be masked by a
buffer-return failure.

The marker actively misleads: it tells whoever resolves the next merge from master that the file is
safe to take from upstream wholesale, on a claim that does not hold.

The identically-worded banner on `AnnotationDaoImpl` was checked and **is** accurate — that file
really is only the rename. Do not change it.

### The fix

Per D7. If no real constraint exists, restore `try (this)` in all four classes and delete the banner.
If a constraint does exist, keep `try/finally` but capture and attach the close failure via
`addSuppressed`, and correct the banner to describe what actually diverges.

### Risk of making this change — **LOW**

Restoring `try (this)` returns four files to the upstream form, which is the better-tested path.
Exception-path behaviour changes are worth a deliberate test, since the whole point is what happens
when both the body and `close()` throw.

### Verification

A unit test where the consumer throws and `close()` also throws, asserting the consumer's exception is
the one propagated with the close failure attached.

---

## F11 — Smaller behavioural items — MEDIUM / LOW — **6 of 11 DONE** (`438106dc1f`, `9400f3359c`, `a6432bd258`)

| Item | Files | Fix | Risk |
|---|---|---|---|
| Playback destroys and recreates two server searches every 300 ms; `startNewSearch` opens with a store destroy, so each Map tab drives ~6–7 destroy/create/poll cycles per second | `FloorMapTimelinePresenter.java:84`, `FloorMapMapPresenter.java:716–762` | Skip re-running the facts query when no fact changes fall between ticks; or reuse the search instead of destroy/create | MEDIUM — needs a correct staleness rule or the map silently shows old data |
| Group delete fires one REST `find` per selected key; group duplicate runs a full facts re-query per object because `loadAtTime` sits inside the loop | `FloorMapEditorPresenter.java:1841–1877` | One `find` with `Key IN (…)`; hoist `loadAtTime` out of the loop | LOW — the existing multi-shard delete has unit tests to extend |
| `applyChanges` executes one statement per change inside the transaction | `UpdatableTemporalStoreDaoImpl.java:327–379` | `trx.batch(...)`, preserving order between upsert and delete groups | LOW — already transactional; keep ordering |
| `populateDraft` filters with `HAVING` and no `GROUP BY` where `WHERE` was meant, potentially defeating the index and scanning all documents' live assets including `longblob` | `DocumentAssetDaoImpl.java:232–248` | Change `.having(...)` to `.where(...)` | LOW — but confirm the rewrite returns identical rows |
| Uploaded assets served same-origin with `html`/`js`/`svg` content types; vis iframe has no `sandbox` | `DocumentAssetServlet.java:344–351`, `VisFrame.java:52,66` | Sandbox the iframe and/or add CSP; `Content-Disposition: attachment` for non-image types | MEDIUM — inherited from the old visualisation servlet; sandboxing may break existing visualisations that need same-origin access. Needs its own decision |
| No size cap on asset upload; the 512 KiB constant gates viewing, not upload | `DocumentAssetDaoImpl.java:566–622` | Configurable maximum, enforced before streaming to the blob | LOW |
| Two unescaped names interpolated into `from` clauses while every sibling path escapes | `FloorMapMapPresenter.java:1195`, `IndexDataPresenter.java:92` | Wrap in `QuotedStringUtil.escapeDoubleQuoted` | TRIVIAL |
| `TermHandler` converts `NumberFormatException` to `IllegalArgumentException` without chaining the cause, on a shared path used by meta/annotation/index queries | `TermHandler.java:~329,338,346` | Pass `e` as the cause; check no caller distinguishes the original type | LOW |
| `GWT.log` diagnostic left in new code | `FloorMapSettingsPresenter.java:386` | Remove | TRIVIAL |
| Duplicated sentinel-rationale paragraph, twice near-verbatim | `DateTimeFormatter.toCompactPattern` | Delete one | TRIVIAL |
| Histogram Java fallback colours left at blue-600 after the stylesheet moved to blue-800 for contrast, so the "matches the light theme" doc is false | `HistogramWidget.java:72–79`, `stroom-histogram.css` | Update the constants to match the CSS | TRIVIAL |
| `DocumentPluginEventManager` banner says opening a document routes through the initialisation handler; the call is only in `fireShowCreateDocumentDialogEvent` | `DocumentPluginEventManager.java:147–153` | Correct the banner; state in `DocInitialisationHandler` that the cancel-deletes-the-document contract makes it unsafe on the general open path | TRIVIAL — but worth doing, because wiring that call into the open path would make cancelling destroy an existing document |

---

---

## F12 — Name-clash guard leaks the existence of documents the user cannot see — MEDIUM (security) — **DONE 2026-08-21**

**Reported by the team, 2026-08-21. Confirmed.**

**Files:** `stroom-sqlstore/stroom-sqlstore-impl/src/main/java/stroom/sqlstore/impl/SqlTemporalStoreDocStoreImpl.java`
(`checkNameNotInUse`, `checkNameNotInUseByOther`, `throwNameClash`); same unfiltered listing in
`UpdatableSqlTemporalStore.hasPermission`.

### What is wrong

The guard enumerates **every** `SqlTemporalStoreDoc` in the system, with no permission filtering:

```java
private void checkNameNotInUse(final String name) {
    final boolean inUse = getStore().list().stream()
            .anyMatch(dr -> dr.getName().equals(name));
    if (inUse) { throwNameClash(name); }
}
```

`StoreImpl.list()` is `return persistence.list(type);` — no permission check anywhere on that path. The
resulting error then confirms the clash by name:

> "A SqlTemporalStore with name 'X' already exists. Names must be unique because they are used as map
> identifiers."

### Why it matters

A user with CREATE permission in any folder can probe for the existence of stores they have no
permission to see, anywhere in the tree. Try to create `payroll-locations`; a refusal confirms such a
document exists. Repeat to enumerate. That is an existence-and-name oracle over documents outside the
user's visibility, which is exactly what document permissions are meant to prevent.

Contrast the house mechanism, which gets its candidate set from the caller rather than a global listing —
`ExplorerServiceImpl` builds it from `explorerNodeService.getChildren(destinationFolderRef)`, which is
both **permission-filtered** and **folder-scoped**, and then auto-renames rather than refusing:

```java
final Set<String> otherDestinationChildrenNames = explorerNodeService.getChildren(destinationFolderRef)
        .stream() ... .map(DocRef::getName).collect(Collectors.toSet());
handler.copyDocument(sourceNode.getDocRef(), name, !allowRename, otherDestinationChildrenNames);
```

### The fix, and the consequence that follows from it

Use the standard mechanism: drop the three overrides, let `AbstractDocumentStore` handle create/copy/
rename with the explorer-supplied `existingNames`, and take `UniqueNameUtil.getCopyName` behaviour like
every other store. That also fixes the D3 gaps for free — the case/accent mismatch, the `importDocument`
bypass, and the dropped VIEW check in `copyDocument` all disappear with the overrides.

**But this makes duplicate names possible again, exactly as for other stores** — and duplicate names are
what the name-as-storage-key design (F1) cannot survive. So:

> **F1 is no longer optional, and ordering matters.** Fixing this leak *without* F1 reintroduces the
> shared-dataset corruption: two stores named `locations` would once more address the same rows, with
> each owner able to `clear()` the other's data. **F1 must land first, or in the same change.**

Do not fix F12 on its own.

### Risk of making this change — **LOW on its own, HIGH if sequenced wrong**

Deleting three overrides is trivial. The risk is entirely in the ordering: land F12 before F1 and you have
traded an information leak for silent cross-document data loss, which is a strictly worse position. If F1
cannot land promptly, the safer interim step is to keep the guard but make the *error message* generic
("that name is not available") while still refusing — closing the oracle without opening the corruption.
That is a stopgap, not a fix, because the guard's global unfiltered listing still contradicts the
permission model and the timing difference remains observable.

### Verification

- A test asserting a user without VIEW on an existing store gets no name-clash information when creating
  a store with that name.
- After F1: a test that two same-named stores in different folders keep separate data (the regression this
  whole chain protects).

---

## F13 — Plan B has no server-side latest-per-key, so the Map tab fetches a window it discards — HIGH — **NEW 2026-09-01, reframed twice**

**Files:** `FloorMapMapPresenter.runQueryAtSelectedTime` / `EVENTS_WINDOW_MS` /
`publishEventEntities`, `FloorMapQueryPresenter.latestPerEntity` / `parseRows`,
`PlanBSearchHelper.search`, `TemporalStateDb.getState` / `condense`

Introduced by the Plan B move and its playback fix (`db0cd682ee`, `8ee09c4952`).

> **Reframed twice, and both corrections matter.**
>
> *First framing* — "the Map tab imposes window semantics on a state store", weighing position
> against trail fidelity. Wrong: **nothing consumes the window**.
>
> *Second framing* — "the reduction must be expressible in StroomQL, because the events query is
> user-editable". Also wrong: **the facts store already solves this outside StroomQL**, with a
> dedicated snapshot endpoint. That makes the fix additive rather than query-layer design work.
>
> Two of the six options originally listed only made sense under the first framing and are struck
> out below.

### The Map tab was designed for this store; one query is missing, not the design

Audit of every Map feature that depends on the events store:

| Feature | How it reads events | On Plan B |
|---|---|---|
| Histogram (timeline density bars) | Runs the events query with **`timeRange = null`** — no range term at all, deliberately, so the store's temporal dedup does not fire and every historical entry comes back; `[start, end]` is applied *client-side* by `HistogramDataModel` | **Works** — a match-all scan is what Plan B does natively. Capped at 10,000 rows in **LMDB key order**, so a large store's histogram is truncated by key, not by time (pre-existing) |
| Timeline data range | Derived from the histogram result via `dataRangeHandler` | **Works** — follows the histogram |
| **Entity positions** | Per-tick query needing *latest per entity `<= T`* | **Broken** — the whole of this finding |
| Animation and trails | Interpolated client-side from successive positions | Downstream of positions only |
| Tracking roster | From the published entity list (`FloorMapDataEvent`) | Downstream only |
| Groups and group overlay | From the published entity list | Downstream only |
| Area membership | Computed from placed entities plus area facts | Downstream only |
| Editor layer discovery | Event types from the published entity list | Downstream only |

So **exactly one query does not work.** Everything else is either native to Plan B — the histogram
genuinely prefers it — or downstream of that one query. Earlier drafts of this finding read as
though the Map tab were at odds with Plan B. It is not.

### What that one query needs

**One position per entity, as at time T.**

The SQL Temporal Store supplied this server-side, two ways: a `MAX(effective_time)` subquery grouped
by key inside `find`, and a dedicated REST endpoint,
`SqlTemporalStoreResource.fetchAtTime(FetchAtTimeRequest{mapName, timeTo})`, which the Editor uses.

Plan B has neither. `PlanBSearchHelper.search` iterates the whole LMDB database and applies the
expression as a per-row predicate — there is a standing `TODO` at line 61 noting a range could
narrow the iteration but does not. So an unbounded lower bound returns every entry ever recorded.

The 20-second window was substituted to bound that. **It is a workaround for a missing reduction,
not a requirement of the feature.**

### The window has no consumer

The obvious justification for a window is trails — a path needs several points per entity. That is
not where trails come from. `FloorMapEntityAnimator.recordTrailPoint` is called from
`advanceFrame`, the animation frame loop, with the **interpolated** position:

```java
anim.progress = Math.min(1.0, anim.progress + deltaMs / ANIMATION_DURATION_MS);
recordTrailPoint(anim.id, anim.currentX(), anim.currentY(), timestampMs);
```

Trails are built client-side by tweening between successive positions. Their points carry the
**scheduler timestamp** — wall clock — and fade against `TRAIL_MAX_AGE_MS`, not against event
times. A trail is a rendering artefact of recent motion, not a data-derived path.

And `latestPerEntity` reduces the window to **one row per entity** before anything reaches the
canvas, so the extra rows are discarded on arrival. The window costs transfer and parsing, breaks
position, and delivers nothing to any consumer.

### Decision recorded — trails are best-effort

**2026-09-01.** Trail fidelity is explicitly best-effort. Trails exist only while playback runs;
jumping to a time shows no trail until the timeline advances, and that is accepted.

This matters because the window's discarded rows *could* one day drive true data-derived trails —
the recorded path rather than an interpolated one. Declaring trails best-effort means the window
can be removed without foreclosing anything anyone wants. Without the decision written down, the
next reader finds rows fetched and thrown away and cannot tell whether they are load-bearing.

### Symptoms

**An entity that stops emitting disappears after 20 seconds**, though the store knows exactly where
it is. Under the SQL Temporal Store a snapshot query returned its last known position however old.
A **behavioural regression**, not a new limitation.

**Plan B's condense makes it reach data that is still being emitted.** `TemporalStateDb.condense`
removes consecutive entries whose values are equal
(`Objects.equals(lastState.val(), state.val())`) older than its threshold, keeping the earliest of
each run. Lossless for a snapshot read — the value at T is unchanged — and lossy for a window read:
a stationary entity re-emitting the same location has every repeat removed, the survivor falls
outside the window, and it vanishes. Condense is off by default, so it amplifies rather than
causes. Calling this "the condense bug" points at the wrong file.

### Options

**Option A — reduce inside Plan B's search path, as the SQL store already does.** *Recommended.*

> **Third framing of this option, and the previous one was wrong.** It proposed a dedicated REST
> endpoint mirroring `SqlTemporalStoreResource.fetchAtTime`, on the grounds that snapshot semantics
> could not be expressed through StroomQL. They already are. `fetchAtTime` is the **Editor's** path;
> the Map tab's *facts* query gets snapshot-at-T through StroomQL, from the store's own search
> implementation. Reaching for REST solved a problem that does not exist, and dragged in a new role,
> a second value schema, a new parser, a migration, a filtering story and permission and audit
> decisions — none of which are needed. The REST variant is kept below as a fallback only.

#### The two search paths are already parallel; one reduces and one does not

```
SqlTemporalStore                              Plan B
UpdatableSqlTemporalStore.createResultStore    StateSearchProvider.createResultStore
  -> dao.search(docUuid, criteria, ...)          -> shardManager.get(name, reader -> reader.search(..))
      -> getQueryTime(criteria)                      -> TemporalStateDb.search
      -> if non-null: MAX(effective_time)            -> PlanBSearchHelper.search
         grouped by key  == snapshot at T               == per-row predicate, no reduction
```

Both are reached from StroomQL. The asymmetry is not REST versus query — it is that
`UpdatableTemporalStoreDaoImpl.search` honours an upper time bound as a snapshot and
`TemporalStateDb.search` does not. **That is the entire gap.**

#### The change

**Server.** Implement *latest per key at or before T* inside `TemporalStateDb.search`, triggered by
an upper time bound in the criteria, mirroring `getQueryTime`.

**Client.** `runQueryAtSelectedTime` sends `to = T + 1` with no `from`.
`ResultStoreManager.addTimeRangeExpression` emits each bound independently and `TimeRange` permits a
null `from`, so this is a one-line change. `EVENTS_WINDOW_MS` and `latestPerEntity` are then deleted.

#### The convention is this fork's, not Stroom's — corrected

An earlier draft claimed Stroom already treats "upper time bound means snapshot" as the
temporal-store contract, citing `HistogramQueryHelper`'s deliberate `timeRange = null`. **That is
false, and inverted.** `stroom-sqlstore` does not exist on `origin/master`; neither does
`stroom/widget/histogram`. Both are fork-only. So the only implementation of the convention is this
fork's own new module, and the only code working around it is a helper written for this feature.
"Plan B is the outlier" is true only inside this fork.

That matters twice. It makes Option A an **upstream proposal**, not a local fix — which the
recommendation already said, but the "established convention" paragraph oversold. And it adds a cost
the earlier draft omitted: implementing it fork-locally means **modifying pristine upstream files in
an actively developed upstream module**, so every merge from master risks silently losing or
breaking the reduction. This repo already maintains a marker convention for exactly that problem,
which is a sign of how well it works.

**The reference implementation is a heuristic, not a contract**, in three ways any mirror has to
confront:

- `getQueryTime` parses with `DateUtil.parseUnknownString` — epoch milliseconds and ISO only.
  Relative bounds (`now()`, `hour()`) **throw, are swallowed, and the SQL store silently takes the
  full-history path**. Every dashboard preset except "All time" uses relative bounds
  (`TimeRanges`: `"now()-1m"`, `"hour()"`, `"day()"` …), so snapshot semantics fire for absolute
  times only. A Plan B mirror must choose the same parser (format-dependent semantics) or the query
  engine's date parsing (the trigger then fires for every relative preset — a far larger blast
  radius than the reference actually has).
- `getQueryTime` accepts `EQUALS`, `<` and `<=`, but always applies `le(queryTime)` — **`<` is
  silently widened to `<=`**. Since the injected term is `LESS_THAN to`, the SQL store evaluates
  `<= T+1`, not `<= T`.
- `ExpressionUtil.terms` recurses through OR and NOT, so a time term inside `NOT(...)` or an OR
  branch triggers snapshot mode, and `getFilteredExpression` then strips **all** time terms —
  including an explicit user lower bound the snapshot result may then violate.

#### What this avoids compared with the REST variant

Because there is no new access path, most of the review findings against the previous framing do not
apply:

| Objection to the REST variant | Under a search-path reduction |
|---|---|
| Loses query-driven overlay filtering — a store-wide snapshot draws other maps' entities | **Gone.** The expression still applies; `PlanBSearchHelper` already builds the predicate. This was the strongest objection. |
| USE vs VIEW: mirroring `fetchAtTime` would break maps for USE-only users | **Gone.** Same path, same `PlanBDocCache` USE check. |
| Audit flood at ~3 requests/sec, or losing the search-audit trail | **Gone.** Still a search, logged exactly as now. |
| Needs `Role.LOCATION`, a second value schema, a `parseEvents`, a document migration | **All unnecessary.** Column mapping untouched. |
| `entityIdColumn` / `locationIdColumn` cannot be removed because the Events Query tab uses them | **Moot.** Both tabs keep working unchanged. |
| Response bounding | **Improves.** The overlay's `OffsetRange(0, 1000)` becomes 1,000 *entities* instead of 1,000 raw rows truncated before dedup. |

Still delivered: the window and `latestPerEntity` go, and with them the rendered-time comparison
hazard — a server-side reduction compares stored times, not strings formatted to a viewing user's
date pattern.

#### What the option must specify, and what it must not delete

**Emit in stored-key order, not hash order.** Ungrouped table rows are keyed by a sequential
insertion-order id (`KeyFactoryFactory` / `FlatUngroupedLmdbRowKeyFactory`), so today insertion
order equals LMDB scan order and is stable between ticks. A `HashMap`-accumulated reduction emits in
hash order, which reshuffles globally whenever the key set changes — and with more than 1,000
entities the overlay's `OffsetRange(0, 1000)` would truncate a *different* thousand each tick,
flickering entities in and out. Use a TreeMap over copied prefix bytes with an unsigned-lexicographic
comparator, or a second pass in scan order.

**Cap the accumulation.** `HashMap<prefixBytes, best entry <= T>` is O(distinct keys) heap with no
bound, held inside an `env.read` transaction, on a store type built for very large cardinality. The
SQL store does this in the database engine. "Bounded by entity count" is true for a floor map and
unbounded in general; a snapshot query against a 100M-key store is a node-OOM vector. Needs a cap or
a spill strategy before it goes upstream.

**Do not delete `latestPerEntity`.** The server would reduce per store **`Key`**; the client reduces
per the **configurable** `entityIdColumn`. Those coincide only because the generated default query
says `Key as "Entity ID"`. Any map whose entity column is something else would get multiple rows per
entity the moment the client reduction is removed. Keep it — post-reduction it is near-free and
idempotent — and it is also what provides draw-order stability, which its own comment says.
**Delete only `EVENTS_WINDOW_MS`.**

**Decide and write down the exact semantics**, testing against `UpdatableTemporalStoreDaoImpl` as
the oracle: honour the term's real condition or reproduce the `le` widening; strip all time terms or
honour a user lower bound (the former reproduces the reference's violation, the latter resurrects
the disappearing-entity symptom for any query carrying one); trigger on top-level-AND time terms
only, or accept the reference's structure-blindness explicitly.

**Filter-then-reduce, matching the reference.** The SQL store applies non-time terms inside the
subquery, giving "latest **matching** row `<= T`". StroomQL `where` terms reach Plan B's expression
identically, so testing the existing predicate per row and reducing among survivors matches.

**Variable-length prefixes.** A pass that "emits on prefix change" misgroups: `VariableKeySerde`
mixes inline, uid-lookup and hash-lookup encodings *within one store*, so one key's stored prefix can
be a byte-prefix of another's. Prefix extraction itself needs no serde changes — every serde writes a
fixed-size time suffix, so `key[0 .. len - timeSerde.getSize())` is uniform — but map keys must be
value-equal wrappers over **copied** bytes, since LMDB buffers are transient. There is no DUPSORT, so
no per-key ties. Decode times rather than comparing time bytes (`NanoTimeSerde` writes a signed
offset from 2000).

**The histogram can conflict with itself under the implicit trigger.** The Map tab runs the *same
user-editable events query text* for the histogram with `timeRange = null`. If a user writes an
upper-bound time term into the query text, the implicit trigger would collapse the histogram to one
row per key — the option can break another consumer inside the same feature.

**A Plan B bug found on the way, worth raising separately and with more severity than first
recorded.** `getState` reads the decoded key but never compares it — it only checks
`containsPrefix`. When another key's stored prefix byte-extends the requested one and its entry
comes first in the reverse scan, `getState` returns **the wrong key's state**. Not a false miss: a
wrong value.

**Unaffected either way:** the `Event Type` column mismatch, and the histogram's 10,000-row cap
applied in LMDB key order rather than by time.

#### The two decisions this option needs

**1. What triggers the reduction?** *Implicitly*, on an upper time bound, mirroring the SQL store —
consistent, no new syntax, but it changes behaviour for any existing Plan B `TEMPORAL_STATE` query
carrying an upper bound that expects every row. *Explicitly*, via a StroomQL construct — no
back-compat break, at the cost of new surface area and of Plan B behaving unlike the SQL store for
identical query text.

Gating on `StateType` does **not** narrow the blast radius meaningfully: `TEMPORAL_STATE` is exactly
the type at issue. In-repo exposure is nil — upstream's `TestTemporalStateDb` uses an empty
expression and the Data tab sends no range — so the risk lives entirely in deployed user content,
which cannot be enumerated from here. That unenumerable-ness is what makes implicit dangerous.

**2. Which date parser?** This controls the blast radius more than the trigger choice does, and the
earlier draft missed it entirely. With `DateUtil.parseUnknownString` (the reference's behaviour),
relative bounds throw and are swallowed, so **no dashboard preset triggers the reduction** — the
change is nearly invisible, and also nearly useless outside the floor map, whose client sends an
absolute epoch value. With the query engine's date parsing, **every time-ranged dashboard or
StroomQL query against any `TEMPORAL_STATE` store** becomes one-row-per-key with the lower bound
discarded.

Both are Plan B product policy. Together they are the only things genuinely blocking this option.

*Risk: MEDIUM–HIGH*, revised back up. The floor-map-side work is one client line; the risk is in the
reduction's correctness across key types, the unbounded accumulation, the two policy decisions, and
the permanent merge exposure of modifying upstream internals in a fork.

**Option A2 — the REST variant, as a fallback.** A dedicated Plan B endpoint returning entries, with
value-schema parsing on the client. Worth reaching for if the decisions above go against changing
search behaviour *and* new StroomQL syntax is refused. It costs a new `Role.LOCATION`, a second
value schema on the document, a `parseEvents` producing `FloorMapObject`, a document migration, an
`ExpressionCriteria` filter to preserve overlay filtering, and explicit permission and audit
decisions.

*One dimension the objection table above omits, in A2's favour:* **fork-owned REST surface is
cheaper to maintain than fork-modified upstream internals.** A new file in a new module does not
conflict on merge; a behavioural change inside `TemporalStateDb.search` does, every time. That does
not overturn the comparison, but it narrows it more than the table suggests.

*Risk: MEDIUM–HIGH.*

**Option B — drop the lower bound and reduce on the client.** Send `to = T + 1` with no `from`;
`ResultStoreManager.addTimeRangeExpression` emits each bound independently and `TimeRange` permits
a null `from`, so this is a one-line change and `latestPerEntity` already does the reduction.
Semantically exact.
*Risk: **broken by construction**, not merely costly.* Rows returned grow without bound as history
accumulates — but the fatal detail is ordering: the overlay requests `OffsetRange(0, 1000)`, which
truncates in **key order before** the client reduction runs. With a per-key history depth of H, only
about `1000 / H` entities survive to be reduced at all. This is worse than the earlier "HIGH to
operate" rating implied, and it is not fixable by raising the cap.

> **An earlier recommendation of "measure Option B first" is withdrawn.** The cost profile is
> unacceptable *by construction*, not empirically; measuring on a small development store would
> give a falsely encouraging answer that failed months later. Viable only where total history is
> known to stay small — a demo, not a default.

**Option C — carry the last known position forward on the client.** Hold
`entityId -> {position, sourceTime}` across ticks; the window query *updates* positions and no
longer *defines existence*.
*Risk: MEDIUM.* Wrong under non-monotonic time — scrubbing from T=100 back to T=10 must not carry a
position backwards from the future, so it needs invalidating on any backward or discontinuous jump
via the previous `selectedTime` in `onTimeChange`. That is the class of state bug that produced the
four trail defects. It also only *approximates* the store: it shows the last position this session
observed, not the last that exists, so opening the map already scrubbed to T still shows nothing
for a quiet entity. Useful as a stopgap if Option A is blocked; not a destination.

**~~Option D — two queries, window for movement and snapshot for presence.~~** Struck out. It
assumed the window served trails. There is one question to ask, so there is no second query.

**~~Option E — widen or configure the window.~~** Struck out as a design. The window has no
purpose, so widening it is not a smaller version of the right answer, only a crude approximation of
unbounded lookback — and a per-document setting would expose a subtle failure mode as a number no
user can reason about. Retained only as a stopgap constant, never a setting.

**Option F — revert the events store to a SQL Temporal Store.** Restores snapshot semantics by
using the store that supplies them. *Risk: LOW technically*, but the Map tab was designed around
Plan B temporal state and this abandons that. Listed for completeness.

### Recommendation

**Two tracks, and the local one is not a contingency.**

**Option A is the right destination**, and the correctness argument is genuinely decisive: condense
keeps the **earliest** entry of an identical run, so *latest at or before T* returns the right value
on condensed data, always, and a windowed query never can.

But both of its open decisions are **upstream Plan B product policy**, and an upstream product
decision stalls by default. Implementing it fork-locally would change the behaviour of an upstream
store for every non-floor-map user of the deployment, while taking on permanent merge exposure. So:

- **Upstream ask:** *`TemporalStateDb.search` should honour an upper time bound the way
  `UpdatableTemporalStoreDaoImpl.search` does — return the latest entry per key at or before it —
  with the trigger and date-parser questions answered as policy.* That is the single capability the
  SQL Temporal Store has and Plan B lacks, and every symptom in this finding follows from its
  absence.
- **Plan of record locally: Option C**, with hard tests on backward scrubbing. Earlier drafts called
  it "a stopgap only if the decision stalls"; that under-plans it. Assume the decision stalls and
  build accordingly.

**Option B is off the table** — broken by construction, see above. **Option E's constant** is the
cheapest thing that reduces the symptom if even Option C is too much for now.

When Option A eventually lands, `EVENTS_WINDOW_MS` goes and the two workaround instructions in
`floormap-planb-events-store.md` — keep condense off, emit at least every 20 seconds — go with it.
`latestPerEntity` **stays** (see above), as do the column mapping, both column settings, the value
schema, permissions and the audit trail.

### As built — Option C, 2026-09-03

Implemented. Positions are now client-side state in `FloorMapEventState` (`stroom-core-shared`,
GWT-free with the clock passed in, so the interval logic is unit-testable), corrected by a periodic
bounded re-read run through a new `FloorMapBaselineQueryHelper`. `EVENTS_WINDOW_MS` is gone;
`latestPerEntity` stays, as predicted.

Five things came out differently from the plan, all found while implementing:

| Planned | Built | Why |
|---|---|---|
| One not-before (60 s) gating every baseline | **Two** — 60 s routine, **1 s** for a timeline jump (`JUMP_INTERVAL_MS`) | A loop wrap is `t <= lastQueriedTime`, so a single 60 s not-before held it back — freezing the map through most of every pass, and **looping is the default**. The two conditions guard different things: a jump makes the state wrong *now* and no delta can fix it, whereas nothing-ever-landed means the baseline itself is failing and retrying fast only rescans the store. Missed by the plan and by four reviews, which reasoned that a wrap "semantically requires a baseline" without tracing what the hold did to it. |
| **Strip** the user's top-level `sort` clause | **Detect** it, and reduce by comparing effective times instead | Stripping means finding where the clause *ends*, which means recognising every keyword that could follow; getting that wrong corrupts a query that works today. Detection needs one keyword, cannot damage anything, and buys the same protection. |
| Entity-ID-not-the-store-`Key` was "documentable only" | **Also detected**, and sends the reduction down the same time-comparison path | A note tells the user something they cannot easily act on. `FloorMapEventsQueryOrder.bindsEntityIdToStoreKey` checks for the one shape that is known safe (`Key as "<column>"`, over `BasicTokeniser`'s masked spans so a `jq` program containing `.key` does not match) and errs towards *false*: a safe-but-unusual query loses the stronger ordering and nothing else. |
| Stamp the cursor with `selectedTime` when a result lands | Stamp with **the upper bound the read was issued for** (`pendingDeltaTo`, `Outcome.to()`) | A result arrives a round trip after it was asked for and playback ticks three times a second, so by arrival the selected time has moved on. Stamping the later time marks a range as read that nobody read, and its events are lost until the next baseline. |
| Any error refuses the baseline | Only **`ERROR` and above** | Refusing keeps stale positions until the next baseline, so a recurring `WARNING` could have frozen the map indefinitely. |

Also: `QueryModel` gained a settable `timeout` (marked `STROOMWORKS-LOCAL`; no such API existed,
and the 1-second default made every baseline late and silent about it), and
`FloorMapTimelinePresenter` gained `setDiscontinuityHandler` — fired at the *discrete* jumps only,
because the existing `clearAnimationStateHandler` fires per frame on a wrap.

**Not covered by tests, stated rather than hidden:** the searching→idle edge logic in
`FloorMapBaselineQueryHelper` needs an `EventBus` and a `QueryModel`, so it has no JVM test; it is
reasoned from `QueryModel`'s call order (`setData` 422 → `setErrors` 439 → `setSearching(false)`
447) and copies the pattern `FloorMapQueryPresenter` already documents. Two guards in
`bindsEntityIdToStoreKey` are unfalsifiable under the current tokeniser and are commented as such.
Manual testing is still outstanding — the list is in the implementation plan.

### Verification

- An entity whose last event is older than the window is still drawn at its last known position.
- Scrubbing backwards never shows a position from later than the selected time. Worth having
  whichever option is chosen; mandatory for Option C.
- With condense enabled and a stationary entity re-emitting an unchanged location, the entity
  remains on the map.
- The histogram is unchanged by the switch — same density bars before and after.
- Entity type comes from the data rather than the `@` heuristic once role-based mapping is in.
- Trails still render during playback, and their absence after a time jump is asserted as expected
  rather than treated as a defect.
- **Option A specifically:** a `HASH_LOOKUP` or `VARIABLE` keyed store where one key's stored
  prefix is a byte-prefix of another's groups correctly; a query with a `where` clause still filters
  the overlay; a store shared by two floor maps still shows only its own entities; and an existing
  Plan B query carrying an upper time bound behaves as whichever way the implicit/explicit decision
  goes, asserted deliberately either way.

---

## F14 — Four silent failure paths in the events pipeline — MEDIUM — **NEW 2026-09-01**

**Files:** `FloorMapMapPresenter.reportUnparsedEvents`, `.placeEventEntities`, `.updateCanvas`
(Editor), `FloorMapCanvasViewImpl`

Not a defect in behaviour but in **reporting**, and it cost several hours of this session's
debugging. The events pipeline has four stages, each of which can produce nothing, and three of
them say nothing when they do.

### The stages, and what each says when it comes up empty

| Stage | Empty because | Reported? |
|---|---|---|
| Query returns rows | no data in window; wrong store; wrong time | **No** — `reportUnparsedEvents` returns early unless rows are non-empty |
| Rows parse to entities | entity/location column names do not match | Yes — names both columns and the available ones |
| Entities resolve to positions | `location` names fact keys that do not exist | Yes, **but only if facts are non-empty** |
| Facts themselves parse | wrong value schema or format; nothing at the selected time | **No** — only a `Console.warn` for a non-invertible matrix |

So the two most likely first-run failures — no events in the window, and no facts at all — are
exactly the two that produce silence. The guard in `placeEventEntities` is deliberate:

```java
if (placed.isEmpty() && lastRawEventObjects != null && !lastRawEventObjects.isEmpty()
    && lastFacts != null && !lastFacts.isEmpty()) {
```

Facts arriving after events is normal and self-corrects on the next refresh, so reporting it would
be noise. But *permanently* empty facts are indistinguishable from that transient state, and
produce identical silence for ever.

There is a second problem on top: every diagnostic goes to `Console.error`, i.e. the browser
developer console. A user who is not a developer sees an empty map and nothing else.

### Options

**Option 1 — complete the existing conditions.** Report the empty cases too, with a message per
stage.
*Risk: LOW.* But naively done it reintroduces the noise the guards were added to prevent: the
transient facts-after-events case would log on every tick during normal startup.

**Option 2 — one stage classifier.** *Recommended.* Replace the scattered guards with a single
method that walks the four stages in order and reports **the first one that came up empty**, naming
the stage. Four silences and two ad-hoc messages become one message with a stage label, and the
"which of these is it" question this session kept asking is answered directly.
*Risk: LOW.* It is a reporting change with no behavioural effect, and the existing two messages
already carry the diagnostic detail worth keeping.

**Option 3 — filter on persistence, not on emptiness.** Report only after N consecutive ticks with
the same empty stage, so the transient startup case stays quiet and a permanent one still speaks.
*Risk: LOW.* Pairs with Option 2 and is what makes it safe to report the cases the guards currently
suppress. Needs a counter reset on any time change, or scrubbing would accumulate false positives.

**Option 4 — surface it in the UI, not only the console.** *Recommended alongside 2 and 3.* An
unobtrusive status line on the canvas — "No events in the last 20 seconds at this time" — where a
non-developer will see it. The canvas already has an accessible summary and live region for the
current time, so there is somewhere to put it.
*Risk: LOW–MEDIUM.* Wording has to avoid crying wolf during normal startup, which is what Option 3
provides. It must not become a modal or a toast; the map looking empty is often correct.

### Recommendation

Options 2 + 3 together, then 4. The pair is a contained change to one presenter with no behavioural
effect, and it converts the most expensive class of problem in this feature — "nothing is drawn and
nothing says why" — into a named stage.

### Verification

- A test per stage asserting the classifier names that stage and no other.
- A test that the transient facts-after-events sequence reports nothing.
- A test that a persistently empty stage does report, after the threshold.

### Help text — Entity ID Column and Location ID Column · **requested 2026-09-04**

Same family as the finding above: the user cannot tell what is wrong. Here they cannot tell what the
control is *for*.

These two live on the **Events Query tab**, and each must name a column the events query selects — by
the alias in the query text, not by the underlying field. The generated default query aliases them
`Entity ID` and `Location ID`, and `FloorMapEventsQuery` interpolates the same constants into both
the query and the settings so they agree by construction. A hand-edited query breaks that agreement
silently.

**What goes wrong when they are wrong:** `FloorMapQueryPresenter.parseRows` finds no matching column,
returns no entities, and the canvas draws nothing. The query itself still returns rows, so the Events
Query tab looks healthy while the Map tab looks as though playback is off. The only signal is a
console line — `returned N rows but no entities` — which a non-developer will not see.

**Suggested text for Entity ID Column:**

> The name of the column in the events query that identifies each entity — a person, vehicle or
> asset. Must match a column the query selects, by its alias. The default query aliases this column
> `Entity ID`. If it does not match, the map draws no entities even though the query returns rows.

**And for Location ID Column:**

> The name of the column in the events query holding each entity's location. Its values are either
> coordinates (`<map>, <x>, <y>`) or the key of a fact to place the entity on — a desk or a gate — in
> which case moving that fact moves the entity with it. Must match a column the query selects, by
> its alias; the default query aliases this column `Location ID`.

Worth pairing with a validation hint rather than only prose: the tab already knows the query's column
names, so a value that matches none of them could be flagged in place instead of failing silently on
the Map tab.

**Also worth recording while here:** a floor map created *before* `db0cd682ee` has both settings
`null`, because only the init dialog was fixed to populate them and nothing migrates an existing
document. Such a map renders no entities until someone sets them by hand, with no indication why.

---

## F15 — The facts query polls three times a second for data that changes weekly — MEDIUM — **NEW 2026-09-04**

**Files:** `FloorMapMapPresenter.onTimeChange` / `.runQueryAtSelectedTime` / `.parseFacts`,
`FloorMapTimelinePresenter.PLAYBACK_QUERY_INTERVAL_MS`,
`UpdatableTemporalStoreDaoImpl.getQueryTime` / `.getFilteredExpression`

Every throttled playback tick runs the facts query at `[T, T]` — the whole fact set, no `where`
clause, capped at 1 000 rows. The throttle is 300 ms
(`FloorMapTimelinePresenter.PLAYBACK_QUERY_INTERVAL_MS`), so that is roughly **three full fact reads a
second per open Map tab**, plus one on every scrub, step, tab return, open and save.

**The user's operational answer, 2026-09-04: facts change about once a week, a couple at a time. The
rest never move. But new facts written to the database must still be picked up.**

That reframes the poll. It is not serving playback — the answer is almost always byte-identical to the
one before it. It is serving **external write detection**, at three times a second, for data that
changes at 1.7 µHz.

### What it costs

The SQL is cheap: indexed `doc_uuid`, a `MAX(effective_time)` group-by, a few hundred rows. Two other
things are not.

- **Result-store churn.** Every `startNewSearch` destroys and recreates a server-side LMDB-backed
  result store, polls it, and tears it down. This is half of the ~6–7 cycles per second per Map tab
  recorded under **F11 · playback search churn**; F13's delta read did not touch it.
- **Client work per tick.** `parseFacts` re-parses every row, re-pushes type styles and facts to the
  canvas, rebuilds the tracking roster, re-places every event entity and recomputes area containment —
  three times a second, almost always reaching the identical answer. `reanchorEventEntities` is
  already guarded on its *result* and documents why; the parse and the containment recompute are not.

### Why the obvious fix does not work

The intuitive version — fetch the next 60 s of facts, plus margin, once a minute — cannot work against
this store, and it fails the same way F13 does.

`getQueryTime` takes the first `EQUALS`/`<`/`<=` time term as a **snapshot boundary**;
`getFilteredExpression` then strips *every* time term. So `[T, T+70s]` becomes
`MAX(effective_time) per key WHERE effective_time <= T+70s` — one row per key as of **T+70 s**, the
future state, not the versions in between. The map would draw the floor plan as it will be up to
seventy seconds ahead of the playhead: an object that moves at T+30 s would appear already moved at T.
Two hundred fewer queries, wrong picture.

**And there is no facts delta at all.** A "what changed since X" query is inexpressible here: a time
term is either lifted as a snapshot boundary or stripped, so `> X` simply vanishes. Detection has to
be a periodic full re-read. That is the same class of gap as F13, pointing the other way.

### What does work

Pass `timeRange = null`. That is the trick `HistogramQueryHelper.run` already uses and documents: with
no `TimeRange` the DAO takes its standard path (`getQueryTime` returns null) and returns **every**
historical entry. Hold that client-side and the snapshot at any T is a local computation.

Volume is not a concern at the stated change rate. The search provider builds
`new ExpressionCriteria(...)` with no `PageRequest`, so `JooqUtil.getLimit(null, true)` returns
`Integer.MAX_VALUE` — there is **no server-side row limit**; the binding cap is the client's
`OffsetRange(0, 1000)`, with `DataStoreSettings.maxResults` at 1 000 000 far above it. Full history is
*keys + about two rows a week*, so raising the facts cap to 20 000 (matching the events cap) puts
truncation decades out, and a truncation warning covers the case where the assumption is wrong.

### Options

| | Approach | Effect |
|---|---|---|
| **1** | Leave it | ~3 fact reads/s/tab for data that changes weekly; half of F11's churn stays |
| **2** | Skip the downstream work when the parsed facts are unchanged | Removes the client-side cost only. ~10 lines, no query-semantics risk, no behaviour change. Separable and safe on its own |
| **3** | Fetch a forward window (`[T, T+70s]`) every 60 s | **Broken** — returns the future snapshot, not the intervening versions. Recorded so it is not re-proposed |
| **4** | Fetch full history once (`timeRange = null`), compute the snapshot locally, re-fetch on a cadence for external writes | The fix. Playback, scrub and step become zero-query for facts |

### Recommendation

**Option 4, with Option 2 folded in** — the unchanged-result guard is what makes the periodic re-fetch
almost free, so they belong together. Option 2 alone is a reasonable first step if 4 is not wanted yet.

Shape:

1. Fetch full fact history with `timeRange = null`; hold it.
2. Compute the snapshot at T client-side each tick.
3. Re-fetch on a cadence for external writes, plus immediately on open, save and tab return — which
   `refresh()` already does.
4. Skip downstream work when the result is unchanged, which will be essentially always.
5. Facts row cap 1 000 → 20 000, with a truncation warning.

**Correctness improves as well as cost.** Today a fact change is picked up whenever a tick's snapshot
happens to cross it — accurate to one tick of *wall clock*, which at 10× playback speed is about three
seconds of timeline. Holding the history makes it exact at every frame.

### The one call to make — cadence

New facts currently appear within 300 ms; afterwards they appear within one interval. At a weekly
change rate even 300 s would be five orders of magnitude faster than the data, so the only case that
constrains this is a person adding a fact and watching for it to land.

**Recommend 30 s:** still a ~360× reduction, worst case half a minute, and with the unchanged-result
guard each re-fetch costs almost nothing downstream. 60 s would reuse
`FloorMapEventState.BASELINE_INTERVAL_MS` and save a constant; the difference is felt only by someone
watching for their own write.

**Assumption to confirm before building:** hundreds to low thousands of distinct fact keys. At tens of
thousands the arithmetic changes and the history should be measured first.

### Risk of making this change — **LOW** (option 2) / **MEDIUM** (option 4)

Option 2 cannot change behaviour — it skips work that produces an identical result. Option 4 changes
where the snapshot is computed, so the cases to hold are playback across a fact change, a scrub
backwards across one, and an externally written fact appearing within the cadence.

### Verification

- Playing across the instant a fact changes shows it change at that instant, at 1× and at 10× speed.
- Scrubbing backwards across a fact change shows the earlier version.
- A fact written directly to the database appears within the cadence, with no tab switch.
- A fact written while the Map tab is hidden and paused appears on return, immediately.
- The facts query runs once per cadence during playback, not once per tick — assert on query count.
- With no facts changing, the canvas, roster and area membership are not re-pushed per tick.
- A store whose history exceeds the cap warns once and still draws the facts it has.

---

## F16 — A trail reappears when an entity starts moving again — UNTRIAGED — **NEW 2026-09-04**

**Files:** `FloorMapEntityAnimator.advance` (the fade-cancel branch), `.recordTrailPoint`

**Reported from manual testing, 2026-09-04:** "trails becoming activated again when a person moves —
this looks wrong". Recorded rather than diagnosed; no decision taken and none needed yet.

### What was observed

An entity that has been stationary long enough for its trail to fade starts moving again, and the
trail comes back rather than starting fresh.

### The likely mechanism — found by reading, **not** yet reproduced

A trail fades over `TRAIL_FADE_DURATION_MS` (2 s) once an animation completes. Each frame,
`advance` reconsiders every fading trail:

```java
if (activeAnimations.containsKey(id)) {
    doneFading.add(id);            // moving again - cancel the fade
} else if (timestampMs - fade.getValue() >= TRAIL_FADE_DURATION_MS) {
    entityTrails.remove(id);       // fully faded
    doneFading.add(id);
}
```

Cancelling the fade **removes the fade timer but leaves `entityTrails` intact**. So a new movement
does not begin a new trail — it resumes the old one, at full opacity, and appends to it.

Two things bound how bad that can look, which is why it is worth measuring before deciding anything:

- A fade that has already run its 2 s course removes the trail, so the effect needs a *second*
  movement within 2 s of the first finishing.
- `recordTrailPoint` trims by age (`TRAIL_MAX_AGE_MS`, 20 s) — but **only when a point is recorded**.
  Nothing trims while an entity is stationary, so the stale points survive until the first frame of
  the next movement and are dropped on that frame. A single frame of a resurrected trail at full
  brightness is consistent with "looks wrong" and would be easy to miss in a screenshot.

There is a documented precedent in the same method: an earlier bug where "a trail could be part-way
through its fade while nothing is animating… drew a fading trail at full opacity for that frame, and
the next loop tick put it back, which reads as the trail flickering bright." This looks like a
residual case of the same class.

### A second candidate, which the plan already half-anticipated

F13's *accepted behaviours* include: "a baseline landing after deltas were in flight rewinds
`lastQueriedTime` and replaces wholesale, so an entity that moved during its flight can flicker back
one tick." That flicker is a position change, so **the animator records it as movement and draws a
trail segment for it** — out and back. Accepting the flicker as cosmetic assumed it lasted one tick;
a trail makes it linger for the trail's lifetime, which is a weaker case than the one that was
accepted. Worth checking whether what was seen coincides with a baseline landing.

### What to check first

1. Reproduce with a short cadence — `generate.py --interval-seconds 30` gives movements close enough
   together to hit the within-2-s case repeatedly.
2. Determine which candidate it is: does it happen on *any* resumed movement (fade-cancel), or only
   around a baseline (the flicker)? A11's row-limit console line is a rough clock for the latter.
3. Decide whether the resurrected trail is one frame or persistent. That decides whether this is a
   cosmetic blemish or a wrong picture.

### Options, unranked and undecided

| | Approach | Note |
|---|---|---|
| 1 | Leave it | May be a single frame, in which case the cost of fixing exceeds the benefit |
| 2 | Discard the trail when a fade is cancelled | One line. Loses continuity for an entity that pauses briefly mid-journey, which is arguably the case the current behaviour is *for* |
| 3 | Trim by age on every frame, not only when recording a point | Removes the stale-points half without changing the continuity behaviour. Costs a pass over fading trails per frame |
| 4 | Suppress trail recording for a movement the baseline caused | Only if the second candidate turns out to be the real one; needs the animator to know why a position changed, which it currently does not |

### Risk of leaving it — **LOW**

Cosmetic, on a feature whose trails are explicitly best-effort: F13's decision record already states
that positions must be correct and trails are best effort. This does not affect positions, counts or
area membership.

---

# Part C — Javadoc corrections

65 docs were confirmed wrong against their implementations. Almost all are harmless drift, and the
volume is partly a side effect of unusually thorough documentation — these files carry far more javadoc
than the surrounding codebase, so there is more of it to fall out of date. Risk of correcting any of
them is **TRIVIAL**, with one exception (F7, already covered above, where the *code* should change).

Handle in one or two mechanical sweeps, separate from behavioural commits so review stays readable.

### C1 — Contracts stated backwards (fix first; a caller written to these would be wrong)

| File | Wrong claim | Reality |
|---|---|---|
| `FloorMapEditorPresenter.java:123–125, 1951–1953` | A failed flush shows an error and "reloads all panels from the server" | It deliberately keeps staged changes for retry and does **not** reload over in-progress edits. `onSave`'s own doc says so correctly |
| `ValuePathAccessor.java:55` | `@throws JavaScriptException` on malformed input | `JSONParser.parseStrict` wraps it and throws `JSONException`. A `catch` written to this doc would not catch the failure the doc exists to warn about |
| `FloorMapTimeListPresenter.java:197` | The earliest entry is selected when the timeline precedes all entries | The selection is cleared and Edit/Delete disabled |
| `FloorMapEditorPresenter.java:2016` | `pathForRole` falls back to the default schema when the document is unavailable | There is no fallback; it throws |
| `DocumentAssetService.java:269–275` | Content that cannot be viewed returns null | Oversized content throws `DataTooBigException` (`DocumentAssetDaoImpl.java:858`) |
| `FetchAtTimeRequest` | `getMapName()` "never null (validated on construction)" | The constructor is a bare assignment; null is rejected much later, server-side |

### C2 — Refactoring lag (largest cluster)

Docs still describing the pre-refactor world where the object-edit dialog and Fact List were shared
with the Map tab: `FloorMapObjectEditPresenter` and `FloorMapFactListPresenter` advertise "two
contexts" including the Map tab when all callers are now in the Editor; `FloorMapMapPresenter.onRead`
describes two query models where four are initialised and claims it configures an object-edit
presenter the class no longer holds; `parseFacts` describes a world-to-map transform and
`FloorMapObject` output that moved to render time; the editor view's layout docs put the timeline
south when it is docked north and omit a slot from an apparently exhaustive routing table;
`setDragHandler` says "while dragging" for a handler that fires once on commit; a context-menu doc
lists two of five branches; the Editor dock is documented as holding Layers *and* Groups when Groups
lives on the Map dock.

Worth noting: **no** presenter still claims responsibilities handed to the extracted
`FloorMapEntityAnimator` / `FloorMapScreenGeometry` / `FloorMapDocSession` classes. That refactor's
docs were updated properly — this cluster is all older drift.

### C3 — Documented features that do not exist

A "Tick marks" scrubber feature; a user-choosable type-column dropdown (the type column is
auto-detected by literal name with an `@`-heuristic fallback); a "Map-to-Screen matrix" form field
(only `WORLD_TO_MAP` is written); an occupant list in the area tooltip (an area row gets a fixed note);
a claim that GWT radio buttons fire on deselect, which GWT documents they do not; a "First aid" icon
in `FloorMapIcon`; a Description tab in `FloorMapDoc` (the field drives the Documentation tab).

### C4 — Stale prose

`FloorMapDoc` says only JSON is implemented when `XmlValueAccessor` ships; `FloorMapEditorModel` says
selection is "single-select today" when marquee and Shift/Ctrl multi-select have landed;
`FloorMapEntityList.update` says "only if membership changed" but also returns true on a flag-only
promotion; `FloorMapGroup.getId()` claims identity is never null when a group with neither id nor name
returns null; `onWrite` claims only the area upgrade is merged when type styles and measurement units
are too; `initTimeline` omits the padded `min == max` case; `FloorMapClusterOverlay`'s
`2 × SPREAD_LIMIT` pairwise bound is an approximation presented as a guarantee (holds for rounds 1–2,
not later ones).

### C5 — Copy-paste drift in document-asset (~15 cases)

`DocumentAssetUpdateDelete` documents a `byte[]` payload while holding only a path and a flag; the
edit dialog and the add-item dialog both claim to be the upload dialog; `getName()` is documented as
returning a file-upload widget; `DocumentAssets` has the transfer direction backwards; several docs
name `VisualisationPlugin` or `DocumentEditPresenter` as callers and the latter does not exist;
`decodeToUtf8String` says it throws when it returns null; `splitIntoDocIdAndPath` documents a List
return for a record; `getInputStreamForAsset` documents a `PermissionException` it cannot throw.

### C6 — Wrong algorithm descriptions and shifted labels

`UpdatableSqlTemporalStore` says `fetchAll` deduplicates in-process when the DAO does it entirely in
SQL; `fetchAtTime` calls an uncorrelated derived-table join a "correlated subquery";
`UpdatableTemporalStoreDao.fetchAll` claims equivalence to "find + in-process dedup" (different query,
and unpaged); `setQueryVariables` contrasts from-clause substitution with native `Param` resolution
when `String.replace` rewrites every occurrence; the `startNewSearch` argument labels in
`HistogramQueryHelper.java:139–142` are each off by one against the signature, name a `fireEvents`
parameter that does not exist, and obscure that the call runs with `incremental=false`;
`FloorMapAria.firstFocusable` says "first natively-focusable descendant" when the search is
tag-priority order, not document order; the speed-badge format examples show `"1×"`/`"×1"` when the
code emits `"x1"`.

### C7 — Dangling reference to a deleted document

`docs/floormap-accessibility.md` §9.5 is cited from `AccessibleSelectionCell`'s javadoc and from
comments in `FloorMapCanvasViewImpl.java:434` and `FloorMapQueryViewImpl.java:64`. The file was deleted
by this branch's own tip commit (`7ad6b4d9e4`). Either restore the document or remove the citations.

---

# Part D — Suggested sequencing

> **Written 2026-08-21 and only partly reconciled since.** It predates F9, F13, F14 and F15, and
> several of its PRs have landed — see *Status at a glance*, which is the authoritative record. The
> ordering rationale below still holds; the PR list does not enumerate the later findings except
> where noted.

Revised after D1. F1 was the one severe-and-expensive item; it is now severe-and-cheap, so it moves
from "scheduled separately" to second in the queue, and the stopgap PR disappears.

**PR 1 — zero-risk, land immediately.** F3 (INFO logging), the two unescaped `from` names, the stray
`GWT.log`, the duplicated comment, the histogram fallback colours. All mechanical, no decisions
needed. Do not wait for anything else in this plan.

**PR 2 — F1 (UUID re-key) + F12 (name-clash leak), together.** These are now **coupled and must not be
split**: F12's fix makes duplicate names possible, and duplicate names are what F1 exists to survive.
Landing F12 alone trades an information leak for silent cross-document data loss. Landing F1 alone is safe
but leaves the leak. Do both in one change, F1's schema and resolution work first within it. Correct the migration in place, thread
`DocRef` inward, delete the name-based permission overload, add the uniqueness guard and
`UniqueNameUtil` on copy. Announce the dev-database checksum reset alongside the merge. **Do this
early** — the longer it waits, the more likely someone creates data that a later fix would have to
rescue, which would put the hard version of this problem back on the table.

**PR 3 — correctness, small.** F7 (`hasInverse`), C1 (contracts stated backwards), F11's `HAVING`→
`WHERE` and cause-chaining items.

**PR 4 — config.** F2, once D5 is answered. Possibly just a change-log line.

**PR 5 — ingest performance.** F4 and F5 together; they share the memoised existence check. Sequence
after PR 2 so the memoised key is the UUID rather than the name.

**PR 6 — client performance, cheap tier.** F6 cheap tier, F11's group-operation batching.

**PR 7 — javadoc sweep.** C2–C7. Large but mechanical; keep it out of the behavioural PRs.

**PR 8 — playback query load.** F15 (facts: full history once, snapshot computed client-side) with
F11's playback re-query item. They are the same problem seen from two ends — F13 addressed the events
half of the churn, F15 addresses the facts half, and what is left after both is the staleness rule.
Sequence F15's cheap tier (skip the downstream work when the parsed facts are unchanged) first: it is
separable, carries no query-semantics risk, and makes the periodic re-fetch nearly free.

**Later, scheduled separately.** F6 architecture tier (per D8), F8 lazy fetching, and asset
sandboxing.

Rationale: everything severe is now also cheap, so it all lands early. The only genuinely expensive
item left is the canvas render architecture (F6 tier two), which is a performance ceiling rather than
a correctness problem and can be scoped on its own timetable.

---

# Appendix A — Verified safe: do not "fix" these

Both looked dangerous and were cleared by reading the code. Recorded so a later reviewer does not
re-raise them.

### A.1 — The visualisation-asset table migration preserves existing data

The whole `stroom-dashboard-impl-db` module was deleted and its `visualisation_assets` migration
recreated under `stroom-document-asset-impl-db`, which reads like a recipe for dropping live tables.
It is not. The Flyway history table name is deliberately kept identical
(`visualisation_assets_schema_history`) and the migration file is byte-identical, so an existing
deployment sees the version already applied with a matching checksum, skips it, and the
`CREATE TABLE IF NOT EXISTS` statements no-op. The tables are adopted, not recreated. This was done
carefully.

**Caveat:** this holds only while the datasource resolves to the same database — which is exactly what
F2 puts at risk. Fix F2 and this stays safe.

**Still relevant after D1.** D1 says our systems hold no data, but this reasoning is about the
*visualisation* asset tables, which exist in `origin/master` from 7.11 and may hold data in other
deployments — including upstream's, if this branch ever merges that way. The adoption behaviour is
therefore worth preserving deliberately rather than treating as incidental: keep the history table name
and the migration file byte-identical, and do **not** "tidy" either. This is the one place in the plan
where the no-data answer does not make the concern go away.

### A.2 — The rewritten XSLT containment function does not re-tag existing data

`PointIsInsideXYPolygon` had its containment algorithm replaced with `FloorMapGeometry.contains`. If
the two disagreed on edge cases, every already-ingested event near a polygon boundary would classify
differently — silent, permanent mis-tagging of non-Floor-Map data. Compared line by line, they agree
exactly: same AABB prefilter, same crossing-number test, same strict comparisons. The new version
additionally rejects degenerate polygons and null vertices that the old one would have thrown on. No
behaviour change for existing pipelines.

**Design note, not a bug:** `stroom-pipeline` now depends on `stroom.floormap.shared`, putting a
feature module on the ingest path. Worth a conversation about layering, not a fix in this plan.

### A.3 — Also checked and clean

Every `DELETE`/`UPDATE` in the document-asset DAO is scoped by `owner_doc_uuid`; asset servlet
authentication, per-document permission checks and path-traversal guards are correct; `applyChanges`
is transactional, rejects cross-map batches, and its upsert/delete-by-natural-key are idempotent so
the client's replay-after-failure is safe; no SQL injection (jOOQ typed API, parameterised
`PreparedStatement`s); the `ReferenceData`/`AbstractLookup` changes do not regress ordinary reference
data lookups; client SVG/HTML rendering escapes properly and the earlier stored-XSS fix on the image
`href` is present and complete; the `AnnotationDaoImpl` merge banner is accurate.

---

# Appendix B — Coverage of this review

Static review only — no build or test run was performed. Not covered, and still needed:

- A **runtime sanity pass in super dev mode** for the animation and interaction behaviour (pan,
  zoom-toward-cursor, drag, fit, follow, playback, and the delete / add-time flows).
- ~~The F1 migration designed against a populated database.~~ **No longer needed** — D1 confirmed there
  is no data, so the migration is corrected in place. What replaces it: announce the dev-database
  Flyway checksum reset to the team when PR 2 merges.
- Load testing of the ingest path once F3/F4/F5 are fixed, to confirm the remaining cost is acceptable.

---

# Appendix C — Corrections to this plan

Kept so the reasoning is auditable rather than quietly rewritten.

**2026-08-21 — F2 downgraded HIGH → MEDIUM.** My original assessment named the bad outcome as a *silent*
fallback to the default datasource, making existing visualisation assets appear deleted. That was wrong
in the direction that mattered: `stroom-app/src/main/java/stroom/app/App.java:155–158` explicitly enables
`FAIL_ON_UNKNOWN_PROPERTIES`, so a leftover old key is a hard startup failure naming the field, and the
silent misroute cannot occur. Prompted by learning the visualisation assets are already in use, which
made it worth establishing the mechanism precisely rather than reasoning from Dropwizard defaults.

**2026-08-21 — that downgrade partially walked back: a silent path does exist, but not the one I named.**
Asked why the storage needed to change at all, I checked the second config surface and found one.
"Always fails loudly" holds only for the **datasource**, and only because `AbstractDbConfig` is
`@BootStrapConfig` with `@ReadOnly` connection fields, making it YAML-only. The non-DB
`DocumentAssetConfig` is ordinary UI-editable, DB-stored config, and
`GlobalConfigBootstrapService.getValidProperties` drops overrides whose property path no longer resolves
at **DEBUG** level, leaving the row in place. So `appConfig.visualisationAsset.*` overrides set through
the Properties screen vanish silently on upgrade. Severity stays MEDIUM — lost presentation settings, not
lost assets — but the fix now needs a `config`-table path migration, added to F2 Option 1 step 2. Lesson:
"this config cannot be set silently" needed checking per config class, not once for the subsystem.

**2026-08-21 — F1 downgraded HIGH → LOW fix risk, and moved to PR 2.** See the decision log; D1 removed
the live-data migration and the backfill entirely.

**2026-08-21 — D9 opened, and it may delete F2.** On learning that a Floor Map → dashboard dependency is
acceptable and that Floor Map may move closer to dashboards, I checked what the extraction was actually
required to do. Master's `VisualisationAssetDaoImpl` has zero `VisualisationDoc` references — the storage
was already type-agnostic — so the necessary change was ~5 hard-coded `new DocRef(VisualisationDoc.TYPE,
…)` calls becoming a UUID lookup. The other ~75 files were organisational. F2 is a cost of that
organisational choice, not of the capability, so it is now conditional on D9. My earlier statement that
the module move "was necessary" was wrong: it was necessary only under the layering constraint that has
now been disclaimed.

**2026-08-21 — D3 reframed; two claims withdrawn.** Asked what existing stores do about duplicate names,
I found `SqlTemporalStoreDocStoreImpl` already rejects clashes on create, copy and rename. Withdrawn: (a)
"stroom-sqlstore does not use UniqueNameUtil, so copying a store produces an exact duplicate name" — it
does not use `UniqueNameUtil` but does something stricter, and copy is guarded; (b) "two stores named
`locations` share one dataset" as a reachable scenario — it is not, except via the case/accent gap below.
I had grepped for `UniqueNameUtil` and concluded from its absence, instead of reading the class for an
equivalent guard. Absence of the common idiom is not absence of the behaviour.

Three real gaps survive and are now recorded under D3: the guard compares with `.equals` while storage is
`utf8mb4_0900_ai_ci` and the lookup gate is `equalsIgnoreCase` (so `Locations`/`locations` are two
documents sharing one dataset); `importDocument` is not overridden and bypasses the guard; and
`copyDocument` drops the base class's VIEW check. F1's rename data-loss finding is unaffected — the guard
permits renames.

**2026-08-21 — D3 settled by a team-reported security bug; F1 promoted to mandatory.** The name-clash
guard lists every store with no permission filtering and names the clash in its error, leaking the
existence of documents the user cannot see (now F12, confirmed: `StoreImpl.list()` is
`persistence.list(type)` with no permission check, while the house mechanism sources its candidate names
from `explorerNodeService.getChildren(destinationFolderRef)`, which is permission-filtered and
folder-scoped). The guard must therefore be replaced by the standard mechanism, which reintroduces
duplicate names, which the name-as-key storage design cannot survive. F1 changes from "recommended" to
"required, and sequenced first". F1 and F12 are now a single coupled PR. This inverts my earlier framing:
I had presented the guard as a mitigation that made F1 less urgent; it is in fact a liability that makes
F1 unavoidable.

---

# Appendix D — F1 + F12 implementation record (2026-08-21)

Landed together as one change, as the plan required: F12's fix permits duplicate names, which only
the F1 re-key makes safe.

## Design chosen

`doc_uuid` is the storage key; the wire format and the GWT client are **unchanged**. Name→DocRef
resolution happens once at the service boundary, against only the documents the caller can see.
`map_name` is kept as a denormalised label and is never used to scope a read or a write.

The alternative — putting `docUuid` into `TemporalEntry`/`TemporalEntryId` — would have changed the
REST contract and four client presenters for no extra safety, since the client already holds the store
DocRef and the server can resolve it.

## What changed (12 files, +688/−439)

**Schema.** `V07_13_00_001__updatable_temporal_store.sql` corrected in place (safe: never applied to a
real database, per D1): `doc_uuid varchar(255) NOT NULL` added, primary key moved to
`(doc_uuid, key_, effective_time)`, index kept on `map_name` for the Data tab. jOOQ table, key and
record classes hand-edited to match (codegen needs a live DB).

**DAO.** Every method now takes `docUuid`; every predicate, `GROUP BY` and join uses `DOC_UUID`.
`MAP_FIELD` is deliberately **no longer mapped to a column** and map terms are stripped from incoming
expressions — honouring a stale label would make a renamed store look empty, which is the same
data-loss symptom in a new disguise.

**Service.** `checkPermission(String)` and `hasPermission(String)` — the leaky pair — are deleted,
replaced by `resolveStore`/`resolveUuid`, which filter by permission *before* matching by name, match
case-insensitively (consistent with `ReferenceData`'s `equalsIgnoreCase` gate), and refuse an ambiguous
match rather than taking `findFirst()`. The not-found message is deliberately identical for "no such
store" and "exists but you may not see it". `createResultStore` and `count`/`clear` now authorise and
scope on the DocRef they were already given instead of round-tripping through its name. The per-row
permission filters in `find`/`fetchAtTime` are gone — redundant once the query is scoped to one
authorised store, and they were O(rows) permission checks.

**Provider.** `UpdatableTemporalStoreProvider.get` no longer lists every document to check existence.
That listing was both unfiltered and, since callers invoke it once per reference entry, a full
document-store scan per row on the ingest path — so this also removes half of F4.

**F12.** The three overrides in `SqlTemporalStoreDocStoreImpl` are deleted; name handling falls through
to `AbstractDocumentStore`. This also restores the base class's `VIEW` check on copy, which the
override had silently bypassed (D3 gap 4 — now proven by test), and disposes of the case/accent
mismatch (gap 2) and the `importDocument` bypass (gap 3), since all three lived in the deleted code.

## Verification

`./gradlew check` (all tests + checkstyle, repo-wide) **green**; `:stroom-app-gwt:gwtDraftCompile`
**clean**. The DB suite ran against a real MySQL: **19 tests, 0 failures**, including four new ones that
pin the actual defects:

- `testTwoStoresSharingANameKeepSeparateData` — two documents, one name, separate data; clearing one
  leaves the other intact.
- `testRenamingTheStoreKeepsItsData` — same UUID, new name: count, `fetchAll`, `fetch` and a temporal
  `find` all still resolve. This is the headline bug.
- `testApplyChangesCannotEscapeItsStoreViaTheMapName` — a batch naming another store in its operations
  still writes only to the store it was scoped to.
- `testFindIgnoresTheMapNameTerm` — a stale label in the criteria does not hide the data.

Plus `testNoOperationEnumeratesEveryDocument`, which asserts no create/copy/rename path calls
`Store.list()` — the regression test for the leak itself.

## Follow-ups this opens

- **D3's remaining question is now live in code**: ambiguous name resolution throws
  `IllegalStateException`. Confirm that is the wanted behaviour for an XSLT `lookup()` mid-stream, where
  it will surface as a pipeline error.
- **The name-uniqueness constraint is gone**, as D3 recommended, so two stores may now share a name.
  They cannot share data, but they cannot both be addressed by name either.
- **F4/F5 partially done**: the per-entry document-store scan is gone. The unbatched inserts and the
  uncached lookups remain.

---

# Appendix E — D9 decided, F2 implemented (2026-08-26)

**D9: keep the module.** Reverting the extraction was rejected on the grounds that the merge
cost will be dealt with when the merge happens. D5 goes with it: the alias accepts the old keys
whichever of its three cases a deployment is in, so there is nothing to check.

**F2 implemented** (`44d344cee2`). Jackson's `@JsonAlias` is the mechanism, and the branch already
used it — `FloorMapDoc` carries `@JsonAlias("temporalStoreRef")` for the same kind of rename. My
earlier claim that Stroom had no deprecation mechanism was wrong: I searched for a Stroom-specific
subsystem instead of the framework feature that was already in use a few files away.

Three parts: aliases on the two `AppConfig` constructor parameters; a `config`-table path
migration, because `ConfigMapper` reads `@JsonProperty` alone and never consults the alias; and a
change entry.

**One behaviour worth carrying forward.** I asserted that supplying both key names would be
rejected as ambiguous. It is not. An alias is another spelling of the same property, so the last
occurrence in the file wins, silently — with the old key second, the old value won. That is the
real trap in this migration, so the change entry says *rename* the key rather than add the new one
alongside, and `TestAppConfigDeprecatedAssetKeys` pins both orderings.

## Remaining open decisions

**Superseded — see *Status at a glance* at the top of this document.** This section said "only two,
both small" and stayed that way while the branch gained twelve more commits and five new findings,
three of them found by running the application rather than reading it. A summary that is not
reconciled against `git log` decays into the most confident wrong statement in the document.

---

# Appendix F — Events store moved to Plan B (2026-08-28)

Reported separately from the review: the events store was a `SqlTemporalStoreDoc` and should
always have been Plan B. Implemented.

## What made it a two-line change

The floor map never touches the events store through an API. It uses the reference for exactly
three things:

- `FloorMapQueryPresenter.buildQueryVariables` puts the **name** into the `EventStore` query
  variable, so `from param('EventStore')` resolves by name at query time like any other
  data source;
- the two `DocSelectionBoxPresenter` pickers restrict what can be chosen;
- `FloorMapStoreImpl.remapDependencies` re-points it on import.

Nothing writes to it. The facts store is different — the Editor tab writes spatial data back
through `SqlTemporalStoreResource` — so that one stays a `SqlTemporalStoreDoc`, and the split is
now the interesting thing about the pair rather than an inconsistency.

Plan B's `TEMPORAL_STATE` exposes `Key`, `EffectiveTime` and `Value`
(`TemporalStateFields`) — the same three names `SqlTemporalStore` exposes, and exactly the three
the default events query selects. The query written by the init dialog therefore needed no edit
at all.

## State-type validation

`setIncludedTypes` filters by document type only; it cannot filter on state type. Of the eight
`StateType` values only `TEMPORAL_STATE` carries an effective time — `StateFieldUtil` maps it to
`EFFECTIVE_TIME_FIELD` and the others to their own time field or none — so seven of the eight
choices produce a store the default query cannot read.

Left alone that surfaces at query time as an unknown-field error, on a document that has already
been saved. `FloorMapInitPresenter.applyInitialisation` now fetches the chosen `PlanBDoc` and
checks its state type before saving, warning and resetting the dialog if it is wrong. That check
lives in the init dialog specifically because that is the one place that *writes* a query
depending on `EffectiveTime`; a `null` state type is possible and is worded rather than
NPE'd.

**Not done, deliberately:** the Settings tab picker has no equivalent check. Adding one means an
async fetch inside `onWrite`, which is synchronous, so it would need a different shape — a
validation hook or a check on selection change. Worth doing, but it is a separate piece of work
and the init path covers document creation, which is where the default query is generated.

## Existing documents

Any `FloorMapDoc` already pointing `eventsStoreRef` at a `SqlTemporalStoreDoc` keeps working:
the name substitution is type-blind, and `setIncludedTypes` constrains the picker popup but does
not reject a value already set, so the box still displays it. No migration, and none needed here
— confirmed there is no production data.

## Verification

`:stroom-core-client:compileJava`, `:stroom-core-shared:compileJava`, both `checkstyleMain`
tasks (only the three pre-existing `FileLength` warnings), `:stroom-app-gwt:gwtDraftCompile`,
and the floormap and core-shared test tasks. A full `./gradlew check` still wants running before
this is committed alongside the rest.

The changelog entry was reworded: it described the floor map as plotting from "a SQL Temporal
Store", which is now only half the story.

## Appendix F.1 — the Plan B move broke Map playback (2026-09-01)

Found by running it: the Map tab showed no events, while the Events Query tab returned 5014 rows
from the same store. The Map's events query returned **0 rows, unconditionally**.

`FloorMapMapPresenter.runQueryAtSelectedTime` built a zero-width range,
`start == end == selectedTime`, for both queries. `ResultStoreManager.addTimeRangeExpression`
renders a range as `time >= from AND time < to`, so a zero-width range is
`>= T AND < T` — unsatisfiable for every row, for any `T`.

It had always been unsatisfiable. It only worked because `SqlTemporalStore` never applied it:
`getQueryTime` lifts the upper bound out as a snapshot time, `getFilteredExpression` strips every
time term before the SQL is built — so the contradictory lower bound never reaches the database —
and the DAO runs `max(effective_time) <= T` per key. The store was quietly supplying
snapshot-at-T semantics that the caller depended on and never asked for.
`PlanBSearchHelper.search` is a row-by-row predicate with no such reinterpretation, so it
evaluated the contradiction honestly.

**What the review missed.** I checked that Plan B's `TEMPORAL_STATE` exposes `Key`,
`EffectiveTime` and `Value` under the same names, and concluded the query would work verbatim. It
does — on a tab that supplies its own time range. Field names were the easy half; the half that
mattered was time semantics, and the Map tab's idiom was only ever viable against one
implementation.

### The fix

A trailing 20s window ending at `selectedTime + 1` (the `+1` because the generated term is
`LESS_THAN`), plus `FloorMapQueryPresenter.latestPerEntity` reducing the window to one row per
entity client-side.

Applied at the caller rather than at either store, because it is then correct for both:
`SqlTemporalStore` strips the lower bound and still returns its snapshot; Plan B returns the
window and the client reduces it. The reduction is a no-op on already-unique rows, which is
pinned by a test. The facts query keeps its instant — a fact set months old must stay visible,
and a bounded window would hide it.

**Known imperfection, deliberately shipped.** The time column arrives already rendered to the
viewing user's date-time preference, so there is no timestamp to compare, only its presentation.
Epoch milliseconds compare numerically and the default ISO-8601 form sorts correctly as text; a
user pattern that is not lexicographically ordered (`dd/MM/yyyy`) makes the comparison pick the
wrong row of the window. The error is bounded by the window — a stale position, never a wrong
entity — and is strictly better than zero rows. The real fix is for the events query to carry a
raw numeric time beside the formatted one; that is a query-contract change and is not in this
pass.

### Still open from the same session

- **Layers / Discover types find nothing.** Fed by the Editor's own REST path, so untouched by
  this. Leading suspicion: the init dialog hardcodes `ValueFormat.JSON` and
  `initialValueSchema()`, so a deployment whose facts are XML or use other paths gets facts that
  parse with null types — no seen types, no layers, nothing to discover, all silent.
- **The type column is never matched.** `parseRows` looks for a column named `type`
  (case-insensitively); the default query aliases it `Event Type`. Entity type therefore always
  comes from the `entityId.contains("@")` fallback. Changing it alters rendering for existing
  maps, so it needs a decision.

## Appendix F.2 — Floor Map assets were never exported or imported (2026-09-01)

Reported: the Floor Map content pack contained no assets. Confirmed, and it was worse than
export alone.

Assets live in the `stroom.document.asset` subsystem's own table keyed on the owning document's
UUID, not inside the serialised document, so `Store` carries none of them. Every lifecycle
operation that should move them has to say so explicitly. `VisualisationStoreImpl` — the other
owner of assets — overrides four operations to do that. `FloorMapStoreImpl` overrode **none** of
them:

| Operation | Was | Consequence |
|---|---|---|
| `exportDocument` | assets omitted | content pack ships without graphics or backgrounds |
| `importDocument` | assets ignored | nothing to restore even once export is fixed |
| `deleteDocument` | assets orphaned | unreachable, permanent rows; a leak per deleted document |
| `copyDocument` | assets dropped | a duplicated floor map renders with every graphic missing |

All four now delegate to `DocumentAssetService`, mirroring `VisualisationStoreImpl`. This needed a
new `stroom-document-asset-impl` dependency in `stroom-floormap-impl`; both modules are installed
in `CoreModule`, so the binding was already in scope.

**A fifth thing, found while comparing the two.** `FloorMapStoreImpl.copyDocument` overrode the
base method and dropped its `checkDocumentPermission(docRef, VIEW)`, reaching the unchecked
`getStore()` handle directly. `VisualisationStoreImpl` carries that check with a comment saying
exactly why. Reinstated. `ExplorerServiceImpl` guards its own copy path with OWNER, so this is
defence in depth rather than the only barrier — but the store is reachable by other callers.

### Why nothing caught it

Nothing fails when an asset is left behind. An export without assets produces a valid pack; the
import succeeds; the floor map renders with every graphic missing, on the far system, later. The
failure is separated from its cause by a system boundary and a deployment.

`TestFloorMapStoreAssets` therefore asserts the delegation rather than any asset-store behaviour —
the question is only ever "was the asset service told", because whenever the answer was no,
nothing else noticed. Verified by removing all four hand-offs (four tests fail) and by removing
the permission check (one test fails).

### Two consequences to know about

**Existing packs must be re-exported.** The fix changes what export produces; it cannot
retrofit assets into a pack already built.

**Importing an asset-less pack over a document that has assets now deletes them.**
`setAssetsFromImport` deletes every live asset for the owning document before inserting, so an
empty incoming set clears the lot. Before this change import ignored assets entirely and they
survived. This is the same semantic `VisualisationStoreImpl` has had all along — import defines
the asset set, which is what makes a re-import idempotent and lets an upstream deletion
propagate — so it was left alone rather than special-cased on "empty means don't touch". Worth a
release note, because the first thing anyone will do is import the old pack.
