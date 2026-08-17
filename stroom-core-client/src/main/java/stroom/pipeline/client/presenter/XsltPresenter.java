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

package stroom.pipeline.client.presenter;

import stroom.docref.DocRef;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.AbstractTabProvider;
import stroom.entity.client.presenter.DocTabPresenter;
import stroom.entity.client.presenter.LinkTabPanelView;
import stroom.entity.client.presenter.MarkdownEditPresenter;
import stroom.entity.client.presenter.MarkdownTabProvider;
import stroom.pipeline.shared.XsltDoc;
import stroom.security.client.presenter.DocumentUserPermissionsTabProvider;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;

import com.google.gwt.core.client.Scheduler;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import javax.inject.Provider;

public class XsltPresenter extends DocTabPresenter<LinkTabPanelView, XsltDoc> {

    private static final TabData XSLT = new TabDataImpl("XSLT");
    private static final TabData REFERENCES = new TabDataImpl("References");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    /**
     * Held so the References tab can check what is currently in the editor, unsaved changes included,
     * rather than what was last saved.
     */
    private EditorPresenter editorPresenter;

    /**
     * Held so the References tab can be re-checked each time it is opened. Null until the tab has been
     * opened at least once, since its presenter is created lazily.
     */
    private XsltReferencesPresenter referencesPresenter;

    @Inject
    public XsltPresenter(final EventBus eventBus,
                         final LinkTabPanelView view,
                         final Provider<EditorPresenter> editorPresenterProvider,
                         final Provider<XsltReferencesPresenter> referencesPresenterProvider,
                         final Provider<MarkdownEditPresenter> markdownEditPresenterProvider,
                         final DocumentUserPermissionsTabProvider<XsltDoc> documentUserPermissionsTabProvider) {
        super(eventBus, view);

        addTab(XSLT, new AbstractTabProvider<XsltDoc, EditorPresenter>(eventBus) {
            @Override
            protected EditorPresenter createPresenter() {
                editorPresenter = editorPresenterProvider.get();
                editorPresenter.setMode(AceEditorMode.XML);
                registerHandler(editorPresenter.addValueChangeHandler(event -> onChange()));
                registerHandler(editorPresenter.addFormatHandler(event -> onChange()));
                return editorPresenter;
            }

            @Override
            public void onRead(final EditorPresenter presenter,
                               final DocRef docRef,
                               final XsltDoc document,
                               final boolean readOnly) {
//                presenter.getBasicAutoCompletionOption().setOn();
//                presenter.getSnippetsOption().setOn();
//                presenter.deRegisterCompletionProviders();


                presenter.setText(document.getData());
                presenter.setReadOnly(readOnly);
                presenter.getFormatAction().setAvailable(!readOnly);
            }

            @Override
            public XsltDoc onWrite(final EditorPresenter presenter, final XsltDoc document) {
                return document.copy().data(presenter.getText()).build();
            }
        });
        addTab(REFERENCES, new AbstractTabProvider<XsltDoc, XsltReferencesPresenter>(eventBus) {
            @Override
            protected XsltReferencesPresenter createPresenter() {
                referencesPresenter = referencesPresenterProvider.get();
                return referencesPresenter;
            }

            // Deliberately no onRead: the check happens in selectTab instead. TabContentProvider only
            // reads a tab the first time it is opened, so checking here would show the author a stale
            // answer every time they edited the stylesheet and came back.
        });
        addTab(DOCUMENTATION, new MarkdownTabProvider<XsltDoc>(eventBus, markdownEditPresenterProvider) {
            @Override
            public void onRead(final MarkdownEditPresenter presenter,
                               final DocRef docRef,
                               final XsltDoc document,
                               final boolean readOnly) {
                presenter.setText(document.getDescription());
                presenter.setReadOnly(readOnly);
            }

            @Override
            public XsltDoc onWrite(final MarkdownEditPresenter presenter,
                                   final XsltDoc document) {
                return document.copy().description(presenter.getText()).build();
            }
        });
        addTab(PERMISSIONS, documentUserPermissionsTabProvider);
        selectTab(XSLT);
    }

    /**
     * Re-checks the references each time that tab is opened, so the answer is always about the stylesheet
     * currently in the editor.
     * <p>
     * Deferred because {@code super.selectTab} schedules the work that creates the tab's presenter, so the
     * presenter does not exist until after this method returns. Scheduling after it means running after it.
     */
    @Override
    public void selectTab(final TabData tab) {
        super.selectTab(tab);
        if (REFERENCES.equals(tab)) {
            Scheduler.get().scheduleDeferred(this::checkReferences);
        }
    }

    private void checkReferences() {
        final XsltDoc document = getEntity();
        if (referencesPresenter != null && document != null) {
            // The editor's text where there is an editor, so unsaved edits are what gets checked. It is
            // null only if the XSLT tab has never been opened, which leaves the stored body as all there is.
            final String data = editorPresenter != null
                    ? editorPresenter.getText()
                    : document.getData();
            referencesPresenter.check(document.asDocRef(), data);
        }
    }

    @Override
    public String getType() {
        return XsltDoc.TYPE;
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
