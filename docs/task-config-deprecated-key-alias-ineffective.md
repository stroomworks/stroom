# The deprecated config-key alias stops the boot failure but never carries a value

**Component:** `stroom-config` — `StroomConfigurationSourceProvider.mergeInDefaultConfig`,
`AppConfig`'s `@JsonAlias` declarations
**Severity:** medium for `documentAsset`, **high for `documentAssetDb`**. A value the operator set is
silently replaced by the compiled default, with no error, no warning and nothing in the log.
**Found:** 2026-09-08, running test E2 of `docs/floormap-test-protocol.md` against a clean instance.
**Status:** diagnosed and reproduced locally; **not fixed** — the fix touches config loading for the
whole application, so it wants a decision rather than a commit.

> **This means F2 is only half done.** The plan records it as **DONE 2026-08-26** (`44d344cee2`).
> The half that works is real and worth keeping: an existing `config.yml` carrying an old key no
> longer fails to boot. The half that does not work is that the key is then ignored.

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

## Suggested fix

**Rename deprecated keys in the source provider, before the defaults merge.** In
`StroomConfigurationSourceProvider.open()`, walk the `appConfig` node and rewrite
`visualisationAsset` → `documentAsset` and `visualisationAssetDb` → `documentAssetDb`, then merge as
now. The merge then sees only current names and behaves correctly.

Three things this gets right that the alias alone cannot:

1. The value actually applies.
2. `ConfigMapper` sees it, so the Properties screen reports it as a **YAML** override rather than
   showing the default — which is what let this defect hide.
3. It is the natural place to **log a deprecation warning**, which nothing does today. An operator
   on the old spelling currently gets no signal at all: it works (or appears to), until the aliases
   are removed one day and boot fails with no prior notice.

**Keep `@JsonAlias` as well**, for any path that bypasses the provider — but note that keeping it is
what makes the broken state look tested, so the outcome test matters more than the annotation.

**Alternative:** drop the alias and rely on the provider rename alone. One mechanism instead of two,
and `FAIL_ON_UNKNOWN_PROPERTIES` never sees the old key. Cleaner, but it moves the whole
back-compatibility guarantee into a code path that runs before validation, which is worth being
deliberate about.

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
