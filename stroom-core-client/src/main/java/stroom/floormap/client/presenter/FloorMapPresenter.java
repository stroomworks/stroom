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
import stroom.document.client.event.ChangeEvent;
import stroom.entity.client.presenter.AbstractTabProvider;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.floormap.shared.FloorMapDoc;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.SvgButton;
import stroom.svg.client.SvgPresets;
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
    private static final TabData EVENTS_QUERY = new TabDataImpl("Events Query");
    private static final TabData FACTS_QUERY = new TabDataImpl("Facts Query");
    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData ASSETS = new TabDataImpl("Assets");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final DocumentAssetPresenter<FloorMapDoc> documentAssetPresenter;
    private final InlineSvgToggleButton editModeButton;
    private final ButtonView addObjectButton;
    private FloorMapMapPresenter floorMapMapPresenter;
    private FloorMapSettingsPresenter floorMapSettingsPresenter;
    private FloorMapEventsQueryPresenter eventsQueryPresenter;
    private FloorMapQueryPresenter factsQueryPresenter;

    @Inject
    public FloorMapPresenter(final EventBus eventBus,
                             final LinkTabPanelView view,
                             final Provider<FloorMapMapPresenter> floorMapMapPresenterProvider,
                             final Provider<FloorMapSettingsPresenter> floorMapSettingsPresenterProvider,
                             final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                             final Provider<FloorMapEventsQueryPresenter> floorMapEventsQueryPresenterProvider,
                             final Provider<FloorMapQueryPresenter> floorMapQueryPresenterProvider,
                             final DocumentUserPermissionsTabProvider<FloorMapDoc> documentUserPermissionsTabProvider,
                             final DocumentAssetPresenter<FloorMapDoc> documentAssetPresenter) {
        super(eventBus, view);
        this.documentAssetPresenter = documentAssetPresenter;

        editModeButton = new InlineSvgToggleButton();
        editModeButton.setSvg(SvgImage.EDIT);
        editModeButton.setTitle("Edit Mode");
        editModeButton.setState(false);
        toolbar.addButton(editModeButton);

        addObjectButton = SvgButton.create(SvgPresets.ADD);
        addObjectButton.setTitle("Add New Object");
        addObjectButton.setVisible(false);
        toolbar.addButton(addObjectButton);

        //noinspection unused
        registerHandler(editModeButton.addClickHandler(e -> {
            if (floorMapMapPresenter != null) {
                floorMapMapPresenter.toggleEditMode(editModeButton.getState());
            }
            addObjectButton.setVisible(editModeButton.getState());
        }));

        //noinspection unused
        registerHandler(addObjectButton.addClickHandler(e -> {
            if (floorMapMapPresenter != null) {
                floorMapMapPresenter.promptAndAddObject();
            }
        }));

        addTab(MAP, new DocTabProvider<>(() -> {
            floorMapMapPresenter = floorMapMapPresenterProvider.get();
            return floorMapMapPresenter;
        }));

        addTab(EVENTS_QUERY, new DocTabProvider<>(() -> {
            eventsQueryPresenter = floorMapEventsQueryPresenterProvider.get();
            return eventsQueryPresenter;
        }));

        addTab(FACTS_QUERY, new AbstractTabProvider<FloorMapDoc, FloorMapQueryPresenter>(eventBus) {
            @Override
            protected FloorMapQueryPresenter createPresenter() {
                factsQueryPresenter = floorMapQueryPresenterProvider.get();
                registerHandler(eventBus.addHandler(ChangeEvent.getType(), () -> fireDirtyEvent(true)));
                return factsQueryPresenter;
            }

            @Override
            public void onRead(final FloorMapQueryPresenter presenter,
                               final DocRef docRef,
                               final FloorMapDoc document,
                               final boolean readOnly) {
                presenter.read(docRef, document.getFactsQuery(), document.getFactsQueryTimeRange(),
                        document.getFactsQueryTablePreferences(), null, null, false);
                presenter.setTaskMonitorFactory(FloorMapPresenter.this);
            }

            @Override
            public FloorMapDoc onWrite(final FloorMapQueryPresenter presenter,
                                       final FloorMapDoc document) {
                return document.copy()
                        .factsQuery(presenter.getQuery())
                        .factsQueryTimeRange(presenter.getQueryTimeRange())
                        .factsQueryTablePreferences(presenter.getQueryTablePreferences())
                        .build();
            }
        });

        addTab(SETTINGS, new DocTabProvider<>(() -> {
            floorMapSettingsPresenter = floorMapSettingsPresenterProvider.get();
            return floorMapSettingsPresenter;
        }));
        addTab(ASSETS, new DocTabProvider<>(() -> documentAssetPresenter));

        addTab(DOCUMENTATION, new MarkdownTabProvider<>(eventBus, markdownEditPresenterProvider) {
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
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        super.onRead(docRef, document, readOnly);
        if (editModeButton != null) {
            editModeButton.setState(false);
            editModeButton.setVisible(getSelectedTab() == MAP);
        }
        if (addObjectButton != null) {
            addObjectButton.setVisible(false);
        }
    }

    @Override
    protected void afterSelectTab(final PresenterWidget<?> content) {
        if (content == documentAssetPresenter) {
            onChange();
        }
        if (editModeButton != null) {
            editModeButton.setVisible(content instanceof FloorMapMapPresenter);
        }
        if (addObjectButton != null && editModeButton != null) {
            addObjectButton.setVisible(content instanceof FloorMapMapPresenter && editModeButton.getState());
        }

        // Auto-populate default template for Facts Query if it is empty/blank
        if (content == factsQueryPresenter) {
            final String currentQuery = factsQueryPresenter.getQuery();
            if (currentQuery == null || currentQuery.trim().isEmpty()) {
                DocRef storeRef;
                if (floorMapSettingsPresenter != null) {
                    storeRef = floorMapSettingsPresenter.getTemporalStoreRef();
                } else {
                    storeRef = getEntity().getTemporalStoreRef();
                }

                if (storeRef != null && storeRef.getName() != null && !storeRef.getName().isEmpty()) {
                    final String template = "from \"" + storeRef.getName() + "\"\n"
                            + "select \n"
                            + "  Key, \n"
                            + "  EffectiveTime, \n"
                            + "  jq(Value, \".type\") as type, \n"
                            + "  jq(Value, \".name\") as name, \n"
                            + "  jq(Value, \".maps\") as maps, \n"
                            + "  jq(Value, \".coords\") as coords, \n"
                            + "  jq(Value, \".img\") as img, \n"
                            + "  jq(Value, \"\\\"tm-world-to-map\\\"\") as tm_world_to_map, \n"
                            + "  jq(Value, \"\\\"tm-map-to-screen\\\"\") as tm_map_to_screen";

                    factsQueryPresenter.read(getEntity().asDocRef(), template,
                            getEntity().getFactsQueryTimeRange(), getEntity().getFactsQueryTablePreferences(),
                            null, null, false);
                }
            }
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
        return super.hasAssociatedDirty() ||
                (floorMapMapPresenter != null && floorMapMapPresenter.hasAssociatedDirty()) ||
                (documentAssetPresenter != null && documentAssetPresenter.isDirty());
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
