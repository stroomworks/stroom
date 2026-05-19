/*
 * Copyright 2016-2025 Crown Copyright
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
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.DocPresenter;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.feed.shared.FeedDoc;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.security.shared.DocumentPermission;
import stroom.ui.config.client.UiConfigCache;

import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    final DocSelectionBoxPresenter sourceFeedPresenter;
    private final UiConfigCache uiConfigCache;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final DocSelectionBoxPresenter sourceFeedPresenter,
                                     final UiConfigCache uiConfigcache,
                                     final Provider<EditorPresenter> editorPresenterProvider) {
        super(eventBus, view);
        this.sourceFeedPresenter = sourceFeedPresenter;
        this.uiConfigCache = uiConfigcache;

        // Link the view to this presenter for dirty event handling
        view.setUiHandlers(this);

        sourceFeedPresenter.setIncludedTypes(FeedDoc.TYPE);
        sourceFeedPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setSourceFeed(sourceFeedPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        // Register handlers to detect changes and mark the document as dirty
        registerHandler(sourceFeedPresenter.addDataSelectionHandler(e -> onChange()));
        registerHandler(getView().addBackgroundImageChangeHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {
        uiConfigCache.get(extendedUiConfig -> {
            if (extendedUiConfig != null) {
                final DocRef selectedDocRef = floorMapDoc.getFeed();
                if (selectedDocRef != null) {
                    sourceFeedPresenter.setSelectedEntityReference(selectedDocRef, true);
                }

                // Load the background image path/data into the view
                final String selectedBackgroundImage = floorMapDoc.getBackgroundImage();
                if (selectedBackgroundImage != null) {
                    getView().setBackgroundImage(selectedBackgroundImage);
                }
            }
        }, this);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        // Build the updated document with values from the view
        return doc
                .copy()
                .feed(sourceFeedPresenter.getSelectedEntityReference())
                .backgroundImage(getView().getBackgroundImage())
                .build();
    }

    @Override
    public void onDirty() {
        // Triggered by the view when a field is modified
        onChange();
    }

    public interface FloorMapSettingsView extends View, HasUiHandlers<DirtyUiHandlers> {

        void setSourceFeed(View view);

        void setBackgroundImage(String backgroundImage);

        String getBackgroundImage();

        HandlerRegistration addBackgroundImageChangeHandler(ValueChangeHandler<String> handler);
    }
}
