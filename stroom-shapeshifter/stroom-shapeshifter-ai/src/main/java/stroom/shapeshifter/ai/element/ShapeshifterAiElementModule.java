/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.job.api.ScheduledJobsBinder;
import stroom.pipeline.factory.PipelineElementModule;
import stroom.shapeshifter.ai.fragment.FragmentOutputFilter;
import stroom.shapeshifter.ai.fragment.FragmentTapFilter;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.stage.Documents;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.shapeshifter.ai.stage.Places;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.Wake;
import stroom.util.RunnableWrapper;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.shared.scheduler.CronExpressions;

import com.google.inject.Scopes;
import com.google.inject.multibindings.OptionalBinder;
import jakarta.inject.Inject;

/**
 * The supervisor element and what it runs over. The advisors are an optional binding whose default asks
 * the model each document names through {@code stroom-ai}; a test node sets it to a script. The runtime
 * state of A26 is bound to the in-memory implementations until the module of §12 item 8 replaces them; a
 * reprocess request is real already, a reprocess filter on the pipeline.
 */
public class ShapeshifterAiElementModule extends PipelineElementModule {

    @Override
    protected void configure() {
        super.configure();
        OptionalBinder.newOptionalBinder(binder(), Advisors.class).setDefault().to(ModelAdvisors.class);
        // The corpus is streams on the source feed with an index the impl-db module binds (design 04 §4.1).
        bind(RegressionSet.class).to(StoredRegressionSet.class).in(Scopes.SINGLETON);
        // A promotion wakes the deferred tasks of its learner's pipeline (design 04 §5.3, design 06 §4.3).
        bind(Wake.class).to(TaskWake.class).in(Scopes.SINGLETON);
        // What a learner learns goes where its element says, read from its pipeline (A113).
        bind(Places.class).to(PipelinePlaces.class);
        // What the worker needs and a processing task is handed: the document an attempt
        // names, and the stream it was raised on (A5, A28).
        bind(Documents.class).to(StoreDocuments.class).in(Scopes.SINGLETON);
        bind(Inputs.class).to(StreamInputs.class).in(Scopes.SINGLETON);
        // A pipeline's deletion marks its learning deleted, and lets go of what waits on it (A134, A135).
        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(PipelineDeletions.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(DeferredLearningJob.class, builder -> builder
                        .name("Shapeshifter AI Deferred Learning")
                        .description("Carry on the Shapeshifter AI attempts that are waiting for the model. "
                                     + "A processing task never asks the model: it defers its stream, "
                                     + "and this job does the learning")
                        .frequencySchedule("1m"))
                .bindJobTo(AttemptRetentionJob.class, builder -> builder
                        .name("Shapeshifter AI Attempt Retention")
                        .description("Remove the record of Shapeshifter AI attempts that finished longer "
                                     + "ago than the retention period, and their turns with them; mark "
                                     + "the learning of pipelines since deleted, and forget it once it "
                                     + "has been deleted for longer than the retention period")
                        .cronSchedule(CronExpressions.EVERY_DAY_AT_MIDNIGHT.getExpression()));
    }

    @Override
    protected void configureElements() {
        // The two shapes a supervised stage takes (§12 item 4): the parser is the extraction stage,
        // given a stream; the filter is the transformation stage, given records. The pipeline editor
        // refuses a parser under a parser, so the second could not be drawn without the second shape.
        bindElement(ShapeshifterAiParser.class);
        bindElement(FragmentOutputFilter.class);
        bindElement(FragmentTapFilter.class);
    }


    // --------------------------------------------------------------------------------


    private static class DeferredLearningJob extends RunnableWrapper {

        @Inject
        DeferredLearningJob(final DeferredLearning deferredLearning) {
            super(deferredLearning::exec);
        }
    }


    // --------------------------------------------------------------------------------


    private static class AttemptRetentionJob extends RunnableWrapper {

        @Inject
        AttemptRetentionJob(final AttemptRetention attemptRetention) {
            super(attemptRetention::exec);
        }
    }
}
