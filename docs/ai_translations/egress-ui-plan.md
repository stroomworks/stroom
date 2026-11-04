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
| R3 | Matching is done on the **normalised** destination, never the raw string. **HTTP:** scheme and host are folded to lower case and the path never is; a trailing dot, an IDN and the default port are normalised; user-info, a backslash, `;` parameters, a `%00` and a `%2F` or `%5C` inside a segment are rejected; percent-decoding happens once and a double-encoded value is rejected; the fragment is dropped; the query is **not** part of a match (and is not stored, R29); `https` on port 80 or `http` on port 443 is not treated as the default port. **FILE:** paths are made canonical with links resolved. | Must |
| R4 | One kind per rule: HTTP, EMAIL, AI_MODEL, FILE, OBJECT_STORE, MESSAGE_BUS, SEARCH_BACKEND. Where the real endpoint is held in a document (an OpenAI model, Solr connection, Kafka or S3 configuration), the guard checks the **resolved endpoint at call time**, so editing the document cannot bypass a rule. "Endpoint" is defined per kind: AI_MODEL and SEARCH_BACKEND (Elastic, Solr) are scheme, host and port; MESSAGE_BUS is each bootstrap server's host and port; OBJECT_STORE is region and bucket (or endpoint where one is set). A rule on a document UUID alone (as the §6.2 sketch shows for the local model) is **not** enough, so the sketch row is a rule on the model's endpoint with the document shown as a label | Must |
| R5 | The guard **fails closed in enforce mode**: an exception, an unknown kind or an unavailable registry denies. **In report-only mode** the same conditions let the call proceed and are recorded loudly as "registry unavailable", so a database fault cannot change an existing pipeline's behaviour (R22) | Must |
| R6 | **Every** sink calls the guard, including the standard XSLT routes (`doc`, `document`, `unparsed-text`, `collection`, `xsl:result-document`) and the XPath routes (`xpath()`, the extraction filter, and the stepping code's XPath evaluators). The sink list is §5.2. `unparsed-text`, `collection` and `xsl:result-document` are **denied outright**: no rule can permit them, and every attempt is logged (open question 6). **They are blocked in report-only mode too**: the kind's mode does not apply to them (project owner decision), so a translation that uses them fails from the first install | Must |
| R7 | A build fails if a class on the **explicit I/O list** (the network, file, mail, object-store and message-bus client types, matched by import and not by name) has no `@EgressSink` declaration, or is not on an allow-list that carries a **reason for each entry**. The scan is import-aware, so unrelated types with the same simple name (Hadoop `Configuration`, jOOQ `Processor`) are not flagged. The allow-list will be **large** at first (about 50 classes import `WebTarget` or `HttpClient` for node-to-node calls in `stroom-node`, `stroom-planb`, `stroom-security-*` and the resources, plus command-line tools such as `DataFeedClient`, `URLTestTool` and `DatabaseTool`). It is **seeded in Phase 1 with a reason per entry** and burned down | Must |
| R8 | A **global mode per kind**: report-only (record, do not block) or enforce. Never allow-all | Must |
| R9 | A **kill switch** that denies all egress of a chosen kind at once. **It overrides report-only**: a kind with the switch ON denies, whatever its mode (the switch is an emergency action, and the screen says so) | Must |
| R10 | HTTP: https only unless a rule names http; **redirects are blocked**. `ConfiguredRedirectStrategy` is installed on every client built from `HttpClientConfiguration` and the default is to follow, and an XSLT can set `followRedirects` in its own JSON configuration, so each path is forced: `followRedirects(false)` on the builder in `CommonHttpClient` (overriding the XSLT's value) and in `HTTPAppender`; `ClientProperties.FOLLOW_REDIRECTS=false` on the `HTTP_POST_FILTER` Jersey client used by `HttpPostFilter`; and the model document's own `followRedirects` overridden in `AiServiceImpl`; XSLT-supplied TLS settings ignored and reported; `Authorization` and `Cookie` headers from the XSLT stripped. The existing `SsrfGuard` is **reused only through `rejectMetadataAndWildcard`**, to refuse cloud-metadata and wildcard addresses. `requirePublicHost` is not used, because it also rejects private (RFC 1918) addresses, which would block an internal SIEM. A rule that names an IP literal overrides the check for that address. `SsrfGuard` resolves DNS, so that cost is outside the R24 measurement | Must |
| R11 | FILE: the check is on the fully expanded, canonical path, **before** any directory or lock file is created. Rolling appenders check both the active and the rolled destination. The check covers the **lock file** (`path + ".lock"`), the final file it is renamed to, and the rolled file (computed only at roll time in `RollingFileDestination`, so the rolled check lives there). The file is opened with `NOFOLLOW_LINKS` and the parent is re-resolved after the open and compared with the checked path. This narrows the link-swap race (K5) but cannot close it for a swapped *directory* component; the residual race is documented. `HDFSFileAppender` is carved out, because an HDFS path cannot be canonicalised with local links resolved Every `outputPaths` entry is checked at save, but a save-time check can only test the **literal part of the template** before the first variable; the full check is at run time | Must |
| R30 | The Blocked and reported table has a **Clear** action, held by the same permission, that removes aggregated attempts (all, or older than a chosen date). The clear is audited with who, when and how many rows | Should |
| R29 | **Destinations can be secrets** (webhook URLs carry tokens in the path or query). The query is never stored. The **path is stored** (matching needs it), so a secret in a path is in the database: masking is a display protection only, and the documentation says so. The Blocked, Audit (old and new values) and rule lists and the test box show the normalised destination with the last path segment **masked** unless the viewer asks to reveal it (an audited action). Credentials the call needs stay in `stroom-credentials`, but a rule destination the matcher must read cannot live there | Must |

### 2.2 The screen and who may use it

| # | Requirement | Priority |
|---|---|---|
| R12 | One UI to see and manage every rule, whatever its kind | Must |
| R13 | A new application permission, for example **Manage Egress Policy**, held by a small group | Must |
| R14 | The policy is protected by its **permission alone**, as every other administrative screen is. No separate agent-identity check is built for 1.0 (project owner decision). The guidance is stated in the documentation: accounts used by AI agents or the MCP server must not hold `Manage Egress Policy` or `Administrator`. Securing the MCP server is a later piece of work | Must |
| R15 | Every change is audited with who, when and the old and new value | Must |
| R16 | A **"would this be allowed?" test box**: enter a kind and destination, see the normalised form and which rule matches or why none does | Should |
| R17 | A **Blocked and reported** list of every denied or report-only attempt, with one click to turn an entry into a rule or a request | Must |
| R18 | A **request and approval** workflow: the person who requests a rule cannot approve it. This is a **process aid, not a security boundary**: under A4 a person who holds the permission can add the rule directly. It is for people who can request but not approve, which needs the permission split in open question 4 | Should |
| R19 | Each rule shows **last used** and a use count, so a stale or relied-on rule is visible | Should |
| R20 | **Inventory import**: run the XSLT dependency parser over existing translations and propose rules | Should |
| R21 | Changing global mode or the kill switch needs the same permission, is audited, and is shown prominently | Must |

### 2.3 Behaviour, safety and quality

| # | Requirement | Priority |
|---|---|---|
| R22 | **No change of behaviour for existing translations** until a kind is switched to enforce, **except** that the three denied-outright XSLT routes (R6) are blocked from the first install. Report-only is the default on first install | Must |
| R23 | Consistent on **every node** of a cluster. A rule change or kill switch reaches every node within **10 seconds** *(proposed bound, to be agreed and then tested)*, using the existing entity-event cluster invalidation **plus a periodic reload**, because `EntityEventBusImpl.fireRemote` is fire-and-forget with no retry and an unreachable node would otherwise stay stale | Must |
| R24 | The guard adds negligible cost to a call: the rules are held in memory and matching is a lookup. Target: under **50 microseconds per decision at the 99th percentile**, measured in Phase 3 *(proposed number)* | Must |
| R25 | Decisions are recorded without flooding: denied and report-only attempts are **aggregated** (destination, sink, pipeline, count, first and last seen), not logged one per call. The Audit tab shows **rule and setting changes** (every one) and the **aggregated decisions** (not each call) | Must |
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
| A6 | Stroom's existing patterns are available for this feature: a module set of `api`, `impl`, `impl-db`, a resource interface in `stroom-core-shared`, `@AutoLogged` on the resource, a content tab in `stroom-core-client`, and cluster-wide cache invalidation *(all verified: the first four in `stroom-credentials`; the cache pattern is `EntityEventBusImpl` with `@EntityEventHandler`, fanned out by `EntityEventResourceImpl` and used by `FeedDocCache` and `ProcessorProfileCache`)* | Some plumbing would need to be built here |
| A7 | The GWT client cannot use `java.net.URI` or `java.nio.file.Path` *(verified: nothing in the shared modules imports either, and the repository has no emulation of them)*, so **normalisation runs on the server only**. The screen shows the normalised form by asking the server | If the client can share the code, the test box can be instant |
| A8 | **Agents are not distinguished from other users** (project owner decision). The current MCP bridge is a development prototype, authenticates as an ordinary user and is not for production; `ADMINISTRATOR` holds every permission. The protection is the permission and the guidance in R14 | If agents later need to be refused by identity, a mechanism is added then; nothing in this design prevents it |
| A9 | Enforcement is **by kind**, switched on one kind at a time | If a single switch is needed, simpler |
| A10 | The data-read functions (`meta-stream-for-id`, lookups) are **out of scope** here. They are a data-exposure question (question 18 in the note) | They would need their own permission check |

## 4. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| K1 | **A sink is missed**, so data leaves with no check | Medium | High | The build-failing enumeration (R7). Real-node tests for each route. The inventory in §6.5.3 of the note is the first population |
| K2 | **Switching to enforce breaks existing translations** | High | High | Report-only first, the Blocked list, inventory import, one kind at a time, the kill switch as a way back |
| K3 | **Normalisation bug** lets a differently written destination match a rule (or not) | Medium | High | Exact or whole-component prefix only. A table of bypass cases as tests (§6.5.8 of the note). Normalise before every comparison. Fail closed |
| K4 | **Prefix on a path** is read as a string prefix (`/stroom/output-old` matching `/stroom/output`) | Low | High | Compare by path components. A named test |
| K5 | **A symbolic link inside an allowed directory** points outside it, or **is swapped after the check** (a time-of-check to time-of-use race) | Medium | High | Resolve links on the nearest existing ancestor, then compare. Check before creating directories. Open the resolved real path with links not followed and confirm it is the path that was checked (R11) |
| K6 | **False reassurance**: an approved generic webhook is still an exfiltration route | High | Medium | Say so on the screen and in the documentation. Limits are deferred, not forgotten (§6.5.4.1) |
| K7 | **Stale rules on one node** after a change | Medium | Medium | Cluster invalidation. A bound on the delay. Show the version each node holds |
| K8 | **The guard becomes a bottleneck** | Low | Medium | In-memory rules, hash lookup for exact, a sorted structure for prefix. Measure in Phase 3 (R24) |
| K9 | **The Blocked list floods** | Medium | Medium | Aggregate (R25). The table is uncapped for now (open question 8); an administrator can clear it (R30), and its size is watched |
| K10 | **Lock-out**: the policy blocks the route the admin needs to fix it | Low | High | The policy never governs Stroom's own database or its UI, nor **cluster-internal REST** (node-to-node calls such as `NodeServiceImpl`, `RemoteNodeSearch`, `FileTransferClientImpl`, `EntityEventResourceImpl`; these go through `NodeService.remoteRestCall` and `WebTargetFactory`, a different path from `HttpClientProviderCache`, so the guard never sees them), nor the **OpenID Connect outbound calls** (`OpenIdPublicKeysSupplier`, `OpenIdTokenRequestHelper`, `ExternalIdpConfigurationProvider`): enforcing HTTP over these would stop everyone logging in. These all go on the R7 allow-list with that reason. The kill switch is a database setting changed from the screen or by an administrator |
| K11 | **`DO_NOT_RECOVER`** turns recoverable XSLT warnings into failures for translations that relied on them | Medium | Medium | Scope it **per Saxon creation site** (`SaxonConfinement.confine(Configuration, mode)`), not globally, and apply it to untrusted content first. Test on existing translations in report-only |
| K12 | **An approver approves without looking**, or approves for a requester they trust blindly | Medium | Medium | Show what the rule would permit, in plain words, and what has already been blocked against it. Audit |
| K13 | **The policy is edited by an AI agent** that acts as a user holding the permission (the prototype MCP bridge uses an administrator account) | Medium | High | Accepted for 1.0 (R14, A8). The documentation says agent accounts must not hold the permission. Not for production use until the MCP server is secured. Every change is audited (R15) |
| K14 | **The UI is large** and slows the guard work | Medium | Medium | The phased plan (§7): engine and a small screen first |
| K15 | **Report-only never ends**, and nothing is ever enforced | Medium | High | A visible banner while any kind is in report-only, a count of attempts that would have been denied, and **a date set by the project owner** by which each kind is enforced (open question 7) |
| K16 | **Real-node behaviour differs** from the standalone probe | Medium | High | Phase 0 test on a real node, before design is fixed |
| K17 | **Existing translations that use the three denied routes fail on first install**, even in report-only | Medium | Medium | Run the XSLT dependency parser over the estate **before** the upgrade and list affected translations in the release notes and operations guide. Failures are logged to the Blocked list with the pipeline and feed |

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
| `stroom-app` | Wiring; **one stylesheet per BEM block** under `ui/css/` and their imports in `app.css` (`TestStylesheets` allows a sheet to style only its own block); **the build-failing sink-enumeration test**; integration tests | `stroom-app` is the only place that sees every module, so the enumeration test belongs there *(verified: `TestEntityEventHandlers` and `TestRestResources` already use ClassGraph scans there, and the new test copies that pattern)* |
| `stroom-util` | A small interface for the Saxon hooks (for example `SaxonAccessGuard`) and `SaxonConfinement.confine(Configuration, …)`, so low-level modules need not depend on `stroom-egress-api` | `stroom-util` already depends on Saxon *(verified)* |
| `stroom-pipeline` | The guard calls in the XSLT functions `http-call`, `fetch-json`, `send-email`, `ask-ai`; the elements `HTTPAppender`, `HttpPostFilter`, `FileAppender`, `RollingFileAppender`, `HDFSFileAppender`; `XsltPoolImpl` (Saxon hooks); the client-config handling in `CommonHttpClient`, which is also given redirect-following **off** (R10); `TinyTreeBufferFilter` (which creates the Saxon configuration; `SAXEventRecorder` does not) and `xml/event/EventListUtils` (XPath evaluators in the stepping code) confined | The largest change |
| `stroom-query` (`stroom-query-language`) | The `xpath()` function uses a confined Saxon configuration. The query function `askAi` (`AskAi.java`) needs **no change of its own**: it only calls `AiProvider.chat`, so the guard sits in `AiServiceImpl.chat` and covers `askAi`, the XSLT `ask-ai` and Shapeshifter together | The widest exposure today (§6.5.6.2 of the note). Anyone who can run a dashboard query can reach both. `stroom-query-language` need not depend on `stroom-egress-api` |
| `stroom-analytics-impl` | `EmailSender` and `ReportExecutor.sendReport` call the guard as EMAIL, checking every to, cc and bcc address | Analytic and report email. `NotificationEmailDestination` is a DTO in `stroom-core-shared`, not a sink |
| `stroom-ai-impl` | `AiServiceImpl` model calls check the **resolved base URL** of the model document (R4) | The model document's `baseUrl` is editable, so a rule on the document alone could be bypassed |
| `stroom-aws-client` | The guard sits at `S3ClientPoolImpl`, the single choke point for the S3 appender, the data store (`stroom-data-store-impl-fs`, `s3v2`) and receive; `SqsClientFactory` and `stroom-receive-common` `S3EventService` call it too (OBJECT_STORE, MESSAGE_BUS) | `S3VolumeService` in `stroom-data-store-api` is only a lookup interface and moves no data |
| `stroom-search` (Elastic and Solr, as above) and the cluster test endpoints | `ElasticClusterResourceImpl.testCluster` and the Solr equivalent connect to a URL typed into a document. **Search reads** (`ElasticSearchProvider`, `SolrSearchFactory`) are egress by §1's definition and call the guard as SEARCH_BACKEND, as do the indexing filters | |
| `stroom-core` and others | `IconPassthroughServlet` (server-side fetch of a remote URL) and `PathwaysService` (`WebTargetFactory.create(url)`) call the guard as HTTP | Found in review; today `IconPassthroughServlet` is covered only by `SsrfGuard` |
| `stroom-dropwizard-common` | The Prometheus `PushGateway` calls the guard as HTTP | Metrics push |
| `stroom-security-identity` | Its `EmailSender` (account emails) calls the guard as EMAIL, or is on the R7 allow-list with a reason | Low risk, but R7 will flag it |
| `stroom-search` (`-extraction`, `-solr`, `-elastic`) | `XPathExtractionOutputFilter` confined; `SolrIndexingFilter` and `ElasticIndexingFilter` call the guard | |
| `stroom-kafka` (`-impl`) | `StandardKafkaProducer` calls the guard | |
| `stroom-aws` (`-s3-impl`) | `S3Appender` calls the guard | |
| `stroom-ai` and `stroom-shapeshifter-ai` | Model calls check an `AI_MODEL` rule; `XsltStep` and `OutputRecords` use the shared confinement | `ShapeshifterAiParser` stays in the inventory |
| `stroom-config` | Any bootstrap setting (for example the default mode on first install) and `expected.yaml` regenerated by `GenerateExpectedYaml` | Never hand-edited *(verified, `AGENTS.md`)* |
| User documentation | A page for administrators, including R14's guidance on agent accounts | |

**Out of scope, by decision.** `stroom-gitrepo-impl` (git push of content) moves **configuration**, not data, and is a
separate question. The MCP server is a prototype outside this repository and is secured later. **`stroom-proxy`** is a
separate application that does not depend on `stroom-pipeline`: its forwarders (`ForwardHttpPostDestination`,
`ForwardFileDestination`, `ForwardS3Destination`) are not covered here, and whether it gets its own registry is open
question 11.

Classes that **create a Saxon `Processor`, `Configuration` or `TransformerFactory`** are listed in §6.5.6.2 of the note.
A scan test (R7's partner) keeps that list from drifting.

## 6. The screen

The UI guide's rules *(verified)*: a screen is a **component** (a UiBinder template and its Java, dumb) and a
**controller** (REST calls, dialogs, validation); no presenters; Stroom's widgets and not GWT's; no `@UiHandler`;
BEM styles in the component's own sheet; stories for each screen. A content tab such as Jobs or Users extends
`ContentTabController` and is opened by its area's content plugin.

### 6.1 Structure

```text
Administration  >  Egress Policy        (a content tab: EgressPolicyTabController extends ContentTabController,
  |                                        hosting a TabbedController and EgressPolicyTabs, as UserTabController does)
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
| Plugin | `EgressPlugin extends ContentPlugin<EgressPolicyTabController>` (as `CredentialsPlugin` does). The menu is **Administration** (`MenuKeys.ADMINISTRATION_MENU`) |
| REST client | `EgressClient` (as `CredentialClient`) |
| GIN module | `EgressModule` |
| Stylesheets | **One per BEM block**, each styling only its own block: `egress-policy.css`, `egress-rule-editor.css`, `egress-destinations.css`, `egress-blocked-list.css` and so on, each imported in `app.css` |
| Widgets | `RadioGroup` (mode, match), `TickBox` (enabled, kill switch), `SelectionBox` (kind), `DataGrid` (lists), `FormGroup` (refusal reason) |
| Stories | One set for **every** screen and dialog: `EgressDestinationsStories`, `EgressRuleEditorStories`, `EgressTestBoxStories`, `EgressBlockedListStories`, `EgressRequestsStories`, `EgressAuditStories`, `EgressSettingsStories`, each with default, read-only, and OK and Cancel for dialogs. They sit beside `CredentialsScreenStories` in `stroom-gwt-workbench` |

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

*Clear* (R30) removes rows, audited. *Ignore* hides the row from this list and **does not change the policy**: the destination stays denied (or reported).
The hide is audited, shows who and when, and a row reappears if its count rises after the hide.

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
| **0. Verify** | A real-node test that the four Saxon hooks close the routes in a running Stroom (open question 16 in the note). A6 to A8 are settled by reading the code (see §3). Read the UI guide's worked examples (receipt rules, the analytic notification's stream destination) | The finding reproduced or refuted. The unknowns above settled |
| **1. Engine in report-only, every sink** | `stroom-egress-*`, the database tables, the guard and normalisers, **the permission** (the REST loader needs it), the Saxon confinement, and a guard call at **every** sink in §5.2 (HTTP, email, AI, S3, Kafka, Solr, Elastic, files, the XSLT and XPath routes), all in report-only. **The build-failing enumeration test (R7).** **No screen yet**; rules are loaded by an administrative REST call | Existing pipelines are unchanged. Report-only decisions are recorded. A new sink without a declared kind fails the build. Unit tests pass for matching and normalisation |
| **2. A small screen** | Destinations list and rule editor, Blocked and reported, Settings. Stories for each | An administrator can add a rule from a blocked attempt and switch HTTP **and FILE** to enforce |
| **3. Enforce, per kind** | Enforce for each remaining kind, with its tests. The test box. Measure the guard's cost against R24 | Enforce works per kind. The R23 bound and R24 target are met |
| **4. Governance** | Requests and approval, Audit tab, last-used columns, inventory import from the dependency parser | A request by one person cannot be approved by that person. Import proposes rules from an existing estate |
| **5. Harden and document** | The bypass tests, the kill-switch drill, cluster staleness test, user documentation, a roll-out guide | Every item in §9 passes |

The **1.0 floor** for automatic mode (the egress policy with the Saxon hooks, §6.7 of the note) is Phases 0 to 2 **and
enforce switched on for HTTP and FILE**, because the Saxon routes that matter (`doc`, `document`, `unparsed-text`,
`xsl:result-document`) are HTTP and FILE egress. Phase 2's exit test therefore includes enforcing FILE, with its
tests, not only HTTP. Phase 3 enforces the remaining kinds and measures cost. Phase 4 can follow.
Phase 1 is large (a guard call in about thirteen modules), so the R7 allow-list is **seeded** with every unguarded
class and a reason, then burned down through Phases 1 to 3.

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
| Report-only | The hook refuses it **outright**, as in enforce mode: these routes are not subject to the mode. The attempt is logged (FILE read, `unparsed-text()`, the pipeline and feed) |
| Enforce | The hook refuses it **outright**: no rule can permit it. The stylesheet fails with "may not read external resources", and the attempt is logged |

The Blocked list marks it with a warning and no *Add to policy*: reading Stroom's own configuration is not something to
permit, and for 1.0 these routes cannot be opened by a rule (open question 6). A site that truly needs a file read
must do it another way, for example with a lookup.

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
| Fail-closed: the guard throws, the registry is unavailable | Enforce mode denies. Report-only proceeds and records "registry unavailable" | R5, R22 |
| HTTP bypass table: redirect to a non-permitted host (blocked), `%2F` and `%00` in a segment, double encoding, backslash, `;` parameters, fragment, `https` on port 80, metadata address | Normalisation and R10 hold | R3, R10 |
| A link swapped between the check and the open | The file cannot escape its prefix | R11 |
| A model document's `baseUrl` edited after a rule is made | The rule still governs the resolved endpoint | R4 |
| A webhook with a secret path and query | The query is not stored and the last segment is masked, including in Audit and the test box | R29 |
| Redirect refused on each path: `CommonHttpClient` (with an XSLT that sets `followRedirects`), `HTTPAppender`, `HttpPostFilter` (Jersey), `AiServiceImpl` | No path follows a redirect | R10 |
| Kill switch ON in a report-only kind | It denies | R9 |
| A node that missed the cluster event | It converges by the periodic reload within the bound | R23 |
| Every rule or setting change, and every kill-switch or mode change, produces an audit event with old and new values | Audit | R15, R21 |
| A flood of identical attempts becomes one aggregated row with a count | No flooding | R25 |
| The lock file, final file and rolled file of a rolling appender are each checked, and a destination outside the prefix creates no directory | The file guard covers all three | R11 |
| Report-only changes nothing about a pipeline's output, except that `unparsed-text`, `collection` and `xsl:result-document` fail | No behaviour change before enforce, apart from the denied-outright routes | R22, R6 |
| Kill switch ON and OFF, on two nodes | Consistency and speed | R9, R23 |
| Permission: a user without the permission cannot read or change rules; the requester cannot approve | Access control | R13, R18 |
| Workbench stories for every screen and dialog, with the leak check | The UI guide's rules | R26 |
| Guard cost measured under load | Negligible overhead | R24 |

## 10. Open questions

1. **Where do rules and settings live?** *Decided by the project owner: the database* (A3). The alternative,
   configuration properties, was rejected: the database gives per-change audit and no restart.
2. **How is a cluster kept consistent?** *Settled:* the entity-event bus (A6). The 10-second bound (R23) is proposed.
3. **Can an agent be recognised?** *Decided:* not for 1.0 (R14, A8). Stroom has an internal processing (service) user
   but I found no endpoint that refuses a caller because of the kind of credential, so there is no precedent to reuse.
   Revisit when the MCP server is secured.
4. **Who may hold the permission**, and is a second approver required for new destinations? *Decided: anyone with the
   permission; no second approver for 1.0.* R18 stays a process aid (A4). Revisit if a customer asks for a split.
5. **Is exact-or-prefix enough for every kind?** *Decided: yes for 1.0.* Kafka topic families and S3 key families are
   the likeliest to want more, and customer feedback decides whether anything is added.
6. **Should the standard XSLT routes be denied outright?** *Decided: yes, in every mode, including report-only.* `unparsed-text`, `collection` and
   `xsl:result-document` are denied with no rule possible, and **every attempt is logged** (to the Blocked list, as an
   aggregated row, in every mode). A rule cannot open them in 1.0.
7. **How long may report-only last?** *Decided: not tackled in the product.* It is documented in the operations guide
   (with the banner and the would-have-been-denied count) and left to the operator (risk K15).
8. **How are the aggregated attempts kept?** *Decided: an uncapped table for now, with a UI option to clear it.* The
   clear is a permission-gated, audited action (R30). Growth is watched; a cap or expiry can follow.
9. **Layout.** The sketches show function only. The final layout follows the UI guide and is settled in the stories.
10. **Where does the normaliser run for the test box?** On the server by a REST call (A7, settled).
11. **Does `stroom-proxy` get an egress policy** (`ForwardHttpPostDestination`, `ForwardFileDestination`, `ForwardS3Destination`)? *Decided: out of scope for 1.0.* The user documentation says so.
12. **What are the agreed values** for the R23 delay and the R24 cost? *Decided: the proposed values stand* (10 seconds; 50 microseconds at p99) and are adjusted after the Phase 3 measurement.

## 11. What to do first

1. **Phase 0**: reproduce the Saxon finding on a real node. Open questions 1 to 3 are now settled.
2. Read the UI guide's two worked examples (receipt rules and the analytic notification's stream destination) and
   the workbench's `WRITING-STORIES.md`, which I have not read.
3. Then Phase 1, with the guard and the normalisers first, because everything else depends on them being right.
