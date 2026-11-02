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

import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/// A pipeline's deletion, as its learning hears of it (design 06 A120, A134, A135): the learning is
/// marked deleted, to be read in the Supervisor until it ages out, and the streams waiting on it wait no
/// more, since no task of a deleted pipeline will run them again. An open session is the worker's to
/// abandon, when it next comes to it.
///
/// A pipeline can come back: an export of it imported keeps its uuid, and Stroom says it is created. Its
/// learning is then its own again, and the mark goes, or the pipeline would run while its sessions were
/// abandoned, the Supervisor hid it, and the retention job forgot what it was learning.
///
/// Every node hears the event, and the marking is the same whichever does it first. A delete or a
/// return no node heard is found by the retention job, which calls [#deleted] or [#restored] for it.
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
            // The event is everybody's: a failure here is this feature's, and the retention job finds the
            // pipeline gone, or back, at midnight and marks it then.
            LOGGER.error(() -> LogUtil.message("The learning of pipeline {} could not be marked after {}: {}",
                    pipelineUuid, event.getAction(), e.getMessage()), e);
        }
    }

    /// Mark a deleted pipeline's learning deleted, where it keeps any, and let go of the streams waiting
    /// on it.
    ///
    /// @param pipelineUuid The pipeline.
    public void deleted(final String pipelineUuid) {
        final boolean marked = deletedPipelines.deleted(pipelineUuid, System.currentTimeMillis());
        // Whether or not this marked it: a stream a task was still deciding when the mark was made can
        // write its row after it, and the next hearing lets that go too.
        final int released = deferrals.releasedAll(pipelineUuid);
        if (marked || released > 0) {
            LOGGER.info(() -> LogUtil.message(
                    "Pipeline {} has been deleted: its learning is marked deleted, and {} waiting stream(s) "
                    + "let go", pipelineUuid, released));
        }
    }

    /// The pipeline is there, and its learning is no longer marked deleted, where it was.
    ///
    /// @param pipelineUuid The pipeline.
    public void restored(final String pipelineUuid) {
        if (deletedPipelines.restored(pipelineUuid)) {
            LOGGER.info(() -> LogUtil.message("Pipeline {} is back: its learning is no longer marked deleted",
                    pipelineUuid));
        }
    }
}
