/*
 * Copyright 2026 Crown Copyright
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


package stroom.pipelinefactory.client.component;

import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

/// A Pipeline Factory document's editor (design 06 §3.5): what it makes and where (Settings), the
/// filter it gives each pipeline (Filter), what it has made so far (Made), and its Documentation and
/// Permissions tabs (see [DocumentEditor]).
public class PipelineFactoryEditor extends DocumentEditor<PipelineFactoryDoc> {

    private static final TabData SETTINGS = new TabDataImpl("Settings");
    private static final TabData FILTER = new TabDataImpl("Filter");
    private static final TabData MADE = new TabDataImpl("Made");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    /// Makes the editor, with its tabs, the Settings tab shown first.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param view                     Its component.
    /// @param settingsProvider         Makes the Settings tab.
    /// @param filterProvider           Makes the Filter tab.
    /// @param madeProvider             Makes the Made tab.
    /// @param documentationTabProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider      Makes the Permissions tab.
    @Inject
    public PipelineFactoryEditor(final EventBus eventBus,
                                 final LinkTabPanel view,
                                 final Provider<PipelineFactorySettingsController> settingsProvider,
                                 final Provider<PipelineFactoryFilterController> filterProvider,
                                 final Provider<MadePipelineListController> madeProvider,
                                 final Provider<DocumentationTabController> documentationTabProvider,
                                 final Provider<DocumentUserPermissionsTabController> permissionsProvider) {
        super(eventBus, view);

        addTab(SETTINGS,
                settingsProvider::get,
                PipelineFactorySettingsController::read,
                PipelineFactorySettingsController::write);
        addTab(FILTER,
                filterProvider::get,
                PipelineFactoryFilterController::read,
                PipelineFactoryFilterController::write);
        // What the factory has met is runtime state, not part of the document, so it writes nothing
        addTab(MADE,
                madeProvider::get,
                MadePipelineListController::read);
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                PipelineFactoryDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(SETTINGS);
    }
}
