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

import stroom.learningflow.client.component.ApproachFlow.Condition;
import stroom.learningflow.client.component.ApproachFlow.Destination;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// One rule of a learning-flow step on the Setup tab, shown as a sentence such as "If *the records don't read
/// all the input* then *go back to 2 · Find what one record is*", with a button to remove it.
///
/// The condition and the destination are each chosen from a menu. The menus offer only the outcomes the step
/// can produce and the places it can go ([ApproachFlow#conditions], [ApproachFlow#destinations]); the step
/// card ([StepCard]) fills them.
public class RuleRow extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<Condition> condition;
    @UiField
    SelectionBox<Destination> destination;
    @UiField
    ToolbarButton remove;

    private Consumer<Condition> onCondition = chosen -> {
    };
    private Consumer<Destination> onDestination = chosen -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Makes an empty rule.
    public RuleRow() {
        initWidget(BINDER.createAndBindUi(this));
        condition.setOnChange(chosen -> onCondition.accept(chosen));
        destination.setOnChange(chosen -> onDestination.accept(chosen));
        remove.setOnClick(() -> {
            if (!isReadOnly()) {
                onRemove.run();
            }
        });
    }

    /// Shows the rule.
    ///
    /// @param conditions   The conditions the rule may be triggered by, for the condition menu.
    /// @param chosen       The condition the rule is triggered by.
    /// @param destinations The places the rule may go to, for the destination menu.
    /// @param goes         The place the rule goes to.
    /// @param number       The rule's position among the step's rules, counted from one, used in the menus'
    ///                     accessible names.
    public void setRule(final List<Condition> conditions,
                        final Condition chosen,
                        final List<Destination> destinations,
                        final Destination goes,
                        final int number) {
        condition.clear();
        condition.addItems(conditions);
        condition.setValue(chosen);
        condition.setAccessibleName("Rule " + number + ": if");
        destination.clear();
        destination.addItems(destinations);
        destination.setValue(goes);
        destination.setAccessibleName("Rule " + number + ": then");
        remove.setTitle("Remove rule " + number);
    }

    /// @param onCondition Called with the new condition when the user chooses one.
    public void setOnCondition(final Consumer<Condition> onCondition) {
        this.onCondition = onCondition;
    }

    /// @param onDestination Called with the new destination when the user chooses one.
    public void setOnDestination(final Consumer<Destination> onDestination) {
        this.onDestination = onDestination;
    }

    /// @param onRemove Run when the user removes the rule.
    public void setOnRemove(final Runnable onRemove) {
        this.onRemove = onRemove;
    }

    /// Puts the keyboard focus on one of the rule's menus, e.g. to restore it after the step card is redrawn.
    ///
    /// @param onDestinationMenu True to focus the destination menu, false to focus the condition menu.
    public void focus(final boolean onDestinationMenu) {
        if (onDestinationMenu) {
            destination.focus();
        } else {
            condition.focus();
        }
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        condition.setReadOnly(readOnly);
        destination.setReadOnly(readOnly);
        remove.setVisible(!readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, RuleRow> {

    }
}
