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
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// Where one step goes, outcome by outcome (A37, design 01 §10.2): the edges out of it
/// ([TransitionList]), each added and edited in a [TransitionEditorController] dialog.
///
/// Each is taken at most once, and a step with none re-asks itself on a shortfall until its
/// candidates are gone, which is the default and needs no row here. What the rows are for is
/// escalation: the flow that goes to a target question when the direct one came back short.
public class TransitionListController extends Controller {

    private final Provider<TransitionEditorController> editorProvider;
    private final List<Transition> transitions = new ArrayList<>();
    private TransitionList view;
    /// The steps a transition may go to: the flow's own, as they stand while this step is edited.
    private List<String> steps = List.of();
    /// Counts the steps read, so that a dialog left open while another step is read changes nothing.
    private int reads;
    private boolean readOnly = true;
    private Runnable onChange = () -> {
    };

    /// Made by GIN; [#attach(TransitionList)] gives it its list.
    ///
    /// @param eventBus       The application's event bus.
    /// @param editorProvider Makes the dialog that edits a transition.
    @Inject
    public TransitionListController(final EventBus eventBus,
                                    final Provider<TransitionEditorController> editorProvider) {
        super(eventBus);
        this.editorProvider = editorProvider;
    }

    /// Attaches it to its list, declared in its owner's template.
    ///
    /// @param view The list.
    /// @return This.
    public TransitionListController attach(final TransitionList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAdd(this::add);
        view.setOnEdit(this::edit);
        view.setOnRemove(this::remove);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows a step's transitions, none selected.
    ///
    /// @param transitions The step's transitions.
    /// @param steps       The ids of the flow's steps, which is what a transition may go to.
    public void read(final List<Transition> transitions, final List<String> steps) {
        reads++;
        this.steps = List.copyOf(steps);
        this.transitions.clear();
        this.transitions.addAll(transitions);
        view.setSelected(null);
        view.setData(this.transitions);
    }

    /// @return The transitions shown, as they are now.
    public List<Transition> write() {
        return new ArrayList<>(transitions);
    }

    /// @param onChange Run when a transition is added, edited or removed.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// Selects the transition that fires on an outcome's name, or on `spent`, as a click on its edge
    /// in the chart does; none where there is none.
    ///
    /// @param on The outcome's name, or `spent`.
    public void select(final String on) {
        Transition found = null;
        for (final Transition transition : transitions) {
            final String name = transition.onSpent()
                    ? Transition.SPENT
                    : transition.getOn().getDisplayValue();
            if (name.equals(on)) {
                found = transition;
            }
        }
        view.setSelected(found);
    }

    private void add() {
        if (!readOnly) {
            show(new Transition(StepOutcome.PASSED, null), null, transitions::add);
        }
    }

    private void edit() {
        final Transition existing = view.getSelected();
        if (!readOnly && existing != null) {
            show(existing, existing, edited -> transitions.set(indexOf(existing), edited));
        }
    }

    private void remove() {
        final Transition selected = view.getSelected();
        if (!readOnly && selected != null) {
            transitions.remove(indexOf(selected));
            view.setSelected(null);
            changed();
        }
    }

    // The dialog isn't modal, so the step behind it may change while it is open (another step chosen
    // on the chart, or the transition removed): OK then changes nothing, and says so
    private void show(final Transition transition,
                      final Transition existing,
                      final Consumer<Transition> apply) {
        final int read = reads;
        final List<Transition> others = new ArrayList<>(transitions);
        if (existing != null) {
            others.remove(indexOf(existing));
        }
        editorProvider.get().edit(this, transition, steps, others, written -> {
            if (read != reads || (existing != null && indexOf(existing) < 0)) {
                AlertEvent.fireWarn(this,
                        "The step this transition belongs to was changed while it was being edited, "
                        + "so nothing was changed.",
                        null);
                return;
            }
            apply.accept(written);
            changed();
        });
    }

    // By identity: two transitions may be equal, and the one shown is the one meant
    private int indexOf(final Transition transition) {
        for (int i = 0; i < transitions.size(); i++) {
            if (transitions.get(i) == transition) {
                return i;
            }
        }
        return -1;
    }

    private void changed() {
        view.setData(transitions);
        onChange.run();
    }
}
