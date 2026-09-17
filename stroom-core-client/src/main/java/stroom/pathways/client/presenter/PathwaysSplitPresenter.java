/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.client.presenter;

import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.pathways.client.presenter.PathwaysSplitPresenter.PathwaysSplitView;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

public class PathwaysSplitPresenter extends DocPresenter<PathwaysSplitView, PathwaysDoc> {

    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    private final PathwayListPresenter pathwayListPresenter;
    private final PathwayTreePresenter pathwayTreePresenter;
    private final RestFactory restFactory;

    private DocRef docRef;

    @Inject
    public PathwaysSplitPresenter(final EventBus eventBus,
                                  final PathwaysSplitView view,
                                  final PathwayListPresenter pathwayListPresenter,
                                  final PathwayTreePresenter pathwayTreePresenter,
                                  final RestFactory restFactory) {
        super(eventBus, view);
        this.pathwayListPresenter = pathwayListPresenter;
        this.pathwayTreePresenter = pathwayTreePresenter;
        this.restFactory = restFactory;
        view.setTable(pathwayListPresenter.getView());
        view.setTree(pathwayTreePresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(pathwayListPresenter.getSelectionModel().addSelectionHandler(e -> {
            final PathwaySummary selected = pathwayListPresenter.getSelectionModel().getSelected();
            if (selected == null) {
                pathwayTreePresenter.read(null, isReadOnly());
                return;
            }
            // The row carries no model, only its size — a pathway holds every path it has seen, so the
            // list cannot afford to bring them along. Fetch the one being looked at.
            restFactory
                    .create(PATHWAYS_RESOURCE)
                    .method(res -> res.fetchPathway(new FetchPathwayRequest(docRef, selected.getName())))
                    .onSuccess(pathway -> pathwayTreePresenter.read(pathway, isReadOnly()))
                    .onFailure(new DefaultErrorHandler(this, null))
                    .taskMonitorFactory(this)
                    .exec();
        }));
    }

    @Override
    protected void onRead(final DocRef docRef, final PathwaysDoc document, final boolean readOnly) {
        this.docRef = docRef;
        pathwayListPresenter.onRead(docRef, document, readOnly);
    }

    @Override
    protected PathwaysDoc onWrite(final PathwaysDoc document) {
        return pathwayListPresenter.onWrite(document);
    }

    public interface PathwaysSplitView extends View {

        void setTable(View view);

        void setTree(View view);
    }
}
