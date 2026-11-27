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

/// A helper shared by a pipeline's Learning Status and Quality Assurance tabs for working with the learners
/// of the pipeline's Smart Pipeline elements.
///
/// It finds an element's learner among the pipeline's learners, and reads the settings of the Learning Flow a
/// learner learns under, caching them so each is read only once while the tab is open.
final class PipelineLearners {

    private final SupervisorResource supervisorResource;
    private final RestFactory restFactory;
    private final HasControllerNode owner;
    private final Supplier<TaskMonitorFactory> taskMonitor;
    /// Cache of the settings of each Learning Flow read so far, keyed by the Learning Flow's UUID.
    private final Map<String, LearningFlowDoc> settings = new HashMap<>();

    /// @param supervisorResource The REST resource used to read the settings.
    /// @param restFactory        Makes REST requests.
    /// @param owner              The controller that owns the requests. No callback runs once it is disposed.
    /// @param taskMonitor        Supplies the task monitor to show a request on, from the owner at the time the
    ///                           request starts.
    PipelineLearners(final SupervisorResource supervisorResource,
                     final RestFactory restFactory,
                     final HasControllerNode owner,
                     final Supplier<TaskMonitorFactory> taskMonitor) {
        this.supervisorResource = supervisorResource;
        this.restFactory = restFactory;
        this.owner = owner;
        this.taskMonitor = taskMonitor;
    }

    /// Finds an element's learner among a pipeline's learners.
    ///
    /// @param learners  The pipeline's learners, or null for none.
    /// @param elementId The element's id.
    /// @return The element's learner, or null if it has none.
    static SupervisorLearner learnerOf(final List<SupervisorLearner> learners, final String elementId) {
        return NullSafe.list(learners).stream()
                .filter(learner -> elementId.equals(learner.getLearner().getElementId()))
                .findFirst()
                .orElse(null);
    }

    /// Clears the cached settings, e.g. when another pipeline is shown.
    void clear() {
        settings.clear();
    }

    /// Gets the settings of the Learning Flow a learner learns under, from the cache or else from the server.
    /// A failure to read them is reported to the user in the usual way for a failed request.
    ///
    /// @param row  The learner, which must name a Learning Flow.
    /// @param then Called with the settings.
    void read(final SupervisorLearner row, final Consumer<LearningFlowDoc> then) {
        read(row, then, false);
    }

    /// Gets the settings of the Learning Flow a learner learns under, from the cache or else from the server.
    /// Unlike [#read], a learner that names no Learning Flow, or a failure to read the settings, gives null
    /// rather than an error.
    ///
    /// @param row  The learner.
    /// @param then Called with the settings, or null.
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
                // A null failure handler means the default one, which alerts the user
                .onFailure(nullOnFailure
                        ? error -> then.accept(null)
                        : null)
                .owner(owner)
                .taskMonitorFactory(taskMonitor.get())
                .exec();
    }
}
