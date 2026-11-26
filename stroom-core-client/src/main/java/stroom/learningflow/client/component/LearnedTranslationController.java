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
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.pipeline.shared.PipelineResource;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.structure.client.PipelineElementTypesFactory;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// Fills the "What it learned" section ([LearnedTranslation]) of a pipeline's Structure tab, shown while a Smart
/// Pipeline element is selected. It fetches what was learned only when the section is expanded, and again each
/// time it is, so that nothing is fetched for a person who never looks.
///
/// It finds the element's learner among the pipeline's learners (subject to the pipeline's permissions, as the
/// Learning Status tab does), and gets the learned translation's elements from the fragment pipeline's layers.
/// The links open the learned translation, a document it uses, or the pipeline's Learning Status tab, which the
/// owning screen shows.
public class LearnedTranslationController extends Controller {

    private static final PipelineResource PIPELINE_RESOURCE = GWT.create(PipelineResource.class);
    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    static final String NOT_LEARNED = "Nothing learned yet. It learns from the first stream it is given that "
                                      + "nothing translates; its Learning Status says how that is going.";
    static final String UNREADABLE = "What it learned can't be read here.";

    private final RestFactory restFactory;
    private final PipelineElementTypesFactory elementTypesFactory;
    private final LearnedTranslation view;
    private DocRef pipeline;
    private String elementId;
    private DocRef fragment;
    /// A counter identifying the latest request, so that a reply to an earlier, slower request is ignored.
    private int asked;
    private Runnable onOpenStatus = () -> {
    };

    /// Created by GIN. The owning screen shows the view alongside the selected element's properties.
    ///
    /// @param eventBus            The application's event bus.
    /// @param view                The section's component.
    /// @param restFactory         Creates REST requests.
    /// @param elementTypesFactory Supplies the display name of each element type.
    @Inject
    public LearnedTranslationController(final EventBus eventBus,
                                        final LearnedTranslation view,
                                        final RestFactory restFactory,
                                        final PipelineElementTypesFactory elementTypesFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        this.elementTypesFactory = elementTypesFactory;
        initWidget(view);
        view.setOnToggle(open -> {
            if (open) {
                read();
            }
        });
        view.setOnOpenFragment(() -> open(fragment));
        view.setOnOpenDocument(this::open);
        view.setOnOpenStatus(() -> onOpenStatus.run());
    }

    /// Shows what a Smart Pipeline element has learned, fetching it now if the section is expanded.
    ///
    /// @param pipelineRef The pipeline, as saved.
    /// @param element     The element's id.
    public void show(final DocRef pipelineRef, final String element) {
        pipeline = pipelineRef;
        elementId = element;
        view.setShown(true);
        if (view.isOpen()) {
            read();
        }
    }

    /// Hides the section, for when the selected element isn't a Smart Pipeline, or nothing is selected. Any
    /// reply still outstanding is ignored.
    public void hide() {
        asked++;
        elementId = null;
        view.setShown(false);
    }

    /// @param onOpenStatus Run when the user asks to open the pipeline's Learning Status tab at the element
    ///                     shown.
    public void setOnOpenStatus(final Runnable onOpenStatus) {
        this.onOpenStatus = onOpenStatus;
    }

    /// @return The id of the Smart Pipeline element shown, or null for none.
    public String getElementId() {
        return elementId;
    }

    // Fetches the pipeline's learners to find this element's learned translation, then that translation's
    // elements
    private void read() {
        if (pipeline == null || elementId == null) {
            return;
        }
        final int request = ++asked;
        final String element = elementId;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.pipelineLearners(pipeline.getUuid()))
                .onSuccess(learners -> {
                    if (request != asked) {
                        return;
                    }
                    final SupervisorLearner row = PipelineLearners.learnerOf(learners, element);
                    fragment = NullSafe.get(row, SupervisorLearner::getFragment);
                    if (fragment == null) {
                        view.showMessage(NOT_LEARNED);
                    } else {
                        readParts(request, fragment);
                    }
                })
                .onFailure(error -> {
                    if (request == asked) {
                        view.showMessage(UNREADABLE);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Fetches the learned translation's elements from its layers. If they can't be fetched, only the link to
    // the translation is shown
    private void readParts(final int request, final DocRef learned) {
        final String name = Objects.requireNonNullElse(learned.getName(), "Learned translation");
        elementTypesFactory.get(taskMonitor(), types -> restFactory
                .create(PIPELINE_RESOURCE)
                .method(resource -> resource.fetchPipelineLayers(learned))
                .onSuccess(layers -> {
                    if (request == asked) {
                        view.showLearned(name, LearnedParts.of(layers, element ->
                                NullSafe.get(types.getElementType(element), PipelineElementType::getDisplayValue)));
                    }
                })
                .onFailure(error -> {
                    if (request == asked) {
                        view.showLearned(name, List.of());
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec());
    }

    private void open(final DocRef document) {
        if (document != null) {
            OpenDocumentEvent.fire(this, document, true);
        }
    }
}
