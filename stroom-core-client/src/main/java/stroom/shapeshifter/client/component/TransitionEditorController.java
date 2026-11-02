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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Edits where a step goes on one outcome in a dialog ([TransitionEditor]): `on <outcome> goto
/// <step>` (A37, design 01 §10.2).
///
/// Both ends are chosen and neither is typed. The outcomes are a closed list the checks produce, and
/// the targets are the flow's own steps, so a transition cannot name an outcome nothing raises or a
/// step that is not there, which is the whole point of editing the graph rather than its text.
public class TransitionEditorController extends Controller {

    private final TransitionEditor view;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public TransitionEditorController(final EventBus eventBus, final TransitionEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the editor as a dialog, owned by `opener` until it is closed. OK gives the transition to
    /// `onOk`, unless the step already goes somewhere on its outcome, when it says so and the dialog
    /// stays open.
    ///
    /// Each outcome may be gone to once: a step with two transitions on one outcome would take
    /// whichever the reader happened to find first, which is not a flow but a coin toss. Said here
    /// rather than left to the store's refusal on save, so that a person is told where they chose it.
    ///
    /// @param opener     What shows it.
    /// @param transition The transition to edit, or a new one.
    /// @param steps      The ids of the flow's steps, which is what a transition may go to.
    /// @param others     The step's other transitions, whose outcomes this one may not take.
    /// @param onOk       Given the transition as edited.
    public void edit(final HasControllerNode opener,
                     final Transition transition,
                     final List<String> steps,
                     final List<Transition> others,
                     final Consumer<Transition> onOk) {
        read(transition, steps);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(500, 250))
                .caption("Transition")
                // An editor of an item in a list: the flow behind it can be read while it is open
                .modeless()
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final Transition written = write();
                    final boolean clash = others.stream()
                            .anyMatch(other -> other.getOn() == written.getOn());
                    if (clash) {
                        AlertEvent.fireError(this, "This step already goes somewhere on '"
                                                   + NullSafe.getOrElse(written.getOn(),
                                StepOutcome::getDisplayValue, Transition.SPENT)
                                                   + "'.", e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    private void read(final Transition transition, final List<String> steps) {
        final String target = transition.getGoTo() == null
                ? Transition.ABANDON
                : transition.getGoTo();
        final List<String> targets = new ArrayList<>(steps);
        if (!isPlace(target) && !targets.contains(target)) {
            // A step this transition names that is not there any more: renamed, or removed. Offered
            // anyway, so that opening the transition shows what it says: dropped from the list it would
            // come back blank, and a blank target is *abandon*, so merely looking at a dangling
            // transition would quietly turn it into one that gives the attempt up. It stays wrong, the
            // flow's own check goes on saying so, and the person decides what it should be.
            targets.add(target);
        }
        // The places that are not steps, last: the end of the flow, the two exits, and the step's own
        // default exit (A83). Places rather than steps, so offered beside the step ids, not among them.
        targets.add(Transition.END);
        targets.add(Transition.PARK);
        targets.add(Transition.GIVE_UP);
        targets.add(Transition.ABANDON);
        view.setTargets(targets);
        view.setOutcome(transition.getOn());
        view.setTarget(target);
    }

    private Transition write() {
        final String target = view.getTarget();
        return new Transition(view.getOutcome(), Transition.ABANDON.equals(target)
                ? null
                : target);
    }

    private static boolean isPlace(final String target) {
        return Transition.END.equals(target)
               || Transition.PARK.equals(target)
               || Transition.GIVE_UP.equals(target)
               || Transition.ABANDON.equals(target);
    }
}
