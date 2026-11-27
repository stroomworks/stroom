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

package stroom.learningflow.element;

import stroom.job.api.ScheduledJobsBinder;
import stroom.learningflow.fragment.FragmentOutputFilter;
import stroom.learningflow.fragment.FragmentTapFilter;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.stage.Documents;
import stroom.learningflow.stage.Inputs;
import stroom.learningflow.stage.Places;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.Wake;
import stroom.pipeline.factory.PipelineElementModule;
import stroom.util.RunnableWrapper;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.shared.scheduler.CronExpressions;

import com.google.inject.Scopes;
import com.google.inject.multibindings.OptionalBinder;
import jakarta.inject.Inject;

/// Guice module for the Smart Pipeline element, the internal elements used to run fragments, the
/// node-side implementations of the services learning uses, and the two Learning Flow scheduled jobs.
///
/// [Advisors] is an optional binding. Its default asks the model each Learning Flow document names,
/// through `stroom-ai`; a test node can override it with a scripted advisor.
public class LearningFlowElementModule extends PipelineElementModule {

    @Override
    protected void configure() {
        super.configure();
        OptionalBinder.newOptionalBinder(binder(), Advisors.class).setDefault().to(ModelAdvisors.class);
        // The regression corpus is stored as streams on the source feed, with an index that the impl-db
        // module binds.
        bind(RegressionSet.class).to(StoredRegressionSet.class).in(Scopes.SINGLETON);
        // A promotion wakes the deferred tasks of its learner's pipeline.
        bind(Wake.class).to(TaskWake.class).in(Scopes.SINGLETON);
        // Learned content is written to the folder and name set on the learner's element in its pipeline.
        bind(Places.class).to(PipelinePlaces.class);
        // How the background worker reads the Learning Flow document an attempt names, and the stream the
        // attempt was opened on.
        bind(Documents.class).to(StoreDocuments.class).in(Scopes.SINGLETON);
        bind(Inputs.class).to(StreamInputs.class).in(Scopes.SINGLETON);
        // The schema a document's translations must conform to, read from Stroom's XMLSchema documents.
        bind(TargetSchemas.class).to(XmlSchemaTargetSchemas.class).in(Scopes.SINGLETON);
        // Deleting a pipeline marks its learning deleted and releases the streams waiting on it.
        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(PipelineDeletions.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(DeferredLearningJob.class, builder -> builder
                        .name("Learning Flow Deferred Learning")
                        .description("Carry on the Learning Flow attempts that are waiting for the model. "
                                     + "A processing task never asks the model: it defers its stream, "
                                     + "and this job does the learning")
                        .frequencySchedule("1m"))
                .bindJobTo(AttemptRetentionJob.class, builder -> builder
                        .name("Learning Flow Attempt Retention")
                        .description("Remove the record of Learning Flow attempts that finished longer "
                                     + "ago than the retention period, and their turns with them; mark "
                                     + "the learning of pipelines since deleted, and forget it once it "
                                     + "has been deleted for longer than the retention period")
                        .cronSchedule(CronExpressions.EVERY_DAY_AT_MIDNIGHT.getExpression()));
    }

    @Override
    protected void configureElements() {
        // The Smart Pipeline element itself, which sits in the parser position, plus the two internal
        // elements that the fragment runner adds when it runs a fragment: the output filter at the end
        // of the chain and the tap below the parser.
        bindElement(LearningFlowParser.class);
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
