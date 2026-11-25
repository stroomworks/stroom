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

package stroom.learningflow.client.component;

import stroom.dispatch.client.RestFactory;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.task.client.TaskMonitorFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// What a pipeline's tabs share about its Smart Pipeline elements' learners: finding an element's learner
/// among the pipeline's, and the settings of each Learning Flow a learner learns under, read once while the
/// tab is open.
final class PipelineLearners {

    private final SupervisorResource supervisorResource;
    private final RestFactory restFactory;
    private final HasControllerNode owner;
    private final Supplier<TaskMonitorFactory> taskMonitor;
    /// The settings of each Learning Flow a learner learns under, by its uuid.
    private final Map<String, LearningFlowDoc> settings = new HashMap<>();

    /// @param supervisorResource The supervisor's resource, which reads the settings.
    /// @param restFactory        Makes REST requests.
    /// @param owner              The controller whose requests these are: nothing is told once it is disposed.
    /// @param taskMonitor        The task monitor a request shows on, as the owner has it when it starts.
    PipelineLearners(final SupervisorResource supervisorResource,
                     final RestFactory restFactory,
                     final HasControllerNode owner,
                     final Supplier<TaskMonitorFactory> taskMonitor) {
        this.supervisorResource = supervisorResource;
        this.restFactory = restFactory;
        this.owner = owner;
        this.taskMonitor = taskMonitor;
    }

    /// Finds an element's learner among a pipeline's.
    ///
    /// @param learners  The pipeline's learners, or null for none.
    /// @param elementId The element's id.
    /// @return The element's learner, or null where it has none.
    static SupervisorLearner learnerOf(final List<SupervisorLearner> learners, final String elementId) {
        return NullSafe.list(learners).stream()
                .filter(learner -> elementId.equals(learner.getLearner().getElementId()))
                .findFirst()
                .orElse(null);
    }

    /// Forgets the settings read, e.g. when another pipeline is shown.
    void clear() {
        settings.clear();
    }

    /// The settings of the Learning Flow a learner learns under, read once. A failure to read them is told
    /// as any failed request is.
    ///
    /// @param row  The learner, which names a Learning Flow.
    /// @param then Given the settings.
    void read(final SupervisorLearner row, final Consumer<LearningFlowDoc> then) {
        read(row, then, false);
    }

    /// The settings of the Learning Flow a learner learns under, read once; null where the learner names
    /// none or they can't be read.
    ///
    /// @param row  The learner.
    /// @param then Given the settings, or null.
    void readOrNull(final SupervisorLearner row, final Consumer<LearningFlowDoc> then) {
        if (row.getDoc() == null) {
            then.accept(null);
            return;
        }
        read(row, then, true);
    }

    private void read(final SupervisorLearner row, final Consumer<LearningFlowDoc> then, final boolean nullOnFailure) {
        final String docUuid = row.getDoc().getUuid();
        final LearningFlowDoc known = settings.get(docUuid);
        if (known != null) {
            then.accept(known);
            return;
        }
        restFactory
                .create(supervisorResource)
                .method(resource -> resource.settings(docUuid, row.getLearner().getPipelineUuid(),
                        row.getLearner().getElementId()))
                .onSuccess(doc -> {
                    settings.put(docUuid, doc);
                    then.accept(doc);
                })
                // Left unset, a failure is alerted as any request's is
                .onFailure(nullOnFailure
                        ? error -> then.accept(null)
                        : null)
                .owner(owner)
                .taskMonitorFactory(taskMonitor.get())
                .exec();
    }
}
