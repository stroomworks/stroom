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
import stroom.document.asset.client.presenter.DocumentAssetPresenter;
import stroom.entity.client.presenter.AbstractTabProvider;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.floormap.shared.FloorMapDoc;
import stroom.query.client.presenter.QueryEditPresenter;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.PresenterWidget;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Provider;

public class FloorMapPresenter extends DocTabPresenter<LinkTabPanelView, FloorMapDoc> {

    private static final TabData MAP = new TabDataImpl("Map");
    private static final TabData QUERY = new TabDataImpl("Query");
    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData ASSETS = new TabDataImpl("Assets");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final DocumentAssetPresenter<FloorMapDoc> documentAssetPresenter;

    @Inject
    public FloorMapPresenter(final EventBus eventBus,
                             final LinkTabPanelView view,
                             final Provider<FloorMapMapPresenter> floorMapMapPresenterProvider,
                             final Provider<FloorMapSettingsPresenter> floorMapSettingsPresenterProvider,
                             final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                             final Provider<QueryEditPresenter> queryEditPresenterProvider,
                             final DocumentUserPermissionsTabProvider<FloorMapDoc> documentUserPermissionsTabProvider,
                             final DocumentAssetPresenter<FloorMapDoc> documentAssetPresenter) {
        super(eventBus, view);
        this.documentAssetPresenter = documentAssetPresenter;

        addTab(MAP, new DocTabProvider<>(floorMapMapPresenterProvider::get));

        addTab(QUERY, new AbstractTabProvider<FloorMapDoc, QueryEditPresenter>(eventBus) {
            @Override
            protected QueryEditPresenter createPresenter() {
                final QueryEditPresenter presenter = queryEditPresenterProvider.get();
                registerHandler(presenter.addChangeHandler(() -> fireDirtyEvent(true)));
                return presenter;
            }

            @Override
            public void onRead(final QueryEditPresenter presenter,
                               final DocRef docRef,
                               final FloorMapDoc document,
                               final boolean readOnly) {
               presenter.setQuery(docRef, document.getQuery(), readOnly);
               presenter.setTimeRange(document.getQueryTimeRange());
               presenter.read(document.getQueryTablePreferences());
               presenter.setTaskMonitorFactory(FloorMapPresenter.this);
           }

           @Override
            public FloorMapDoc onWrite(final QueryEditPresenter presenter,
                                       final FloorMapDoc document) {
                return document.copy()
                        .query(presenter.getQuery())
                        .queryTimeRange(presenter.getTimeRange())
                        .queryTablePreferences(presenter.write())
                        .build();
           }
        });

        addTab(SETTINGS, new DocTabProvider<>(floorMapSettingsPresenterProvider::get));
        addTab(ASSETS, new DocTabProvider<>(() -> documentAssetPresenter));

        addTab(DOCUMENTATION, new MarkdownTabProvider<FloorMapDoc>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final FloorMapDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public FloorMapDoc onWrite(final MarkdownEditPresenter presenter,
                                      final FloorMapDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });

        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(MAP);
    }

    @Override
    protected void afterSelectTab(final PresenterWidget<?> content) {
        if (content == documentAssetPresenter) {
            onChange();
        }
    }

    @Override
    public String getType() {
        return FloorMapDoc.TYPE;
    }

    @Override
    protected TabData getPermissionsTab() {
        return PERMISSIONS;
    }

    @Override
    protected TabData getDocumentationTab() {
        return DOCUMENTATION;
    }

    @Override
    protected boolean hasAssociatedDirty() {
        return super.hasAssociatedDirty() || (documentAssetPresenter != null && documentAssetPresenter.isDirty());
    }

    /**
     * Provide a callback to be inserted into the save chain after the save is complete.
     * @return The consumer for the callback. The second parameter will be the
     * consumer to call after this method has completed.
     */
    @Override
    public BiConsumer<FloorMapDoc, Consumer<FloorMapDoc>> getPostSaveCallback() {
        return this::saveAssets;
    }

    /**
     * Provide a callback to be inserted into the SaveAs chain after the document saveAs
     * has happened.
     * @return The consumer for the callback. The second parameter will be the
     * consumer to call after this method has completed.
     */
    @Override
    public BiConsumer<FloorMapDoc, Consumer<FloorMapDoc>> getPostSaveAsCallback() {
        return this::saveAsAssets;
    }

    /**
     * Called by DocumentPlugin to save the assets associated with the document.
     * Specified in getPostSaveCallback().
     * @param document The document that was written by all the data in all the tabs.
     * @param callback Thing to call when the assets have been saved.
     */
    public void saveAssets(final FloorMapDoc document, final Consumer<FloorMapDoc> callback) {
        documentAssetPresenter.onSave(document, callback);
    }

    /**
     * Called by DocumentPlugin to do a SaveAs to a new document.
     * Specified in getPostSaveAsCallback().
     * @param document The new document to save to.
     * @param callback Thing to call when the assets have been saved.
     */
    public void saveAsAssets(final FloorMapDoc document, final Consumer<FloorMapDoc> callback) {
        documentAssetPresenter.onSaveAs(document, callback);
    }
}
