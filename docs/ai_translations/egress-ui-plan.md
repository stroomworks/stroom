# Egress policy and its admin UI: a plan

**Status:** a planning draft for the project owner. Nothing here is built. It plans the work described in §6.5 of
`shared-modules-design-note.md`, using the simplified rule model the project owner chose (exact or prefix matching,
no patterns, expiry, limits, client profiles, scope or direction).

**Why a plan.** Egress control is the floor for automatic mode in AI translations (§6.7 of the note), and it
is worth having for every translation, with or without AI. The note's §6.5 says what the policy is. This document
says how it would be built: what is required, what is assumed, what could go wrong, which modules are made or
changed, what the screens look like, and how it behaves on real cases.

**Where it lives.** This is a Stroom core feature, not an AI feature. It sits beside the AI documents only because
the AI work needs it first. It can move to `docs/` when the work is approved.

**How far to trust this.** The module layout and the UI rules are read from the repository and are marked
*(verified)*. Everything marked *(to confirm)* is something I expect and have not checked. Nothing was run.

---

## 1. What is being built

One registry of **destinations** that Stroom may send data to, or read from, outside its own process. One **guard**
that every such route calls before it acts. One **admin screen** where a person with a protected permission adds,
reviews and removes destinations, and sees what was blocked.

```text
   every sink                    one guard                    one registry                 one screen
 ----------------           --------------------        --------------------         ------------------
  http-call / fetch-json                                                              Egress Policy
  HTTPAppender, HttpPost    check(kind, destination)    rules: kind, destination,      Destinations
  File / RollingFile        ---------------------->     match (exact or prefix),       Blocked and reported
  S3, Kafka, Solr, Elastic  allow / deny / report       comment, enabled               Requests
  send-email, ask-ai        and record the decision                                     Audit
  doc(), unparsed-text()...  (fails closed)                                             Settings
```

**The rule has five fields:** kind, destination, match (exact or prefix), comment, enabled. Nothing else. The reasoning
for what was left out is in §6.5.4.1 of the note.

## 2. Requirements

Numbered so they can be traced to tests. "Must" is the 1.0 floor for automatic mode in AI translations (§6.7), and
"should" is wanted but not on that floor.

### 2.1 The policy

| # | Requirement | Priority |
|---|---|---|
| R1 | A destination is permitted only if an **enabled rule** matches it. No rule means denied | Must |
| R2 | A rule matches by **exact** string or by **prefix by whole component**, chosen per rule. No wildcards or regular expressions | Must |
| R3 | Matching is done on the **normalised** destination, never the raw string (user-info rejected, case, trailing dot, default port, IDN, `..`, encoded characters; paths made canonical with links resolved) | Must |
| R4 | One kind per rule: HTTP, EMAIL, AI_MODEL, FILE, OBJECT_STORE, MESSAGE_BUS, SEARCH_BACKEND | Must |
| R5 | The guard **fails closed**: an exception, an unknown kind or an unavailable registry denies | Must |
| R6 | **Every** sink calls the guard, including the standard XSLT routes (`doc`, `document`, `unparsed-text`, `collection`, `xsl:result-document`) and the XPath routes (`xpath()` and the extraction filter) | Must |
| R7 | A build fails if a class that can do I/O has no declared egress kind | Must |
| R8 | A **global mode per kind**: report-only (record, do not block) or enforce. Never allow-all | Must |
| R9 | A **kill switch** that denies all egress of a chosen kind at once | Must |
| R10 | HTTP: https only unless a rule names http; redirects re-checked at each hop or disabled; XSLT-supplied TLS settings ignored and reported; `Authorization` and `Cookie` headers from the XSLT stripped | Must |
| R11 | FILE: the check is on the fully expanded, canonical path, **before** any directory or lock file is created. Rolling appenders check both the active and the rolled destination. Every `outputPaths` entry is checked at save | Must |

### 2.2 The screen and who may use it

| # | Requirement | Priority |
|---|---|---|
| R12 | One UI to see and manage every rule, whatever its kind | Must |
| R13 | A new application permission, for example **Manage Egress Policy**, held by a small group | Must |
| R14 | The policy and its permission are **protected from AI agents**: an agent acting with a user's authority cannot edit the policy, and the MCP server exposes no tool that can | Must |
| R15 | Every change is audited with who, when and the old and new value | Must |
| R16 | A **"would this be allowed?" test box**: enter a kind and destination, see the normalised form and which rule matches or why none does | Should |
| R17 | A **Blocked and reported** list of every denied or report-only attempt, with one click to turn an entry into a rule or a request | Must |
| R18 | A **request and approval** workflow: the person who requests a rule cannot approve it | Should |
| R19 | Each rule shows **last used** and a use count, so a stale or relied-on rule is visible | Should |
| R20 | **Inventory import**: run the XSLT dependency parser over existing translations and propose rules | Should |
| R21 | Changing global mode or the kill switch needs the same permission, is audited, and is shown prominently | Must |

### 2.3 Behaviour, safety and quality

| # | Requirement | Priority |
|---|---|---|
| R22 | **No change of behaviour for existing translations** until a kind is switched to enforce. Report-only is the default on first install | Must |
| R23 | Consistent on **every node** of a cluster, with a bounded delay when a rule changes | Must |
| R24 | The guard adds negligible cost to a call: the rules are held in memory and matching is a lookup | Must |
| R25 | Decisions are recorded without flooding: denied and report-only attempts are **aggregated** (destination, sink, pipeline, count, first and last seen), not logged one per call | Must |
| R26 | The UI follows `stroom-gwt/UI-GUIDE.md` *(verified)*: a component and controller per screen, Stroom's widgets, BEM styles, workbench stories | Must |
| R27 | Tests: unit tests for matching and normalisation, a real-node test for each Saxon route, the sink-enumeration test, and workbench stories for each screen | Must |
| R28 | British English throughout, and the project's Java conventions (`AGENTS.md` *(verified)*) | Must |

## 3. Assumptions

| # | Assumption | If it is wrong |
|---|---|---|
| A1 | One policy for the whole system. There is **no scope** (per feed, pipeline or group), as decided by the project owner | A per-team destination would need scope, and the rule table would change |
| A2 | The Saxon hooks installed together close every probed route, in a running node as they did in a standalone probe | The probe (§6.5.2 of the note) was not run inside Stroom. A real-node test comes first (Phase 0) |
| A3 | Rules live in the **database** and are changed at run time through the UI. They are not in the YAML configuration | Config properties need a restart and cannot be audited per change |
| A4 | The current stage is a **single trust level**: anyone with the permission may approve, and the requester-not-approver rule is the only separation | A second approver or a role split would add a state machine |
| A5 | Existing translations that use these routes can be found by the parser (§5 of the note) and by report-only mode | A destination computed at run time is only found by report-only, which needs time |
| A6 | Stroom's existing patterns are available for this feature: a module set of `api`, `impl`, `impl-db`, a resource interface in `stroom-core-shared`, `@AutoLogged` on the resource, a content tab in `stroom-core-client`, and cluster-wide cache invalidation *(the first four verified in `stroom-credentials`; the cache pattern to confirm)* | Some plumbing would need to be built here |
| A7 | The GWT client cannot use `java.net.URI` or `java.nio.file.Path` *(to confirm)*, so **normalisation runs on the server only**. The screen shows the normalised form by asking the server | If the client can share the code, the test box can be instant |
| A8 | An AI agent is identifiable as one, by the identity it acts with, so the permission can be withheld *(to confirm: how agents authenticate to Stroom and to the MCP server)* | The protection in R14 would need a different mechanism |
| A9 | Enforcement is **by kind**, switched on one kind at a time | If a single switch is needed, simpler |
| A10 | The data-read functions (`meta-stream-for-id`, lookups) are **out of scope** here. They are a data-exposure question (question 18 in the note) | They would need their own permission check |

## 4. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| K1 | **A sink is missed**, so data leaves with no check | Medium | High | The build-failing enumeration (R7). Real-node tests for each route. The inventory in §6.5.3 of the note is the first population |
| K2 | **Switching to enforce breaks existing translations** | High | High | Report-only first, the Blocked list, inventory import, one kind at a time, the kill switch as a way back |
| K3 | **Normalisation bug** lets a differently written destination match a rule (or not) | Medium | High | Exact or whole-component prefix only. A table of bypass cases as tests (§6.5.8 of the note). Normalise before every comparison. Fail closed |
| K4 | **Prefix on a path** is read as a string prefix (`/stroom/output-old` matching `/stroom/output`) | Low | High | Compare by path components. A named test |
| K5 | **A symbolic link inside an allowed directory** points outside it | Medium | High | Resolve links on the nearest existing ancestor, then compare. Check before creating directories |
| K6 | **False reassurance**: an approved generic webhook is still an exfiltration route | High | Medium | Say so on the screen and in the documentation. Limits are deferred, not forgotten (§6.5.4.1) |
| K7 | **Stale rules on one node** after a change | Medium | Medium | Cluster invalidation. A bound on the delay. Show the version each node holds |
| K8 | **The guard becomes a bottleneck** | Low | Medium | In-memory rules, hash lookup for exact, a sorted structure for prefix. Measure in Phase 2 |
| K9 | **The Blocked list floods** | Medium | Medium | Aggregate (R25), cap the table, expire old rows |
| K10 | **Lock-out**: the policy blocks the route the admin needs to fix it | Low | High | The policy never governs Stroom's own database or its UI. The kill switch is a database setting changed from the screen or by an administrator |
| K11 | **`DO_NOT_RECOVER`** turns recoverable XSLT warnings into failures for translations that relied on them | Medium | Medium | Apply it only to untrusted content at first. Test on existing translations in report-only |
| K12 | **An approver approves without looking**, or approves for a requester they trust blindly | Medium | Medium | Show what the rule would permit, in plain words, and what has already been blocked against it. Audit |
| K13 | **The policy is edited by an AI agent**, directly or by a chain of tools | Low | High | R14. A test that lists the MCP tools and asserts none touches the policy |
| K14 | **The UI is large** and slows the guard work | Medium | Medium | The phased plan (§7): engine and a small screen first |
| K15 | **Report-only never ends**, and nothing is ever enforced | Medium | High | A visible banner while any kind is in report-only, and a count of attempts that would have been denied |
| K16 | **Real-node behaviour differs** from the standalone probe | Medium | High | Phase 0 test on a real node, before design is fixed |

## 5. Modules

The shape follows `stroom-credentials`, which has the same needs: a small table, a resource, a content tab
*(verified: `api`, `impl`, `impl-db` and `impl-db-jooq`, `CredentialsResource` in `stroom-core-shared`,
`CredentialsResourceImpl` marked `@AutoLogged`, `CredentialsPlugin` and a `component` package in
`stroom-core-client`, `AppPermission.CREDENTIALS`)*.

### 5.1 New modules

| Module | Contents |
|---|---|
| `stroom-egress` (parent) | Gradle parent for the four below |
| `stroom-egress-api` | `EgressGuard`, `EgressRequest`, `EgressDecision`, `EgressKind`, `EgressMode`, the `@EgressSink` annotation. **Small and dependency-light**, because every sink module depends on it |
| `stroom-egress-impl` | The guard, the registry cache, the **normalisers** (HTTP, email, file, object store, message bus), the matcher, `EgressResourceImpl`, `EgressService`, request and approval logic, decision aggregation, the Guice module |
| `stroom-egress-impl-db` | Flyway migration and DAOs for rules, requests, aggregated decisions and settings, and the DB module |
| `stroom-egress-impl-db-jooq` | Generated jOOQ code *(generated, never edited by hand)* |

### 5.2 Modified modules

| Module | Change | Notes |
|---|---|---|
| `stroom-core-shared` | `AppPermission.MANAGE_EGRESS_POLICY`; `EgressResource` (the REST interface); the DTOs (`EgressRule`, `EgressRequestDto`, `EgressDecisionSummary`, settings); `EgressKind`, `EgressMatch` | Shared with the GWT client, so GWT-compatible code only |
| `stroom-core-client` | The screen: `EgressPlugin`, `EgressClient`, the components and controllers (§6), the GIN module, and registration in the Administration menu | Follows the UI guide *(verified)* |
| `stroom-gwt/stroom-gwt-workbench` | Stories for every screen and dialog | Default, read-only, dialog OK and Cancel, with the leak check *(verified)* |
| `stroom-app` | Wiring; CSS `egress-policy.css` and its import; **the build-failing sink-enumeration test**; integration tests | `stroom-app` is the only place that sees every module, so the enumeration test belongs there *(to confirm)* |
| `stroom-util` | A small interface for the Saxon hooks (for example `SaxonAccessGuard`) and `SaxonConfinement.confine(Configuration, …)`, so low-level modules need not depend on `stroom-egress-api` | `stroom-util` already depends on Saxon *(verified)* |
| `stroom-pipeline` | The guard calls in the XSLT functions `http-call`, `fetch-json`, `send-email`, `ask-ai`; the elements `HTTPAppender`, `HttpPostFilter`, `FileAppender`, `RollingFileAppender`, `HDFSFileAppender`; `XsltPoolImpl` (Saxon hooks); the client-config handling in `CommonHttpClient` | The largest change |
| `stroom-query` (`stroom-query-language`) | The `xpath()` function uses a confined Saxon configuration | The widest exposure today (§6.5.6.2 of the note) |
| `stroom-search` (`-extraction`, `-solr`, `-elastic`) | `XPathExtractionOutputFilter` confined; `SolrIndexingFilter` and `ElasticIndexingFilter` call the guard | |
| `stroom-kafka` (`-impl`) | `StandardKafkaProducer` calls the guard | |
| `stroom-aws` (`-s3-impl`) | `S3Appender` calls the guard | |
| `stroom-ai` and `stroom-shapeshifter-ai` | Model calls check an `AI_MODEL` rule; `XsltStep` and `OutputRecords` use the shared confinement | `ShapeshifterAiParser` stays in the inventory |
| `stroom-config` | Any bootstrap setting (for example the default mode on first install) and `expected.yaml` regenerated by `GenerateExpectedYaml` | Never hand-edited *(verified, `AGENTS.md`)* |
| MCP server | Assert the tool list has nothing that edits the policy | R14 |
| User documentation | A page for administrators | |

Classes that **create a Saxon `Processor`, `Configuration` or `TransformerFactory`** are listed in §6.5.6.2 of the note.
A scan test (R7's partner) keeps that list from drifting.

## 6. The screen

The UI guide's rules *(verified)*: a screen is a **component** (a UiBinder template and its Java, dumb) and a
**controller** (REST calls, dialogs, validation); no presenters; Stroom's widgets and not GWT's; no `@UiHandler`;
BEM styles in the component's own sheet; stories for each screen. A content tab such as Jobs or Users extends
`ContentTabController` and is opened by its area's content plugin.

### 6.1 Structure

```text
Administration  >  Egress Policy                     (a content tab, EgressPolicyController, a TabbedController)
  |
  +-- Destinations            EgressDestinations + Controller         the list, with filter and toolbar
  |      +-- Rule editor      EgressRuleEditor + Controller           a dialog: add or edit a rule
  |      +-- Test box         EgressTestBox + Controller              a smart part, in the editor and on the list
  +-- Blocked and reported    EgressBlockedList + Controller          the aggregated attempts
  +-- Requests                EgressRequests + Controller             pending proposals, approve or reject
  +-- Audit                   EgressAudit + Controller                decisions and changes
  +-- Settings                EgressSettings + Controller             mode per kind, kill switch
```

| Piece | Name |
|---|---|
| Plugin | `EgressPlugin extends ContentPlugin<EgressPolicyController>` (as `CredentialsPlugin` does) |
| REST client | `EgressClient` (as `CredentialClient`) |
| GIN module | `EgressModule` |
| Stylesheet | `stroom-app/src/main/resources/ui/css/egress-policy.css`, BEM blocks `egress-policy`, `egress-rule-editor` and so on |
| Stories | `EgressDestinationsStories`, `EgressRuleEditorStories`, `EgressBlockedListStories`, `EgressSettingsStories`, each with default, read-only, and OK and Cancel |

### 6.2 Destinations

```text
+-- Egress Policy ------------------------------------------------------------------------------------+
| [Destinations] [Blocked and reported 12] [Requests 2] [Audit] [Settings]                              |
|                                                                                                       |
|  !  HTTP is in REPORT-ONLY mode: attempts are recorded but not blocked.   [ Go to Settings ]           |
|                                                                                                       |
|  [+ Add] [Edit] [Delete] [Enable/Disable]   Kind: [All          v]   Filter: [______________ ]        |
|  +---+-----------+----------------------------------------+--------+-----------+----------+--------+ |
|  |   | Kind      | Destination                            | Match  | Last used | Uses 30d | Enabled| |
|  +---+-----------+----------------------------------------+--------+-----------+----------+--------+ |
|  | o | HTTP      | https://siem.example.org:8443/api/     | Prefix | 2 min ago |   48,210 |   [x]  | |
|  |   | HTTP      | https://hooks.example.org/stroom       | Exact  | 1 day ago |      312 |   [x]  | |
|  |   | EMAIL     | alerts@example.org                     | Exact  | 3 h ago   |       14 |   [x]  | |
|  |   | FILE      | /stroom/output                         | Prefix | 1 min ago |  901,114 |   [x]  | |
|  |   | AI_MODEL  | 2f0b8c1e-...-a41  (Local model)        | Exact  | 5 min ago |    1,820 |   [x]  | |
|  |   | HTTP      | https://old.example.org/               | Prefix | never     |        0 |   [ ]  | |
|  +---+-----------+----------------------------------------+--------+-----------+----------+--------+ |
|                                                                                                       |
|  Would this be allowed?   Kind [HTTP v]  Destination [ https://siem.example.org:8443/api/x ]  [Test]  |
|  -> Allowed by rule 3 (Prefix https://siem.example.org:8443/api/). Normalised: same.                  |
+-------------------------------------------------------------------------------------------------------+
```

### 6.3 The rule editor (a dialog)

```text
+-- Add destination ---------------------------------------------------------------+
|  Kind         [ HTTP              v ]                                            |
|  Destination  [ https://siem.example.org:8443/api/                          ]    |
|  Match        (o) Exact     ( ) Prefix                                           |
|                                                                                  |
|  Normalised as   https://siem.example.org:8443/api/                              |
|  This would permit   any path under /api/ on siem.example.org, port 8443, https. |
|  Already blocked against this   312 attempts, last 2 min ago  [ View ]           |
|                                                                                  |
|  Comment (required)  [ SIEM event forwarder, owner: security operations     ]    |
|  [x] Enabled                                                                     |
|                                                       [ Cancel ]  [ OK ]         |
+----------------------------------------------------------------------------------+
```

The dialog **shows what the rule permits in plain words** and **what has already been blocked against it**, so the
person decides with the effect in front of them (risk K12). OK is refused, with the reason under the field, while
the destination cannot be normalised or the comment is empty *(the UI guide's rule: input that cannot be kept is
refused, never changed)*.

### 6.4 Blocked and reported

```text
+-- Blocked and reported -------------------------------------------------------------------------------+
|  Show [Last 7 days v]   [x] Denied   [x] Would be denied (report-only)                                 |
|  +-------------+----------------------------------------+------------------+--------+-------+-------+ |
|  | Kind        | Destination (normalised)               | Sink             | Pipeline| Count | Last  | |
|  +-------------+----------------------------------------+------------------+--------+-------+-------+ |
|  | HTTP        | https://hooks.slack.example/T000/B000  | http-call        | alerts | 1,204 | 1 min | |
|  | FILE        | /var/tmp/export/2026-11-04/out.dat     | FileAppender     | export |    96 | 5 min | |
|  | FILE read   | /etc/passwd                            | unparsed-text()  | feed-x |     3 | 2 h   | |
|  | HTTP        | https://169.254.169.254/latest/meta    | doc()            | feed-y |     1 | 1 d   | |
|  +-------------+----------------------------------------+------------------+--------+-------+-------+ |
|  [ Add to policy... ]   [ Request a rule... ]   [ Ignore ]                                            |
+--------------------------------------------------------------------------------------------------------+
```

A row such as `/etc/passwd` read by `unparsed-text()` is **not** something to add. The screen says so: for the standard
XSLT routes the recommended default is to deny outright (§6.5.6.3 of the note), so *Add to policy* is replaced by a
warning for those sinks and asks for a second look.

### 6.5 Settings

```text
+-- Settings ---------------------------------------------------------------------------------------------+
|  Mode by kind                                                                                           |
|    HTTP             ( ) Report only   (o) Enforce        Kill switch  [ OFF ]                            |
|    EMAIL            (o) Report only   ( ) Enforce        Kill switch  [ OFF ]                            |
|    FILE             (o) Report only   ( ) Enforce        Kill switch  [ OFF ]                            |
|    AI_MODEL         ( ) Report only   (o) Enforce        Kill switch  [ OFF ]                            |
|    OBJECT_STORE, MESSAGE_BUS, SEARCH_BACKEND  ...                                                       |
|                                                                                                         |
|  Kill switch: when ON, every egress of that kind is denied, whatever the rules say.                     |
|  Changing a mode or a kill switch is recorded with your name.             [ Cancel ]   [ Save ]         |
+---------------------------------------------------------------------------------------------------------+
```

## 7. Phases

| Phase | Deliverable | Exit test |
|---|---|---|
| **0. Verify** | A real-node test that the four Saxon hooks close the routes in a running Stroom (open question 16 in the note). Confirm A6, A7 and A8. Read the UI guide's worked examples (receipt rules, the analytic notification's stream destination) | The finding reproduced or refuted. The unknowns above settled |
| **1. Engine in report-only** | `stroom-egress-*`, the database tables, the guard and normalisers, the Saxon confinement, calls added to the HTTP functions, `HTTPAppender`, the file appenders and the XSLT and XPath routes. **No screen yet**; rules are loaded by an administrative REST call | Existing pipelines are unchanged. Report-only decisions are recorded. Unit tests pass for matching and normalisation |
| **2. A small screen** | Destinations list and rule editor, Blocked and reported, Settings, the permission. Stories for each | An administrator can add a rule from a blocked attempt and switch HTTP to enforce |
| **3. Enforce, and the rest of the sinks** | S3, Kafka, Solr, Elastic, email, AI. The build-failing enumeration. The test box. Measure the guard's cost | A sink without a kind fails the build. Enforce works per kind |
| **4. Governance** | Requests and approval, Audit tab, last-used columns, inventory import from the dependency parser | A request by one person cannot be approved by that person. Import proposes rules from an existing estate |
| **5. Harden and document** | The bypass tests, the kill-switch drill, cluster staleness test, user documentation, a roll-out guide | Every item in §9 passes |

Phases 0 to 2 are the **1.0 floor** for automatic mode (the egress policy with the Saxon hooks, §6.7 of the note).
Phase 3 completes the coverage. Phase 4 can follow.

## 8. Worked examples

Each shows what a person does and what the system does. Destinations are made up.

### 8.1 Adding a SIEM destination from a blocked attempt (HTTP, prefix)

1. In Report-only, the Blocked list shows `https://siem.example.org:8443/api/events` from `HttpPostFilter`, pipeline
   `siem-forward`, 48,210 times.
2. The administrator selects the row and chooses *Add to policy*. The editor opens filled in: kind HTTP, destination
   `https://siem.example.org:8443/api/events`, match Exact.
3. They know the path varies by tenant, so they change it to `https://siem.example.org:8443/api/` and Prefix. The
   editor says: *"This would permit any path under /api/ on siem.example.org, port 8443, https."*
4. They enter the comment "SIEM forwarder, security operations" and press OK. The change is audited with their name.

| Later request | Decision |
|---|---|
| `https://siem.example.org:8443/api/events/9` | **Allowed** (prefix, whole segments) |
| `https://siem.example.org:8443/apix` | **Denied** (`/apix` is not under `/api/`) |
| `https://siem.example.org.evil.example:8443/api/` | **Denied** (the host differs) |
| `https://allowed.example@evil.example/` with user-info | **Denied** (user-info is rejected before matching) |
| `http://siem.example.org:8443/api/events` | **Denied** (https only unless the rule names http) |

### 8.2 A file appender: prefix on a directory

A rule: FILE `/stroom/output`, Prefix. A pipeline's `outputPaths` is `/stroom/output/${feed}/${year}/${uuid}.dat`.

| Expanded path | Decision | Why |
|---|---|---|
| `/stroom/output/FW02/2026/3f2a.dat` | **Allowed** | Under the directory, by whole components |
| `/stroom/output-old/FW02/x.dat` | **Denied** | `output-old` is not the component `output` |
| `/stroom/output/../etc/cron.d/x` | **Denied** | `..` is resolved first, giving `/stroom/etc/cron.d/x` |
| `/stroom/output/link/x.dat` where `link` points to `/etc` | **Denied** | The link is resolved on the nearest existing ancestor, giving `/etc/x.dat` |
| Any of the above before the directory exists | **No directory is created** | The check runs before `createDirectories` |

For a rolling appender, both the active file's directory and the rolled-file destination are checked, and every entry
in `outputPaths` is checked when the pipeline is saved, so a bad entry is found when it is written and not on a
one-in-N random pick.

### 8.3 Discovering what an existing estate does (report-only, then enforce)

1. First install. Every kind is in report-only. Nothing is blocked.
2. After a week the Blocked list shows 11 destinations. Nine belong to known systems. Two are surprises: a pipeline
   posting to an old webhook, and a feed using `doc('http://…')`.
3. The administrator adds nine rules, with owners in the comments. They ask the pipeline owners about the other two.
4. The webhook is retired. The `doc()` use is replaced.
5. HTTP is switched to enforce. The banner on the Destinations tab disappears for HTTP. EMAIL and FILE remain in
   report-only, and the banner names them.
6. A month later a new pipeline calls an unlisted host. It is **denied**, and appears in the Blocked list at once.

### 8.4 The standard XSLT routes (`unparsed-text`)

An XSLT contains `unparsed-text('file:///stroom/config/application.yml')`.

| Mode | What happens |
|---|---|
| Report-only | Saxon proceeds. The attempt is recorded as *would be denied*: FILE read, `unparsed-text()`, the pipeline and feed |
| Enforce | The hook refuses it. The stylesheet fails with "may not read external resources", and the failure is recorded |

The Blocked list marks it with a warning and no *Add to policy*: reading Stroom's own configuration is not something to
permit. If a site truly needs a read, an administrator adds a FILE rule, and the screen shows exactly what it permits.

### 8.5 An AI-authored change (the save-time check)

A model proposes an XSLT that computes a destination: `http-call(concat('https://', $host, '/hook'), …)`.

1. The review panel (`review-panel-design.md`) lists the sinks in the proposed change. For this one it says:
   *"computed at run time, will be denied unless it matches a rule."*
2. For AI-authored content the **save check rejects computed destinations outright**, so the change cannot be
   saved as written. The panel says why and suggests a literal URL.
3. If the model's next try uses the literal `https://hooks.example.org/stroom`, the panel shows *"permitted by rule 2"*
   and the review continues.

No part of this edits the policy. The model has no tool that can, and the permission is not held by an agent.

### 8.6 Request and approval (Phase 4)

1. A pipeline author hits a denial for `https://new-vendor.example/api/`. They choose *Request a rule*, with the
   reason "new vendor integration, ticket 4821".
2. The request appears under **Requests** for the people holding Manage Egress Policy. The author cannot see an
   *Approve* button on their own request.
3. An approver opens it. The panel shows what it would permit and the 14 attempts already blocked. They approve. The
   rule is created, audited with both names, and the author's pipeline works on its next run.

### 8.7 The kill switch drill

A destination is found to be compromised.

1. The administrator opens Settings and turns the HTTP kill switch **ON**. Every HTTP egress is denied at once on
   every node, whatever the rules say. The Destinations tab shows a red banner.
2. They disable the one rule, review the Audit tab for what went to that destination and when, and switch the kill
   switch **OFF**. The rest of the policy works as before.
3. The drill is a test (§9): switching it ON and OFF is audited and takes effect on all nodes within the stated delay.

### 8.8 Email: exact addresses only

A rule `EMAIL alerts@example.org`, Exact. A message to `alerts@example.org` is **allowed**. A message to
`bob@example.org` is **denied**. There is no domain-wide rule, because that would be a suffix match, which is the
pattern risk the project owner wanted to avoid. Each address is listed.

## 9. Tests and acceptance

| Test | Proves | Requirement |
|---|---|---|
| Matching and normalisation unit tests with the bypass table (user-info, case, IDN, trailing dot, default port, encoded characters, `..`, look-alike host, look-alike directory) | A differently written destination cannot match a rule it should not | R2, R3 |
| Path tests: links, relative paths, variables that expand outside the prefix, check before directory creation | The file guard | R11 |
| A real-node test per Saxon route: denied with no rule, permitted with one | The probe finding is closed in a running node | R6, K16 |
| The build-failing enumeration of I/O classes | No new sink without a declared kind | R7 |
| A scan for new Saxon `Processor`, `Configuration` and `TransformerFactory` creation sites | No unconfined evaluation of untrusted content | R6 |
| Fail-closed: the guard throws, the registry is unavailable | Everything denied | R5 |
| Report-only changes nothing about a pipeline's output | No behaviour change before enforce | R22 |
| Kill switch ON and OFF, on two nodes | Consistency and speed | R9, R23 |
| An MCP tool-list assertion | An agent cannot edit the policy | R14 |
| Permission: a user without the permission cannot read or change rules; the requester cannot approve | Access control | R13, R18 |
| Workbench stories for every screen and dialog, with the leak check | The UI guide's rules | R26 |
| Guard cost measured under load | Negligible overhead | R24 |

## 10. Open questions

1. **Where do rules and settings live?** The plan assumes the database (A3). The alternative is configuration
   properties, which Stroom already audits through its Properties screen *(to confirm)*. The reason to prefer the
   database is per-change audit and no restart.
2. **How is a cluster kept consistent?** Which existing cache and invalidation mechanism do the other admin tables use?
3. **Can an agent be recognised?** How do AI agents and the MCP server authenticate, and can the permission be withheld
   from that identity by construction and not by a list?
4. **Who may hold the permission**, and is a second approver required for new destinations? (Question 20 in the note.)
5. **Is exact-or-prefix enough for every kind?** (Question 19 in the note.) Kafka topic families and S3 key families
   are the likeliest to want more.
6. **Should the standard XSLT routes be denied outright** with no rule possible, as the note recommends, or can a rule
   open them?
7. **How long may report-only last**, and what happens if a kind stays in it indefinitely (risk K15)?
8. **How are the aggregated attempts kept**, and for how long? A table with a cap, or the event-logging service?
9. **Layout.** The sketches show function only. The final layout follows the UI guide and is settled in the stories.
10. **Where does the normaliser run for the test box?** On the server by a REST call (A7), unless the client can
    share the code.

## 11. What to do first

1. **Phase 0**: reproduce the Saxon finding on a real node, and settle open questions 1 to 3.
2. Read the UI guide's two worked examples (receipt rules and the analytic notification's stream destination) and
   the workbench's `WRITING-STORIES.md`, which I have not read.
3. Then Phase 1, with the guard and the normalisers first, because everything else depends on them being right.
