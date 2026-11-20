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
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.InputChecks;
import stroom.widget.form.client.TextArea;
import stroom.widget.form.client.TextBox;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog that edits one check (the one model of checks): whether it is Off, Advice
/// or a Gate, its threshold where it has one, and beneath them the panel of settings that belongs to the
/// check, or none for a check that takes none. A threshold or expected ratio that isn't a number it can
/// be is refused: the field says why, and the dialog's OK refuses while it is shown.
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
    FlowPanel schemaConformanceParameters;
    @UiField
    FlowPanel extractionQualityParameters;
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

    /// Makes an empty editor, showing no settings.
    public CheckEditor() {
        initWidget(BINDER.createAndBindUi(this));
        state.addItems(CheckState.values());
        basis.addItems(YieldBasis.values());
        threshold.setInvalidReason(InputChecks.numberFrom(0, 1));
        // Checked only while the yield's panel is shown, as hidden fields are passed over
        expectedRatio.setInvalidReason(ScoreValues.aboveZero());
        showCheck(null);
    }

    /// @return The list of a business rules check's assertions, for its controller to attach to.
    public XPathAssertionList getAssertions() {
        return assertions;
    }

    /// Shows what belongs to a check: its threshold, where it has one, and the panel of its own settings,
    /// hiding the others. The compile check is always a gate, so its state can't be chosen.
    ///
    /// @param check The check, or null for none.
    public void showCheck(final Check check) {
        thresholdGroup.setVisible(check != null && check.hasThreshold());
        yieldParameters.setVisible(check == Check.YIELD);
        schemaConformanceParameters.setVisible(check == Check.CONFORMANCE);
        extractionQualityParameters.setVisible(check == Check.QUALITY);
        businessRulesParameters.setVisible(check == Check.RULES);
        state.setEnabled(check != Check.COMPILE);
    }

    /// @return The state chosen.
    public CheckState getState() {
        return state.getValue();
    }

    /// @param value The state.
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

    /// @return What the yield's output records are counted against.
    public YieldBasis getBasis() {
        return basis.getValue();
    }

    /// @param value What the yield's output records are counted against.
    public void setBasis(final YieldBasis value) {
        basis.setValue(value);
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

    /// @return Whether the transform's own messages count as failed rules.
    public boolean isIncludeTransformMessages() {
        return includeTransformMessages.getValue();
    }

    /// @param value Whether the transform's own messages count as failed rules.
    public void setIncludeTransformMessages(final boolean value) {
        includeTransformMessages.setValue(value);
    }

    /// Puts the focus on its first field, the state.
    public void focus() {
        state.focus();
    }

    // The assertions' list follows read only itself, through its controller
    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        state.setReadOnly(readOnly);
        threshold.setReadOnly(readOnly);
        expectedRatio.setReadOnly(readOnly);
        basis.setReadOnly(readOnly);
        allowUnknownEventDetail.setReadOnly(readOnly);
        requiredFields.setReadOnly(readOnly);
        includeTransformMessages.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, CheckEditor> {

    }
}
