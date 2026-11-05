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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.egress.shared.EgressAttemptDto;
import stroom.egress.shared.EgressClearRequest;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressResource;
import stroom.egress.shared.EgressRuleDto;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// What the policy has refused or reported ([EgressAttemptList]). Reveal asks the server for the whole destination,
/// which it records; Clear asks first, and the server records it too.
public class EgressAttemptListController extends Controller {

    private static final EgressResource RESOURCE = GWT.create(EgressResource.class);

    private EgressAttemptList view;
    private final RestFactory restFactory;
    private Consumer<EgressRuleDto> onAddRule = rule -> {
    };

    /// Made by GIN; [#attach(EgressAttemptList)] gives it its list.
    ///
    /// @param eventBus    The application's event bus.
    /// @param restFactory Reads, reveals and clears the attempts.
    @Inject
    public EgressAttemptListController(final EventBus eventBus,
                                       final RestFactory restFactory) {
        super(eventBus);
        this.restFactory = restFactory;
        refresh();
    }

    /// @param onAddRule Given a rule to start from, when Add Rule is pressed. The rule has the selected attempt's
    ///                  kind and destination as shown, so it is the owner's to complete, with the revealed
    ///                  destination where the shown one is masked.
    public void setOnAddRule(final Consumer<EgressRuleDto> onAddRule) {
        this.onAddRule = onAddRule;
    }

    /// Attaches this to its list, which it fills from the server. The list is then this controller's widget.
    ///
    /// @return This, for its owner to keep.
    public EgressAttemptListController attach(final EgressAttemptList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAddRule(this::addRule);
        view.setOnReveal(this::reveal);
        view.setOnClear(this::clear);
        view.setOnRefresh(this::refresh);
        refresh();
        return this;
    }

    /// Reads the attempts again.
    public void refresh() {
        restFactory.create(RESOURCE)
                .method(EgressResource::listAttempts)
                .onSuccess((List<EgressAttemptDto> attempts) -> view.setData(attempts))
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Asks for the whole destination, as the one shown is masked, and offers it as a new rule
    private void addRule() {
        final EgressAttemptDto selected = view.getSelectionModel().getSelected();
        if (selected != null) {
            restFactory.create(RESOURCE)
                    .method(resource -> resource.revealAttempt(selected.getId()))
                    .onSuccess(destination -> {
                        if (destination == null) {
                            gone();
                        } else {
                            onAddRule.accept(new EgressRuleDto(
                                    null,
                                    selected.getKind(),
                                    destination,
                                    EgressMatch.EXACT,
                                    "",
                                    true));
                        }
                    })
                    .owner(this)
                    .taskMonitorFactory(taskMonitor())
                    .exec();
        }
    }

    private void reveal() {
        final EgressAttemptDto selected = view.getSelectionModel().getSelected();
        if (selected != null) {
            restFactory.create(RESOURCE)
                    .method(resource -> resource.revealAttempt(selected.getId()))
                    .onSuccess(destination -> {
                        if (destination == null) {
                            gone();
                        } else {
                            AlertEvent.fireInfo(this, "Destination", destination, null);
                        }
                    })
                    .owner(this)
                    .taskMonitorFactory(taskMonitor())
                    .exec();
        }
    }

    private void gone() {
        AlertEvent.fireInfo(this, "That attempt is no longer held. Refresh the list.", null);
    }

    private void clear() {
        ConfirmEvent.fireWarn(this,
                "Clear the list of blocked and reported attempts? It will fill again as they recur.",
                ok -> {
                    if (ok && !isDisposed()) {
                        restFactory.create(RESOURCE)
                                .method(resource -> resource.clearAttempts(new EgressClearRequest(null)))
                                .onSuccess(cleared -> refresh())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }
}
