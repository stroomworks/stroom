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

package stroom.shapeshifter.client.component;

import stroom.docref.DocRef;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.shapeshifter.client.component.Readiness.Fix;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SuggestionAction;
import stroom.widget.component.client.Contexts;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.gwt.core.client.Scheduler;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

/// A Shapeshifter AI document's editor (design 07): **Feeds**, the document's feeds and what each needs,
/// which it opens on; **Setup**, everything the document holds, on one page in the order a translation
/// meets it (how it is learned, its quality checks, approving it, monitoring it in use); then Documentation and
/// Permissions. Which streams reach the document is its pipelines' filters' to say.
///
/// The Feeds tab says, across its top, whether the document will learn anything ([Readiness]), worked out
/// from the document as the Setup tab has it; its fixes are made in the Setup tab's sections, which is why
/// the editor makes and owns both from the start.
public class ShapeshifterAiEditor extends DocumentEditor<ShapeshifterAiDoc> {

    private static final TabData FEEDS = new TabDataImpl("Pipelines");
    private static final TabData SETUP = new TabDataImpl("Setup");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final FeedsTabController feeds;
    private final SetupTabController setup;
    /// Whether the document's own tab is the one shown in Stroom.
    private boolean contentVisible = true;

    /// Makes the editor, with its tabs.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param view                     Its component.
    /// @param feedsProvider            Makes the Feeds tab.
    /// @param setupProvider            Makes the Setup tab.
    /// @param documentationTabProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider      Makes the Permissions tab.
    @Inject
    public ShapeshifterAiEditor(final EventBus eventBus,
                                final LinkTabPanel view,
                                final Provider<FeedsTabController> feedsProvider,
                                final Provider<SetupTabController> setupProvider,
                                final Provider<DocumentationTabController> documentationTabProvider,
                                final Provider<DocumentUserPermissionsTabController> permissionsProvider) {
        super(eventBus, view);
        feeds = make(feedsProvider);
        setup = make(setupProvider);

        // The feeds are what is saved, not what is being edited: learning runs under the saved document
        addOwnedTab(FEEDS, feeds, FeedsTabController::read);
        addOwnedTab(SETUP, setup, SetupTabController::read, SetupTabController::write);
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                ShapeshifterAiDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(FEEDS);

        feeds.setOnFix(this::fix);
    }

    /// Shows the Setup tab's AI model and instructions, e.g. from a pipeline whose feed waits because learning
    /// is switched off.
    public void showLearning() {
        showSection(SetupSection.LEARNING);
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc) {
        feeds.setReadiness(Readiness.of(doc), false);
    }

    /// Whether the document will learn anything is worked out again each time the Feeds tab is shown, from
    /// the document as the Setup tab has it, so that a change made there is seen before it is saved.
    @Override
    protected void afterSelectTab(final TabData tab) {
        if (FEEDS.equals(tab)) {
            showReadiness();
        }
        feeds.setShowing(contentVisible && FEEDS.equals(tab));
    }

    /// The Feeds tab is read again only while it is on screen.
    @Override
    public void onContentTabVisible(final boolean visible) {
        contentVisible = visible;
        feeds.setShowing(visible && FEEDS.equals(getSelectedTab()));
    }

    private void showReadiness() {
        final ShapeshifterAiDoc written = write();
        if (written != null) {
            feeds.setReadiness(Readiness.of(written), isDirty());
        }
    }

    // A readiness check's fix: the Setup tab shown (which reads the document into it, if it hasn't been shown
    // before) at the fix's section and, where the document can be changed, the change made there, for the
    // person to see and save
    private void fix(final Fix fix) {
        final boolean editable = !Boolean.TRUE.equals(context(Contexts.READ_ONLY));
        showSection(fix.getSection());
        switch (fix) {
            case SWITCH_ON -> {
                if (editable) {
                    setup.switchLearningOn();
                }
            }
            // Once the tab is on the page and scrolled
            case CHOOSE_MODEL -> Scheduler.get().scheduleDeferred(setup::focusModel);
            case CHOOSE_TARGET_SCHEMA -> Scheduler.get().scheduleDeferred(setup::focusTargetSchema);
            case USE_STANDARD_CHECKS -> {
                if (editable) {
                    setup.usePreset(QualityPreset.STANDARD);
                }
            }
            case OPEN_APPROACH -> {
                // Shown by its section
            }
            default -> throw new IllegalArgumentException("Unknown fix " + fix);
        }
    }

    /// A stopped run's suggestion followed on the Setup tab, from the pipeline whose run stopped: the limits,
    /// the learning steps or the model.
    ///
    /// @param action The suggestion's action.
    public void followSuggestion(final SuggestionAction action) {
        switch (action) {
            case CHANGE_LIMITS -> {
                selectTab(SETUP);
                Scheduler.get().scheduleDeferred(setup::showLimits);
            }
            case CHANGE_MODEL -> {
                showSection(SetupSection.LEARNING);
                Scheduler.get().scheduleDeferred(setup::focusModel);
            }
            default -> showSection(SetupSection.APPROACH);
        }
    }

    // The Setup tab shown, scrolled to a section once it is laid out
    private void showSection(final SetupSection section) {
        selectTab(SETUP);
        Scheduler.get().scheduleDeferred(() -> setup.showSection(section));
    }
}
