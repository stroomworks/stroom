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
import stroom.document.client.editor.DocumentEditorHost;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.PipelineResource;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/// A pipeline's AI Translation tab ([PipelineAiTab]; design 07): the learning of the pipeline's Shapeshifter AI
/// elements, followed and acted on from the pipeline rather than from the document they learn under, by the
/// pipeline's permissions (a person who may view it reads; one who may edit it acts). It finds the elements in
/// the pipeline as merged with its parents ([PipelineAiElement]), tells its owner whether there are any (for
/// the tab to be shown only where there are), and shows the chosen one's feed ([FeedDetailController]) with the
/// settings of the document it learns under, which the server gives to anyone who may view the pipeline.
///
/// A pipeline's tab isn't refreshed by Stroom as a content tab is, so the tab reads the feed again itself,
/// every ten seconds while it is on the page.
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
    /// The settings of each document a feed learns under, by its uuid, read once while the tab is open.
    private final Map<String, ShapeshifterAiDoc> settings = new HashMap<>();
    private DocRef pipeline;
    private List<PipelineAiElement> elements = List.of();
    private PipelineAiElement chosen;
    /// The id of the element to show once the elements are known, e.g. from a document's Feeds tab.
    private String wanted;
    /// Whether the tab is the one shown, in the pipeline shown: it isn't read again while it isn't.
    private boolean showing;
    private Consumer<Boolean> onAvailable = any -> {
    };
    private Consumer<List<PipelineAiElement>> onElements = found -> {
    };
    private Runnable onOpenQuality = () -> {
    };

    /// Made by GIN, with its tab and the feed's controller.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The tab.
    /// @param detail      Where the element chosen stands.
    /// @param restFactory Makes REST requests.
    @Inject
    public PipelineAiTabController(final EventBus eventBus,
                                   final PipelineAiTab view,
                                   final FeedDetailController detail,
                                   final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        initWidget(view);
        this.detail = own(detail).attach(view.getFeedDetail());
        detail.setOnActed(this::refresh);
        detail.setOnOpenSettings(() -> openDocument(editor -> editor.showLearning()));
        detail.setOnOpenSetup(action -> openDocument(editor -> editor.followSuggestion(action)));
        detail.setOnOpenQuality(() -> onOpenQuality.run());
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

    /// Finds the pipeline's AI elements, tells its owner whether it has any, and shows the one chosen.
    ///
    /// @param pipelineRef The pipeline.
    public void read(final DocRef pipelineRef) {
        if (pipeline == null || !pipeline.getUuid().equals(pipelineRef.getUuid())) {
            settings.clear();
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

    /// Shows one of the pipeline's AI elements, now if they are known, else once they are.
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

    /// @param onAvailable Told whether the pipeline has any AI elements, each time it is read, for the tab to
    ///                    be shown only where it has.
    public void setOnAvailable(final Consumer<Boolean> onAvailable) {
        this.onAvailable = onAvailable;
    }

    /// @param onOpenQuality Run when the person asks to see the issues QA flagged, on the Quality Assurance tab.
    public void setOnOpenQuality(final Runnable onOpenQuality) {
        this.onOpenQuality = onOpenQuality;
    }

    /// @return The id of the AI Translator element shown, or null for none.
    public String getChosenElementId() {
        return NullSafe.get(chosen, PipelineAiElement::getId);
    }

    /// @param onElements Told the pipeline's AI Translator elements, each time it is read, e.g. for its Quality
    ///                   Assurance tab, so that the pipeline isn't read twice.
    public void setOnElements(final Consumer<List<PipelineAiElement>> onElements) {
        this.onElements = onElements;
    }

    /// Says whether the tab is on screen: it is read again every ten seconds only while it is, and at once
    /// when it comes back.
    ///
    /// @param showing Whether it is the tab shown, in the pipeline shown.
    public void setShowing(final boolean showing) {
        final boolean back = showing && !this.showing;
        this.showing = showing;
        if (back) {
            refresh();
        }
    }

    /// Reads the chosen element's feed again: its learner row, and the settings of the document it learns
    /// under where they haven't been read.
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
                .method(resource -> resource.pipelineLearners(pipeline.getUuid()))
                .onSuccess(learners -> {
                    final SupervisorLearner row = NullSafe.list(learners).stream()
                            .filter(learner -> element.getId().equals(learner.getLearner().getElementId()))
                            .findFirst()
                            .orElse(null);
                    if (row == null || row.getDoc() == null) {
                        show(element, row, null);
                    } else {
                        withSettings(row, doc -> show(element, row, doc));
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // The element asked for, else the one chosen before, else the first
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

    // The settings of the document a feed learns under, read once
    private void withSettings(final SupervisorLearner row, final Consumer<ShapeshifterAiDoc> then) {
        final String docUuid = row.getDoc().getUuid();
        final ShapeshifterAiDoc known = settings.get(docUuid);
        if (known != null) {
            then.accept(known);
            return;
        }
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.settings(docUuid, row.getLearner().getPipelineUuid(),
                        row.getLearner().getElementId()))
                .onSuccess(doc -> {
                    settings.put(docUuid, doc);
                    then.accept(doc);
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Shows a feed, where the element chosen still is the one it was read for
    private void show(final PipelineAiElement element, final SupervisorLearner row, final ShapeshifterAiDoc doc) {
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

    // Opens the document the chosen element learns under, and asks its editor to show something
    private void openDocument(final Consumer<ShapeshifterAiEditor> then) {
        final DocRef document = NullSafe.get(chosen, PipelineAiElement::getDocument);
        if (document == null) {
            return;
        }
        OpenDocumentEvent.builder(this, document)
                .forceOpen(true)
                .callbackOnOpen(opened -> {
                    if (opened instanceof final DocumentEditorHost<?> host
                        && host.getEditor() instanceof final ShapeshifterAiEditor editor) {
                        then.accept(editor);
                    }
                })
                .fire();
    }
}
