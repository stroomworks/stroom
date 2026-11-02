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


package stroom.pipelinefactory.client.component;

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryResource;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;


/// A Pipeline Factory's Made tab ([MadePipelineList]): what it has met and made (design 06 §3.5), one
/// row per combination, newest first, read each time the factory is. *Open* opens the pipeline made
/// for a combination; *Forget* lets a combination's next stream make its pipeline afresh, the
/// pipeline already made, if any, left alone.
public class MadePipelineListController extends Controller {

    private static final PipelineFactoryResource RESOURCE = GWT.create(PipelineFactoryResource.class);

    private final MadePipelineList view;
    private final RestFactory restFactory;
    private String factoryUuid;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus    The application's event bus.
    /// @param view        The list.
    /// @param restFactory Reads what the factory has met, and forgets a combination.
    @Inject
    public MadePipelineListController(final EventBus eventBus,
                                      final MadePipelineList view,
                                      final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
        this.view = view;
        initWidget(view);
        view.setOnOpen(combination -> OpenDocumentEvent.fire(this, combination.getPipeline(), true));
        view.setOnForget(this::forget);
        view.setOnRefresh(this::refresh);
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Reads what a factory has met.
    ///
    /// @param docRef The factory.
    /// @param doc    Its document, which holds none of it: what the factory has met is runtime state.
    public void read(final DocRef docRef, final PipelineFactoryDoc doc) {
        factoryUuid = docRef.getUuid();
        refresh();
    }

    private void refresh() {
        if (factoryUuid == null) {
            return;
        }
        restFactory.create(RESOURCE)
                .method(resource -> resource.combinations(factoryUuid))
                .onSuccess(combinations -> view.setData(CombinationText.newestFirst(combinations)))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void forget(final PipelineFactoryCombination selected) {
        final String also = selected.isPipelineExists()
                ? " The pipeline already made for it is left as it is, so its next stream will make a second one."
                : "";
        final String question = "Forget " + CombinationText.describe(selected)
                                + "? Its next stream makes its pipeline afresh." + also;
        ConfirmEvent.fire(this, question, ok -> {
            if (ok && !isDisposed()) {
                restFactory.create(RESOURCE)
                        .method(resource -> resource.forget(factoryUuid, selected.getValues()))
                        .onSuccess(forgotten -> refresh())
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec();
            }
        });
    }
}
