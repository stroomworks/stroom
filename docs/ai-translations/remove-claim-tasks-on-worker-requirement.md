# Task: remove Shapeshifter AI's requirement for `claimTasksOnWorker`

*Server-side task · branch `ai_translations` · written 12 November 2026*

Shapeshifter AI refuses to let a processor filter be created over a pipeline holding its element unless `stroom.processor.claimTasksOnWorker` is on. This task removes that requirement, so AI pipelines run with either way of getting tasks to workers. It is option H in [simplification-options.md](simplification-options.md#h-drop-the-worker-claiming-requirement), which gives the full reasoning. This document is the work needed to carry it out.

Line numbers were checked against the tree on 12 November 2026. Class and method names are given as well, because line numbers drift.

---

## 1. Background

`stroom.processor.claimTasksOnWorker` (gh-5699) is experimental and off by default. It selects how processor tasks reach worker nodes:

- **Off (the default):** the master node fills a task queue in memory from the database and hands tasks out.
- **On:** each worker claims `CREATED` tasks straight from the database.

Changing it is a hard cutover of the whole cluster.

Shapeshifter added a `DEFERRED` task status. Its element defers a task when nothing has been learned yet. Later, a *wake* (`ProcessorTaskDao.wakeDeferredTasks`) or the Processor Task Reaper's retry sweep returns the task to `CREATED`.

The requirement was added by commit `579e4969b8` ("Shapeshifter AI and the Pipeline Factory"). Its stated reason, in design documents 03 §3 and 08, is that "a wake writes rows the worker reads, which a master-fed task queue would not see".

## 2. Why removing it is safe

That reason doesn't hold. These points were confirmed by an independent review of the code:

- **Deferring, waking and the retry sweep are plain database updates, the same in both modes.**
  - `DataProcessorTaskHandler.java:231` sets the task to `DEFERRED` with no node.
  - `ProcessorTaskDaoImpl.wakeDeferredTasks` (`:485`) returns it to `CREATED` with no node.
  - `ProcessorTaskReaper.java:110-113` runs `sweepDeferredTasks` "in both modes".
- **The master queue re-reads `CREATED` tasks from the database each time it fills.** `ProcessorTaskQueueManagerImpl.queueCreatedTasks` calls `ProcessorTaskDaoImpl.findExistingCreatedTasks` (`:1635-1649`) for every filter, starting from the first task each time. A woken task is exactly what that query finds.
- **Its only delay is the empty-filter backoff,** `skipEmptyFilterFetchDuration` (10 seconds, `FilterFetchBackoff`). A wake doesn't reset it.
- **An existing integration test already passes in both modes.** `stroom-app/src/test/java/stroom/processor/impl/TestDeferredTask.java:114-203` runs with the setting off and on. It covers deferring, the retry sweep, a wake and a reprocess.
- **The design's history says the master queue is untouched.** `design/history/04-processing-flow.md:347-350` says so "because a sleeping task is simply not `CREATED`". The reason first recorded for the requirement (lines 385-388) was about efficiency, not correctness.
- **The check already has gaps.** It runs only when a filter is created, and `SupervisedPipelineCheck` answers "not supervised" whenever it can't read the pipeline. So AI pipelines can already end up running with the master queue.

## 3. Scope

**In scope**

- Remove the check, the `SupervisedPipelines` interface and its Shapeshifter binding.
- Restore `ProcessorFilterServiceImpl` to the shape it had before `579e4969b8`.
- Change the tests that rely on the refusal, and add tests that show AI pipelines work with the master queue.
- Correct the design documents and the notes in `docs/ai-translations/`.

**Out of scope**

- Any change to `claimTasksOnWorker` itself, to the master queue or to worker claiming. Worker claiming stays as it is: experimental and off by default.
- Any change to `DEFERRED`, `wakeDeferredTasks`, `sweepDeferredTasks` or `ProcessorTaskCreatorImpl`'s count of held tasks.
- Making wakes faster with the master queue, for example by resetting `FilterFetchBackoff` on a wake. That can be done later if the delay matters (section 8).
- Option G (dropping the Pipeline Factory). Step 4.3 notes how the two interact.

## 4. Code changes

### 4.1 `stroom-processor`

| File | Change |
|---|---|
| `stroom-processor/stroom-processor-api/src/main/java/stroom/processor/api/SupervisedPipelines.java` | Delete the file. |
| `stroom-processor/stroom-processor-impl/src/main/java/stroom/processor/impl/ProcessorModule.java:71-72` | Delete the multibinder `GuiceUtil.buildMultiBinder(binder(), SupervisedPipelines.class);` and the comment above it. Remove the import. |
| `stroom-processor/stroom-processor-impl/src/main/java/stroom/processor/impl/ProcessorFilterServiceImpl.java` | Make the four changes listed below. |
| `stroom-processor/stroom-processor-impl/src/test/java/stroom/processor/impl/TestProcessorFilterServiceDocDependencies.java:61-71` | Drop the last two constructor arguments, `ProcessorConfig::new` and `Set.of()`, and any import that becomes unused. |

The four changes to `ProcessorFilterServiceImpl`:

- Delete `requireWorkerClaimingForSupervised` and its comment (`:121-140`).
- Delete its two calls: in `create(CreateProcessFilterRequest)` at `:170`, and in `create(ProcessorFilter)` at `:282-285`, together with the `if` and its comment.
- Delete the fields `processorConfigProvider` and `supervisedPipelines` (`:95-96`), their constructor parameters (`:107-108`) and their assignments (`:117-118`). The check is the only user of `processorConfigProvider` in this class. Both fields were added by `579e4969b8`, so the constructor goes back to ending with `docDependencyServiceProvider`.
- Remove the imports that become unused: `SupervisedPipelines`, and `java.util.Set` and `ProcessorConfig` if nothing else uses them.

### 4.2 `stroom-shapeshifter`

| File | Change |
|---|---|
| `stroom-shapeshifter/stroom-shapeshifter-ai/src/main/java/stroom/shapeshifter/ai/element/SupervisedPipelineCheck.java` | Delete the file. |
| `stroom-shapeshifter/stroom-shapeshifter-ai/src/test/java/stroom/shapeshifter/ai/element/TestSupervisedPipelineCheck.java` | Delete the file. |
| `stroom-shapeshifter/stroom-shapeshifter-ai/src/main/java/stroom/shapeshifter/ai/doc/ShapeshifterAiModule.java:38-40` | Delete the multibinder binding of `SupervisedPipelineCheck` and its comment. Remove the `SupervisedPipelines` import. |

Before deleting `SupervisedPipelineCheck`, check whether anything else uses its logic for "does this pipeline, as merged, hold a Shapeshifter element" (`grep -rn "SupervisedPipelineCheck\|isSupervised" stroom-*`). On 12 November 2026 nothing else did.

### 4.3 `stroom-app` tests

| File | Change |
|---|---|
| `stroom-app/src/test/java/stroom/shapeshifter/TestASupervisedPipelineNeedsWorkerClaiming.java` | Delete it, and replace it with the new test in 5.1. |
| `stroom-app/src/test/java/stroom/pipelinefactory/TestPipelineFactoryMakesPipelines.java:176-192` | Change `aPipelineWhoseFilterIsRefusedIsDeletedAgain`, as described below. |
| The 8 Shapeshifter tests that set the flag (list below) | Remove `setClaimTasksOnWorker(true)` and its comment, except where 5.2 parameterises the test. Keep `setSkipEmptyFilterFetchDuration(StroomDuration.ZERO)`. |

`aPipelineWhoseFilterIsRefusedIsDeletedAgain` currently relies on the AI refusal. What it actually tests is still worth keeping: when the processor refuses a filter, the pipeline just made is deleted again, so the combination isn't made twice. Make the filter be refused another way. This is harder than it looks: `PipelineMaker.make` runs as the factory's run-as user, so `checkRunAs` and the pipeline `VIEW` check both pass. That is the security problem described in option G. If no natural refusal can be arranged, bind a test-only `ProcessorFilterService` that refuses a named pipeline, in a Guice override for this test class. Assert on that refusal instead of `claimTasksOnWorker`. Then remove the `claiming` save and restore in `setUp`/`tearDown` (`:82-90`) if nothing else in the class needs it.

If option G is adopted first, this whole test class goes with the Pipeline Factory and this change isn't needed.

The scenario tests that set the flag:

- `TestScenarioAFromNothingToServed.java:160`
- `TestScenarioAStepsFourToSix.java:185`
- `TestScenarioBManyStreamsManyThreads.java:182`
- `TestScenario13ReleaseInAPipeline.java:131`
- `TestScenario16AServingRunWritesAsItGoes.java:163`
- `TestScenario20SentinelInAPipeline.java:110`
- `TestScenario57TheSessionRunsTheHeldStreamInFullInAPipeline.java:161`
- `TestADeletedPipelinesLearningInANode.java:100`

All are in `stroom-app/src/test/java/stroom/shapeshifter/`. Find any others with `grep -rln "setClaimTasksOnWorker" stroom-app/src/test/java/stroom/shapeshifter`.

Leave `ProcessorTaskTestHelper`, `CommonTranslationTestHelper`, `TestDeferredTask`, `TestWorkerTaskClaiming` and `TestProcessorTaskReaper` alone. They test both modes for their own sake.

## 5. New tests

AGENTS.md asks for tests that fail before the change and pass after it.

### 5.1 A filter over an AI pipeline is made with either setting

Replace `TestASupervisedPipelineNeedsWorkerClaiming` with `stroom-app/src/test/java/stroom/shapeshifter/TestASupervisedPipelineRunsInEitherMode.java`. It extends `AbstractCoreIntegrationTest` and is parameterised over `claimTasksOnWorker` = `false`, `true`.

- Create a feed, a Shapeshifter AI document and a pipeline holding its element, reusing the `supervised(...)` helper from the deleted test.
- Assert that `processorFilterService.create(...)` returns a filter in both modes.
- Do the same for a pipeline that only *inherits* the element from a parent.
- Restore the original setting in `@AfterEach`.

Before the change, the `false` cases throw `IllegalStateException` ("supervised stage"). After it, they pass.

### 5.2 A feed goes from nothing to served with the master queue

Parameterise `TestScenarioAFromNothingToServed` over `claimTasksOnWorker`, in the same way as `TestDeferredTask`, using `ProcessorTaskTestHelper.setClaimTasksOnWorker`. Do the same for at least one relearning scenario (`TestScenarioAStepsFourToSix`). The `false` case shows a real AI feed end to end with the master queue:

1. its streams defer;
2. learning promotes a result;
3. the wake returns the waiting tasks to `CREATED`;
4. the master queue hands them out;
5. they complete.

Before the change, the `false` case can't even create its filter.

### 5.3 A wake is picked up through the empty-filter backoff

`TestDeferredTask` sets `skipEmptyFilterFetchDuration` to zero, so no test covers the backoff after a wake. Add one case to `TestDeferredTask` that runs only with the master queue and a short, non-zero backoff (for example 1 second):

- Defer a task.
- Let the master look at the filter and find it empty.
- Wake the task.
- Assert that it isn't queued before the backoff ends, and is queued and completed after it.

This pins down the delay section 8 accepts. It passes both before and after the change, so it documents behaviour rather than proving the fix.

### 5.4 Pipeline Factory refusal

The changed `aPipelineWhoseFilterIsRefusedIsDeletedAgain` (4.3) must still show the pipeline being deleted after a refusal.

### 5.5 Content Templates

`stroom-core`'s `TestContentAutoCreationServiceImpl` uses mocks, so it can't show this. Add an integration test in `stroom-app` that uses the real `ContentAutoCreationServiceImpl` and `ProcessorFilterService`. With the setting off, receiving data for an unknown feed under an `INHERIT_PIPELINE` template whose pipeline holds the AI element creates the feed, the pipeline and its filter. Before the change, `ContentAutoCreationServiceImpl.java:694` rethrows the refusal and the sender gets an error.

## 6. Documentation changes

| File | Change |
|---|---|
| `stroom-shapeshifter/stroom-shapeshifter-ai/design/03-processing.md:136-138` | Replace the "Nodes must claim their own tasks" paragraph. A deferred or woken task is an ordinary database row, so both ways of getting tasks to workers serve it. With the master queue, a wake can wait up to `skipEmptyFilterFetchDuration` before its tasks are queued. |
| `stroom-shapeshifter/stroom-shapeshifter-ai/design/08-data-and-code-map.md:125` | Remove the `SupervisedPipelines` row, or replace it with the processor changes that remain (`DEFERRED`, `wakeDeferredTasks`, `sweepDeferredTasks`). |
| `stroom-shapeshifter/stroom-shapeshifter-ai/design/08-data-and-code-map.md:172` | Drop "`claimTasksOnWorker` must be on". |
| `stroom-shapeshifter/stroom-shapeshifter-ai/design/09-rulings.md` | No change: no ruling records the requirement (checked 12 November 2026; the only "claim" is A45, Shapeshifter's own session claim). |
| `stroom-shapeshifter/stroom-shapeshifter-ai/design/history/04-processing-flow.md` | Leave the history as written. Optionally add a dated note at line 385 saying the requirement was later removed, and why. |
| `docs/ai-translations/server-side-inventory.html:641` and `simplified-server-side-inventory.html:665` | Remove the "Processor hook: `SupervisedPipelines`" row. |
| `docs/ai-translations/simplification-options.md` and `.html` | Mark option H as done, and remove the sentence in option G that depends on it. |

`docs/reference/` doesn't mention Shapeshifter in the `claimTasksOnWorker` entries, so nothing changes there.

Add a CHANGELOG entry with `./log_change.sh`, never by editing `CHANGELOG.md`. Use the GitHub issue that tracks the AI translations work, for example:

```bash
./log_change.sh Feature NNNN "Shapeshifter AI pipelines no longer need stroom.processor.claimTasksOnWorker to be on; they run with either way of getting tasks to workers."
```

## 7. Verification

### 7.1 Build and tests

Run these in order:

```bash
./gradlew compile{,Test}Java
./gradlew checkstyle{Main,Test}
./gradlew :stroom-processor:stroom-processor-impl:test
./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai:test
./gradlew :stroom-app:test --tests 'stroom.processor.impl.TestDeferredTask' \
                           --tests 'stroom.processor.impl.TestWorkerTaskClaiming' \
                           --tests 'stroom.shapeshifter.*' \
                           --tests 'stroom.pipelinefactory.*'
```

Then run the full checks AGENTS.md requires before a pull request: `./gradlew clean build shadowJar buildDistribution` and `./buildDockerImages.sh`.

Also confirm that nothing refers to the removed types:

```bash
grep -rn "SupervisedPipeline\|requireWorkerClaimingForSupervised" --include='*.java' . | grep -v /build/
```

### 7.2 Trial on a test cluster

The integration tests run on one node. Before AI feeds run with the master queue in earnest, try these on a cluster of at least three nodes, with the setting off:

1. **A wake issued from a worker node.** Learning runs on whichever node holds the session, so the wake usually comes from a node that isn't the master. Check that the woken tasks are queued within about `skipEmptyFilterFetchDuration` plus one fill cycle.
2. **Master failover while tasks are deferred.** Stop the master while an AI feed has deferred tasks. Check that the new master queues them once they are woken or swept, and that tasks the old master left in `QUEUED` are released as for any other filter (*Processor Task Manager Release Old Queued Tasks*).
3. **Filters limited by processing profiles.** Put the AI filter in a profile limited to some nodes. Check that the woken tasks go only to those nodes.
4. **A large wake.** Defer a few thousand streams on one pipeline and promote a result. Check how many of the master queue's places (`queueSize`) the woken tasks take, and whether other filters are held back while they drain.

## 8. Known effects and risks

These are accepted, and none of them affects correctness:

- **A wake is slower with the master queue.** It can take up to `skipEmptyFilterFetchDuration` (10 seconds) plus the fill cycle. A wake doesn't reset the master's backoff, because it usually runs on another node. If the delay matters, a later change could have `wakeDeferredTasks` bump the filter's creation version in `FilterFetchBackoff`, but that only works on the master node itself.
- **Waking a pipeline that is still learning wastes work.** Its tasks pass through the master queue only to defer again, using queue places. Worker claiming avoids most of this. This was the original efficiency reason for the requirement, and it is now the reason a site with many AI feeds might choose worker claiming.
- **A filter's task limit is looser with the master queue.** `ProcessorTaskCreatorImpl` counts `CREATED` and `DEFERRED` tasks (`:108, 455-457`), but not tasks waiting in `QUEUED`. This was already true before Shapeshifter.
- **The master queue's own weaknesses** (tasks left behind by a failed master, and no sweep of `QUEUED` tasks) apply to AI filters as to any other.

If the trial in 7.2 shows a real fault rather than a delay, don't put the check back. Fix the master queue's handling instead, because AI pipelines can already reach the master queue through the gaps listed in section 2.

## 9. Done when

- [ ] `SupervisedPipelines`, `SupervisedPipelineCheck` and their tests are deleted, and the grep in 7.1 finds nothing.
- [ ] `ProcessorFilterServiceImpl` no longer takes `ProcessorConfig` or `SupervisedPipelines`.
- [ ] The new tests in 5.1, 5.2, 5.3 and 5.5 exist, and the `false` cases of 5.1, 5.2 and 5.5 fail on the old code.
- [ ] `aPipelineWhoseFilterIsRefusedIsDeletedAgain` still tests deletion after a refusal, or has gone with option G.
- [ ] The scenario tests no longer set `claimTasksOnWorker`, except where they are parameterised.
- [ ] Design documents 03 and 08, the inventories and the simplification options are updated.
- [ ] A CHANGELOG entry has been added with `./log_change.sh`.
- [ ] The full build and the Docker images build pass.
- [ ] The test-cluster trial in 7.2 has been run and its results recorded.
