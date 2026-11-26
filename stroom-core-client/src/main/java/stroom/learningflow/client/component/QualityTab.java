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
import stroom.widget.button.client.Button;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/// A pipeline's Quality Assurance tab, for a person checking the quality of one Smart Pipeline's translated
/// output.
///
/// It shows, in order:
/// - a choice of Smart Pipeline element, if the pipeline has more than one;
/// - the headline figures the translation is judged by ([QualityFigures]), each with the basis for it and
///   coloured when it needs attention;
/// - the volume of data day by day, as a chart ([VolumeChart]) and in a collapsible table;
/// - what the records were translated into (the cardinality);
/// - samples of the records, each beside its translated output;
/// - the score of each learning run, newest first.
///
/// When there is nothing to show, a message says why. [QualityTabController] fills it.
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
    Label cardinalityNote;
    @UiField
    AriaPanel cardinality;
    @UiField
    Label samplesNote;
    @UiField
    AriaPanel samples;
    @UiField
    DataGrid<SupervisorAttempt> runs;

    private Consumer<PipelineAiElement> onElement = chosen -> {
    };
    private LongFunction<String> dates = String::valueOf;
    private LongFunction<String> dayLabels = String::valueOf;

    /// Makes the tab, empty.
    public QualityTab() {
        initWidget(BINDER.createAndBindUi(this));
        element.setOnChange(chosen -> onElement.accept(chosen));
        days.addResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> dayLabels.apply(day.getDayMs())).build(),
                DataGridUtil.headingBuilder("Day").build(),
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
        final String note = QualityFigures.cardinalityNote(report);
        cardinalityNote.setText(note == null
                ? "No translation is in use, so there are no records to count."
                : note);
        cardinality.clear();
        for (final Figure figure : QualityFigures.cardinality(report)) {
            cardinality.add(figure(figure));
        }
        cardinality.setVisible(cardinality.getWidgetCount() > 0);
    }

    /// Shows sample records that the translation in use was learned and checked on, each beside its output.
    ///
    /// @param shown The samples, newest first.
    public void showSamples(final List<QualitySample> shown) {
        samples.clear();
        for (final QualitySample sample : shown) {
            samples.add(sample(sample));
        }
        samples.setVisible(!shown.isEmpty());
        samplesNote.setText(shown.isEmpty()
                ? "There are no samples: no translation is in use, or it keeps no records."
                : PlainWords.count(shown.size(), "record") + ", newest first.");
    }

    /// Shows a message in place of the samples, e.g. that the user is not permitted to view the data.
    ///
    /// @param why The message.
    public void showSamplesNote(final String why) {
        samples.clear();
        samples.setVisible(false);
        samplesNote.setText(why);
    }

    /// Sets the pipeline's Smart Pipeline elements. The choice of element is shown only if there is more than
    /// one.
    ///
    /// @param elements The pipeline's Smart Pipeline elements.
    /// @param chosen   The element to show.
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

    // Builds one sample: a heading with the record's kind, target, score and acceptance date, then the record
    // as received beside its output
    private AriaPanel sample(final QualitySample sample) {
        final AriaPanel item = new AriaPanel();
        item.setRole("listitem");
        item.addStyleName("quality-tab__sample");
        final StringBuilder head = new StringBuilder(sample.getKind() == null
                ? "Record"
                : sample.getKind().getDisplayValue());
        if (sample.getTarget() != null) {
            head.append(" as ").append(sample.getTarget());
        }
        head.append(" · scored ").append(PlainWords.percent(sample.getScore()))
                .append(" · accepted ").append(dates.apply(sample.getAcceptedMs()));
        final Label heading = new Label(head.toString());
        heading.setStyleName("quality-tab__sample-head");
        item.add(heading);
        final FlowPanel pair = new FlowPanel();
        pair.setStyleName("quality-tab__sample-pair");
        pair.add(side(sample.getText() == null
                        ? "Received, as the parser read it"
                        : "Received",
                sample.getText() == null
                        ? sample.getRecord()
                        : sample.getText()));
        pair.add(side("Became", sample.getOutput() == null
                ? "No event: this kind of record yields none."
                : sample.getOutput()));
        item.add(pair);
        return item;
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
        final Label note = new Label(figure.getNote());
        note.setStyleName("quality-tab__figure-note");
        item.add(note);
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
