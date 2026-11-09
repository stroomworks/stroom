/*
 * Copyright 2016 Crown Copyright
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

package stroom.pipeline.client.component;

import stroom.document.client.component.CodeTabController;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.pipeline.shared.XsltDoc;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

/// An XSLT's editor: its XSLT, References, Documentation and Permissions tabs (see [DocumentEditor]).
public class XsltEditor extends DocumentEditor<XsltDoc> {

    private static final TabData XSLT = new TabDataImpl("XSLT");
    private static final TabData REFERENCES = new TabDataImpl("References");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    // Held so the References tab can check what is in the editor, unsaved changes included, rather
    // than what was last saved. Null until the XSLT tab is first shown.
    private CodeTabController xslt;
    // Held so the References tab can be checked again each time it is shown. Null until it is first
    // shown.
    private XsltReferencesListController references;

    /// Makes the editor, with its XSLT, References, Documentation and Permissions tabs.
    ///
    /// @param eventBus             The application's event bus.
    /// @param view                 Its component.
    /// @param codeTabProvider      Makes the code tab.
    /// @param referencesProvider   Makes the References tab.
    /// @param documentationTabProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider  Makes the Permissions tab.
    @Inject
    public XsltEditor(final EventBus eventBus,
                      final LinkTabPanel view,
                      final Provider<CodeTabController> codeTabProvider,
                      final Provider<XsltReferencesListController> referencesProvider,
                      final Provider<DocumentationTabController> documentationTabProvider,
                      final Provider<DocumentUserPermissionsTabController> permissionsProvider) {
        super(eventBus, view);
        addTab(XSLT,
                () -> {
                    xslt = codeTabProvider.get();
                    xslt.setMode(AceEditorMode.XML);
                    xslt.setOnChange(this::onChange);
                    return xslt;
                },
                (code, docRef, doc) -> code.setText(doc.getData()),
                (code, doc) -> doc.copy().data(code.getText()).build());
        // Reading only says what to check: the check happens when the tab is shown (afterSelectTab), so
        // an author who edits the stylesheet and comes back sees an answer about what they have now
        addTab(REFERENCES,
                () -> {
                    references = referencesProvider.get().attach(new XsltReferencesList());
                    return references;
                },
                (list, docRef, doc) -> list.setStylesheet(docRef, () -> xslt != null
                        ? xslt.getText()
                        // The XSLT tab has never been shown, so the stored body is all there is
                        : doc.getData()));
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                XsltDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(XSLT);
    }

    @Override
    protected void afterSelectTab(final TabData tab) {
        if (REFERENCES.equals(tab) && references != null) {
            references.check();
        }
    }
}
