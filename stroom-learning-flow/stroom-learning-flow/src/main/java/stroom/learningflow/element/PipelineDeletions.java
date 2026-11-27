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

import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/// Handles pipeline delete and create events for Learning Flow. When a pipeline is deleted, its learning
/// is marked deleted, so the Supervisor can show it as deleted until the retention job removes it, and
/// the streams waiting on it are released, since no task of a deleted pipeline will run them again. Any
/// open session is abandoned by the background worker the next time it reaches it.
///
/// A pipeline can come back: importing an export of it keeps its UUID, and Stroom raises a create event.
/// Its learning is then unmarked; otherwise the pipeline would run while its sessions were abandoned, the
/// Supervisor hid it, and the retention job removed what it had learned.
///
/// Every node receives the event, and marking gives the same result whichever node does it first. A
/// delete or restore that no node received an event for is found by the retention job
/// ([AttemptRetention]), which calls [#deleted] or [#restored].
@Singleton
@EntityEventHandler(type = PipelineDoc.TYPE, action = {EntityAction.DELETE, EntityAction.CREATE})
public class PipelineDeletions implements EntityEvent.Handler {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineDeletions.class);

    private final DeletedPipelines deletedPipelines;
    private final Deferrals deferrals;

    @Inject
    public PipelineDeletions(final DeletedPipelines deletedPipelines,
                             final Deferrals deferrals) {
        this.deletedPipelines = deletedPipelines;
        this.deferrals = deferrals;
    }

    @Override
    public void onChange(final EntityEvent event) {
        final String pipelineUuid = event.getDocRef().getUuid();
        try {
            if (EntityAction.DELETE.equals(event.getAction())) {
                deleted(pipelineUuid);
            } else {
                restored(pipelineUuid);
            }
        } catch (final RuntimeException e) {
            // The event is shared with other handlers, so a failure here is logged rather than thrown. The
            // retention job will find the pipeline deleted or restored at its next run and mark it then.
            LOGGER.error(() -> LogUtil.message("The learning of pipeline {} could not be marked after {}: {}",
                    pipelineUuid, event.getAction(), e.getMessage()), e);
        }
    }

    /// Marks a deleted pipeline's learning as deleted, if it has any, and releases the streams waiting on
    /// it.
    ///
    /// @param pipelineUuid The UUID of the deleted pipeline.
    public void deleted(final String pipelineUuid) {
        final boolean marked = deletedPipelines.deleted(pipelineUuid, System.currentTimeMillis());
        // Release waiting streams whether or not this call made the mark, because a task that was still
        // deciding a stream when the mark was made can write its waiting row afterwards, and a later call
        // needs to release that too.
        final int released = deferrals.releasedAll(pipelineUuid);
        if (marked || released > 0) {
            LOGGER.info(() -> LogUtil.message(
                    "Pipeline {} has been deleted: its learning is marked deleted, and {} waiting stream(s) "
                    + "let go", pipelineUuid, released));
        }
    }

    /// Clears the deleted mark from a pipeline's learning, if it was marked, because the pipeline exists
    /// again.
    ///
    /// @param pipelineUuid The UUID of the restored pipeline.
    public void restored(final String pipelineUuid) {
        if (deletedPipelines.restored(pipelineUuid)) {
            LOGGER.info(() -> LogUtil.message("Pipeline {} is back: its learning is no longer marked deleted",
                    pipelineUuid));
        }
    }
}
