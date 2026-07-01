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

package stroom.floormap.client.presenter;

import stroom.docref.DocRef;
import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.planb.shared.PlanBDoc;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    private final DocSelectionBoxPresenter eventsStoreRefPresenter;
    private final DocSelectionBoxPresenter factsStoreRefPresenter;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider) {
        super(eventBus, view);

        view.setUiHandlers(this);

        this.eventsStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.eventsStoreRefPresenter.setIncludedTypes(PlanBDoc.TYPE);
        this.eventsStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setEventsStoreRefView(this.eventsStoreRefPresenter.getView());

        this.factsStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.factsStoreRefPresenter.setIncludedTypes(SqlTemporalStoreDoc.TYPE);
        this.factsStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setFactsStoreRefView(this.factsStoreRefPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(eventsStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
        //noinspection unused e
        registerHandler(factsStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {

        eventsStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getEventsStoreRef(), true);
        eventsStoreRefPresenter.setEnabled(!readOnly);
        factsStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getFactsStoreRef(), true);
        factsStoreRefPresenter.setEnabled(!readOnly);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        return doc.copy()
                .eventsStoreRef(eventsStoreRefPresenter.getSelectedEntityReference())
                .factsStoreRef(factsStoreRefPresenter.getSelectedEntityReference())
                .build();
    }

    public DocRef getEventsStoreRef() {
        return eventsStoreRefPresenter.getSelectedEntityReference();
    }

    public DocRef getFactsStoreRef() {
        return factsStoreRefPresenter.getSelectedEntityReference();
    }

    @Override
    public void onDirty() {
        onChange();
    }

    public interface FloorMapSettingsView extends View, HasUiHandlers<DirtyUiHandlers>, ReadOnlyChangeHandler {

        void setEventsStoreRefView(View view);

        void setFactsStoreRefView(View view);

    }
}
