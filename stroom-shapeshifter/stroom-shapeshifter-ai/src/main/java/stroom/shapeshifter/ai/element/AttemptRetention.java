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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Outputs;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/// The prune job of design 01 §12 item 8: the record of attempts that finished longer ago than the
/// document's node is told to keep them, and their turns with them. One row per attempt and one per turn
/// is the fastest-growing thing this feature writes, and a transcript nobody will ever read is a cost
/// with no reader.
///
/// An attempt still learning, still waiting for the model, or waiting for a person to decide is never
/// pruned however old it is: age is not what says an attempt is over.
///
/// It is also where a deleted pipeline's learning ends (design 06 A134, A136): a pipeline whose learning
/// is kept but which no longer exists is marked deleted, for a delete no node heard, and the learning of
/// a pipeline deleted for longer than attempts are kept is forgotten.
@Singleton
public class AttemptRetention {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AttemptRetention.class);

    /// The most batches one run will take, so that a night's pruning cannot run into the day's work. A
    /// batch is a thousand attempts, so a run clears a million and the next run takes what is left.
    private static final int MAX_BATCHES = 1000;

    private final Attempts attempts;
    private final Outputs outputs;
    private final KeptLearning keptLearning;
    private final PipelineDeletions pipelineDeletions;
    private final Provider<PipelineStore> pipelineStoreProvider;
    private final Provider<ShapeshifterAiConfig> configProvider;
    private final SecurityContext securityContext;

    @Inject
    public AttemptRetention(final Attempts attempts,
                            final Outputs outputs,
                            final KeptLearning keptLearning,
                            final PipelineDeletions pipelineDeletions,
                            final Provider<PipelineStore> pipelineStoreProvider,
                            final Provider<ShapeshifterAiConfig> configProvider,
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
            // Whatever the retention: a deleted pipeline's learning is marked so whether or not it will
            // ever be forgotten, and one that is back is not.
            checkPipelines();
            final long keepFor = configProvider.get().getAttemptRetention().toMillis();
            if (keepFor <= 0) {
                LOGGER.debug("Attempts are kept for ever: nothing is pruned");
                return;
            }
            // Until there is nothing left to forget, not once: a batch is bounded so that no single
            // delete holds locks across the table, and one batch a night would never catch a cluster
            // that makes more attempts than that in a day.
            final long before = System.currentTimeMillis() - keepFor;
            final int attemptsPruned = untilDone(() -> attempts.prune(before));
            final int outputsPruned = untilDone(() -> outputs.prune(before));
            if (attemptsPruned > 0 || outputsPruned > 0) {
                LOGGER.info(() -> LogUtil.message(
                        "Pruned {} finished attempt(s) with their turns, and {} output record(s)",
                        attemptsPruned, outputsPruned));
            }
            // After the pruning, so that what a deleted pipeline's learners made long ago has gone in the
            // bounded batches above, and forgetting one deletes what is left of it.
            forgetDeleted(before);
        });
    }

    /// Mark the learning of every pipeline that no longer exists, for a delete no node heard (A134), and
    /// clear the mark of every one that is there again, for a return no node heard.
    ///
    /// The pipelines there are, read once, as the processing user, who is shown every one. A marked
    /// pipeline the list shows is back. One the list does not show is marked only once it has been read
    /// on its own and found to be nothing: marking is the first step to forgetting, and a list that came
    /// back short — a store that failed part-way — must not be what makes a live pipeline's learning
    /// forgotten. A pipeline whose read fails is left as it is, until the next run can tell.
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
                    // Marked where it was not, and for one that was, whatever waits on it let go: a task
                    // deciding a stream as the pipeline went can write its row after the mark.
                    pipelineDeletions.deleted(pipelineUuid);
                }
            }
        } catch (final RuntimeException e) {
            // Tried again at the next run; the pruning goes on.
            LOGGER.error(() -> LogUtil.message("Deleted pipelines could not be looked for: {}", e.getMessage()), e);
        }
    }

    /// Whether the pipeline is gone, as a read of it on its own says: not found, which a store backed by
    /// the database says by throwing and another may say with nothing. A read that fails any other way
    /// says nothing either way.
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

    /// Forget the learning of every pipeline deleted before the time (A136), one pipeline at a time, so
    /// that one that fails is tried again at the next run and the rest are not held up by it. A pipeline
    /// that is back has had its mark cleared above, so it is not among them.
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

    /// One batch after another until a batch comes back short, or until enough batches have been taken
    /// that the rest can wait for the next run.
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
