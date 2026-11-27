# Shapeshifter AI: make the output schema a single setting that the prompts, checks and scorers all use

## Problem

The Shapeshifter AI target output schema is set in more than one place, and the settings don't agree:

- The **schema conformance scorer** validates output against the document's configured schema group (`SchemaConformanceParameters.schemaGroup`, default `EVENTS`).
- The **prompts and target checks** always tell the model to write `event-logging:3`, whatever the schema group is set to:
  - `Templates.java` `TRANSFORMATION_RULES`: the namespace, `xsi:schemaLocation`, `Version="3.0.0"` and a hand-written summary of event-logging's structure (EventTime/EventSource/EventDetail order, allowed EventDetail branches, no `Data`/`Unknown`).
  - `Templates.java` admission template (about line 143): refers to `EventDetail`/`TypeId`.
  - `QuestionText.java` `ELEMENTS`: "XSLTFilter transforms XML records into event-logging:3 events".
  - `TargetChecks.java` `EVENTS_OPEN`: hard-coded `<Events xmlns="event-logging:3" …>` wrapper used when checking proposed targets.
  - `Admission`, `Corpus`, `ExtractionQualityScorer` and `ExtractionQualityParameters` (`allowUnknownEventDetail`): XPaths and wording tied to `EventDetail/Unknown`.

So if a user picks a different schema group, the model is still told to produce event-logging:3, and every attempt fails the conformance gate.
Today the only way round this is to change the schema group *and* override the `TRANSFORMATION_RULES` template in the flow, and the `TargetChecks` wrapper can't be changed at all.

## Decisions

### D1. Where the target-schema setting lives

> **Decided, 19 November 2026: option A**, a *Target schema* field on the Learning Flow under *AI model and instructions* (delivery plan, stage 0b group 7).
> Each target schema gets its own Learning Flow, since the business rules, required fields, prompts and the Target and Admit steps all depend on it.
> The field names **one schema version, not a group**: `EVENTS` holds seven event-logging versions, from 3.0.0 to 4.1.0. Step 1 below resolves to that one schema, not to a group.
> A new Learning Flow defaults to the latest event-logging version installed, saved on the document so a later install doesn't change it.

- **Option A (recommended): a top-level field on `ShapeshifterAiDoc`.**
  It is set once on the Learning Flow's Setup tab, and the prompts, `TargetChecks` and the conformance scorer all read it.
  The scorer's `schemaGroup` parameter is removed (or deprecated and ignored), and existing values are migrated into the new field.
  - For: the schema describes what the document produces, so it belongs on the document.
    It is still there if the conformance scorer is removed or disabled, and the prompts don't depend on how scoring is configured.
  - Against: needs a document migration and a new field on the Learning Flow's Setup tab.
- **Option B: keep it on the schema conformance scorer** and have the prompts and checks read it from there.
  - For: no new field, and less migration.
  - Against: the prompts depend on a scorer being present.
    A document without that scorer has no defined target, so it would need a fallback default, which hard-codes event-logging again.
    It also mixes up "what to produce" with "how to score it".

### D2. How the model is told about a schema other than event-logging

> **Decided, 18 November 2026: option G** (delivery plan, stage 0b group 6). Every target schema must be usable, not only event-logging.

The options are lettered F–H to match the delivery plan, where D2 shares stage 0b group 6 with SIM-5 (A–B) and N9 (C–E). The code assumption audit, written earlier, calls them A–C.

- **Option F: require a template override.**
  The schema's identity (namespace, schemaLocation, version, root element) is generated, but its structural rules have to be written by hand in the flow's `TRANSFORMATION_RULES` override.
  Without an override, the model gets only the identity and generic rules.
  - For: least work.
  - Against: weak results until someone writes the rules, and two places to keep in step again (schema and override text).
- **Option G (recommended): generate a structural summary from the XSD**, using the content-model loading `SchemaConformanceScorer` already does for its hints: root element, record element, required children in order, and allowed choices.
  A template override can still replace or add to it.
  - For: the schema really is set in one place, and any schema gets a usable starting prompt.
  - Against: more work.
    Large schemas need the summary capped to keep prompts short, and the generated wording has to be tested against real schemas.
- **Option H: generic rules only**, so the model learns the structure from validation feedback.
  - For: no generation work.
  - Against: costs more attempts and budget per stage, and is likely to stall on schemas with deep required structure.

Whichever option is chosen, the hand-written event-logging guidance stays as the built-in text when the target is event-logging, so the default behaviour doesn't change.

## Proposed change

1. Add the target-schema setting where D1 decides, resolving to one `XmlSchemaDoc` (schema group plus, if needed, namespace/version).
   Show it once on the Learning Flow's Setup tab, under *AI model and instructions* or *Quality checks*.
2. Have the schema conformance scorer use that setting.
   Migrate existing documents: copy any non-default `schemaGroup` into the new setting, then remove or deprecate the parameter.
3. Build the prompt's description of the target from the resolved `XmlSchemaDoc`: namespace, `schemaLocation`, version and root element.
   Put these into `TRANSFORMATION_RULES`, the admission template and `QuestionText.ELEMENTS` as template variables rather than literal text.
4. Build the `TargetChecks` wrapper from the same resolved schema instead of `EVENTS_OPEN`/`EVENTS_CLOSE`.
5. Separate the event-logging-specific guidance (the EventDetail branches, the "no `Data`/`Unknown`" rule, the `EventDetail/Unknown` admission marker) from the generic rules:
   - keep it as the built-in guidance when the target is event-logging;
   - for other schemas, provide guidance as D2 decides;
   - make the event-logging-only scorer options (e.g. `allowUnknownEventDetail`) and the `Admission`/`Corpus` XPaths available only when the target is event-logging, or make them generic.
6. Keep template overrides working as they do now, so a flow can still replace the wording.

## Acceptance criteria

- Changing the target schema in the one setting changes, together: the namespace/schemaLocation/version in every prompt, the `TargetChecks` wrapper, and the schemas the conformance scorer validates against.
- `event-logging:3` no longer appears as a literal in the Shapeshifter AI prompt or check code, except inside guidance that only applies when the target is event-logging.
- Existing documents that use the default `EVENTS` group behave exactly as they do now (same prompts, same scores).
- Existing documents with a non-default `schemaGroup` are migrated to the new setting.
- If D2 option G is chosen: the generated summary is capped in length, and has been checked against at least one schema other than event-logging.
- Tests:
  - `QuestionText` prompts reflect a non-event-logging schema;
  - `TargetChecks` wraps with the configured root/namespace;
  - the conformance scorer uses the single setting;
  - the migration of a non-default `schemaGroup` is covered;
  - a regression test fails on current code because the prompt says event-logging:3 while the scorer validates against a different group.
- Workbench stories (`ShapeshifterAiEditorStories`) updated for the Setup tab's new setting (default, read-only, dialog OK/Cancel).
