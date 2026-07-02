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
import stroom.security.shared.DocumentPermission;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

/**
 * Presenter for the Settings tab. Allows configuring the temporal store
 * reference used by a floor map document.
 */
public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    private final DocSelectionBoxPresenter temporalStoreRefPresenter;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider) {
        super(eventBus, view);

        view.setUiHandlers(this);

        this.temporalStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.temporalStoreRefPresenter.setIncludedTypes("SqlTemporalStore");
        this.temporalStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setTemporalStoreRefView(this.temporalStoreRefPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(temporalStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {

        temporalStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getTemporalStoreRef(), true);
        temporalStoreRefPresenter.setEnabled(!readOnly);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        return doc.copy()
                .temporalStoreRef(temporalStoreRefPresenter.getSelectedEntityReference())
                .build();
    }

    /**
     * Returns the currently selected temporal store reference.
     *
     * @return the {@link DocRef} for the selected temporal store, or {@code null} if none is selected.
     */
    public DocRef getTemporalStoreRef() {
        return temporalStoreRefPresenter.getSelectedEntityReference();
    }

    @Override
    public void onDirty() {
        onChange();
    }

    /**
     * View contract for the floor map settings tab. Provides a slot for the
     * temporal store reference selection widget.
     */
    public interface FloorMapSettingsView extends View, HasUiHandlers<DirtyUiHandlers>, ReadOnlyChangeHandler {

        /**
         * Sets the temporal store reference selection widget into the view.
         *
         * @param view the {@link DocSelectionBoxPresenter} view to embed.
         */
        void setTemporalStoreRefView(View view);

    }
}
