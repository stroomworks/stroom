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

package stroom.gitrepo.client.component;

import stroom.dependency.client.component.StructureGraphController;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.gitrepo.shared.GitRepoDoc;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

/// A Git repository's editor: its Structure, Settings, Documentation and Permissions tabs (see
/// [DocumentEditor]).
///
/// A Git repository holds documents as a folder does, and is often used as one, so Structure (the
/// documents in it and how they depend on each other, as a graph, read only) is its first tab and the
/// one shown, as it is for a folder (`FolderTabs`).
public class GitRepoEditor extends DocumentEditor<GitRepoDoc> {

    private static final TabData STRUCTURE = new TabDataImpl("Structure");
    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    /// Makes the editor, with its tabs.
    ///
    /// @param eventBus             The application's event bus.
    /// @param view                 Its component.
    /// @param structureProvider    Makes the Structure tab.
    /// @param settingsProvider     Makes the Settings tab.
    /// @param documentationTabProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider  Makes the Permissions tab.
    @Inject
    public GitRepoEditor(final EventBus eventBus,
                         final LinkTabPanel view,
                         final Provider<StructureGraphController> structureProvider,
                         final Provider<GitRepoSettingsController> settingsProvider,
                         final Provider<DocumentationTabController> documentationTabProvider,
                         final Provider<DocumentUserPermissionsTabController> permissionsProvider) {
        super(eventBus, view);

        // The server leaves out of the graph whatever the user may not see, so the tab needs no
        // permission of its own
        addTab(STRUCTURE,
                structureProvider::get,
                (structure, docRef, doc) -> structure.read(docRef));
        addTab(SETTINGS,
                settingsProvider::get,
                GitRepoSettingsController::read,
                GitRepoSettingsController::write);
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                GitRepoDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(STRUCTURE);
    }
}
