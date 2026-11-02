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

import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog that edits where a step goes on one outcome (A37, design 01 §10.2): the
/// outcome it fires on, and the step or place it goes to, both chosen from closed lists.
/// [TransitionEditorController] shows it.
public class TransitionEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<StepOutcome> outcome;
    @UiField
    SelectionBox<String> target;

    /// Makes an empty editor, offering every outcome.
    public TransitionEditor() {
        initWidget(BINDER.createAndBindUi(this));
        // No outcome chosen is 'spent': the transition that fires when the step's candidates are gone
        outcome.setNonSelectString(Transition.SPENT);
        outcome.addItems(StepOutcome.values());
    }

    /// @return The outcome chosen, or null for `spent`.
    public StepOutcome getOutcome() {
        return outcome.getValue();
    }

    /// @param stepOutcome The outcome to show as chosen, or null for `spent`.
    public void setOutcome(final StepOutcome stepOutcome) {
        outcome.setValue(stepOutcome);
    }

    /// @param targets What the transition may go to, in the order they are offered.
    public void setTargets(final List<String> targets) {
        target.clear();
        target.addItems(targets);
    }

    /// @return What the transition goes to, as chosen.
    public String getTarget() {
        return target.getValue();
    }

    /// @param goTo What the transition goes to.
    public void setTarget(final String goTo) {
        target.setValue(goTo);
    }

    /// Puts the focus on its first field, the outcome.
    public void focus() {
        outcome.focus();
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TransitionEditor> {

    }
}
