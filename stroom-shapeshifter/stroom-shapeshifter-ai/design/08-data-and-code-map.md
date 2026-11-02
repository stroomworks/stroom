# 8. Data and code

Where everything lives: the modules and what each owns, the seams between the logic and its storage,
the tables, the REST resources, the changes made to Stroom outside the feature's own modules, and how the
tests are arranged. Each earlier document ends with the classes for its own subject; this is the map
across all of them.

## 8.1 Modules

| Module | Holds |
|---|---|
| `stroom-pipeline-factory/stroom-pipeline-factory-impl` | The Pipeline Factory: store, job, runner, scanner, maker, Made service, config ([2](02-pipeline-factory.md)) |
| `stroom-pipeline-factory/stroom-pipeline-factory-impl-db` | `pipeline_factory_*` tables, `CombinationsDao`, jOOQ |
| `stroom-shapeshifter/stroom-shapeshifter-ai` | The whole of Shapeshifter AI except its tables: the element, the stage, learning, scoring, fragments, the resources, the jobs. No database dependency |
| `stroom-shapeshifter/stroom-shapeshifter-ai-impl-db` | `shapeshifter_*` tables, one DAO per seam, jOOQ, the delete-event handler |
| `stroom-core-shared` `stroom.shapeshifter.shared`, `stroom.pipelinefactory.shared` | Documents, settings, enums, resource interfaces and row types shared with the client |
| `stroom-core-client` `stroom.shapeshifter.client`, `stroom.pipelinefactory.client` | The screens ([7](07-ui.md)) |
| `stroom-gwt/stroom-gwt-workbench` `app.shapeshifter`, `app.pipelinefactory` | Workbench stories and fixtures |

Guice wiring in `stroom-app`: `AppModule` installs `ShapeshifterAiModule` (the document, resources and
jobs), which installs `ShapeshifterAiElementModule` (the element and the node's seam bindings).
`CoreModule` installs the factory's module and `ShapeshifterAiDaoModule`; `DbConnectionsModule` installs
both database modules.

### Packages of `stroom-shapeshifter-ai`

| Package | What it is |
|---|---|
| `doc` | The document type: store, serialiser, `ShapeshifterAiResourceImpl`, `SupervisorResourceImpl`, `ShapeshifterAiModule` |
| `element` | Everything that needs a node: the parser element, `Supervision`, `StageFactory`, the jobs (`DeferredLearning`, `AttemptRetention`), the node's seam implementations (`StoredRegressionSet`, `TaskWake`, `PipelinePlaces`, `StoreDocuments`, `StreamInputs`), `ModelAdvisor`, `PipelineStepRunner`, `PipelineDeletions`, `StepDetails` |
| `stage` | The decisions and the seams: `Stage`, `Decision`, `DeferredWorker`, `Input`, `Corpus`, and one interface per kind of runtime state |
| `learning` | The flow interpreter and the conversation: `Conversation`, questions, templates, reply grammars, `Advisor`, `RecordedAdvisor`, `StepRunner` |
| `scoring` | The scorers and `Scorecard` |
| `fragment` | Running and writing fragments: `FragmentRunner` (`PipelineFragmentRunner`, `StandInFragmentRunner`), `RecordGate`, `FragmentWriter`, `ContentCreator` |
| `extraction`, `transformation` | The module's own step runners: `DataSplitterStep`, `JsonStep`, `XmlFragmentStep`, `XsltStep` |
| `state` | In-memory twins of every seam, and `InMemoryStateModule` |
| `cache` | `CachedLearnerBindings`, `CachedLearnerStates`: per-node caches over the rows |

## 8.2 The seams

Runtime state is reached only through interfaces in `stage`. A node binds them to DAOs; tests bind them to
the `state.InMemory*` twins (`InMemoryStateModule`). The same `Stage` runs over either.

| Seam | In a node | In memory |
|---|---|---|
| `Attempts` | `AttemptsDao` | `InMemoryAttempts` |
| `LearnerBindings` | `CachedLearnerBindings` over `LearnerBindingsDao` | `InMemoryLearnerBindings` |
| `LearnerStates` | `CachedLearnerStates` over `LearnerStatesDao` | `InMemoryLearnerStates` |
| `Learners` | `LearnersDao` | `InMemoryLearners` |
| `LearningPipelines` | `LearningPipelinesDao` | `InMemoryLearningPipelines` |
| `Deferrals` | `DeferralsDao` | `InMemoryDeferrals` |
| `Outputs` | `OutputsDao` | `InMemoryOutputs` |
| `Snapshots` | `SnapshotsDao` | `InMemorySnapshots` |
| `Guidance` | `GuidanceDao` | `InMemoryGuidance` |
| `Spend` | `SpendDao` | `InMemorySpend` |
| `CorpusIndex` | `CorpusIndexDao` | `InMemoryCorpusIndex` |
| `RegressionSet` | `StoredRegressionSet` (streams + `CorpusIndex`) | `InMemoryRegressionSet` |
| `DeletedPipelines`, `KeptLearning` | `DeletedPipelinesDao` | `InMemoryDeletedPipelines`, `InMemoryKeptLearning` |
| `Wake` | `TaskWake` (processor task service) | `InMemoryWake` |
| `Places` | `PipelinePlaces` (reads the pipeline's element properties) | `InMemoryPlaces` |
| `Documents` | `StoreDocuments` | `InMemoryDocuments` |
| `Inputs` | `StreamInputs` (the stream store) | `InMemoryInputs` |
| `Advisors` | `ModelAdvisors` (`stroom-ai`) | a scripted advisor (`testFixtures`) |
| `FragmentRunner` | `PipelineFragmentRunner` | `StandInFragmentRunner` |
| `StepRunner` (per element) | `PipelineStepRunner` | `DataSplitterStep`, `JsonStep`, `XmlFragmentStep`, `XsltStep` |
| `ContentCreator` | `ExplorerContentCreator` | in `testFixtures` |

Two pairs, runners and fragment runners, are held to the same answers by `stroom-app` tests
(`TestStepRunnersAgree`, `TestFragmentRunnersAgree`), since a stand-in that drifted would have the model
correcting a fault the real pipeline does not have.

## 8.3 Tables

All in one migration per module, numbered for Stroom 8.0 (A111): `V08_00_00_001__shapeshifter_ai.sql` and
`V08_00_00_001__pipeline_factory.sql`. Each runtime table names the learner by `pipeline_uuid` and
`element_id` (A114). The document is recorded beside it, for display and for permissions, but is not part
of the key.

| Table | One row per | Holds | Read and written by |
|---|---|---|---|
| `shapeshifter_learner_state` | Learner | Document, give-up reason, relearning mark and reason, draft awaiting review, rolling score (A117, A124) | `LearnerStatesDao` |
| `shapeshifter_binding` | Binding | Learner, fragment, snapshot, score, provisional, draft, record boundary, promoted time (A124, A140) | `LearnerBindingsDao` |
| `shapeshifter_attempt` | Session | Learner, document, held stream, feed and type, status, reason, binding, score, tokens, grant, the claim (`claimed` is 1 while open, so one open per learner), node and lease (A28, A45) | `AttemptsDao` |
| `shapeshifter_turn` | Turn | Step, candidate, question kind and summary, answer, answered by, outcome, deciding check's score and threshold (A144), carried guidance | `AttemptsDao` |
| `shapeshifter_deferral` | Waiting stream per learner | Stream, feed, type, stamp failed against, reason, records short, first deferred, tries | `DeferralsDao` |
| `shapeshifter_output` | Stream per binding per pipeline | The binding, fragment, snapshot, score, provisional and record boundary that produced it; record spans where kept | `OutputsDao` |
| `shapeshifter_snapshot` | Snapshot | The fragment's configuration text and its documents' versions (A61) | `SnapshotsDao` |
| `shapeshifter_corpus` | Corpus entry | Binding, the stream and segment holding the entry, what it became, its score, the attempt (A18) | `CorpusIndexDao` |
| `shapeshifter_guidance` | Message | Learner, document, message, author, when; withdrawing deletes the row (A46) | `GuidanceDao` |
| `shapeshifter_spend` | Document | Tokens and calls in the current window, and breaker columns nothing sets yet (A44) | `SpendDao` |
| `shapeshifter_deleted_pipeline` | Pipeline | When its deletion was first heard (A120, A134) | `DeletedPipelinesDao` |
| `pipeline_factory_combination` | Factory and combination | State, tries, pipeline, filter, message ([2.5](02-pipeline-factory.md#25-what-it-remembers)) | `CombinationsDao` |
| `pipeline_factory_scan` | Factory | High-water mark | `CombinationsDao` |

**What is not in a table:**

- the learned content: fragments, Data Splitter and XSLT documents, in the explorer (A41);
- the corpus entries' text: streams of type `Shapeshifter Corpus` on the source feed (A18);
- documents: `ShapeshifterAiDoc` and `PipelineFactoryDoc`, in the doc store;
- the deferred status: the processor's own `processor_task` table.

Regenerate jOOQ after changing a migration with
`./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai-impl-db:generateJooq` (or the factory's). The
migrations have not been released, so they are rewritten rather than added to (A111) until they are.

## 8.4 REST resources

| Resource | Path | Does |
|---|---|---|
| `ShapeshifterAiResource` | `/shapeshifterAi/v1` | Fetch and update a document; the built-in template text and its version |
| `SupervisorResource` | `/shapeshifterAiSupervisor/v1` | `learners` (the pipelines list), `learners/{doc}/waiting`, `relearn`, `giveUp`; `find` and `{id}` for sessions; `{id}/turns/{n}` (amend), `approve`, `reject`, `grant`, `exhaust`; `serving/{doc}/{binding}/improve`, `accept`, `retract`; `guidance/{doc}` (get, post, delete); `backlog` (only for the editor header's count, ISSUES item 11) |
| `PipelineFactoryResource` | `/pipelineFactory/v1` | Fetch and update a factory; `combinations`; `forget` |

Each Supervisor request checks the person's permission on the document the learner runs under: View to
read, Edit to act. The resource is `@AutoLogged`, so requests are audited through Stroom's REST event
logging.

## 8.5 Changes to Stroom outside the feature's modules

The feature needed these in shared Stroom code. Each is general: none knows what Shapeshifter AI is.

| Where | Change | Why |
|---|---|---|
| `stroom-processor` | `TaskStatus.DEFERRED`; `DeferTaskException`; the handler's third outcome; the reaper's return after `deferredTaskRetryDelay`; `ProcessorTaskDao.wakeDeferredTasks`; deferred tasks in the filter budget; the deferred count on the claim system info | [3.5](03-processing.md#35-deferring-a-stream) (A59, A109) |
| `stroom-processor` | `SupervisedPipelines`: a filter over a pipeline that may defer is refused while the node is master-fed. Deferred tasks need `stroom.processor.claimTasksOnWorker`, since a wake writes a row the worker reads and a master-fed queue would not see it | A deployment requirement |
| `stroom-core` | `AbstractProcessorTaskExecutor` lets a deferral through without writing an error stream | [3.5](03-processing.md#35-deferring-a-stream) |
| `stroom-pipeline` | Elements and plumbing (`AbstractParser`, `CombinedParser`, `DSParser`, `XMLFragmentParser`, `XsltFilter`, `XMLFilterFork`, the factories) let a deferral through | [3.5](03-processing.md#35-deferring-a-stream) |
| `stroom-pipeline` | `InjectedCode`: run an element with configuration that is not in the store, scoped to the pipeline being built. Stepping used to be the only way | Judging a candidate before writing it |
| `stroom-pipeline` | `HeadlessCapture`, `PipelineCapture`: capture what each element made of an input without a stepping session; shared with the stepper | Running candidates and fragments as real pipelines |
| `stroom-pipeline` | `HasStepDetails` and `ElementMonitor` changes: an element can give the stepper a pane of its own; nested elements can be stepped | The stage pane ([3.11](03-processing.md#311-stepping)) |

## 8.6 Tests

**Tier 1: scenarios in memory** (`stroom-shapeshifter-ai/src/test`). The scenarios of archived design 02
run with the in-memory seams, the module's step runners and a **scripted model**: each test says what the
model answers to each question (`testFixtures` `scenario.Script`, `QuestionMatcher`). They need no node and
no database and run in seconds. Unit tests sit beside them in each package.

**Tier 2: in a node** (`stroom-app/src/test/java/stroom/shapeshifter`, `stroom/pipelinefactory`). The same
scenarios, through `testFixtures`, in a real pipeline with real processing, against the database.
Examples: `TestScenarioAFromNothingToServed`, `TestScenario30DeferredWorkerInAPipeline`,
`TestScenario57TheSessionRunsTheHeldStreamInFullInAPipeline`, `TestScenarioBManyStreamsManyThreads`,
`TestADeletedPipelinesLearningInANode`, `TestDemoContentPackImports`. DAO tests in the `-impl-db` modules
run against the database too.

**Live** (`TestLiveScenarios`). The scenarios' machinery driven by a real model in place of the script, to
learn whether the questions draw replies the grammar accepts and what a session costs. It is a report,
not a verdict: it fails only if the harness breaks. It runs only when `SHAPESHIFTER_AI_BASE_URL` is set,
with `SHAPESHIFTER_LEARNING_MODEL` and `SHAPESHIFTER_AI_API_KEY` (and optionally
`SHAPESHIFTER_AI_TEMPERATURE`), against any OpenAI-compatible endpoint, and writes each run's transcript
under `build/live/<flow>`.

**UI**: workbench stories with the leak check ([7.5](07-ui.md#75-code-and-stories)).

## 8.7 The demo content pack

`shapeshifter-ai-demo-v1.0.zip` at the repository root, with one data zip per feed in `demo-data/`. It holds
a Shapeshifter AI document (`DemoDocument`: the settings scenario 51 learned four unrelated formats with),
a `stroom-ai` model document naming a stored secret (no key is in the pack), four feeds, and a pipeline
and processor filter per feed, as a factory would make them. `DemoContentPack` writes it in Stroom's import
format without a database. Regenerate it with
`./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai:generateDemoContentPack` after changing the demo
document, the flow examples or the templates; `TestDemoContentPack` checks the committed pack against what
the code would write.

## 8.8 Configuration

| Path | Class | Properties |
|---|---|---|
| `stroom.shapeshifterAi` | `ShapeshifterAiConfig` | `deferredLearningBatchSize`, `sessionLease`, `attemptRetention`, `bindingCache`, `learnerStateCache`, `db` ([4.10](04-learning.md#410-configuration)) |
| `stroom.pipelineFactory` | `PipelineFactoryConfig` | `scanBatchSize`, `maxScanBatchesPerRun`, `retryFailedAfter`, `db` ([2.3](02-pipeline-factory.md#23-the-job)) |
| `stroom.processor` | `ProcessorConfig` | `deferredTaskRetryDelay` (5m); `claimTasksOnWorker` must be on |

`expected.yaml` in `stroom-config-app` is regenerated with `GenerateExpectedYaml` after a config change.
