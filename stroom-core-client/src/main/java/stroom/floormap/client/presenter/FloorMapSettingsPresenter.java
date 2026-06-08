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
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.DocPresenter;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.feed.shared.FeedDoc;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.security.shared.DocumentPermission;
import stroom.ui.config.client.UiConfigCache;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import javax.inject.Provider;

public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc> {

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

        sourceFeedPresenter.setIncludedTypes(FeedDoc.TYPE);
        sourceFeedPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setSourceFeed(sourceFeedPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(sourceFeedPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {
        uiConfigCache.get(extendedUiConfig -> {
            if (extendedUiConfig != null) {
                final DocRef selectedDocRef = floorMapDoc.getFeed();

                if (selectedDocRef != null) {
                    sourceFeedPresenter.setSelectedEntityReference(selectedDocRef, true);
                }
            }
        }, this);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        return doc
                .copy()
                .feed(sourceFeedPresenter.getSelectedEntityReference())
                .build();
    }

    public interface FloorMapSettingsView extends View {

        void setSourceFeed(View view);
    }
}
