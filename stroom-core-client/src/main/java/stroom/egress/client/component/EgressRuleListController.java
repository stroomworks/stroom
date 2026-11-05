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

package stroom.egress.client.component;

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.egress.shared.EgressResource;
import stroom.egress.shared.EgressRuleDto;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// The permitted destinations ([EgressRuleList]). New and Edit open the editor; Delete asks first; the Enabled tick
/// saves at once.
public class EgressRuleListController extends Controller {

    private static final EgressResource RESOURCE = GWT.create(EgressResource.class);

    private EgressRuleList view;
    private final RestFactory restFactory;
    private final Provider<EgressRuleEditorController> editorProvider;

    /// Made by GIN; [#attach(EgressRuleList)] gives it its list.
    ///
    /// @param eventBus       The application's event bus.
    /// @param restFactory    Reads, saves and deletes the rules.
    /// @param editorProvider Makes the editor, for one use.
    @Inject
    public EgressRuleListController(final EventBus eventBus,
                                    final RestFactory restFactory,
                                    final Provider<EgressRuleEditorController> editorProvider) {
        super(eventBus);
        this.restFactory = restFactory;
        this.editorProvider = editorProvider;
    }

    /// Attaches this to its list, which it fills from the server. The list is then this controller's widget.
    ///
    /// @return This, for its owner to keep.
    public EgressRuleListController attach(final EgressRuleList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnNew(this::add);
        view.setOnEdit(this::edit);
        view.setOnDelete(this::delete);
        view.setOnRefresh(this::refresh);
        view.setOnEnabledChange(this::setEnabled);
        refresh();
        return this;
    }

    /// Reads the rules again.
    public void refresh() {
        restFactory.create(RESOURCE)
                .method(EgressResource::listRules)
                .onSuccess((List<EgressRuleDto> rules) -> view.setData(rules))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Opens the editor with a new rule, for a destination to be permitted.
    ///
    /// @param rule The rule to start from, or null for an empty one.
    public void addFrom(final EgressRuleDto rule) {
        makeDialog(editorProvider).show(this, rule, "New Egress Rule", saved -> refresh());
    }

    private void add() {
        addFrom(null);
    }

    private void edit() {
        final EgressRuleDto selected = view.getSelectionModel().getSelected();
        if (selected != null) {
            makeDialog(editorProvider).show(this, selected, "Edit Egress Rule", saved -> refresh());
        }
    }

    private void delete() {
        final EgressRuleDto selected = view.getSelectionModel().getSelected();
        if (selected != null) {
            ConfirmEvent.fireWarn(this,
                    "Delete the rule for " + selected.getDestination() + "? Anything that relied on it will be "
                    + "reported, or refused if its kind is enforced.",
                    ok -> {
                        if (ok && !isDisposed()) {
                            restFactory.create(RESOURCE)
                                    .method(resource -> resource.deleteRule(selected.getId()))
                                    .onSuccess(deleted -> refresh())
                                    .owner(this)
                                    .taskMonitorFactory(taskMonitor())
                                    .exec();
                        }
                    });
        }
    }

    private void setEnabled(final EgressRuleDto rule, final boolean enabled) {
        final EgressRuleDto changed = new EgressRuleDto(
                rule.getId(), rule.getKind(), rule.getDestination(), rule.getMatch(), rule.getComment(), enabled);
        restFactory.create(RESOURCE)
                .method(resource -> resource.saveRule(changed))
                .onSuccess(saved -> refresh())
                .onFailure(new DefaultErrorHandler(this, this::refresh))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }
}
