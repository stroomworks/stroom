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

import stroom.floormap.shared.FloorMapDoc;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Provider;

public class FloorMapPresenter
        extends DocTabPresenter<LinkTabPanelView, FloorMapDoc> {

    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData EXECUTION = new TabDataImpl("Execution");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public FloorMapPresenter(final EventBus eventBus,
                             final LinkTabPanelView view,
                             final Provider<FloorMapSettingsPresenter> floorMapSettingsPresenterProvider,
                             final Provider<FloorMapProcessingPresenter> floorMapProcessingPresenterProvider,
                             final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                             final DocumentUserPermissionsTabProvider<FloorMapDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        final FloorMapProcessingPresenter floorMapProcessingPresenter = floorMapProcessingPresenterProvider.get();
        floorMapProcessingPresenter.setDocumentEditPresenter(this);

        addTab(SETTINGS, new DocTabProvider<>(floorMapSettingsPresenterProvider::get));
        addTab(EXECUTION, new DocTabProvider<>(() -> floorMapProcessingPresenter));
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
        selectTab(SETTINGS);
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
}
