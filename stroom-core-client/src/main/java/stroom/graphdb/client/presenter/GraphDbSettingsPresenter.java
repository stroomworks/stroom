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

package stroom.graphdb.client.presenter;

import stroom.docref.DocRef;
import stroom.document.client.event.ChangeUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.graphdb.client.view.GraphNodeTypeMappingsView;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.planb.client.view.RetentionSettingsView;
import stroom.planb.shared.TemporalPrecision;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/**
 * Task B1: the {@code GraphDbDoc} Settings tab - an
 * editor for the three graph-level, Tier-1 fields identified:
 * {@code temporalPrecision}, {@code retention} and {@code nodeTypeMappings}.
 *
 * <p><b>Does not bind {@code description}.</b> See {@link GraphDbPresenter}'s class javadoc for why a previous
 * Settings tab was removed - it re-bound {@code description}, which the Documentation tab owns, and the two
 * tabs' writes clashed last-write-wins. {@link #onWrite} only ever {@code copy}s the document and sets the
 * three fields above, so {@code description} always passes through unchanged; {@link #onRead}/{@link #onWrite}
 * have no method to read or write {@code description} at all.</p>
 */
public class GraphDbSettingsPresenter
        extends DocPresenter<GraphDbSettingsPresenter.GraphDbSettingsView, GraphDbDoc> {

    @Inject
    public GraphDbSettingsPresenter(
            final EventBus eventBus,
            final GraphDbSettingsView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    @Override
    protected void onRead(final DocRef docRef, final GraphDbDoc doc, final boolean readOnly) {
        getView().onReadOnly(readOnly);
        getView().setTemporalPrecision(doc.getTemporalPrecision());
        getView().setRetention(doc.getRetention());
        getView().setNodeTypeMappings(doc.getNodeTypeMappings());
    }

    @Override
    protected GraphDbDoc onWrite(final GraphDbDoc doc) {
        return doc.copy()
                .temporalPrecision(getView().getTemporalPrecision())
                .retention(getView().getRetention())
                .nodeTypeMappings(getView().getNodeTypeMappings())
                .build();
    }

    public interface GraphDbSettingsView
            extends View,
                    RetentionSettingsView,
                    GraphNodeTypeMappingsView,
                    ReadOnlyChangeHandler,
                    HasUiHandlers<ChangeUiHandlers> {

        TemporalPrecision getTemporalPrecision();

        void setTemporalPrecision(TemporalPrecision temporalPrecision);
    }
}
