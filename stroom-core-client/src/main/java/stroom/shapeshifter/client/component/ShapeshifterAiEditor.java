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

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.component.DocumentationTabController;
import stroom.document.client.editor.DocumentEditor;
import stroom.document.client.editor.LinkTabPanel;
import stroom.security.client.component.DocumentUserPermissionsTabController;
import stroom.shapeshifter.client.component.ProcessHeader.Stage;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.PageRequest;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;

/// A Shapeshifter AI document's editor: the two stages of the process, in the order a stream meets them
/// (design 06, A110) — the scores that judge what is served and what is learned, and the flow that
/// learns — then its Documentation and Permissions. Which streams reach the document is its pipelines'
/// filters' to say. Each stage's tab shows the process across its top ([ProcessHeader], A98), its own
/// stage marked, with this document's figures from the Supervisor.
public class ShapeshifterAiEditor extends DocumentEditor<ShapeshifterAiDoc> {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private static final TabData SCORE = new TabDataImpl("Score");
    private static final TabData LEARN = new TabDataImpl("Learn");
    private static final TabData DOCUMENTATION = new TabDataImpl("Documentation");
    private static final TabData PERMISSIONS = new TabDataImpl("Permissions");

    /// The stages' tabs' headers, as each tab is made, which the figures are shown in.
    private final List<ProcessHeader> headers = new ArrayList<>();
    private final RestFactory restFactory;
    /// The last read asked for, so that a slower earlier reply arriving after a later one is ignored.
    private int asked;
    /// What the two reads have answered so far: streams waiting, sessions parked.
    private Long waiting;
    private Long parked;

    /// Makes the editor, with its tabs.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param view                     Its component.
    /// @param scoreTabProvider         Makes the Score tab.
    /// @param learnTabProvider         Makes the Learn tab.
    /// @param documentationTabProvider Makes the Documentation tab's editor.
    /// @param permissionsProvider      Makes the Permissions tab.
    /// @param restFactory              Reads the document's figures from the Supervisor.
    @Inject
    public ShapeshifterAiEditor(final EventBus eventBus,
                                final LinkTabPanel view,
                                final Provider<ScoreTabController> scoreTabProvider,
                                final Provider<LearnTabController> learnTabProvider,
                                final Provider<DocumentationTabController> documentationTabProvider,
                                final Provider<DocumentUserPermissionsTabController> permissionsProvider,
                                final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;

        addTab(SCORE,
                () -> {
                    final ScoreTabController scoreTab = scoreTabProvider.get();
                    addHeader(scoreTab.getProcessHeader(), Stage.SCORE);
                    return scoreTab;
                },
                ScoreTabController::read,
                ScoreTabController::write);
        addTab(LEARN,
                () -> {
                    final LearnTabController learnTab = learnTabProvider.get();
                    addHeader(learnTab.getProcessHeader(), Stage.LEARN);
                    return learnTab;
                },
                LearnTabController::read,
                LearnTabController::write);
        addDocumentationTab(DOCUMENTATION,
                documentationTabProvider,
                ShapeshifterAiDoc::getDescription,
                (doc, description) -> doc.copy().description(description).build());
        addPermissionsTab(PERMISSIONS, permissionsProvider);
        setDefaultTab(SCORE);
    }

    // A stage's tab's header: its own stage marked, the other a way to its tab, and the figures so far
    private void addHeader(final ProcessHeader header, final Stage stage) {
        header.setStage(stage);
        header.setOnStage(chosen -> selectTab(chosen == Stage.SCORE
                ? SCORE
                : LEARN));
        header.setFigures(waiting, parked);
        headers.add(header);
    }

    private void showFigures() {
        headers.forEach(header -> header.setFigures(waiting, parked));
    }

    /// The figures beside the stages are this document's alone: the streams waiting on a session of its,
    /// and the sessions of its parked for a person. Read with the document, and guarded against a slower
    /// earlier read arriving after a later one.
    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc) {
        // Cleared as a set: a figure from the last read must not stand beside a new one until its own
        // reply arrives.
        waiting = null;
        parked = null;
        showFigures();
        final int request = ++asked;
        final String uuid = docRef.getUuid();
        restFactory.create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.backlog(uuid, new PageRequest(0, 1)))
                .onSuccess(page -> {
                    if (request == asked) {
                        waiting = total(page.getPageResponse().getTotal(), page.size());
                        showFigures();
                    }
                })
                .onFailure(error -> {
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
        restFactory.create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.find(new AttemptCriteria(new PageRequest(0, 1), null, uuid, null,
                        null, List.of(AttemptStatus.PARKED))))
                .onSuccess(page -> {
                    if (request == asked) {
                        parked = total(page.getPageResponse().getTotal(), page.size());
                        showFigures();
                    }
                })
                .onFailure(error -> {
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private static Long total(final Long total, final int size) {
        return total != null
                ? total
                : (long) size;
    }
}
