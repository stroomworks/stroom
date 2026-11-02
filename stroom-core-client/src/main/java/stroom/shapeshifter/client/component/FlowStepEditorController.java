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

import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.form.client.HasInvalidInput;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// One step of a learning flow as a form ([FlowStepEditor]; A37, design 01 §10.2, §12 item 24), with
/// its transitions ([TransitionListController]). The Learn tab edits a step in place beside the chart
/// (design 05 §3), so the chart follows every change rather than an OK: every field changed, and every
/// transition added, edited or removed, is told to its owner ([#setOnChange(Runnable)]), which reads
/// the step back with [#write()].
public class FlowStepEditorController extends Controller {

    private final TransitionListController transitionList;
    private FlowStepEditor view;
    /// Set while a step is read into the form, so that filling it is not taken for an edit.
    private boolean reading;
    private Runnable onChange = () -> {
    };

    /// Made by GIN, with its transitions' list; [#attach(FlowStepEditor)] gives it its form.
    ///
    /// @param eventBus       The application's event bus.
    /// @param transitionList The step's transitions.
    @Inject
    public FlowStepEditorController(final EventBus eventBus,
                                    final TransitionListController transitionList) {
        super(eventBus);
        this.transitionList = transitionList;
    }

    /// Attaches it to its form, declared in its owner's template.
    ///
    /// @param view The form.
    /// @return This.
    public FlowStepEditorController attach(final FlowStepEditor view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        own(transitionList).attach(view.getTransitions());
        view.setOnChange(this::changed);
        transitionList.setOnChange(this::changed);
        watch(Contexts.READ_ONLY, view::setReadOnly);
        return this;
    }

    /// @param onChange Run when the user changes a field of the form, or a transition.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// Shows a step in the form.
    ///
    /// @param step  The step.
    /// @param steps The ids of every step of the flow, which is what a transition may go to.
    public void read(final FlowStep step, final List<String> steps) {
        reading = true;
        try {
            view.setId(NullSafe.string(step.getId()));
            // The kind first, since the form follows it: only a CONFIGURE step takes a role, and the
            // form turns the role off rather than let a person set one the step will refuse
            view.setKind(step.getKind());
            view.setRole(step.getRole());
            view.setWhen(step.getWhen());
            view.setCandidates(LearnFlow.limitText(step.getCandidates()));
            view.setKinds(LearnFlow.limitText(step.getKinds()));
            view.setChecks(step.getChecks());
            view.setElements(String.join("\n", step.getElements()));
            transitionList.read(step.getTransitions(), steps);
        } finally {
            reading = false;
        }
    }

    /// The step as the form has it.
    ///
    /// @return The step.
    /// @throws IllegalArgumentException As [FlowStep]'s own constructor does, for anything the form
    ///                                  cannot stop a person writing (an id that is not a word), or
    ///                                  where a field holds text that can't be kept (a limit that is
    ///                                  not a whole number of one or more), which its field says too.
    ///                                  The caller shows it beside the form.
    public FlowStep write() {
        final HasInvalidInput invalid = view.findInvalidInput();
        if (invalid != null) {
            throw new IllegalArgumentException(invalid.getInvalidReason());
        }
        final String id = NullSafe.trim(view.getId());
        final QuestionKind kind = view.getKind();
        return new FlowStep(
                id.isEmpty()
                        ? null
                        : id,
                kind,
                QuestionKind.CONFIGURE == kind
                        ? view.getRole()
                        : null,
                view.getWhen(),
                LearnFlow.parseLimit(view.getCandidates()),
                LearnFlow.parseLimit(view.getKinds()),
                view.getChecks(),
                transitionList.write(),
                QuestionKind.CHAIN == kind
                        ? LearnFlow.elements(view.getElements())
                        : null);
    }

    /// Selects the transition that fires on an outcome, or on `spent`, as a click on its edge does.
    ///
    /// @param on The outcome's name, or `spent`.
    public void selectTransition(final String on) {
        transitionList.select(on);
    }

    /// The candidates a step with none of its own uses (A96): Start's default, shown in the empty
    /// field.
    ///
    /// @param candidates The default.
    public void setDefaultCandidates(final int candidates) {
        view.setCandidatesPlaceholder(candidates + ", from Start");
    }

    private void changed() {
        if (!reading) {
            onChange.run();
        }
    }
}
