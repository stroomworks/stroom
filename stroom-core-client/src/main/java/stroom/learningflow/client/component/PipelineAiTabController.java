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
import stroom.document.client.editor.DocumentEditorHost;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.pipeline.shared.PipelineResource;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// Controls a pipeline's Learning Status tab ([PipelineAiTab]), where the learning of the pipeline's Smart
/// Pipeline elements is followed and acted on.
///
/// The learning is managed from the pipeline rather than from the Learning Flow document it learns under, so
/// access follows the pipeline's permissions: a user who may view the pipeline can see its learning, and one
/// who may edit it can act on it. The controller finds the Smart Pipeline elements in the pipeline merged with
/// its ancestors ([PipelineAiElement]), tells its owner whether there are any (so the tab is shown only when
/// there are), and shows the chosen element's learning ([FeedDetailController]) together with the settings of
/// the Learning Flow it learns under. The server returns those settings to anyone who may view the pipeline.
///
/// Stroom does not refresh a document's tabs as it does a content tab, so this tab refreshes itself every ten
/// seconds while it is visible.
public class PipelineAiTabController extends Controller {

    private static final PipelineResource PIPELINE_RESOURCE = GWT.create(PipelineResource.class);
    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final int REFRESH_MS = 10_000;
    static final String NOT_LEARNED = "This element hasn't learned anything yet. It learns from the "
                                              + "first stream it is given that nothing translates.";
    static final String NO_DOCUMENT = "This element doesn't name a Learning Flow to learn under. "
                                              + "Choose one on the Structure tab.";

    private final PipelineAiTab view;
    private final FeedDetailController detail;
    private final RestFactory restFactory;
    /// Finds learners and caches the settings of each Learning Flow they learn under while the tab is open.
    private final PipelineLearners learners;
    private DocRef pipeline;
    private List<PipelineAiElement> elements = List.of();
    private PipelineAiElement chosen;
    /// The id of the element asked to be shown (e.g. from a Learning Flow's Pipelines tab), to be selected once
    /// the pipeline's elements are known.
    private String wanted;
    /// Whether this tab is the selected tab of the visible pipeline. It is only refreshed while it is.
    private boolean showing;
    private Consumer<Boolean> onAvailable = any -> {
    };
    private Consumer<List<PipelineAiElement>> onElements = found -> {
    };

    /// Made by GIN.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The tab.
    /// @param detail      The controller for the chosen element's learning.
    /// @param restFactory Makes REST requests.
    @Inject
    public PipelineAiTabController(final EventBus eventBus,
                                   final PipelineAiTab view,
                                   final FeedDetailController detail,
                                   final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        this.learners = new PipelineLearners(SUPERVISOR_RESOURCE, restFactory, this, this::taskMonitor);
        initWidget(view);
        this.detail = own(detail).attach(view.getFeedDetail());
        detail.setOnActed(this::refresh);
        detail.setOnOpenSettings(() -> openDocument(editor -> editor.showLearning()));
        detail.setOnOpenSetup(action -> openDocument(editor -> editor.followSuggestion(action)));
        view.setOnElement(element -> {
            if (element != null && !element.equals(chosen)) {
                chosen = element;
                refresh();
            }
        });

        final Timer timer = new Timer() {
            @Override
            public void run() {
                if (showing && pipeline != null && asWidget().isAttached()) {
                    refresh();
                }
            }
        };
        timer.scheduleRepeating(REFRESH_MS);
        onDispose(timer::cancel);
    }

    /// Finds the pipeline's Smart Pipeline elements, tells the owner whether there are any, and shows the
    /// chosen one.
    ///
    /// @param pipelineRef The pipeline.
    public void read(final DocRef pipelineRef) {
        if (pipeline == null || !pipeline.getUuid().equals(pipelineRef.getUuid())) {
            learners.clear();
            chosen = null;
        }
        pipeline = pipelineRef;
        restFactory
                .create(PIPELINE_RESOURCE)
                .method(resource -> resource.fetchPipelineLayers(pipelineRef))
                .onSuccess(layers -> {
                    elements = PipelineAiElement.of(layers);
                    onAvailable.accept(!elements.isEmpty());
                    onElements.accept(elements);
                    chosen = pick();
                    view.setElements(elements, chosen);
                    refresh();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Shows one of the pipeline's Smart Pipeline elements, now if the elements are known, otherwise once
    /// they have been read.
    ///
    /// @param elementId The element's id.
    public void showElement(final String elementId) {
        wanted = elementId;
        if (!elements.isEmpty()) {
            chosen = pick();
            view.setElements(elements, chosen);
            refresh();
        }
    }

    /// @param onAvailable Called each time the pipeline is read, with whether it has any Smart Pipeline
    ///                    elements, so that the owner shows the tab only when it does.
    public void setOnAvailable(final Consumer<Boolean> onAvailable) {
        this.onAvailable = onAvailable;
    }

    /// @param onElements Called each time the pipeline is read, with its Smart Pipeline elements, e.g. for the
    ///                   Quality Assurance tab, so that the pipeline doesn't have to be read twice.
    public void setOnElements(final Consumer<List<PipelineAiElement>> onElements) {
        this.onElements = onElements;
    }

    /// Tells the controller whether the tab is visible. It refreshes every ten seconds only while visible, and
    /// refreshes at once when it becomes visible again.
    ///
    /// @param showing Whether this tab is the selected tab of the visible pipeline.
    public void setShowing(final boolean showing) {
        final boolean back = showing && !this.showing;
        this.showing = showing;
        if (back) {
            refresh();
        }
    }

    /// Reads the chosen element's learner again, and the settings of the Learning Flow it learns under if they
    /// have not already been read.
    public void refresh() {
        final PipelineAiElement element = chosen;
        if (pipeline == null || element == null) {
            return;
        }
        if (element.getDocument() == null) {
            show(element, null, null);
            return;
        }
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetchPipelineLearners(pipeline.getUuid()))
                .onSuccess(found -> {
                    final SupervisorLearner row = PipelineLearners.learnerOf(found, element.getId());
                    if (row == null || row.getDoc() == null) {
                        show(element, row, null);
                    } else {
                        learners.read(row, doc -> show(element, row, doc));
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Picks the element asked for, else the one chosen before, else the first
    private PipelineAiElement pick() {
        for (final String id : new String[]{wanted, NullSafe.get(chosen, PipelineAiElement::getId)}) {
            for (final PipelineAiElement element : elements) {
                if (element.getId().equals(id)) {
                    return element;
                }
            }
        }
        return elements.isEmpty()
                ? null
                : elements.get(0);
    }

    // Shows the learner, unless the user has chosen another element while it was being read
    private void show(final PipelineAiElement element, final SupervisorLearner row, final LearningFlowDoc doc) {
        if (!Objects.equals(element, chosen)) {
            return;
        }
        if (element.getDocument() == null) {
            detail.setFeed(null);
            view.showNothing(NO_DOCUMENT);
        } else if (row == null) {
            detail.setFeed(null);
            view.showNothing(NOT_LEARNED);
        } else {
            detail.setDocument(doc);
            detail.setFeed(row);
            view.showDetail();
        }
    }

    // Opens the Learning Flow the chosen element learns under, then passes its editor to `then`
    private void openDocument(final Consumer<LearningFlowEditor> then) {
        final DocRef document = NullSafe.get(chosen, PipelineAiElement::getDocument);
        if (document == null) {
            return;
        }
        OpenDocumentEvent.builder(this, document)
                .forceOpen(true)
                .callbackOnOpen(opened -> {
                    if (opened instanceof final DocumentEditorHost<?> host
                        && host.getEditor() instanceof final LearningFlowEditor editor) {
                        then.accept(editor);
                    }
                })
                .fire();
    }
}
