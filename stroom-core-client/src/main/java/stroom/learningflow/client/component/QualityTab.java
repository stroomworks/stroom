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

import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.client.component.QualityFigures.Figure;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.QualitySample;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.VolumeDay;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/// A Smart Pipeline's **Quality Assurance** tab, for a QA person judging one pipeline's translated output on its
/// own: which of its Smart Pipeline elements, where it has more than one; then, each in a section of its own, the
/// overall figures its translation is judged by ([QualityFigures]), coloured where they call for attention and
/// saying what they rest on only then (otherwise as a tooltip); its **volume** day by day, as a chart
/// ([VolumeChart]) and, folded, a table; its **regression set**, the records its translation in use was learned and
/// checked on, counted by what each became and shown one at a time beside it, with buttons to step through them;
/// and, folded, each learning run's score, newest first. Where there is nothing to judge, a sentence says why.
/// [QualityTabController] fills it.
public class QualityTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel chooser;
    @UiField
    SelectionBox<PipelineAiElement> element;
    @UiField
    Label nothing;
    @UiField
    FlowPanel content;
    @UiField
    Label message;
    @UiField
    AriaPanel figures;
    @UiField
    Label volumeSummary;
    @UiField
    Html volumeChart;
    @UiField
    DataGrid<VolumeDay> days;
    @UiField
    Label regressionNote;
    @UiField
    Label regressionSummary;
    @UiField
    Label samplesNote;
    @UiField
    FlowPanel samples;
    @UiField
    FlowPanel stepper;
    @UiField
    ToolbarButton previousSample;
    @UiField
    ToolbarButton nextSample;
    @UiField
    Label samplePosition;
    @UiField
    AriaPanel sample;
    @UiField
    DataGrid<SupervisorAttempt> runs;

    private Consumer<PipelineAiElement> onElement = chosen -> {
    };
    private LongFunction<String> dates = String::valueOf;
    private LongFunction<String> dayLabels = String::valueOf;
    /// The regression set's records shown, newest first, and which of them is on screen.
    private List<QualitySample> regressionRecords = List.of();
    private int recordIndex;
    /// The report, which counts the regression set's records; null until it is read.
    private QualityReport report;
    /// Why the regression set isn't shown, e.g. that the person may not view data; null where it is.
    private String regressionRefused;

    /// Makes the tab, empty.
    public QualityTab() {
        initWidget(BINDER.createAndBindUi(this));
        element.setOnChange(chosen -> onElement.accept(chosen));
        previousSample.setOnClick(() -> showRecord(recordIndex - 1));
        nextSample.setOnClick(() -> showRecord(recordIndex + 1));
        regressionNote.setText(QualityFigures.regressionNote(null));
        days.addResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> dayLabels.apply(day.getDayMs())).build(),
                DataGridUtil.headingBuilder("Day (UTC)").build(),
                120);
        days.addResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> PlainWords.number(day.getStreams())).build(),
                DataGridUtil.headingBuilder("Streams").build(),
                100);
        days.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> PlainWords.number(day.getRecords())).build(),
                DataGridUtil.headingBuilder("Records").build(),
                100);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> dates.apply(run.getCreateTimeMs())).build(),
                DataGridUtil.headingBuilder("Started").build(),
                180);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getStatus() == null
                        ? ""
                        : run.getStatus().getDisplayValue()).build(),
                DataGridUtil.headingBuilder("Result").build(),
                140);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getScore() == null
                        ? ""
                        : PlainWords.percent(run.getScore())).build(),
                DataGridUtil.headingBuilder("Score").build(),
                80);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> PlainWords.number(run.getTokensSpent()))
                        .build(),
                DataGridUtil.headingBuilder("Tokens").build(),
                100);
        runs.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getDecision() == null
                        ? ""
                        : run.getDecision()).build(),
                DataGridUtil.headingBuilder("Decision").build(),
                300);
    }

    /// @param dates Formats a time in milliseconds as a date and time in the user's preferred format.
    public void setDateFormat(final LongFunction<String> dates) {
        this.dates = dates;
    }

    /// @param dayLabels Formats the start of a day (UTC) in milliseconds as a short label, e.g. "17 Nov".
    public void setDayFormat(final LongFunction<String> dayLabels) {
        this.dayLabels = dayLabels;
    }

    /// Shows the daily volume and the cardinality of the translation in use.
    ///
    /// @param report The quality report for the chosen element.
    public void showReport(final QualityReport report) {
        final List<VolumeDay> window = report.getDays() == null
                ? List.of()
                : report.getDays();
        volumeSummary.setText(QualityFigures.volume(window));
        volumeChart.setHTML(SafeHtmlUtils.fromTrustedString(VolumeChart.draw(window, dayLabels)));
        days.setRowData(0, window);
        days.setRowCount(window.size(), true);
        this.report = report;
        showRegressionSummary();
    }

    /// Says what the regression set is, and whether a new translation is held to it.
    ///
    /// @param gate Whether the regression check is a gate; null where it isn't known.
    public void setRegressionGate(final Boolean gate) {
        regressionNote.setText(QualityFigures.regressionNote(gate));
    }

    /// Shows the regression set: the records the translation in use was learned and checked on, beside what they
    /// became, one at a time from the newest.
    ///
    /// @param shown Its records, newest first: all of them, or the newest where it holds more.
    public void showSamples(final List<QualitySample> shown) {
        regressionRecords = new ArrayList<>(shown);
        regressionRefused = null;
        showRecord(0);
        showRegressionSummary();
    }

    /// Says why the regression set isn't shown, e.g. that the person may not view the data.
    ///
    /// @param why What to say.
    public void showSamplesNote(final String why) {
        regressionRecords = List.of();
        regressionRefused = why;
        showRecord(0);
        showRegressionSummary();
    }

    /// The pipeline's Smart Pipeline elements: the choice of them is offered only where there is more than one.
    ///
    /// @param elements Its Smart Pipeline elements.
    /// @param chosen   The one shown.
    public void setElements(final List<PipelineAiElement> elements, final PipelineAiElement chosen) {
        element.clear();
        element.addItems(elements);
        element.setValue(chosen);
        chooser.setVisible(elements.size() > 1);
    }

    /// Shows the headline figures and the learning runs of the chosen element.
    ///
    /// @param shown       The element's figures.
    /// @param learnedRuns The element's learning runs, newest first.
    public void showQuality(final QualityFigures shown, final List<SupervisorAttempt> learnedRuns) {
        // Another element's counts aren't this one's: its report says them again
        report = null;
        showRegressionSummary();
        nothing.setVisible(false);
        content.setVisible(true);
        message.setText(shown.getMessage() == null
                ? ""
                : shown.getMessage());
        message.setVisible(shown.getMessage() != null);
        figures.clear();
        for (final Figure figure : shown.getFigures()) {
            figures.add(figure(figure));
        }
        runs.setRowData(0, learnedRuns);
        runs.setRowCount(learnedRuns.size(), true);
    }

    /// Shows a message, in place of the tab's content, explaining why there is nothing to show for the chosen
    /// element, e.g. that it has not learned anything yet.
    ///
    /// @param why The message.
    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(true);
        content.setVisible(false);
    }

    /// @param onElement Called with the element the user chooses.
    public void setOnElement(final Consumer<PipelineAiElement> onElement) {
        this.onElement = onElement;
    }

    // Shows one of the regression set's records, and which it is of how many, with a step either way where there
    // is one to take
    private void showRecord(final int index) {
        final int count = regressionRecords.size();
        recordIndex = Math.max(0, Math.min(index, count - 1));
        sample.clear();
        samples.setVisible(count > 0);
        if (count == 0) {
            return;
        }
        fillRecord(regressionRecords.get(recordIndex));
        stepper.setVisible(count > 1);
        samplePosition.setText(QualityFigures.position(recordIndex, count));
        previousSample.setEnabled(recordIndex > 0);
        nextSample.setEnabled(recordIndex < count - 1);
    }

    // How many records the regression set holds and what they became, and why they aren't shown where they aren't
    private void showRegressionSummary() {
        final String summary = QualityFigures.regressionSummary(report, regressionRecords.size());
        regressionSummary.setText(summary == null
                ? ""
                : summary);
        regressionSummary.setVisible(summary != null);
        samplesNote.setText(regressionRefused == null
                ? ""
                : regressionRefused);
        samplesNote.setVisible(regressionRefused != null);
    }

    // A record beside what it became: what it is and scored, then the record as received and the output it was
    // accepted with
    private void fillRecord(final QualitySample record) {
        final StringBuilder head = new StringBuilder(record.getKind() == null
                ? "Record"
                : record.getKind().getDisplayValue());
        if (record.getTarget() != null) {
            head.append(" as ").append(record.getTarget());
        }
        head.append(" · scored ").append(PlainWords.percent(record.getScore()))
                .append(" · accepted ").append(dates.apply(record.getAcceptedMs()));
        final Label heading = new Label(head.toString());
        heading.setStyleName("quality-tab__sample-head");
        sample.add(heading);
        final FlowPanel pair = new FlowPanel();
        pair.setStyleName("quality-tab__sample-pair");
        pair.add(side(record.getText() == null
                        ? "Received, as the parser read it"
                        : "Received",
                record.getText() == null
                        ? record.getRecord()
                        : record.getText()));
        pair.add(side("Became, when accepted", record.getOutput() == null
                ? "No event: this kind of record yields none."
                : record.getOutput()));
        sample.add(pair);
    }

    private static FlowPanel side(final String title, final String text) {
        final FlowPanel side = new FlowPanel();
        side.setStyleName("quality-tab__sample-side");
        final Label label = new Label(title);
        label.setStyleName("quality-tab__sample-title");
        side.add(label);
        final Label code = new Label(text == null
                ? ""
                : text);
        code.setStyleName("quality-tab__code");
        side.add(code);
        return side;
    }

    // Builds one figure: its label, its value and its note, coloured according to its tone
    private static AriaPanel figure(final Figure figure) {
        final AriaPanel item = new AriaPanel();
        item.setRole("listitem");
        item.addStyleName("quality-tab__figure");
        item.addStyleName(toneClass(figure.getTone()));
        final Label label = new Label(figure.getLabel());
        label.setStyleName("quality-tab__figure-label");
        item.add(label);
        final Label value = new Label(figure.getValue());
        value.setStyleName("quality-tab__figure-value");
        item.add(value);
        if (figure.isNoteShown()) {
            final Label note = new Label(figure.getNote());
            note.setStyleName("quality-tab__figure-note");
            item.add(note);
        } else {
            // The value says enough: what it rests on is there on hover
            item.setTitle(figure.getNote());
        }
        return item;
    }

    private static String toneClass(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "quality-tab__figure--good";
            case WARNING:
                return "quality-tab__figure--warning";
            case DANGER:
                return "quality-tab__figure--danger";
            case INFO:
                return "quality-tab__figure--info";
            default:
                return "quality-tab__figure--idle";
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, QualityTab> {

    }
}
