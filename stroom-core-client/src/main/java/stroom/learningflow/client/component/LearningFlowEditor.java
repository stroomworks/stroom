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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.learningflow.client.FlowExamplesCache;
import stroom.learningflow.client.component.Readiness.Fix;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.SuggestionAction;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.widget.component.client.Contexts;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.gwt.core.client.Scheduler;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

/// The editor for a Learning Flow document. It has four tabs: **Pipelines**, which it opens on, listing the
/// Smart Pipelines that learn under the document and what each needs; **Setup**, every setting the document
/// holds, on one page in the order a translation meets them (how it is learned, its quality checks, approving
/// it, monitoring it in use); then Documentation and Permissions. Which streams reach the document is decided
/// by its pipelines' processor filters, not by the document.
///
/// Across its top, the Pipelines tab says whether the document will learn anything ([Readiness]), worked out
/// from the document as the Setup tab currently has it. Its fixes are made in the Setup tab's sections, which
/// is why the editor creates and owns both tabs from the start.
public class LearningFlowEditor extends DocumentEditor<LearningFlowDoc> {

    private static final TabData FEEDS = new TabDataImpl("Pipelines");
    private static final TabData SETUP = new TabDataImpl("Setup");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    private final FeedsTabController feeds;
    private final SetupTabController setup;
    /// The default setups, fetched from the server, which the readiness check uses to name the document's
    /// learning steps.
    private final FlowExamplesCache flowExamples;
    /// Whether this document's content tab is the one shown in Stroom.
    private boolean contentVisible = true;

    /// Creates the editor, with its tabs.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param view                     The tab panel the tabs are shown in.
    /// @param feedsProvider            Creates the Pipelines tab.
    /// @param setupProvider            Creates the Setup tab.
    /// @param documentationTabProvider Creates the Documentation tab's editor.
    /// @param permissionsProvider      Creates the Permissions tab.
    /// @param flowExamples             Fetches and caches the default setups from the server.
    @Inject
    public LearningFlowEditor(final EventBus eventBus,
                                final LinkTabPanel view,
                                final Provider<FeedsTabController> feedsProvider,
                                final Provider<SetupTabController> setupProvider,
                                final Provider<DocumentationTabController> documentationTabProvider,
                                final Provider<DocumentUserPermissionsTabController> permissionsProvider,
                                final FlowExamplesCache flowExamples) {
        super(eventBus, view);
        this.flowExamples = flowExamples;
        feeds = make(feedsProvider);
        setup = make(setupProvider);

        // The Pipelines tab is given the saved document, not the one being edited, because learning runs
        // under the saved document. So it has no write function
        addOwnedTab(FEEDS, feeds, FeedsTabController::read);
        addOwnedTab(SETUP, setup, SetupTabController::read, SetupTabController::write);
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                LearningFlowDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(FEEDS);

        feeds.setOnFix(this::fix);
    }

    /// Shows the Setup tab's AI model and instructions section, e.g. when opened from a pipeline whose feed is
    /// waiting because learning is switched off.
    public void showLearning() {
        showSection(SetupSection.LEARNING);
    }

    @Override
    protected void onRead(final DocRef docRef, final LearningFlowDoc doc) {
        // The readiness check names which default setup the document's steps match, and the default setups
        // are defined on the server, so they are fetched before it is drawn. If they have already been
        // fetched the cache answers at once. If the fetch fails, the steps are described as the document's
        // own setup rather than given a wrong name.
        flowExamples.get(examples -> {
            if (!isDisposed()) {
                feeds.setReadiness(Readiness.of(doc, examples), false);
            }
        }, taskMonitor());
    }

    /// Each time the Pipelines tab is shown, whether the document will learn anything is worked out again from
    /// the document as the Setup tab currently has it, so that the effect of a change made there is seen
    /// before it is saved.
    @Override
    protected void afterSelectTab(final TabData tab) {
        if (FEEDS.equals(tab)) {
            showReadiness();
        }
        feeds.setShowing(contentVisible && FEEDS.equals(tab));
    }

    /// The Pipelines tab is refreshed only while it is on screen.
    @Override
    public void onContentTabVisible(final boolean visible) {
        contentVisible = visible;
        feeds.setShowing(visible && FEEDS.equals(getSelectedTab()));
    }

    private void showReadiness() {
        final LearningFlowDoc written = write();
        if (written != null) {
            flowExamples.get(examples -> {
                if (!isDisposed()) {
                    feeds.setReadiness(Readiness.of(written, examples), isDirty());
                }
            }, taskMonitor());
        }
    }

    // Applies a fix offered by the readiness check. It shows the Setup tab (which reads the document into it,
    // if it hasn't been shown before) at the fix's section and, if the document can be edited, makes the
    // change there for the person to see and save
    private void fix(final Fix fix) {
        final boolean editable = !Boolean.TRUE.equals(context(Contexts.READ_ONLY));
        showSection(fix.getSection());
        switch (fix) {
            case SWITCH_ON -> {
                if (editable) {
                    setup.switchLearningOn();
                }
            }
            // Deferred until the tab is on the page and scrolled
            case CHOOSE_MODEL -> Scheduler.get().scheduleDeferred(setup::focusModel);
            case CHOOSE_TARGET_SCHEMA -> Scheduler.get().scheduleDeferred(setup::focusTargetSchema);
            case USE_STANDARD_CHECKS -> {
                if (editable) {
                    setup.usePreset(CheckPreset.STANDARD);
                }
            }
            case OPEN_APPROACH -> {
                // Showing the section is all this fix does
            }
            default -> throw new IllegalArgumentException("Unknown fix " + fix);
        }
    }

    /// Follows, on the Setup tab, a suggestion made when a pipeline's learning run stopped: to change the
    /// limits, the learning steps or the AI model.
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

    // Shows the Setup tab, scrolled to a section once it has been laid out
    private void showSection(final SetupSection section) {
        selectTab(SETUP);
        Scheduler.get().scheduleDeferred(() -> setup.showSection(section));
    }
}
