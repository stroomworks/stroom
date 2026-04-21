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

package stroom.domaintype.client.presenter;

import stroom.docref.DocRef;
import stroom.domaintype.shared.DomainTypeDoc;
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

public class DomainTypePresenter extends DocTabPresenter<LinkTabPanelView, DomainTypeDoc> {

    private static final TabData DOMAIN_TYPES = TabDataImpl.builder()
            .withLabel("Types")
            .withTooltip("List of domain types (class.attributeType) held in this document.")
            .build();
    private static final TabData DOCUMENTATION = TabDataImpl.builder()
            .withLabel("Documentation")
            .withTooltip(TabData.createDocumentationTooltip(DomainTypeDoc.TYPE))
            .build();
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    @Inject
    public DomainTypePresenter(final EventBus eventBus,
                               final LinkTabPanelView view,
                               final Provider<DomainTypeListPresenter> domainTypeListPresenterProvider,
                               final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                               final DocumentUserPermissionsTabProvider<DomainTypeDoc>
                                       documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(DOMAIN_TYPES, new DocTabProvider<>(domainTypeListPresenterProvider::get));

        addTab(DOCUMENTATION, new MarkdownTabProvider<DomainTypeDoc>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final DomainTypeDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public DomainTypeDoc onWrite(final MarkdownEditPresenter presenter,
                                         final DomainTypeDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(DOMAIN_TYPES);
    }

    @Override
    public String getType() {
        return DomainTypeDoc.TYPE;
    }

    @Override
    protected TabData getPermissionsTab() {
        return PERMISSIONS;
    }

    @Override
    protected TabData getDocumentationTab() {
        return DOCUMENTATION;
    }

    public interface DomainTypeView extends LinkTabPanelView {
    }
}
