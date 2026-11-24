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

package stroom.shapeshifter.client.component;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.PipelineResource;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.structure.client.PipelineElementTypesFactory;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// What an AI Translator element has learned, on its pipeline's Structure tab ([LearnedTranslation]): shown while
/// such an element is selected, and read only once it is unfolded, and again each time it is, so that a person
/// who never asks costs nothing. It finds the element's learner among the pipeline's (by the pipeline's
/// permissions, as Learning Status does), and the learned translation's elements from the fragment pipeline's
/// layers. The links open the learned translation, a document it uses, or the pipeline's Learning Status, which
/// its owner shows.
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
    /// Which read is current, so that a slower earlier reply is ignored.
    private int asked;
    private Runnable onOpenStatus = () -> {
    };

    /// Made by GIN, with its view, which its owner shows where an element's properties are.
    ///
    /// @param eventBus            The application's event bus.
    /// @param view                Its component.
    /// @param restFactory         Makes REST requests.
    /// @param elementTypesFactory Says what each element type is called.
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

    /// Shows what an AI Translator element has learned, read now if it is unfolded.
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

    /// Hides it, while an element that isn't an AI Translator is selected, or none.
    public void hide() {
        asked++;
        elementId = null;
        view.setShown(false);
    }

    /// @param onOpenStatus Run when the user asks for the pipeline's Learning Status, at the element shown.
    public void setOnOpenStatus(final Runnable onOpenStatus) {
        this.onOpenStatus = onOpenStatus;
    }

    /// @return The id of the AI Translator element shown, or null for none.
    public String getElementId() {
        return elementId;
    }

    // The element's learner among the pipeline's, then its learned translation's elements
    private void read() {
        if (pipeline == null || elementId == null) {
            return;
        }
        final int request = ++asked;
        final String element = elementId;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetchPipelineLearners(pipeline.getUuid()))
                .onSuccess(learners -> {
                    if (request != asked) {
                        return;
                    }
                    final SupervisorLearner row = NullSafe.list(learners).stream()
                            .filter(learner -> element.equals(learner.getLearner().getElementId()))
                            .findFirst()
                            .orElse(null);
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

    // The learned translation's elements, from its layers; where they can't be read, its link alone
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
