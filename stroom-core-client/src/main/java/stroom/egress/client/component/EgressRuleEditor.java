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

import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// A permitted destination: its kind, destination, how it matches, why it is permitted and whether it is enabled,
/// with a button that asks the server how it would treat the destination.
public class EgressRuleEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<EgressKind> kind;
    @UiField
    TextBox destination;
    @UiField
    SelectionBox<EgressMatch> match;
    @UiField
    TextBox comment;
    @UiField
    TickBox enabled;
    @UiField
    Button testButton;
    @UiField
    Label testResult;

    private Runnable onKindChange = () -> {
    };
    private Runnable onTest = () -> {
    };

    /// Makes an empty form, offering every kind and both ways of matching.
    public EgressRuleEditor() {
        initWidget(BINDER.createAndBindUi(this));
        kind.addItems(EgressKind.values());
        match.addItems(EgressMatch.values());
        kind.addValueChangeHandler(event -> onKindChange.run());
        testButton.setOnClick(() -> onTest.run());
    }

    /// Puts the focus on the destination.
    public void focus() {
        destination.setFocus(true);
    }

    /// @return The chosen kind.
    public EgressKind getKind() {
        return kind.getValue();
    }

    /// @param kind The chosen kind.
    public void setKind(final EgressKind kind) {
        this.kind.setValue(kind);
    }

    /// @return The destination as typed.
    public String getDestination() {
        return destination.getText();
    }

    /// @param destination The destination.
    public void setDestination(final String destination) {
        this.destination.setText(destination);
    }

    /// @return The chosen way of matching.
    public EgressMatch getMatch() {
        return match.getValue();
    }

    /// @param match The chosen way of matching.
    public void setMatch(final EgressMatch match) {
        this.match.setValue(match);
    }

    /// @return Why the rule exists, as typed.
    public String getComment() {
        return comment.getText();
    }

    /// @param comment Why the rule exists.
    public void setComment(final String comment) {
        this.comment.setText(comment);
    }

    /// @return Whether Enabled is ticked.
    public boolean isEnabled() {
        return enabled.getValue();
    }

    /// @param enabled Whether Enabled is ticked.
    public void setEnabled(final boolean enabled) {
        this.enabled.setValue(enabled);
    }

    /// @param text What the server said of the destination, or an empty text to clear it.
    public void setTestResult(final String text) {
        testResult.setText(text);
    }

    /// @param onKindChange Run when another kind is chosen.
    public void setOnKindChange(final Runnable onKindChange) {
        this.onKindChange = onKindChange;
    }

    /// @param onTest Run when Test is pressed.
    public void setOnTest(final Runnable onTest) {
        this.onTest = onTest;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, EgressRuleEditor> {

    }
}
