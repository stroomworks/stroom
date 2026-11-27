/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.learningflow.LearningFlowConfig;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Outputs;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/// The scheduled retention job for Learning Flow. It removes the record of attempts (sessions), with their
/// turns, and the output records, once they are older than the node's configured attempt retention period.
/// One row per attempt and one per turn is the fastest-growing data this feature writes, and old
/// transcripts are rarely read, so they are pruned.
///
/// An attempt that is still learning, waiting for the model, or waiting for a person to decide is never
/// pruned however old it is, because its age does not mean it is finished.
///
/// The job also cleans up after deleted pipelines. A pipeline that still has learning recorded but no
/// longer exists is marked deleted, which catches deletions that no node received an event for. The
/// learning of a pipeline that has been marked deleted for longer than the retention period is removed.
@Singleton
public class AttemptRetention {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AttemptRetention.class);

    /// The maximum number of batches one run will prune, so that a night's pruning cannot run on into the
    /// day's work. A batch is a thousand rows, so a run removes up to a million and the next run takes
    /// what is left.
    private static final int MAX_BATCHES = 1000;

    private final Attempts attempts;
    private final Outputs outputs;
    private final KeptLearning keptLearning;
    private final PipelineDeletions pipelineDeletions;
    private final Provider<PipelineStore> pipelineStoreProvider;
    private final Provider<LearningFlowConfig> configProvider;
    private final SecurityContext securityContext;

    @Inject
    public AttemptRetention(final Attempts attempts,
                            final Outputs outputs,
                            final KeptLearning keptLearning,
                            final PipelineDeletions pipelineDeletions,
                            final Provider<PipelineStore> pipelineStoreProvider,
                            final Provider<LearningFlowConfig> configProvider,
                            final SecurityContext securityContext) {
        this.attempts = attempts;
        this.outputs = outputs;
        this.keptLearning = keptLearning;
        this.pipelineDeletions = pipelineDeletions;
        this.pipelineStoreProvider = pipelineStoreProvider;
        this.configProvider = configProvider;
        this.securityContext = securityContext;
    }

    public void exec() {
        securityContext.asProcessingUser(() -> {
            // Done whatever the retention period, even if nothing is ever pruned, so that the Supervisor
            // shows a deleted pipeline's learning as deleted, and a restored pipeline's learning is unmarked.
            checkPipelines();
            final long keepFor = configProvider.get().getAttemptRetention().toMillis();
            if (keepFor <= 0) {
                LOGGER.debug("Attempts are kept for ever: nothing is pruned");
                return;
            }
            // Prune repeatedly rather than once. Each batch is bounded so that no single delete holds
            // locks across the table, but one batch a night would never keep up with a cluster that makes
            // more attempts than that in a day.
            final long before = System.currentTimeMillis() - keepFor;
            final int attemptsPruned = untilDone(() -> attempts.prune(before));
            final int outputsPruned = untilDone(() -> outputs.prune(before));
            if (attemptsPruned > 0 || outputsPruned > 0) {
                LOGGER.info(() -> LogUtil.message(
                        "Pruned {} finished attempt(s) with their turns, and {} output record(s)",
                        attemptsPruned, outputsPruned));
            }
            // Done after the pruning, so that most of a deleted pipeline's old rows have already gone in the
            // bounded batches above, and forgetting the pipeline only has to delete what is left.
            forgetDeleted(before);
        });
    }

    /// Marks as deleted the learning of every pipeline that no longer exists, and clears the mark from every
    /// marked pipeline that exists again. This catches deletions and restorations (e.g. by import) that no
    /// node received an entity event for.
    ///
    /// The pipelines are listed once, as the processing user, who can see all of them. A marked pipeline
    /// that is in the list has been restored. A pipeline missing from the list is only marked once a read
    /// of that pipeline on its own confirms it is not found. Marking is the first step towards forgetting
    /// the learning, so an incomplete list (e.g. from a store that failed part-way) must not cause a live
    /// pipeline's learning to be forgotten. A pipeline whose read fails is left as it is until the next run.
    private void checkPipelines() {
        try {
            final PipelineStore pipelineStore = pipelineStoreProvider.get();
            final Set<String> listed = pipelineStore.list().stream()
                    .map(DocRef::getUuid)
                    .collect(Collectors.toSet());
            final Set<String> marked = Set.copyOf(keptLearning.deletedBefore(Long.MAX_VALUE));
            final Set<String> kept = new LinkedHashSet<>(keptLearning.pipelines());
            kept.addAll(marked);
            for (final String pipelineUuid : kept) {
                if (listed.contains(pipelineUuid)) {
                    if (marked.contains(pipelineUuid)) {
                        pipelineDeletions.restored(pipelineUuid);
                    }
                } else if (gone(pipelineStore, pipelineUuid)) {
                    // Called even if it is already marked, because a task that was deciding a stream as
                    // the pipeline was deleted may have written a waiting-stream row after the mark, and
                    // that row needs releasing too.
                    pipelineDeletions.deleted(pipelineUuid);
                }
            }
        } catch (final RuntimeException e) {
            // The check is tried again at the next run; pruning carries on regardless.
            LOGGER.error(() -> LogUtil.message("Deleted pipelines could not be looked for: {}", e.getMessage()), e);
        }
    }

    /// Whether a read of the pipeline on its own says it does not exist. A database-backed store reports
    /// that by throwing [DocumentNotFoundException], and another store may return `null`. A read that fails
    /// in any other way is treated as not gone, since it proves nothing.
    private static boolean gone(final PipelineStore pipelineStore, final String pipelineUuid) {
        try {
            return pipelineStore.readDocument(new DocRef(PipelineDoc.TYPE, pipelineUuid)) == null;
        } catch (final DocumentNotFoundException e) {
            return true;
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Pipeline {} could not be read to see whether it is gone: {}",
                    pipelineUuid, e.getMessage()), e);
            return false;
        }
    }

    /// Forgets the learning of every pipeline marked deleted before the given time. Pipelines are done one
    /// at a time, so that one that fails is tried again at the next run without holding up the rest. A
    /// restored pipeline has already had its mark cleared by [#checkPipelines], so it is not included.
    private void forgetDeleted(final long before) {
        int forgotten = 0;
        for (final String pipelineUuid : keptLearning.deletedBefore(before)) {
            try {
                keptLearning.forget(pipelineUuid);
                forgotten++;
            } catch (final RuntimeException e) {
                LOGGER.error(() -> LogUtil.message("The learning of deleted pipeline {} could not be forgotten: {}",
                        pipelineUuid, e.getMessage()), e);
            }
        }
        if (forgotten > 0) {
            final int count = forgotten;
            LOGGER.info(() -> LogUtil.message("Forgot the learning of {} deleted pipeline(s)", count));
        }
    }

    /// Runs one batch after another until a batch prunes nothing, or until [#MAX_BATCHES] have been run and
    /// the rest can wait for the next run.
    private static int untilDone(final java.util.function.IntSupplier batch) {
        int total = 0;
        for (int taken = 0; taken < MAX_BATCHES; taken++) {
            final int pruned = batch.getAsInt();
            total += pruned;
            if (pruned == 0) {
                break;
            }
        }
        return total;
    }
}
