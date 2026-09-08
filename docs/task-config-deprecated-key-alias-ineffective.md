# A renamed config key was accepted and then silently ignored

**Component:** `stroom-config` — `StroomConfigurationSourceProvider.mergeInDefaultConfig`,
`AppConfig`'s `@JsonAlias` declarations
**Severity:** medium for `documentAsset`, **high for `documentAssetDb`**. A value the operator set is
silently replaced by the compiled default, with no error, no warning and nothing in the log.
**Found:** 2026-09-08, running test E2 of `docs/floormap-test-protocol.md` against a clean instance.
**Status:** **FIXED 2026-09-08**, and the decision went the other way from the recommendation
below: the old key is now **rejected at boot** rather than made to work. Kept for the record; the
analysis of why the alias could never work is what justifies the choice.

> **What was built.** `StroomConfigurationSourceProvider.rejectRenamedKeys` fails the boot when a
> renamed key is present, naming its replacement — before the defaults merge, which is where the
> value was being lost. The `@JsonAlias` declarations are gone, so there is one mechanism rather
> than two. Against the real `local.yml`:
>
> ```
> Configuration file /…/local.yml uses 1 config key(s) that have been renamed. Rename them to
> continue: 'appConfig.visualisationAsset' is now 'appConfig.documentAsset'. Do not add the new
> name alongside the old one - they are the same property, and which one applied would depend on
> their order in the file.
> ```
>
> **Why rejecting is better than making it work.** Accepted-and-ignored was the worst of the three
> possible behaviours. Between the other two, a boot failure is loud, immediate, and states the fix;
> a working alias is silent and leaves the old spelling in place indefinitely, working, until the
> alias is removed one day and boot fails then instead — with no warning in between, since nothing
> logged one.
>
> `TestAppConfigDeprecatedAssetKeys` is replaced by `TestRenamedConfigKeys`, which starts from a
> **file** and asserts the **outcome**. The old test's own javadoc explained that it asserted the
> annotation directly rather than going through a config file — and that is exactly what made it
> blind. Three mutants killed, including removing the rejection entirely.

> **F2's original framing was the mistake.** It treated "an upgraded `config.yml` fails to boot" as
> the defect and back-compatibility as the fix. But a rename the operator has to know about is
> exactly the case where a boot failure is the *right* behaviour — it is the only signal that
> arrives before the wrong value is in use. The alias made the symptom go away and left the problem
> in place.

---

## Symptom

With `appConfig.visualisationAsset.maxUploadSize: "1M"` in the config file Stroom is launched with,
and Stroom restarted after the edit:

- Stroom boots cleanly — no `Unrecognized field` error, which is what the alias was added for.
- The **Properties** screen shows `stroom.documentAsset.maxUploadSize` = `50M`, source **Default**.
- A 110 MB upload is refused naming **50 MB**, not 1 MB.

So the key is accepted and discarded. The operator has no way to tell.

## Cause

`StroomConfigurationSourceProvider.open()` merges a tree built from `new AppConfig()` into the
operator's YAML **before Dropwizard parses it**, to produce "a full tree":

```java
private void mergeInDefaultConfig(final ObjectMapper objectMapper, final JsonNode rootNode) {
    final JsonNode appConfigNode = rootNode.at(APP_CONFIG_JSON_POINTER);
    final AppConfig defaultConfig = new AppConfig();
    ...
    YamlV2Util.mergeYamlNodeTrees(objectMapper,
            objectMapper2 -> appConfigNode,
            objectMapper2 -> objectMapper.valueToTree(defaultConfig));
}
```

`valueToTree(defaultConfig)` serialises using `@JsonProperty` names only, so the defaults tree
always contains `documentAsset` and never `visualisationAsset`. The merged YAML that Dropwizard
actually parses therefore contains **both**:

```yaml
  visualisationAsset:
    maxUploadSize: "1M"      # the operator's
  documentAsset:
    maxUploadSize: "50M"     # injected default, and it comes second
```

An alias is another spelling of the same property, so **the last occurrence wins** — and the
injected default is second. Every boot.

**The commit that added the alias documented this exact hazard.** Its message says *"supplying both
names is not an error … the last occurrence in the file wins. The change entry therefore tells
operators to rename the key rather than add the new one alongside."* The config loader adds the new
one alongside, on every startup, on the operator's behalf.

## Reproduction

```java
// Real entry point - what Stroom uses:
StroomYamlUtil.readAppConfig(Path.of("local.yml"))
        .getDocumentAsset().getMaxUploadSize();     // 50M   ← wrong

// Same YAML, straight through Jackson - three ways, all correct:
mapper.readValue(flatYaml,   AppConfig.class)...   // 1M
mapper.readValue(nestedYaml, Config.class)...      // 1M
// including Dropwizard's own mapper, Jackson.newObjectMapper(new YAMLFactory())
```

Dumping what the source provider hands to Dropwizard shows both keys present, default second.

## Why the existing test passes

`TestAppConfigDeprecatedAssetKeys` calls `mapper.readValue(json, AppConfig.class)` directly. Its own
javadoc explains the choice — *"which is why these tests assert the alias directly rather than going
through a config file"* — and that is precisely what makes it blind here: the defect is not in the
annotation, it is in a step that runs before the annotation is consulted.

**A test that pins the mechanism rather than the outcome.** Any fix must come with a test through
`StroomYamlUtil.readAppConfig`, or the same gap reopens.

## `documentAssetDb` is the serious half

The same merge injects:

```yaml
  documentAssetDb:
    connection: {}
    connectionPool: { ... defaults ... }
```

So an operator who had customised the asset database connection under `visualisationAssetDb` gets an
**empty connection block** and silently falls back to `commonDbDetails` — assets written to a
different database than configured, with no error.

Worse than the non-DB block for two reasons: there is no database-override route to rescue it, since
`documentAssetDb` is `@BootStrapConfig` with `@ReadOnly` connection fields and can only ever be set
in YAML; and a wrong database is a data-placement problem rather than a limit being wrong.

## What was considered, and what was chosen

Two candidates. Both fix the silent revert; they differ in what the operator experiences.

**Make it work** — rewrite the old key to the new one in the source provider before the defaults
merge. The value would apply, `ConfigMapper` would see it, and the Properties screen would report it
as a YAML override.

**Reject it** — fail the boot naming the replacement. **This is what was built.**

Rejecting won on the operator's experience rather than on effort. A working alias is *silent*: the
old spelling stays in the file indefinitely, apparently fine, until the alias is removed one day and
boot fails then instead — with nothing in between, because nothing logged a warning. Rejecting fails
once, immediately, at the moment the operator can act, and states the fix. And it leaves **one**
mechanism instead of two, where the second one existed only to make the first look tested.

## The config-table migration stays, and the asymmetry is deliberate

`V07_13_00_001__document_asset_property_rename.sql` re-points database-held overrides from the old
path to the new one, silently. That is the opposite treatment to YAML, on purpose:

- A **database row** can be re-pointed unambiguously. `config.name` is unique, the migration skips
  any row whose new path already exists, and there is no file for the operator to edit — so
  rejecting would strand a setting they cannot easily see or fix.
- A **YAML key** cannot be handled silently without the loader choosing between two spellings on the
  operator's behalf, which is what went wrong. And the operator has the file open.

So: migrate what can be migrated cleanly, reject what needs a human. Worth stating because the
inconsistency looks like an oversight otherwise.

## Risk

**Medium, and wider than it looks.** `StroomConfigurationSourceProvider` runs on every boot for
every deployment, and a mistake there fails startup for everyone rather than degrading one feature.
Against that, the change is a key rename on a `JsonNode` before an existing merge, with no new
dependency and nothing conditional on deployment state.

## Verification

- `StroomYamlUtil.readAppConfig` on a file using the old key returns the operator's value, for both
  blocks. **This is the test that was missing.**
- The Properties screen shows the value with source **YAML**, not Default.
- Supplying only the new key is unaffected.
- Supplying **both** explicitly still resolves last-one-wins, as documented — the fix must not
  quietly change that, only stop the loader from creating the situation.
- A deprecation warning appears once at boot naming the old key and the new one, if that is included.
