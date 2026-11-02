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

import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.util.shared.Severity;
import stroom.widget.component.client.Component;
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
import java.util.function.Consumer;

/// The content of the dialog that adds or edits one scorer setting (§8.4): its type, weight, threshold
/// and gate, and beneath them the panel of parameters that belongs to the type, or none for a scorer
/// that takes none. A weight, threshold or expected ratio that isn't a number it can be is refused:
/// the field says why, and the dialog's OK refuses while it is shown. [ScorerSettingEditorController]
/// shows it and reads and writes the parameters.
public class ScorerSettingEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<ScorerType> type;
    @UiField
    TextBox weight;
    @UiField
    TextBox threshold;
    @UiField
    TickBox gate;
    @UiField
    FlowPanel yieldParameters;
    @UiField
    TextBox expectedRatio;
    @UiField
    SelectionBox<YieldBasis> basis;
    @UiField
    FlowPanel schemaConformanceParameters;
    @UiField
    TextBox schemaGroup;
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
    @UiField
    FlowPanel errorLoadParameters;
    @UiField
    SelectionBox<Severity> minimumSeverity;
    @UiField
    FlowPanel eventClassificationParameters;
    @UiField
    TextArea recognisedTypes;

    private Consumer<ScorerType> onTypeChange = scorerType -> {
    };

    /// Makes an empty editor, showing no parameters.
    public ScorerSettingEditor() {
        initWidget(BINDER.createAndBindUi(this));
        for (final ScorerType scorerType : ScorerType.values()) {
            if (scorerType.isBuilt()) {
                type.addItem(scorerType);
            }
        }
        basis.addItems(YieldBasis.values());
        minimumSeverity.addItems(List.of(Severity.WARNING, Severity.ERROR, Severity.FATAL_ERROR));
        weight.setInvalidReason(ScoreValues.zeroOrMore());
        threshold.setInvalidReason(InputChecks.numberFrom(0, 1));
        // Checked only while the yield's panel is shown, as hidden fields are passed over
        expectedRatio.setInvalidReason(ScoreValues.aboveZero());
        type.setOnChange(value -> onTypeChange.accept(value));
        showParameters(null);
    }

    /// @param onTypeChange Told the type the user chooses.
    public void setOnTypeChange(final Consumer<ScorerType> onTypeChange) {
        this.onTypeChange = onTypeChange;
    }

    /// @return The list of a business rules scorer's assertions, for its controller to attach to.
    public XPathAssertionList getAssertions() {
        return assertions;
    }

    /// Shows the parameter panel of a type, and hides the others; none for a type that takes none.
    ///
    /// @param scorerType The type, or null for none.
    public void showParameters(final ScorerType scorerType) {
        yieldParameters.setVisible(scorerType == ScorerType.YIELD);
        schemaConformanceParameters.setVisible(scorerType == ScorerType.SCHEMA_CONFORMANCE);
        extractionQualityParameters.setVisible(scorerType == ScorerType.EXTRACTION_QUALITY);
        businessRulesParameters.setVisible(scorerType == ScorerType.BUSINESS_RULES);
        errorLoadParameters.setVisible(scorerType == ScorerType.ERROR_LOAD);
        eventClassificationParameters.setVisible(scorerType == ScorerType.EVENT_CLASSIFICATION);
    }

    /// @return The type chosen.
    public ScorerType getType() {
        return type.getValue();
    }

    /// Shows a type, offering it as well where it is one with no scorer that a document already
    /// names, so that the setting it is can still be read and changed to one that scores.
    ///
    /// @param scorerType The type.
    public void setType(final ScorerType scorerType) {
        if (scorerType != null && !type.getItems().contains(scorerType)) {
            type.addItem(scorerType);
        }
        type.setValue(scorerType);
    }

    /// @return The weight entered, or null when the box doesn't hold a number.
    public Double getWeight() {
        return InputChecks.parseNumber(weight.getValue());
    }

    /// @param value The weight.
    public void setWeight(final double value) {
        weight.setValue(ScoreValues.format(value));
    }

    /// @return The threshold entered, or null when the box doesn't hold a number.
    public Double getThreshold() {
        return InputChecks.parseNumber(threshold.getValue());
    }

    /// @param value The threshold.
    public void setThreshold(final double value) {
        threshold.setValue(ScoreValues.format(value));
    }

    /// @return Whether the scorer is a gate.
    public boolean isGate() {
        return gate.getValue();
    }

    /// @param value Whether the scorer is a gate.
    public void setGate(final boolean value) {
        gate.setValue(value);
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

    /// @return The schema group entered.
    public String getSchemaGroup() {
        return schemaGroup.getValue();
    }

    /// @param value The schema group.
    public void setSchemaGroup(final String value) {
        schemaGroup.setValue(value);
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

    /// @return The least severity of diagnostic that counts against a record.
    public Severity getMinimumSeverity() {
        return minimumSeverity.getValue();
    }

    /// @param value The least severity of diagnostic that counts against a record.
    public void setMinimumSeverity(final Severity value) {
        minimumSeverity.setValue(value);
    }

    /// @return The recognised type ids, one per line in the box; never null, blank lines dropped.
    public List<String> getRecognisedTypes() {
        return ScoreValues.fromLines(recognisedTypes.getValue());
    }

    /// @param values The recognised type ids, shown one per line.
    public void setRecognisedTypes(final List<String> values) {
        recognisedTypes.setValue(ScoreValues.toLines(values));
    }

    /// Puts the focus on its first field, the type.
    public void focus() {
        type.focus();
    }

    // The assertions' list follows read only itself, through its controller
    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        type.setReadOnly(readOnly);
        weight.setReadOnly(readOnly);
        threshold.setReadOnly(readOnly);
        gate.setReadOnly(readOnly);
        expectedRatio.setReadOnly(readOnly);
        basis.setReadOnly(readOnly);
        schemaGroup.setReadOnly(readOnly);
        allowUnknownEventDetail.setReadOnly(readOnly);
        requiredFields.setReadOnly(readOnly);
        includeTransformMessages.setReadOnly(readOnly);
        minimumSeverity.setReadOnly(readOnly);
        recognisedTypes.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ScorerSettingEditor> {

    }
}
