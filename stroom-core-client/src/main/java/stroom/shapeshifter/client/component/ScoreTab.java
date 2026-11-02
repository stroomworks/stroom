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

import stroom.shapeshifter.shared.PromotionMode;
import stroom.widget.component.client.Component;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// A Shapeshifter AI document's Score tab (design 05 §10; A91, A93): the gate a learned fragment must
/// pass (the promotion mode and floor, the held-out share, the least records to learn from, and the
/// regression records kept), the flag a serving binding raises, and beneath them the scorers that are
/// the bar and the judges ([ScorerList], which [ScorerListController] drives). Fractions are shown as
/// whole percentages. [ScoreTabController] fills it and does the work.
public class ScoreTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ProcessHeader processHeader;

    @UiField
    SelectionBox<PromotionMode> promotionMode;
    @UiField
    ValueSpinner promotionFloor;
    @UiField
    ValueSpinner heldOutFraction;
    @UiField
    ValueSpinner minRecordsToLearn;
    @UiField
    ValueSpinner regressionCap;
    @UiField
    ValueSpinner regressionRetentionDays;
    @UiField
    ValueSpinner relearnThreshold;
    @UiField
    ScorerList scorerList;

    private Runnable onChange = () -> {
    };

    /// Makes an empty tab.
    public ScoreTab() {
        initWidget(BINDER.createAndBindUi(this));
        promotionMode.addItems(PromotionMode.values());
        promotionMode.setOnChange(value -> onChange.run());
        promotionFloor.setOnChange(value -> onChange.run());
        heldOutFraction.setOnChange(value -> onChange.run());
        minRecordsToLearn.setOnChange(value -> onChange.run());
        regressionCap.setOnChange(value -> onChange.run());
        regressionRetentionDays.setOnChange(value -> onChange.run());
        relearnThreshold.setOnChange(value -> onChange.run());
    }

    /// @return The scorers' list.
    public ScorerList getScorerList() {
        return scorerList;
    }

    /// @param onChange Run when the user changes a setting above the scorers.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @return The promotion mode chosen.
    public PromotionMode getPromotionMode() {
        return promotionMode.getValue();
    }

    /// @param promotionMode The promotion mode.
    public void setPromotionMode(final PromotionMode promotionMode) {
        this.promotionMode.setValue(promotionMode);
    }

    /// @return The promotion floor, a fraction in [0, 1].
    public double getPromotionFloor() {
        return ScoreValues.fromPercent(promotionFloor.getIntValue());
    }

    /// @param fraction The promotion floor, a fraction in [0, 1].
    public void setPromotionFloor(final double fraction) {
        promotionFloor.setValue(ScoreValues.toPercent(fraction));
    }

    /// @return The held-out fraction, in [0, 1].
    public double getHeldOutFraction() {
        return ScoreValues.fromPercent(heldOutFraction.getIntValue());
    }

    /// @param fraction The held-out fraction, in [0, 1].
    public void setHeldOutFraction(final double fraction) {
        heldOutFraction.setValue(ScoreValues.toPercent(fraction));
    }

    /// @return The least records a stream must bring to be learned from.
    public int getMinRecordsToLearn() {
        return minRecordsToLearn.getIntValue();
    }

    /// @param records The least records a stream must bring to be learned from.
    public void setMinRecordsToLearn(final int records) {
        minRecordsToLearn.setValue(records);
    }

    /// @return The most regression records kept per binding.
    public int getRegressionCap() {
        return regressionCap.getIntValue();
    }

    /// @param records The most regression records kept per binding.
    public void setRegressionCap(final int records) {
        regressionCap.setValue(records);
    }

    /// @return How many days regression records are kept, or null (shown as zero) when the feed's own
    /// retention applies.
    public Integer getRegressionRetentionDays() {
        return ScoreValues.fromDays(regressionRetentionDays.getIntValue());
    }

    /// @param days How many days regression records are kept, or null when the feed's own retention
    ///             applies, shown as zero.
    public void setRegressionRetentionDays(final Integer days) {
        regressionRetentionDays.setValue(ScoreValues.toDays(days));
    }

    /// @return The score below which a serving binding is flagged, a fraction in [0, 1].
    public double getRelearnThreshold() {
        return ScoreValues.fromPercent(relearnThreshold.getIntValue());
    }

    /// @param fraction The score below which a serving binding is flagged, a fraction in [0, 1].
    public void setRelearnThreshold(final double fraction) {
        relearnThreshold.setValue(ScoreValues.toPercent(fraction));
    }

    /// @return The process across the top of the tab, this stage marked, which the document keeps.
    public ProcessHeader getProcessHeader() {
        return processHeader;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        promotionMode.setReadOnly(readOnly);
        promotionFloor.setReadOnly(readOnly);
        heldOutFraction.setReadOnly(readOnly);
        minRecordsToLearn.setReadOnly(readOnly);
        regressionCap.setReadOnly(readOnly);
        regressionRetentionDays.setReadOnly(readOnly);
        relearnThreshold.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ScoreTab> {

    }
}
