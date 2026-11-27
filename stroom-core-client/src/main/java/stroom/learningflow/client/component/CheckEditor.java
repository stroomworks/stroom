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

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.YieldBasis;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.InputChecks;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tickbox.client.TickBox;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog that edits one of a Learning Flow's quality checks. It shows whether the check is
/// Off, Advice or a Gate, its threshold if it has one, and beneath them the panel of settings specific to that
/// check (none for a check that has no settings). A threshold or expected ratio that is not a valid number is
/// marked invalid, with the field saying why, and the dialog's OK is refused while it is.
/// [CheckEditorController] shows it and reads and writes the settings.
public class CheckEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<CheckState> state;
    @UiField
    FormGroup thresholdGroup;
    @UiField
    TextBox threshold;
    @UiField
    FlowPanel yieldParameters;
    @UiField
    TextBox expectedRatio;
    @UiField
    SelectionBox<YieldBasis> basis;
    @UiField
    FlowPanel extractionQualityParameters;
    @UiField
    FlowPanel timesParameters;
    @UiField
    ValueSpinner beforeDays;
    @UiField
    ValueSpinner afterDays;
    @UiField
    TextBox timeField;
    @UiField
    TickBox allowUnknownEventDetail;
    @UiField
    TextArea requiredFields;
    @UiField
    FlowPanel businessRulesParameters;
    @UiField
    XPathAssertionList assertions;
    @UiField
    TickBox includeTransformMessages;

    /// Creates an empty editor, showing no check-specific settings.
    public CheckEditor() {
        initWidget(BINDER.createAndBindUi(this));
        state.addItems(CheckState.values());
        basis.addItems(YieldBasis.values());
        threshold.setInvalidReason(InputChecks.numberFrom(0, 1));
        // Only validated while the yield panel is shown, because hidden fields are not validated
        expectedRatio.setInvalidReason(ScoreValues.aboveZero());
        showCheck(null);
    }

    /// @return The list of a business rules check's assertions, for the controller to attach its own
    ///         controller to.
    public XPathAssertionList getAssertions() {
        return assertions;
    }

    /// Shows the fields that apply to a check (its threshold, if it has one, and the panel of its own settings)
    /// and hides the rest. The compile check is always a gate, so its state can't be changed.
    ///
    /// @param check The check being edited, or null to show no check-specific fields.
    public void showCheck(final Check check) {
        thresholdGroup.setVisible(check != null && check.hasThreshold());
        yieldParameters.setVisible(check == Check.YIELD);
        extractionQualityParameters.setVisible(check == Check.QUALITY);
        timesParameters.setVisible(check == Check.TIMES);
        businessRulesParameters.setVisible(check == Check.RULES);
        state.setEnabled(check != Check.COMPILE);
    }

    /// @return The state chosen: Off, Advice or Gate.
    public CheckState getState() {
        return state.getValue();
    }

    /// @param value The state to show: Off, Advice or Gate.
    public void setState(final CheckState value) {
        state.setValue(value);
    }

    /// @return The threshold entered, or null when the box doesn't hold a number.
    public Double getThreshold() {
        return InputChecks.parseNumber(threshold.getValue());
    }

    /// @param value The threshold, or null for a check that has none.
    public void setThreshold(final Double value) {
        threshold.setValue(value == null
                ? ""
                : ScoreValues.format(value));
    }

    /// @return The yield's expected ratio entered, or null when the box doesn't hold a number.
    public Double getExpectedRatio() {
        return InputChecks.parseNumber(expectedRatio.getValue());
    }

    /// @param value The yield's expected ratio.
    public void setExpectedRatio(final double value) {
        expectedRatio.setValue(ScoreValues.format(value));
    }

    /// @return What the yield check counts output records against.
    public YieldBasis getBasis() {
        return basis.getValue();
    }

    /// @param value What the yield check counts output records against.
    public void setBasis(final YieldBasis value) {
        basis.setValue(value);
    }

    /// @return How many days before its stream was received an event may be dated.
    public int getBeforeDays() {
        return beforeDays.getIntValue();
    }

    /// @return How many days after its stream was received an event may be dated.
    public int getAfterDays() {
        return afterDays.getIntValue();
    }

    /// @return The XPath of the time to check, or blank to use the event-logging schema's event time.
    public String getTimeField() {
        return timeField.getValue();
    }

    /// Shows the window that an event's time must fall in, and which time is checked.
    ///
    /// @param before The days before the stream was received.
    /// @param after  The days after the stream was received.
    /// @param field  The XPath of the time to check, or null to use the event-logging schema's event time.
    public void setTimes(final int before, final int after, final String field) {
        beforeDays.setValue(before);
        afterDays.setValue(after);
        timeField.setValue(field == null
                ? ""
                : field);
    }

    /// @return Whether records classified as Unknown are tolerated.
    public boolean isAllowUnknownEventDetail() {
        return allowUnknownEventDetail.getValue();
    }

    /// @param value Whether records classified as Unknown are tolerated.
    public void setAllowUnknownEventDetail(final boolean value) {
        allowUnknownEventDetail.setValue(value);
    }

    /// @return The required fields' XPaths, one per line in the box; never null, blank lines dropped.
    public List<String> getRequiredFields() {
        return ScoreValues.fromLines(requiredFields.getValue());
    }

    /// @param values The required fields' XPaths, shown one per line.
    public void setRequiredFields(final List<String> values) {
        requiredFields.setValue(ScoreValues.toLines(values));
    }

    /// @return Whether messages the XSLT itself produces count as failed rules.
    public boolean isIncludeTransformMessages() {
        return includeTransformMessages.getValue();
    }

    /// @param value Whether messages the XSLT itself produces count as failed rules.
    public void setIncludeTransformMessages(final boolean value) {
        includeTransformMessages.setValue(value);
    }

    /// Puts the focus on the first field, the state.
    public void focus() {
        state.focus();
    }

    // The assertions list is not set read only here; its own controller does that
    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        state.setReadOnly(readOnly);
        threshold.setReadOnly(readOnly);
        expectedRatio.setReadOnly(readOnly);
        basis.setReadOnly(readOnly);
        allowUnknownEventDetail.setReadOnly(readOnly);
        requiredFields.setReadOnly(readOnly);
        beforeDays.setReadOnly(readOnly);
        afterDays.setReadOnly(readOnly);
        timeField.setReadOnly(readOnly);
        includeTransformMessages.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, CheckEditor> {

    }
}
