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
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.DocTabProvider;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Provider;

/**
 * Task P6.2: {@code GraphDbDoc}'s tabbed editor, mirroring {@code stroom.sqlstore.client.presenter
 * .SqlTemporalStorePresenter} almost verbatim.
 *
 * <p>Code-review fix: a separate Settings tab used to exist here exposing a plain {@code description} field, but
 * the Documentation tab below already binds that exact same field (as its Markdown source) - since
 * {@link stroom.entity.client.presenter.TabContentProvider#write} folds every visited tab's {@code onWrite} over
 * the document with no per-field conflict detection, having two tabs both bind {@code description} meant whichever
 * tab a user happened to open last silently discarded the other tab's edit. That Settings tab was removed
 * entirely rather than left as a field-less placeholder.</p>
 *
 * <p>Task B2 (docs/graphdb-features-implementation-plan.md, Workstream B) reinstates a Settings tab - as the
 * first tab, ahead of Data - but this time it owns exactly the three fields the removed tab did not:
 * {@link GraphDbSettingsPresenter} binds {@code temporalPrecision}, {@code retention} and
 * {@code nodeTypeMappings} only, and deliberately never touches {@code description}, so the failure mode above
 * cannot recur.</p>
 */
public class GraphDbPresenter extends DocTabPresenter<LinkTabPanelView, GraphDbDoc> {

    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData DATA = new TabDataImpl("Data");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public GraphDbPresenter(
            final EventBus eventBus,
            final LinkTabPanelView view,
            final Provider<GraphDbSettingsPresenter> graphDbSettingsPresenterProvider,
            final Provider<GraphDbDataPresenter> graphDbDataPresenterProvider,
            final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
            final DocumentUserPermissionsTabProvider<GraphDbDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(SETTINGS, new DocTabProvider<>(graphDbSettingsPresenterProvider::get));
        addTab(DATA, new DocTabProvider<>(graphDbDataPresenterProvider::get));
        addTab(DOCUMENTATION, new MarkdownTabProvider<>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final GraphDbDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public GraphDbDoc onWrite(final MarkdownEditPresenter presenter,
                                    final GraphDbDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(DATA);
    }

    @Override
    public String getType() {
        return GraphDbDoc.TYPE;
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
