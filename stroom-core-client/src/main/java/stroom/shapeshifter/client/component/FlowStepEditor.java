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

import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// One step of a learning flow as a form (A37, design 01 §10.2, §12 item 24), beside the flow's chart.
///
/// Everything that has a closed list is chosen from it (the kind, the role, the guard, the checks,
/// and both ends of every transition), so that nothing can be typed here that the server would
/// refuse. That is the whole reason the editor exists: the text grammar is exact and unforgiving, and
/// a person learning it by having a save rejected is a person learning it the hard way. Two things are
/// still typed, and both are checked: the id, which is a word, and the two limits, which are numbers
/// with a floor of one.
///
/// Only the fields a kind of step can use are shown (A97). [FlowStepEditorController] fills it, and
/// attaches a [TransitionListController] to its transitions.
public class FlowStepEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    /// A tick per check, in the order the closed list declares them: a multi-select of a list this
    /// short reads better as boxes than as a picker, and it shows the ones that are off as well as the
    /// ones that are on.
    private final Map<Check, TickBox> checkBoxes = new EnumMap<>(Check.class);

    @UiField
    TextBox id;
    @UiField
    SelectionBox<QuestionKind> kind;
    @UiField
    SelectionBox<ConfigureRole> role;
    @UiField
    SelectionBox<StepGuard> when;
    @UiField
    TextBox candidates;
    @UiField
    TextBox kinds;
    @UiField
    FlowPanel checks;
    @UiField
    TransitionList transitions;
    @UiField
    TextArea elements;
    @UiField
    FormGroup roleGroup;
    @UiField
    FormGroup whenGroup;
    @UiField
    FormGroup kindsGroup;
    @UiField
    FormGroup elementsGroup;
    @UiField
    FormGroup checksGroup;
    @UiField
    FormGroup transitionsGroup;
    @UiField
    Label chainNote;

    private Runnable onChange = () -> {
    };

    /// Makes an empty form.
    public FlowStepEditor() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addItems(QuestionKind.values());
        role.addItems(ConfigureRole.values());
        when.addItems(StepGuard.values());
        candidates.setInvalidReason(LearnFlow::limitProblem);
        kinds.setInvalidReason(LearnFlow::limitProblem);
        for (final Check check : Check.values()) {
            final TickBox box = new TickBox();
            box.setLabel(check.getDisplayValue());
            box.setOnChange(value -> onChange.run());
            box.setVisible(offered(check));
            checkBoxes.put(check, box);
            checks.add(box);
        }
        // The role follows the kind, and the form says so as the kind is chosen rather than when the
        // save is refused: only CONFIGURE takes one
        kind.setOnChange(value -> {
            roleFollowsKind();
            shapeForKind();
            onChange.run();
        });
        role.setOnChange(value -> onChange.run());
        when.setOnChange(value -> onChange.run());
        id.addValueChangeHandler(event -> onChange.run());
        candidates.addValueChangeHandler(event -> onChange.run());
        kinds.addValueChangeHandler(event -> onChange.run());
        elements.addValueChangeHandler(event -> onChange.run());
        shapeForKind();
        roleFollowsKind();
    }

    /// @return The step's transitions, which its controller attaches the list's controller to.
    public TransitionList getTransitions() {
        return transitions;
    }

    /// @param onChange Run when the user changes a field.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @return The id, as typed.
    public String getId() {
        return id.getValue();
    }

    /// @param stepId The id, or empty for none.
    public void setId(final String stepId) {
        id.setValue(stepId);
    }

    /// @return The kind chosen.
    public QuestionKind getKind() {
        return kind.getValue();
    }

    /// @param questionKind The kind, which the fields shown follow.
    public void setKind(final QuestionKind questionKind) {
        kind.setValue(questionKind);
        roleFollowsKind();
        shapeForKind();
    }

    /// @return The role chosen; null unless the kind is `CONFIGURE`, the only kind that takes one.
    public ConfigureRole getRole() {
        return QuestionKind.CONFIGURE == kind.getValue()
                ? role.getValue()
                : null;
    }

    /// @param configureRole The role, or null for every element in chain order.
    public void setRole(final ConfigureRole configureRole) {
        role.setValue(configureRole);
        roleFollowsKind();
    }

    /// @return The guard chosen.
    public StepGuard getWhen() {
        return when.getValue();
    }

    /// @param guard The guard.
    public void setWhen(final StepGuard guard) {
        when.setValue(guard);
    }

    /// @return The text of the candidates field.
    public String getCandidates() {
        return candidates.getValue();
    }

    /// @param text The candidates as text, empty for the kind's own default.
    public void setCandidates(final String text) {
        candidates.setValue(text);
    }

    /// @param placeholder What the empty candidates field shows: the default it takes from Start
    ///                    (A96).
    public void setCandidatesPlaceholder(final String placeholder) {
        candidates.setPlaceholder(placeholder);
    }

    /// @return The text of the kinds field.
    public String getKinds() {
        return kinds.getValue();
    }

    /// @param text The kinds as text, empty for the kind's own default.
    public void setKinds(final String text) {
        kinds.setValue(text);
    }

    /// @return The checks ticked, in the order the closed list declares them; empty for the kind's
    ///         own.
    public List<Check> getChecks() {
        final List<Check> ticked = new ArrayList<>();
        checkBoxes.forEach((check, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(check);
            }
        });
        return ticked;
    }

    /// @param ticked The checks to tick.
    public void setChecks(final List<Check> ticked) {
        checkBoxes.forEach((check, box) -> {
            box.setValue(ticked.contains(check));
            // A check whose scorer does not exist is not offered, but one a step already ticks is
            // shown, so that it is seen and kept rather than dropped unseen on the next save
            box.setVisible(offered(check) || ticked.contains(check));
        });
    }

    /// @return The element types a Chain step names, as typed, one per line.
    public String getElements() {
        return elements.getValue();
    }

    /// @param text The element types, one per line; empty for the default set.
    public void setElements(final String text) {
        elements.setValue(text);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        id.setReadOnly(readOnly);
        kind.setReadOnly(readOnly);
        role.setReadOnly(readOnly);
        when.setReadOnly(readOnly);
        candidates.setReadOnly(readOnly);
        kinds.setReadOnly(readOnly);
        elements.setReadOnly(readOnly);
        checkBoxes.values().forEach(box -> box.setReadOnly(readOnly));
    }

    /// Only the fields a kind of step can use are shown (A97): a greyed field says "not yours to
    /// change", which is for a read-only document; a kind that has no such thing shows none.
    private void shapeForKind() {
        final QuestionKind selected = kind.getValue();
        final boolean chain = selected == QuestionKind.CHAIN;
        roleGroup.setVisible(selected == QuestionKind.CONFIGURE);
        whenGroup.setVisible(!chain && selected != QuestionKind.ADMIT);
        kindsGroup.setVisible(selected == QuestionKind.TARGET);
        elementsGroup.setVisible(chain);
        checksGroup.setVisible(!chain);
        transitionsGroup.setVisible(!chain);
        chainNote.setVisible(chain);
    }

    /// Only a `CONFIGURE` step takes a role, so for every other kind the picker is off and empty.
    private void roleFollowsKind() {
        final boolean configures = QuestionKind.CONFIGURE == kind.getValue();
        role.setEnabled(configures);
        if (!configures) {
            role.setValue(null);
        }
    }

    // A check is offered unless it is a scorer's and that scorer does not exist yet
    private static boolean offered(final Check check) {
        return check.getScorer() == null || check.getScorer().isBuilt();
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FlowStepEditor> {

    }
}
